package com.seitharol.player

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.ResolvedPlaybackTarget
import com.seitharol.data.resolver.ResolverEngine
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerManager(
    val exoPlayer: ExoPlayer,
    private val resolverEngine: ResolverEngine,
    private val appContext: Context? = null
) {
    companion object {
        @Volatile
        private var activePreviewInstanceRef: WeakReference<AudioPlayerManager>? = null

        fun stopActivePreviewIfAny() {
            runCatching {
                activePreviewInstanceRef?.get()?.stopPreviewAndClear()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewResolveJob: Job? = null

    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _isPreviewBuffering = MutableStateFlow(false)
    val isPreviewBuffering: StateFlow<Boolean> = _isPreviewBuffering.asStateFlow()

    @Volatile
    private var currentRequestId: Long = 0L

    @Volatile
    private var activePreviewTrack: NormalizedTrack? = null

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
                    }
                    Player.STATE_ENDED -> {
                        exoPlayer.seekTo(0L)
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
                    OuterTuneResolvingDataSource.invalidate(track.sourceId)
                    playPreviewInternal(track, forceFresh = true)
                } else {
                    _isPreviewBuffering.value = false
                    _isPreviewPlaying.value = false
                }
            }
        })
    }

    fun playPreview(track: NormalizedTrack) {
        activePreviewInstanceRef = WeakReference(this)
        if (_activePreviewTrackId.value == track.sourceId &&
            (_isPreviewBuffering.value || exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING || previewResolveJob?.isActive == true)
        ) {
            exoPlayer.playWhenReady = true
            if (exoPlayer.playbackState == Player.STATE_READY && !exoPlayer.isPlaying) {
                exoPlayer.play()
            }
            return
        }
        hasRetriedCurrentPreview = false
        playPreviewInternal(track, forceFresh = false)
    }

    private fun playPreviewInternal(track: NormalizedTrack, forceFresh: Boolean) {
        val requestId = ++currentRequestId
        previewResolveJob?.cancel()

        activePreviewTrack = track
        _activePreviewTrackId.value = track.sourceId
        _isPreviewBuffering.value = true
        _isPreviewPlaying.value = false

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val rawPreview = track.previewUrl
        val isYoutubeWatchUrl = rawPreview != null && (
            rawPreview.contains("youtube.com/watch", ignoreCase = true) ||
                rawPreview.contains("music.youtube.com/watch", ignoreCase = true) ||
                rawPreview.contains("youtu.be/", ignoreCase = true)
            )
        // Fast-path: Direct HTTP preview URL (e.g., pre-decrypted JioSaavn or Spotify 30s MP3),
        // excluding YouTube / YouTube Music HTML watch URLs which must be resolved first!
        if (!forceFresh && rawPreview != null && rawPreview.startsWith("http") && !isYoutubeWatchUrl) {
            startExoPreviewPlayback(
                trackId = track.sourceId,
                streamUrl = rawPreview,
                startPositionMs = if (rawPreview.contains("saavncdn.com")) 25_000L else 0L
            )
            return
        }

        previewResolveJob = scope.launch(Dispatchers.IO) {
            val ytId = when {
                rawPreview != null && rawPreview.startsWith("ytm_clip:") ->
                    InnerTubeStreamExtractor.extractVideoId(rawPreview.removePrefix("ytm_clip:"))
                !rawPreview.isNullOrBlank() ->
                    InnerTubeStreamExtractor.extractVideoId(rawPreview)
                        ?: InnerTubeStreamExtractor.extractVideoId(track.sourceId)
                else -> InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            }

            var streamUrl: String? = null
            var startOffsetMs = 0L

            if (!ytId.isNullOrBlank()) {
                streamUrl = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                    ytId,
                    AudioQuality.LOW,
                    forceRefresh = forceFresh
                ).getOrNull()
                startOffsetMs = if (track.durationMs > 60_000L) 30_000L else 0L
            } else if (track.source == TrackSource.JIOSAAVN || (rawPreview != null && rawPreview.startsWith("saavn_clip:"))) {
                val saavnId = rawPreview?.removePrefix("saavn_clip:") ?: track.sourceId
                val meta = TrackMetadata(
                    id = saavnId,
                    title = track.title,
                    artist = track.artists.firstOrNull().orEmpty(),
                    album = track.album.orEmpty(),
                    durationSeconds = (track.durationMs / 1000L).toInt(),
                    thumbnailUrl = track.artUrl
                )
                streamUrl = StreamResolver.resolveExactStreamUrl(meta, AudioQuality.LOW)
                startOffsetMs = 25_000L
            }

            // Fallback 1: If direct YouTube ID resolution failed or track was non-YouTube, try JioSaavn exact match
            if (streamUrl.isNullOrBlank()) {
                runCatching {
                    val meta = TrackMetadata(
                        id = if (ytId == null) track.sourceId else "",
                        title = track.title,
                        artist = track.artists.firstOrNull().orEmpty(),
                        album = track.album.orEmpty(),
                        durationSeconds = (track.durationMs / 1000L).toInt(),
                        thumbnailUrl = track.artUrl
                    )
                    val saavnUrl = StreamResolver.resolveExactStreamUrl(meta, AudioQuality.LOW)
                    if (!saavnUrl.isNullOrBlank()) {
                        streamUrl = saavnUrl
                        startOffsetMs = 25_000L
                    }
                }
            }

            // Fallback 2: Search YouTube Music by title + artist if stream is still unresolved
            if (streamUrl.isNullOrBlank()) {
                runCatching {
                    val query = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
                    if (query.isNotBlank()) {
                        val candidates = InnerTubeStreamExtractor.searchOfficialYtmSongs(query, limit = 4)
                        for (candidate in candidates) {
                            if (candidate.videoId == ytId && !forceFresh) continue
                            val resolved = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                                candidate.videoId,
                                AudioQuality.LOW,
                                forceRefresh = forceFresh
                            ).getOrNull()
                            if (!resolved.isNullOrBlank()) {
                                streamUrl = resolved
                                startOffsetMs = if (candidate.durationSeconds > 60) 30_000L else 0L
                                break
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (requestId != currentRequestId || _activePreviewTrackId.value != track.sourceId) return@withContext
                if (!streamUrl.isNullOrBlank()) {
                    startExoPreviewPlayback(track.sourceId, streamUrl!!, startOffsetMs)
                } else {
                    _isPreviewBuffering.value = false
                    _isPreviewPlaying.value = false
                }
            }
        }
    }

    private fun startExoPreviewPlayback(trackId: String, streamUrl: String, startPositionMs: Long) {
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        val mediaItem = MediaItem.Builder()
            .setMediaId("preview_$trackId")
            .setUri(Uri.parse(streamUrl))
            .build()
        exoPlayer.setMediaItem(mediaItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun stopPreviewAndClear() {
        previewResolveJob?.cancel()
        currentRequestId++
        activePreviewTrack = null
        _activePreviewTrackId.value = null
        _isPreviewBuffering.value = false
        _isPreviewPlaying.value = false
        exoPlayer.playWhenReady = false
        exoPlayer.pause()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    fun resumeOrPlayPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.mediaItemCount > 0 && exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            playPreview(track)
        }
    }

    fun pausePreview() {
        previewResolveJob?.cancel()
        _isPreviewBuffering.value = false
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            _isPreviewPlaying.value = false
        }
    }

    suspend fun openFullLengthSong(
        track: NormalizedTrack,
        offlineMode: Boolean = false,
        onMaximizePlayer: (NormalizedTrack) -> Unit
    ): Result<Unit> {
        stopPreviewAndClear()
        val validYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            ?: InnerTubeStreamExtractor.extractVideoId(track.previewUrl?.removePrefix("ytm_clip:"))
        val candidateTrack = if (!validYtId.isNullOrBlank()) {
            track.copy(
                source = TrackSource.YOUTUBE,
                sourceId = validYtId,
                isPlayableNatively = true
            )
        } else {
            track
        }

        // Always run through resolverEngine.resolveForFullPlayback so restricted YouTube Music "Art Tracks"
        // (Topic songs) are pre-resolved and automatically fall back to alternative YTM or JioSaavn streams.
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
                    val verifiedTrack = target.resolvedTrack.copy(
                        title = track.title,
                        artists = track.artists,
                        artUrl = track.artUrl.ifBlank { target.resolvedTrack.artUrl },
                        previewUrl = target.url.ifBlank { track.previewUrl }
                    )
                    onMaximizePlayer(verifiedTrack)
                }
            }
            return Result.success(Unit)
        }

        // Fallback: hand the track directly to the main player so its multi-client/JioSaavn resolver can play it
        onMaximizePlayer(candidateTrack)
        return Result.success(Unit)
    }

    fun stopAndDismissNotifications() {
        runCatching {
            previewResolveJob?.cancel()
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            activePreviewTrack = null
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false
            val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
    }
}
