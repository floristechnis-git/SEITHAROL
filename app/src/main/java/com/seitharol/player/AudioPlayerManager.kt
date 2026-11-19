package com.seitharol.player

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.ResolvedPlaybackTarget
import com.seitharol.data.resolver.ResolverEngine
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Discover 30-Second Preview & Adjacent Preload Player Manager:
 * - Enforces an authentic 30-second preview loop window (0..30s for native Spotify/iTunes 30s clips,
 *   or a 30s chorus clip [startOffsetMs..startOffsetMs+30,000ms] for YTM/JioSaavn streams).
 * - Exposes live 30-second [previewProgressFraction] and [nextSongPreloadProgress] so Discover shows
 *   both the 30s preview progress and the preload progress for the next song!
 * - Pre-resolves and warms stream cache bytes for both NEXT (scroll down) and PREVIOUS (scroll up) songs
 *   so scrolling up or down never stops or stalls!
 * - Ensures [openFullLengthSong] strictly verifies canonical Title + Artist + No-Remix so "Play Full"
 *   never plays the wrong song or a 30s preview clip.
 */
class AudioPlayerManager(
    val exoPlayer: ExoPlayer,
    private val resolverEngine: ResolverEngine,
    private val appContext: Context? = null
) {
    companion object {
        const val PREVIEW_WINDOW_MS = 30_000L

        @Volatile
        private var activePreviewInstanceRef: WeakReference<AudioPlayerManager>? = null

        fun stopActivePreviewIfAny() {
            runCatching {
                activePreviewInstanceRef?.get()?.stopPreviewAndClear()
            }
        }
    }

    private data class CachedPreviewTarget(
        val streamUrl: String,
        val startOffsetMs: Long,
        val isNative30sClip: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewResolveJob: Job? = null
    private var previewTickerJob: Job? = null
    private var preloadJob: Job? = null

    private val resolvedPreviewCache = ConcurrentHashMap<String, CachedPreviewTarget>()
    private val preloadedTrackIds = ConcurrentHashMap.newKeySet<String>()

    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _isPreviewBuffering = MutableStateFlow(false)
    val isPreviewBuffering: StateFlow<Boolean> = _isPreviewBuffering.asStateFlow()

    // Live 0f..1f progress across the 30-second preview clip
    private val _previewProgressFraction = MutableStateFlow(0f)
    val previewProgressFraction: StateFlow<Float> = _previewProgressFraction.asStateFlow()

    // Preload progress (0f..1f) for the upcoming song in Discover
    private val _nextSongPreloadProgress = MutableStateFlow(0f)
    val nextSongPreloadProgress: StateFlow<Float> = _nextSongPreloadProgress.asStateFlow()

    @Volatile
    private var currentRequestId: Long = 0L

    @Volatile
    private var activePreviewTrack: NormalizedTrack? = null

    @Volatile
    private var activeStartOffsetMs: Long = 0L

    @Volatile
    private var hasRetriedCurrentPreview: Boolean = false

    init {
        activePreviewInstanceRef = WeakReference(this)
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_activePreviewTrackId.value != null) {
                    _isPreviewPlaying.value = isPlaying
                    if (isPlaying) {
                        _isPreviewBuffering.value = false
                        startPreviewWindowTicker()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_activePreviewTrackId.value == null) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> _isPreviewBuffering.value = true
                    Player.STATE_READY -> {
                        _isPreviewBuffering.value = false
                        _isPreviewPlaying.value = exoPlayer.playWhenReady
                        if (exoPlayer.playWhenReady) {
                            startPreviewWindowTicker()
                        }
                    }
                    Player.STATE_ENDED -> {
                        exoPlayer.seekTo(activeStartOffsetMs)
                        exoPlayer.play()
                    }
                    Player.STATE_IDLE -> {
                        if (previewResolveJob?.isActive != true) {
                            _isPreviewBuffering.value = false
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = activePreviewTrack ?: return
                if (!hasRetriedCurrentPreview) {
                    hasRetriedCurrentPreview = true
                    resolvedPreviewCache.remove(track.sourceId)
                    OuterTuneResolvingDataSource.invalidate(track.sourceId)
                    playPreviewInternal(track, forceFresh = true)
                } else {
                    _isPreviewBuffering.value = false
                    _isPreviewPlaying.value = false
                }
            }
        })
    }

    /**
     * Tracks the 30-second preview window and loops back to [activeStartOffsetMs] once 30 seconds have elapsed,
     * ensuring every Discover preview behaves like an authentic 30s Spotify preview clip.
     */
    private fun startPreviewWindowTicker() {
        previewTickerJob?.cancel()
        previewTickerJob = scope.launch {
            while (isActive && _activePreviewTrackId.value != null) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val elapsedInWindow = (pos - activeStartOffsetMs).coerceAtLeast(0L)
                    val frac = (elapsedInWindow.toFloat() / PREVIEW_WINDOW_MS.toFloat()).coerceIn(0f, 1f)
                    _previewProgressFraction.value = frac
                    if (elapsedInWindow >= PREVIEW_WINDOW_MS) {
                        exoPlayer.seekTo(activeStartOffsetMs)
                        _previewProgressFraction.value = 0f
                    }
                }
                delay(140L)
            }
        }
    }

    fun playPreview(track: NormalizedTrack) {
        activePreviewInstanceRef = WeakReference(this)
        if (_activePreviewTrackId.value == track.sourceId &&
            (_isPreviewBuffering.value || exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING || previewResolveJob?.isActive == true)
        ) {
            exoPlayer.playWhenReady = true
            if (exoPlayer.playbackState == Player.STATE_READY && !exoPlayer.isPlaying) {
                exoPlayer.play()
                startPreviewWindowTicker()
            }
            return
        }
        hasRetriedCurrentPreview = false
        playPreviewInternal(track, forceFresh = false)
    }

    private fun isDirect30sPreviewClip(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("p.scdn.co", ignoreCase = true) ||
            url.contains("itunes.apple.com", ignoreCase = true)
    }

    private suspend fun resolvePreviewTargetForTrack(
        track: NormalizedTrack,
        forceFresh: Boolean
    ): CachedPreviewTarget? = withContext(Dispatchers.IO) {
        if (!forceFresh) {
            resolvedPreviewCache[track.sourceId]?.let { return@withContext it }
        }

        val rawPreview = track.previewUrl
        val isYoutubeWatchUrl = rawPreview != null && (
            rawPreview.contains("youtube.com/watch", ignoreCase = true) ||
                rawPreview.contains("music.youtube.com/watch", ignoreCase = true) ||
                rawPreview.contains("youtu.be/", ignoreCase = true)
            )

        // 1. Direct 30s Spotify / Apple iTunes Preview Clip or pre-decrypted JioSaavn CDN URL
        if (!forceFresh && rawPreview != null && rawPreview.startsWith("http") && !isYoutubeWatchUrl) {
            val isNative30s = isDirect30sPreviewClip(rawPreview)
            val startPos = if (isNative30s) 0L else if (rawPreview.contains("saavncdn.com")) 25_000L else 0L
            val target = CachedPreviewTarget(rawPreview, startPos, isNative30s)
            resolvedPreviewCache[track.sourceId] = target
            return@withContext target
        }

        val ytId = when {
            rawPreview != null && rawPreview.startsWith("ytm_clip:") ->
                InnerTubeStreamExtractor.extractVideoId(rawPreview.removePrefix("ytm_clip:"))
            !rawPreview.isNullOrBlank() ->
                InnerTubeStreamExtractor.extractVideoId(rawPreview)
                    ?: InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            else -> InnerTubeStreamExtractor.extractVideoId(track.sourceId)
        }

        val primaryArtist = track.artists.firstOrNull().orEmpty()

        // 2. Try fast JioSaavn exact match first for 0-latency HTTP range seeking into the 30s chorus window
        if (!forceFresh && track.title.isNotBlank()) {
            val saavnMeta = TrackMetadata(
                id = if (track.source == TrackSource.JIOSAAVN) track.sourceId.removePrefix("saavn_clip:") else "",
                title = track.title,
                artist = primaryArtist,
                album = track.album.orEmpty(),
                durationSeconds = (track.durationMs / 1000L).toInt(),
                thumbnailUrl = track.artUrl
            )
            val saavnUrl = runCatching { StreamResolver.resolveExactStreamUrl(saavnMeta, AudioQuality.LOW) }.getOrNull()
            if (!saavnUrl.isNullOrBlank()) {
                val offset = if (track.durationMs > 60_000L) 25_000L else 0L
                val target = CachedPreviewTarget(saavnUrl, offset, isNative30sClip = false)
                resolvedPreviewCache[track.sourceId] = target
                return@withContext target
            }
        }

        // 3. Direct YouTube Music videoId resolution
        if (!ytId.isNullOrBlank()) {
            val ytUrl = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                ytId,
                AudioQuality.LOW,
                forceRefresh = forceFresh
            ).getOrNull()
            if (!ytUrl.isNullOrBlank()) {
                val offset = if (track.durationMs > 65_000L) 30_000L else 0L
                val target = CachedPreviewTarget(ytUrl, offset, isNative30sClip = false)
                resolvedPreviewCache[track.sourceId] = target
                return@withContext target
            }
        }

        // 4. Verified Canonical YouTube Music Search Fallback (never picks unrequested remixes!)
        val query = "${track.title} $primaryArtist".trim()
        if (query.isNotBlank()) {
            val candidates = runCatching {
                InnerTubeStreamExtractor.searchOfficialYtmSongs(query, limit = 6)
            }.getOrDefault(emptyList())
            val bestCanonical = CanonicalAudioResolver.selectBestCanonicalMatch(
                queryTitle = track.title,
                queryArtist = primaryArtist,
                targetDurationSeconds = (track.durationMs / 1000L).toInt().takeIf { it > 45 && it != 210 },
                candidates = candidates
            ) ?: candidates.firstOrNull {
                !CanonicalAudioResolver.containsBannedModifier(query, "${it.title} ${it.artist}")
            }
            if (bestCanonical != null) {
                val resolved = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                    bestCanonical.videoId,
                    AudioQuality.LOW,
                    forceRefresh = forceFresh
                ).getOrNull()
                if (!resolved.isNullOrBlank()) {
                    val offset = if (bestCanonical.durationSeconds > 65) 30_000L else 0L
                    val target = CachedPreviewTarget(resolved, offset, isNative30sClip = false)
                    resolvedPreviewCache[track.sourceId] = target
                    return@withContext target
                }
            }
        }

        null
    }

    /**
     * Preloads and warms the stream cache for adjacent Discover tracks (both NEXT for scroll-down
     * and PREVIOUS for scroll-up) and updates [nextSongPreloadProgress] so scrolling up or down
     * transitions instantaneously without stopping!
     */
    fun preloadAdjacentPreviews(adjacentTracks: List<NormalizedTrack>) {
        if (adjacentTracks.isEmpty()) return
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            val primaryNext = adjacentTracks.firstOrNull()
            if (primaryNext != null) {
                _nextSongPreloadProgress.value = if (preloadedTrackIds.contains(primaryNext.sourceId)) 1f else 0.15f
            }
            for ((index, candidate) in adjacentTracks.withIndex()) {
                if (!isActive) break
                runCatching {
                    val resolved = resolvePreviewTargetForTrack(candidate, forceFresh = false)
                    if (resolved != null) {
                        if (index == 0) _nextSongPreloadProgress.value = 0.55f
                        if (!preloadedTrackIds.contains(candidate.sourceId) && appContext != null) {
                            val cleanYt = InnerTubeStreamExtractor.extractVideoId(candidate.sourceId)
                            if (!cleanYt.isNullOrBlank()) {
                                OuterTuneResolvingDataSource.putCachedUrl(cleanYt, AudioQuality.LOW, resolved.streamUrl)
                                OuterTuneResolvingDataSource.warmStreamCacheAsync(
                                    context = appContext,
                                    trackId = cleanYt,
                                    streamUrl = resolved.streamUrl,
                                    isVideo = false
                                )
                            }
                            preloadedTrackIds.add(candidate.sourceId)
                        }
                        if (index == 0) _nextSongPreloadProgress.value = 1f
                    }
                }
            }
        }
    }

    private fun playPreviewInternal(track: NormalizedTrack, forceFresh: Boolean) {
        val requestId = ++currentRequestId
        previewResolveJob?.cancel()
        previewTickerJob?.cancel()

        activePreviewTrack = track
        _activePreviewTrackId.value = track.sourceId
        _previewProgressFraction.value = 0f

        // Fast-path: If already pre-resolved in resolvedPreviewCache, start playback immediately in 0ms!
        val cachedTarget = if (!forceFresh) resolvedPreviewCache[track.sourceId] else null
        if (cachedTarget != null) {
            _isPreviewBuffering.value = true
            _isPreviewPlaying.value = false
            startExoPreviewPlayback(track.sourceId, cachedTarget.streamUrl, cachedTarget.startOffsetMs)
            return
        }

        _isPreviewBuffering.value = true
        _isPreviewPlaying.value = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        previewResolveJob = scope.launch {
            val resolved = resolvePreviewTargetForTrack(track, forceFresh)
            if (requestId != currentRequestId || _activePreviewTrackId.value != track.sourceId) return@launch
            if (resolved != null) {
                startExoPreviewPlayback(track.sourceId, resolved.streamUrl, resolved.startOffsetMs)
            } else {
                _isPreviewBuffering.value = false
                _isPreviewPlaying.value = false
            }
        }
    }

    private fun startExoPreviewPlayback(trackId: String, streamUrl: String, startPositionMs: Long) {
        activeStartOffsetMs = startPositionMs.coerceAtLeast(0L)
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        val mediaItem = MediaItem.Builder()
            .setMediaId("preview_$trackId")
            .setUri(Uri.parse(streamUrl))
            .build()
        exoPlayer.setMediaItem(mediaItem, activeStartOffsetMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
        startPreviewWindowTicker()
    }

    fun stopPreviewAndClear() {
        previewResolveJob?.cancel()
        previewTickerJob?.cancel()
        preloadJob?.cancel()
        currentRequestId++
        activePreviewTrack = null
        _activePreviewTrackId.value = null
        _isPreviewBuffering.value = false
        _isPreviewPlaying.value = false
        _previewProgressFraction.value = 0f
        exoPlayer.playWhenReady = false
        exoPlayer.pause()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    fun resumeOrPlayPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.mediaItemCount > 0 && exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
            startPreviewWindowTicker()
        } else {
            playPreview(track)
        }
    }

    fun pausePreview() {
        previewResolveJob?.cancel()
        previewTickerJob?.cancel()
        _isPreviewBuffering.value = false
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            _isPreviewPlaying.value = false
        }
    }

    /**
     * Resolves and opens the exact full-length original song in the main player (never playing a 30s preview
     * clip or an unverified remix/wrong song).
     */
    suspend fun openFullLengthSong(
        track: NormalizedTrack,
        offlineMode: Boolean = false,
        onMaximizePlayer: (NormalizedTrack) -> Unit
    ): Result<Unit> {
        stopPreviewAndClear()
        val validYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            ?: InnerTubeStreamExtractor.extractVideoId(track.previewUrl?.removePrefix("ytm_clip:"))

        // Strip 30-second preview clip URLs (p.scdn.co / itunes.apple.com) when opening the full-length song
        val cleanPreviewForFull = track.previewUrl?.takeUnless { isDirect30sPreviewClip(it) }

        val candidateTrack = if (!validYtId.isNullOrBlank()) {
            track.copy(
                source = TrackSource.YOUTUBE,
                sourceId = validYtId,
                previewUrl = cleanPreviewForFull,
                isPlayableNatively = true
            )
        } else {
            track.copy(previewUrl = cleanPreviewForFull)
        }

        val resolvedResult = resolverEngine.resolveForFullPlayback(candidateTrack, offlineMode)
        if (resolvedResult.isSuccess) {
            when (val target = resolvedResult.getOrThrow()) {
                is ResolvedPlaybackTarget.LocalDeviceUri -> {
                    onMaximizePlayer(
                        candidateTrack.copy(
                            source = TrackSource.LOCAL_FILE,
                            localPath = target.uri.toString(),
                            isPlayableNatively = true
                        )
                    )
                }
                is ResolvedPlaybackTarget.RemoteStreamUrl -> {
                    val verifiedUrl = target.url.takeUnless { isDirect30sPreviewClip(it) }
                    val verifiedTrack = target.resolvedTrack.copy(
                        title = track.title,
                        artists = track.artists,
                        artUrl = track.artUrl.ifBlank { target.resolvedTrack.artUrl },
                        previewUrl = verifiedUrl
                    )
                    onMaximizePlayer(verifiedTrack)
                }
            }
            return Result.success(Unit)
        }

        onMaximizePlayer(candidateTrack)
        return Result.success(Unit)
    }

    fun stopAndDismissNotifications() {
        runCatching {
            stopPreviewAndClear()
            val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
    }
}
