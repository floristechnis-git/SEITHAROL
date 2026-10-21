package com.example.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.model.LyricsLine
import com.example.data.model.SponsorSegment
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.resolver.ResolvedStream
import com.example.resolver.ResolverEngine
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.player.QueueRecommendationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

data class EqualizerPreset(
    val name: String,
    val bandLevels: List<Float> // 5 bands in dB (-10 to +10)
)

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val isShuffle: Boolean = false,
    val activeStream: ResolvedStream? = null,
    val activeLyricIndex: Int = -1,
    val lyricLines: List<LyricsLine> = emptyList(),
    val lyricOffsetMs: Long = 0L,
    val bassBoostPercent: Float = 25f,
    val loudnessGainDb: Float = 0f,
    val currentPresetName: String = "Flat",
    val sleepTimerMinutesLeft: Int = 0,
    val isSingleTrackPlayback: Boolean = false
)

/**
 * Modern AudioPlayerManager powered by AndroidX Media3 ExoPlayer.
 * Completely eliminates "wrong config length" MediaCodec errors by using
 * robust Matroska/MP4 demuxers and handles seamless background audio playback.
 */
class AudioPlayerManager(
    private val context: Context,
    private val resolverEngine: ResolverEngine,
    private val musicRepository: MusicRepository
) {
    private val TAG = "AudioPlayerManager"
    private var exoPlayer: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _skipEvent = MutableSharedFlow<String>()
    val skipEvent: SharedFlow<String> = _skipEvent.asSharedFlow()

    private var activeSponsorSegments: List<SponsorSegment> = emptyList()
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var loadTrackJob: Job? = null
    private var retryJob: Job? = null
    private var prefetchedTrackId: String? = null
    private var trackRetryCount = 0
    private var lastFailedTrackId: String? = null

    val playerInstance: ExoPlayer?
        get() = exoPlayer

    var isAutoplayEnabled: Boolean = true
    private var isFetchingAutoplay = false

    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    val presets = listOf(
        EqualizerPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
        EqualizerPreset("Bass Boost", listOf(8f, 5f, 0f, -1f, -2f)),
        EqualizerPreset("Vocal Clarity", listOf(-2f, 2f, 6f, 4f, 1f)),
        EqualizerPreset("Acoustic Warmth", listOf(4f, 2f, 1f, 3f, 4f)),
        EqualizerPreset("Electronic / EDM", listOf(7f, 3f, -1f, 3f, 6f)),
        EqualizerPreset("Rock Anthem", listOf(5f, 2f, -1f, 4f, 6f))
    )

    init {
        MediaPlaybackService.playerManager = this
        initExoPlayer()
        startPositionTracker()
    }

    private fun initExoPlayer() {
        try {
            exoPlayer?.release()

            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to "https://music.youtube.com/",
                        "Range" to "bytes=0-"
                    )
                )
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)

            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

            val builder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                .setHandleAudioBecomingNoisy(true)

            try {
                builder.setWakeMode(C.WAKE_MODE_LOCAL)
            } catch (t: Throwable) {
                // Ignore wake mode error if restricted in environment
            }

            exoPlayer = builder.build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    _state.value = _state.value.copy(isBuffering = true)
                                }
                                Player.STATE_READY -> {
                                    val dur = duration.coerceAtLeast(0L)
                                    val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs
                                    _state.value = _state.value.copy(
                                        isBuffering = false,
                                        durationMs = safeDur
                                    )
                                    initEqualizer(audioSessionId)
                                    if (isPlaying) {
                                        startPositionTracker()
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    onTrackCompleted()
                                }
                                Player.STATE_IDLE -> {
                                    _state.value = _state.value.copy(isBuffering = false)
                                }
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            super.onMediaItemTransition(mediaItem, reason)
                            if (isAutoplayEnabled) {
                                val isNearEnd = _state.value.currentIndex >= _state.value.queue.size - 1
                                if (isNearEnd && _state.value.queue.isNotEmpty()) {
                                    prefetchAutoplayRecommendation()
                                }
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _state.value = _state.value.copy(isPlaying = isPlaying)
                            startPositionTracker()
                            if (isPlaying) {
                                try {
                                    MediaPlaybackService.startService(context)
                                } catch (t: Throwable) {
                                    // Non-fatal
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                            val current = _state.value.currentTrack
                            retryJob?.cancel()
                            retryJob = coroutineScope.launch {
                                val title = current?.title?.ifBlank { "Track" } ?: "Track"

                                if (current != null) {
                                    if (lastFailedTrackId != current.id) {
                                        lastFailedTrackId = current.id
                                        trackRetryCount = 0
                                    }

                                    // Fail fast with clear notice if device is offline
                                    if (!com.example.util.ErrorFormatter.isOnline(context)) {
                                        _skipEvent.emit("No internet connection. Please check your network.")
                                        trackRetryCount = 0
                                        lastFailedTrackId = null
                                        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                        MediaPlaybackService.stopService(context)
                                        return@launch
                                    }

                                    if (trackRetryCount >= 3) {
                                        Log.w(TAG, "Max retries (3) reached for '$title'. Skipping to next track.")
                                        val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(error, context, title)
                                        _skipEvent.emit(friendlyMsg)
                                        trackRetryCount = 0
                                        lastFailedTrackId = null
                                        if (!_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex) {
                                            next()
                                        } else {
                                            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                            MediaPlaybackService.stopService(context)
                                        }
                                        return@launch
                                    }

                                    trackRetryCount++
                                    // Silent background reconnect delay without spamming dev logs in UI
                                    delay(800)
                                    if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                                    try {
                                        resolverEngine.clearCache()
                                        val freshStream = resolverEngine.resolveTrack(current.copy(audioUrl = ""))
                                        if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                                        _state.value = _state.value.copy(activeStream = freshStream)
                                        setupAndPlaySource(freshStream.streamUrl, current)
                                    } catch (e: Exception) {
                                        if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                                        Log.e(TAG, "Retry attempt $trackRetryCount failed: ${e.message}", e)
                                        if (trackRetryCount >= 3) {
                                            val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, title)
                                            _skipEvent.emit(friendlyMsg)
                                            trackRetryCount = 0
                                            lastFailedTrackId = null
                                            if (!_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex) {
                                                next()
                                            } else {
                                                _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                                MediaPlaybackService.stopService(context)
                                            }
                                        }
                                    }
                                } else if (!_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex) {
                                    next()
                                } else {
                                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                    MediaPlaybackService.stopService(context)
                                }
                            }
                        }
                    })
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e)
        }
    }


    private fun setupAndPlaySource(sourceUrl: String, track: Track) {
        try {
            val player = exoPlayer ?: return
            val parsedUri = when {
                sourceUrl.startsWith("file://") -> Uri.parse(sourceUrl)
                sourceUrl.startsWith("/") -> Uri.fromFile(File(sourceUrl))
                else -> Uri.parse(sourceUrl)
            }
            val mediaItem = track.toMediaItem(parsedUri)

            player.repeatMode = when (_state.value.repeatMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            _state.value = _state.value.copy(
                currentTrack = track,
                isPlaying = true,
                isBuffering = false
            )
            startPositionTracker()
            try {
                MediaPlaybackService.startService(context)
            } catch (t: Throwable) {
                // Non-fatal if foreground service is restricted
            }
        } catch (e: Throwable) {
            Log.e(TAG, "setupAndPlaySource failed", e)
            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        }
    }

    private fun startPositionTracker() {
        positionJob?.cancel()
        positionJob = coroutineScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = player.duration
                        val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs

                        // Check SponsorBlock segments
                        checkSponsorSkip(pos)

                        // Update lyrics active index
                        val currentLyrics = _state.value.lyricLines
                        val adjustedPos = pos + _state.value.lyricOffsetMs
                        val activeIdx = currentLyrics.indexOfLast { it.timestampMs <= adjustedPos }

                        _state.value = _state.value.copy(
                            currentPositionMs = pos,
                            durationMs = if (safeDur > 0L) safeDur else _state.value.durationMs,
                            activeLyricIndex = activeIdx
                        )

                        // Next-Track Stream Prefetching at 75% progress
                        if (safeDur > 0L && (pos.toFloat() / safeDur.toFloat()) > 0.75f) {
                            prefetchNextTrack()
                        }
                    }
                }
                delay(200)
            }
        }
    }

    private fun prefetchNextTrack() {
        val q = _state.value.queue
        val nextIndex = _state.value.currentIndex + 1
        if (nextIndex in q.indices) {
            val nextTrack = q[nextIndex]
            if (prefetchedTrackId != nextTrack.id) {
                prefetchedTrackId = nextTrack.id
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Prefetching stream for next track: ${nextTrack.title} (${nextTrack.id})")
                        resolverEngine.resolveTrack(nextTrack)
                    } catch (e: Exception) {
                        Log.w(TAG, "Prefetch failed for ${nextTrack.id}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun checkSponsorSkip(currentPos: Long) {
        val segmentToSkip = activeSponsorSegments.firstOrNull { seg ->
            seg.autoSkip && currentPos >= seg.startMs && currentPos < seg.endMs - 300
        }
        if (segmentToSkip != null) {
            exoPlayer?.seekTo(segmentToSkip.endMs)
            coroutineScope.launch {
                _skipEvent.emit("SponsorBlock: Skipped ${segmentToSkip.category.displayName}")
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        trackRetryCount = 0
        lastFailedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        val validIndex = startIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(
            queue = tracks,
            currentIndex = validIndex,
            currentTrack = tracks[validIndex],
            isSingleTrackPlayback = false
        )
        loadAndPlayCurrent()
    }

    fun playTrack(track: Track) {
        trackRetryCount = 0
        lastFailedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        val currentQueue = _state.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == track.id }
        if (index != -1) {
            _state.value = _state.value.copy(
                currentIndex = index,
                currentTrack = track,
                isSingleTrackPlayback = true
            )
        } else {
            currentQueue.add(0, track)
            _state.value = _state.value.copy(
                queue = currentQueue,
                currentIndex = 0,
                currentTrack = track,
                isSingleTrackPlayback = true
            )
        }
        loadAndPlayCurrent()
    }

    fun playQueueIndex(index: Int) {
        val q = _state.value.queue
        if (index in q.indices) {
            loadTrackJob?.cancel()
            retryJob?.cancel()
            _state.value = _state.value.copy(currentIndex = index, currentTrack = q[index])
            loadAndPlayCurrent()
        }
    }

    fun removeFromQueue(index: Int) {
        val q = _state.value.queue.toMutableList()
        if (index in q.indices) {
            val wasCurrent = index == _state.value.currentIndex
            q.removeAt(index)
            val newIndex = when {
                q.isEmpty() -> 0
                index < _state.value.currentIndex -> _state.value.currentIndex - 1
                index == _state.value.currentIndex -> index.coerceAtMost(q.lastIndex)
                else -> _state.value.currentIndex
            }
            _state.value = _state.value.copy(queue = q, currentIndex = newIndex)
            if (wasCurrent && q.isNotEmpty()) {
                loadAndPlayCurrent()
            }
        }
    }

    /**
     * Reorders the queue by moving the item at [fromIndex] to [toIndex].
     * The currently-playing index is updated to follow the moved track.
     */
    fun moveTrack(fromIndex: Int, toIndex: Int) {
        val q = _state.value.queue.toMutableList()
        if (fromIndex !in q.indices || toIndex !in q.indices || fromIndex == toIndex) return
        val currentIdx = _state.value.currentIndex
        val movedTrack = q.removeAt(fromIndex)
        q.add(toIndex, movedTrack)
        // Recalculate the current index so the playing track stays correct
        val newCurrentIndex = when {
            currentIdx == fromIndex -> toIndex
            fromIndex < currentIdx && toIndex >= currentIdx -> currentIdx - 1
            fromIndex > currentIdx && toIndex <= currentIdx -> currentIdx + 1
            else -> currentIdx
        }
        _state.value = _state.value.copy(queue = q, currentIndex = newCurrentIndex)
        try {
            exoPlayer?.let { player ->
                if (fromIndex in 0 until player.mediaItemCount && toIndex in 0 until player.mediaItemCount) {
                    player.moveMediaItem(fromIndex, toIndex)
                }
            }
        } catch (t: Throwable) {
            // Ignore if items are resolved dynamically on-demand
        }
    }

    fun clearQueue() {
        val current = _state.value.currentTrack
        if (current != null) {
            _state.value = _state.value.copy(queue = listOf(current), currentIndex = 0)
        } else {
            _state.value = _state.value.copy(queue = emptyList(), currentIndex = 0)
        }
    }

    fun stopPlayback() {
        positionJob?.cancel()
        loadTrackJob?.cancel()
        retryJob?.cancel()
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (t: Throwable) {
            // Ignore
        }
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = false,
            currentPositionMs = 0L,
            currentTrack = null,
            queue = emptyList(),
            currentIndex = 0
        )
    }

    fun addToQueue(track: Track) {
        val q = _state.value.queue.toMutableList()
        q.add(track)
        _state.value = _state.value.copy(queue = q)
    }

    fun playNext(track: Track) {
        val q = _state.value.queue.toMutableList()
        val insertIndex = (_state.value.currentIndex + 1).coerceAtMost(q.size)
        q.add(insertIndex, track)
        _state.value = _state.value.copy(queue = q)
    }

    private fun loadAndPlayCurrent() {
        val current = _state.value.queue.getOrNull(_state.value.currentIndex) ?: return
        prefetchedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        loadTrackJob = coroutineScope.launch {
            try {
                val localLyrics = parseLrc(current.lyrics)
                val expectedDuration = if (current.durationMs > 0L) current.durationMs else 210000L

                _state.value = _state.value.copy(
                    currentTrack = current,
                    isBuffering = true,
                    durationMs = expectedDuration,
                    currentPositionMs = 0L,
                    lyricLines = localLyrics,
                    activeLyricIndex = -1
                )

                // Save checkpoint in local DB
                musicRepository.saveCheckpoint(
                    trackId = current.id,
                    index = _state.value.currentIndex,
                    posMs = 0L,
                    queueIds = _state.value.queue.map { it.id },
                    shuffle = _state.value.isShuffle,
                    repeat = _state.value.repeatMode.ordinal
                )

                // Fetch SponsorBlock segments
                activeSponsorSegments = musicRepository.getSponsorSegments(current.id)

                // Fetch synchronized lyrics from LRCLIB if local lyrics are empty
                if (localLyrics.isEmpty()) {
                    coroutineScope.launch {
                        val lrclibLines = com.example.network.NetworkClients.fetchLrclibLyrics(
                            artist = current.artist,
                            title = current.title,
                            durationSec = expectedDuration / 1000L
                        )
                        if (lrclibLines.isNotEmpty() && _state.value.currentTrack?.id == current.id) {
                            _state.value = _state.value.copy(lyricLines = lrclibLines)
                        }
                    }
                }

                // Verify local file if marked as downloaded, or search local internal storage
                val downloadDir = File(context.filesDir, "downloads")
                val safeId = current.id.replace(Regex("[^A-Za-z0-9_]"), "_")
                val isContentUri = current.localPath?.startsWith("content://") == true
                val isContentReadable = if (isContentUri) {
                    runCatching {
                        context.contentResolver.openAssetFileDescriptor(Uri.parse(current.localPath), "r")?.use { afd ->
                            afd.length != 0L
                        } ?: false
                    }.getOrDefault(false)
                } else false

                val safeTrack = if (isContentReadable) {
                    current.copy(isDownloaded = true, localPath = current.localPath)
                } else {
                    val localCandidates = listOfNotNull(
                        current.localPath?.takeIf { !it.startsWith("content://") }?.let { File(it.removePrefix("file://")) },
                        File(downloadDir, "$safeId.audio"),
                        File(downloadDir, "${current.id}.audio"),
                        File(downloadDir, "${current.id}_high.opus"),
                        File(downloadDir, "${current.id}_high.m4a"),
                        File(downloadDir, "${current.id}_original.opus"),
                        File(downloadDir, "${current.id}_original.m4a"),
                        File(downloadDir, "${current.id}_standard.opus"),
                        File(downloadDir, "${current.id}_standard.m4a"),
                        File(downloadDir, "${current.id}_data saver.m4a")
                    )
                    val foundLocal = localCandidates.firstOrNull { it.exists() && it.length() > 0L }
                    if (foundLocal != null) {
                        current.copy(isDownloaded = true, localPath = foundLocal.absolutePath)
                    } else {
                        current.copy(isDownloaded = false, localPath = null)
                    }
                }

                // Record listening history in Room
                musicRepository.recordPlayHistory(safeTrack)

                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch

                // Resolve Stream using ResolverEngine
                val stream = resolverEngine.resolveTrack(safeTrack)

                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch

                _state.value = _state.value.copy(activeStream = stream)

                setupAndPlaySource(stream.streamUrl, safeTrack)
            } catch (e: Exception) {
                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                Log.e(TAG, "Error loading track: ${e.message}", e)
                _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                val friendlyMessage = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, current.title)
                _skipEvent.emit(friendlyMessage)
                if (!_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex) {
                    next()
                }
            }
        }
    }

    private fun initEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        try {
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Equalizer not supported on this session: ${t.message}")
        }
    }

    fun playPreview(trackId: String, previewUrl: String) {
        val player = exoPlayer ?: return
        if (_activePreviewTrackId.value == trackId && player.isPlaying) return

        _activePreviewTrackId.value = trackId
        player.repeatMode = Player.REPEAT_MODE_ONE
        val mediaItem = MediaItem.Builder()
            .setMediaId("preview_$trackId")
            .setUri(Uri.parse(previewUrl))
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        _isPreviewPlaying.value = true
    }

    fun pausePreview() {
        exoPlayer?.let { player ->
            if (_activePreviewTrackId.value != null) {
                player.playWhenReady = false
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                _isPreviewPlaying.value = false
            }
        }
    }

    fun stopPreviewAndClear() {
        exoPlayer?.let { player ->
            if (_activePreviewTrackId.value != null) {
                player.stop()
                player.repeatMode = Player.REPEAT_MODE_OFF
                _activePreviewTrackId.value = null
                _isPreviewPlaying.value = false
            }
        }
    }

    fun pause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            if (_state.value.currentTrack != null) {
                player.play()
                _state.value = _state.value.copy(isPlaying = true)
                MediaPlaybackService.startService(context)
            } else if (_state.value.queue.isNotEmpty()) {
                loadAndPlayCurrent()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        exoPlayer?.seekTo(clamped)
        val activeIdx = _state.value.lyricLines.indexOfLast { it.timestampMs <= clamped + _state.value.lyricOffsetMs }
        _state.value = _state.value.copy(currentPositionMs = clamped, activeLyricIndex = activeIdx)
    }

    fun next() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return

        val nextIndex = if (_state.value.isShuffle) {
            (0 until queue.size).filter { it != _state.value.currentIndex }.randomOrNull() ?: 0
        } else {
            (_state.value.currentIndex + 1) % queue.size
        }
        _state.value = _state.value.copy(currentIndex = nextIndex)
        loadAndPlayCurrent()
    }

    fun previous() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return

        if (_state.value.currentPositionMs > 4000) {
            seekTo(0)
            return
        }

        val prevIndex = if (_state.value.currentIndex > 0) {
            _state.value.currentIndex - 1
        } else {
            queue.lastIndex
        }
        _state.value = _state.value.copy(currentIndex = prevIndex)
        loadAndPlayCurrent()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.value = _state.value.copy(repeatMode = mode)
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(next)
    }

    fun toggleShuffle() {
        _state.value = _state.value.copy(isShuffle = !_state.value.isShuffle)
    }

    fun adjustLyricOffset(deltaMs: Long) {
        val newOffset = _state.value.lyricOffsetMs + deltaMs
        _state.value = _state.value.copy(lyricOffsetMs = newOffset)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerMinutesLeft = 0)
            return
        }

        _state.value = _state.value.copy(sleepTimerMinutesLeft = minutes)
        sleepTimerJob = coroutineScope.launch {
            var left = minutes
            while (left > 0) {
                delay(60_000)
                left--
                _state.value = _state.value.copy(sleepTimerMinutesLeft = left)
            }
            // Timer expired: gently pause playback
            exoPlayer?.pause()
            _state.value = _state.value.copy(isPlaying = false, sleepTimerMinutesLeft = 0)
            _skipEvent.emit("Sleep timer finished. Playback paused.")
        }
    }

    fun setPreset(preset: EqualizerPreset) {
        _state.value = _state.value.copy(currentPresetName = preset.name)
        equalizer?.let { eq ->
            try {
                val numBands = eq.numberOfBands.toInt()
                val minLevel = eq.bandLevelRange[0]
                val maxLevel = eq.bandLevelRange[1]
                for (i in 0 until numBands) {
                    val db = preset.bandLevels.getOrNull(i) ?: 0f
                    // Map -10dB..+10dB to minLevel..maxLevel (millibels)
                    val targetMilliBels = (db * 100).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt())
                    eq.setBandLevel(i.toShort(), targetMilliBels.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting preset: ${e.message}")
            }
        }
    }

    fun setBassBoost(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        _state.value = _state.value.copy(bassBoostPercent = clamped)
        // Adjust lower band of equalizer if active
        equalizer?.let { eq ->
            try {
                if (eq.numberOfBands > 0) {
                    val level = ((clamped / 100f) * 1000).toInt().toShort()
                    eq.setBandLevel(0, level)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) = setPreset(preset)

    fun setLoudnessGain(gainDb: Float) {
        _state.value = _state.value.copy(loudnessGainDb = gainDb)
    }

    private fun onTrackCompleted() {
        runCatching {
            when (_state.value.repeatMode) {
                RepeatMode.ONE -> {
                    seekTo(0)
                    exoPlayer?.play()
                    _state.value = _state.value.copy(isPlaying = true)
                }
                RepeatMode.ALL -> {
                    next()
                }
                RepeatMode.OFF -> {
                    val isLast = _state.value.currentIndex >= _state.value.queue.lastIndex
                    if (isLast) {
                        if (isAutoplayEnabled) {
                            triggerAutoplayNext()
                        } else {
                            _state.value = _state.value.copy(isPlaying = false, currentPositionMs = 0L)
                        }
                    } else {
                        next()
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Error in onTrackCompleted", e)
        }
    }

    private fun prefetchAutoplayRecommendation() {
        if (isFetchingAutoplay) return
        val current = _state.value.currentTrack ?: return
        isFetchingAutoplay = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val queueMeta = _state.value.queue.map {
                    TrackMetadata(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        durationSeconds = (it.durationMs / 1000).toInt(),
                        thumbnailUrl = it.artUrl
                    )
                }
                val seedMeta = TrackMetadata(
                    id = current.id,
                    title = current.title,
                    artist = current.artist,
                    durationSeconds = (current.durationMs / 1000).toInt(),
                    thumbnailUrl = current.artUrl
                )
                val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    limit = 6
                )
                val nextRec = recommendations.firstOrNull { rec ->
                    _state.value.queue.none { it.id == rec.id }
                }
                if (nextRec != null) {
                    val newTrack = Track(
                        id = nextRec.id,
                        title = nextRec.title,
                        artist = nextRec.artist,
                        album = "",
                        durationMs = nextRec.durationSeconds * 1000L,
                        artUrl = nextRec.thumbnailUrl
                    )
                    musicRepository.insertTrack(newTrack)
                    withContext(Dispatchers.Main) {
                        addToQueue(newTrack)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch autoplay failed: ${e.message}")
            } finally {
                isFetchingAutoplay = false
            }
        }
    }

    fun triggerAutoplayNext() {
        if (isFetchingAutoplay) return
        val current = _state.value.currentTrack ?: _state.value.queue.lastOrNull()
        if (current == null) {
            _state.value = _state.value.copy(isPlaying = false, currentPositionMs = 0L)
            return
        }
        isFetchingAutoplay = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val queueMeta = _state.value.queue.map {
                    TrackMetadata(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        durationSeconds = (it.durationMs / 1000).toInt(),
                        thumbnailUrl = it.artUrl
                    )
                }
                val seedMeta = TrackMetadata(
                    id = current.id,
                    title = current.title,
                    artist = current.artist,
                    durationSeconds = (current.durationMs / 1000).toInt(),
                    thumbnailUrl = current.artUrl
                )
                val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    limit = 6
                )
                val nextRec = recommendations.firstOrNull { rec ->
                    _state.value.queue.none { it.id == rec.id }
                }
                if (nextRec != null) {
                    val newTrack = Track(
                        id = nextRec.id,
                        title = nextRec.title,
                        artist = nextRec.artist,
                        album = "",
                        durationMs = nextRec.durationSeconds * 1000L,
                        artUrl = nextRec.thumbnailUrl
                    )
                    musicRepository.insertTrack(newTrack)
                    withContext(Dispatchers.Main) {
                        addToQueue(newTrack)
                        next()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(isPlaying = false, currentPositionMs = 0L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay recommendation fetch failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(isPlaying = false, currentPositionMs = 0L)
                }
            } finally {
                isFetchingAutoplay = false
            }
        }
    }

    private fun parseLrc(lrcText: String): List<LyricsLine> {
        if (lrcText.isBlank()) return emptyList()
        val rawEntries = mutableListOf<Triple<Long, String, String?>>()
        val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")

        for (line in lrcText.lines()) {
            val match = regex.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues[3].padEnd(3, '0').take(3)
                val ms = fracStr.toLongOrNull() ?: 0L
                val timeMs = (min * 60_000L) + (sec * 1_000L) + ms
                val (primary, translation) = cleanCaretTranslation(match.groupValues[4])
                rawEntries.add(Triple(timeMs, primary, translation))
            }
        }

        if (rawEntries.isEmpty()) return emptyList()
        rawEntries.sortBy { it.first }

        val output = mutableListOf<LyricsLine>()
        val firstVocalTime = rawEntries.first().first
        if (firstVocalTime >= 4500L) {
            output.add(LyricsLine(timestampMs = 0L, text = "", isInstrumental = true))
        }

        for (i in rawEntries.indices) {
            val (startMs, primary, translation) = rawEntries[i]
            val nextStartMs = if (i + 1 < rawEntries.size) rawEntries[i + 1].first else startMs + 5000L
            if (primary.isBlank()) {
                output.add(LyricsLine(timestampMs = startMs, text = "", isInstrumental = true))
            } else {
                val vocalDuration = nextStartMs - startMs
                if (vocalDuration > 8000L) {
                    val vocalEnd = startMs + 4500L
                    output.add(LyricsLine(timestampMs = startMs, text = primary, translation = translation, isInstrumental = false))
                    output.add(LyricsLine(timestampMs = vocalEnd, text = "", isInstrumental = true))
                } else {
                    output.add(LyricsLine(timestampMs = startMs, text = primary, translation = translation, isInstrumental = false))
                }
            }
        }
        return output
    }

    private fun cleanCaretTranslation(rawText: String): Pair<String, String?> {
        val sanitized = rawText.replace("{", "").replace("}", "").trim()
        if (!sanitized.contains("^")) {
            return sanitized to null
        }
        val parts = sanitized.split("^")
        val primary = parts[0].trim()
        val secondary = parts.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
        return primary to secondary
    }

    fun release() {
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        equalizer?.release()
        equalizer = null
        exoPlayer?.release()
        exoPlayer = null
        coroutineScope.cancel()
    }
}
