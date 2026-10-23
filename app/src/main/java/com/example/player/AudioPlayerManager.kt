package com.example.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.example.data.model.LyricsLine
import com.example.data.model.SponsorSegment
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.resolver.ResolvedStream
import com.example.resolver.ResolverEngine
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.InnerTubeStreamExtractor
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
    val bandLevels: List<Float> // 5 bands in dB (-12 to +12)
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
    val isSingleTrackPlayback: Boolean = false,
    val isPartOfPlaylist: Boolean = false
)

/**
 * Modern AudioPlayerManager powered by AndroidX Media3 ExoPlayer.
 * Uses OuterTuneResolvingDataSource with client-matched User-Agents, fast error policy,
 * 14s buffering watchdog, non-blocking IO track resolution, and strict canonical failover.
 */
class AudioPlayerManager(
    private val context: Context,
    private val resolverEngine: ResolverEngine,
    private val musicRepository: MusicRepository
) {
    private val TAG = "AudioPlayerManager"
    private var exoPlayer: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boundAudioSessionId: Int = -1
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception in AudioPlayerManager: ${throwable.message}", throwable)
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _skipEvent = MutableSharedFlow<String>()
    val skipEvent: SharedFlow<String> = _skipEvent.asSharedFlow()

    private var activeSponsorSegments: List<SponsorSegment> = emptyList()
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var loadTrackJob: Job? = null
    private var retryJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var prefetchedTrackId: String? = null
    private var trackRetryCount = 0
    private var lastFailedTrackId: String? = null
    @Volatile
    private var isRetryingOrLoading: Boolean = false
    private val appPreferences = com.example.data.AppPreferences(context)
    private val settingsManager = AppSettingsManager.getInstance(context)
    private var lastUserSeekTimestamp: Long = 0L

    val playerInstance: ExoPlayer?
        get() = exoPlayer

    fun ensurePlayerInitialized() {
        if (exoPlayer == null) {
            initExoPlayer()
            startPositionTracker()
        }
    }

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
        observeAudioSettings()
    }

    private fun observeAudioSettings() {
        coroutineScope.launch {
            settingsManager.state.collect { persisted ->
                applyPersistedEqualizerAndEffects(persisted.eqEnabled, persisted.eqBandsDb, persisted.audioNormalization)
            }
        }
    }

    private fun applyPersistedEqualizerAndEffects(
        eqEnabled: Boolean,
        bandsDb: List<Float>,
        normalizeVolume: Boolean
    ) {
        equalizer?.let { eq ->
            try {
                eq.enabled = eqEnabled
                val numBands = eq.numberOfBands.toInt()
                val minLevel = eq.bandLevelRange[0].toInt()
                val maxLevel = eq.bandLevelRange[1].toInt()
                for (i in 0 until numBands) {
                    val db = if (eqEnabled) (bandsDb.getOrNull(i) ?: 0f) else 0f
                    val targetMilliBels = (db * 100f).toInt().coerceIn(minLevel, maxLevel)
                    eq.setBandLevel(i.toShort(), targetMilliBels.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed applying persisted EQ: ${e.message}")
            }
        }

        loudnessEnhancer?.let { enhancer ->
            try {
                val shouldEnable = normalizeVolume || _state.value.loudnessGainDb != 0f
                enhancer.enabled = shouldEnable
                if (shouldEnable) {
                    val targetGainMb = if (normalizeVolume) {
                        600 // +6 dB gentle loudness normalization
                    } else {
                        (_state.value.loudnessGainDb * 100f).toInt()
                    }
                    enhancer.setTargetGain(targetGainMb)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer update failed: ${e.message}")
            }
        }
    }

    private fun startBufferingWatchdog(expectedTrackId: String) {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = coroutineScope.launch {
            delay(14_000L)
            if (!isActive) return@launch
            val current = _state.value.currentTrack ?: return@launch
            val player = exoPlayer ?: return@launch
            if (current.id == expectedTrackId && (_state.value.isBuffering || isRetryingOrLoading) && !player.isPlaying) {
                Log.w(TAG, "Buffering watchdog triggered after 14s for '${current.title}', rotating stream source...")
                resolverEngine.invalidateTrack(current.id)
                try {
                    val freshStream = withContext(Dispatchers.IO) {
                        withTimeout(10_000L) {
                            resolverEngine.resolveTrack(
                                track = current.copy(audioUrl = ""),
                                preferredQuality = com.seitharol.data.resolver.AudioQuality.LOW,
                                forceRefresh = true,
                                preferNonYoutubeFallback = false
                            )
                        }
                    }
                    if (isActive && _state.value.currentTrack?.id == expectedTrackId) {
                        _state.value = _state.value.copy(activeStream = freshStream, isBuffering = true)
                        setupAndPlaySource(freshStream.streamUrl, current)
                    }
                } catch (e: Exception) {
                    isRetryingOrLoading = false
                    _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                }
            }
        }
    }

    private fun initExoPlayer() {
        try {
            exoPlayer?.release()

            val resolvingFactory = com.seitharol.player.OuterTuneResolvingDataSource.createFactory {
                getPreferredAudioQuality()
            }
            val dataSourceFactory = DefaultDataSource.Factory(context, resolvingFactory)
            // Use minimumLoadableRetryCount = 1 so ExoPlayer fails fast instead of hanging for 45s on a dead URL
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 1))

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000
                )
                .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
                .build()

            val builder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
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
                                _state.value.currentTrack?.id?.let { startBufferingWatchdog(it) }
                            }
                            Player.STATE_READY -> {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                val dur = duration.coerceAtLeast(0L)
                                val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs
                                _state.value = _state.value.copy(
                                    isBuffering = false,
                                    isPlaying = playWhenReady,
                                    durationMs = safeDur
                                )
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                initEqualizer(audioSessionId)
                                if (isPlaying || playWhenReady) {
                                    startPositionTracker()
                                }
                            }
                            Player.STATE_ENDED -> {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                onTrackCompleted()
                            }
                            Player.STATE_IDLE -> {
                                if (!isRetryingOrLoading) {
                                    bufferingWatchdogJob?.cancel()
                                    _state.value = _state.value.copy(isBuffering = false)
                                }
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        super.onMediaItemTransition(mediaItem, reason)
                        val current = _state.value.currentTrack
                        val isCurrentOffline = current?.isDownloaded == true || !current?.localPath.isNullOrBlank()
                        if (isAutoplayEnabled && !isCurrentOffline) {
                            val isNearEnd = _state.value.currentIndex >= _state.value.queue.size - 1
                            if (isNearEnd && _state.value.queue.isNotEmpty()) {
                                prefetchAutoplayRecommendation()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            bufferingWatchdogJob?.cancel()
                            isRetryingOrLoading = false
                        }
                        if (!isPlaying && isRetryingOrLoading) {
                            _state.value = _state.value.copy(isPlaying = false, isBuffering = true)
                            return
                        }
                        _state.value = _state.value.copy(
                            isPlaying = isPlaying,
                            isBuffering = if (isPlaying) false else _state.value.isBuffering
                        )
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
                        val resumePositionMs = (exoPlayer?.currentPosition?.takeIf { it > 0L } ?: _state.value.currentPositionMs).coerceAtLeast(0L)
                        isRetryingOrLoading = true
                        _state.value = _state.value.copy(isPlaying = false, isBuffering = true)
                        val current = _state.value.currentTrack
                        val currentId = current?.id ?: run {
                            isRetryingOrLoading = false
                            _state.value = _state.value.copy(isBuffering = false)
                            return
                        }
                        resolverEngine.invalidateTrack(currentId)

                        retryJob?.cancel()
                        retryJob = coroutineScope.launch {
                            val title = current.title.trim().trim('"', '\'').ifBlank { current.artist.ifBlank { "Track" } }
                            val hasNextTrack = !_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex

                            if (lastFailedTrackId != current.id) {
                                lastFailedTrackId = current.id
                                trackRetryCount = 0
                            }

                            if (!com.example.util.ErrorFormatter.isOnline(context)) {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                _skipEvent.emit("No internet connection. Please check your network.")
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                MediaPlaybackService.stopService(context)
                                return@launch
                            }

                            // Allow up to 2 fast retries before stopping or advancing
                            if (trackRetryCount >= 2) {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                Log.w(TAG, "Retry attempts exhausted for '$title'.")
                                val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(error, context, title, hasNextTrack)
                                _skipEvent.emit(friendlyMsg)
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                if (hasNextTrack) {
                                    next()
                                } else {
                                    try {
                                        exoPlayer?.stop()
                                        exoPlayer?.clearMediaItems()
                                    } catch (_: Throwable) {}
                                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                    MediaPlaybackService.stopService(context)
                                }
                                return@launch
                            }

                            trackRetryCount++
                            delay(150L)
                            if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                            try {
                                // Strictly re-resolve the exact original track ID (rotating to the next verified InnerTube client)
                                // with ZERO cross-provider or remix fallback searches!
                                val freshStream = withTimeout(10_000L) {
                                    resolverEngine.resolveTrack(
                                        track = current.copy(audioUrl = ""),
                                        preferredQuality = getPreferredAudioQuality(),
                                        forceRefresh = true,
                                        preferNonYoutubeFallback = false
                                    )
                                }
                                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                                _state.value = _state.value.copy(activeStream = freshStream, isBuffering = true)
                                setupAndPlaySource(freshStream.streamUrl, current, startPositionMs = resumePositionMs)
                            } catch (e: Exception) {
                                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                                Log.e(TAG, "Retry attempt $trackRetryCount failed: ${e.message}", e)
                                if (trackRetryCount == 1) {
                                    trackRetryCount++
                                    runCatching {
                                        val secondStream = withTimeout(10_000L) {
                                            resolverEngine.resolveTrack(
                                                track = current.copy(audioUrl = ""),
                                                preferredQuality = com.seitharol.data.resolver.AudioQuality.LOW,
                                                forceRefresh = true,
                                                preferNonYoutubeFallback = false
                                            )
                                        }
                                        if (isActive && _state.value.currentTrack?.id == current.id) {
                                            _state.value = _state.value.copy(activeStream = secondStream, isBuffering = true)
                                            setupAndPlaySource(secondStream.streamUrl, current, startPositionMs = resumePositionMs)
                                            return@launch
                                        }
                                    }
                                }
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, title, hasNextTrack)
                                _skipEvent.emit(friendlyMsg)
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                if (hasNextTrack) {
                                    next()
                                } else {
                                    try {
                                        exoPlayer?.stop()
                                        exoPlayer?.clearMediaItems()
                                    } catch (_: Throwable) {}
                                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                    MediaPlaybackService.stopService(context)
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e)
        }
    }

    private fun setupAndPlaySource(sourceUrl: String, track: Track, startPositionMs: Long = 0L) {
        try {
            ensurePlayerInitialized()
            val player = exoPlayer ?: return
            isRetryingOrLoading = true
            startBufferingWatchdog(track.id)
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

            _state.value = _state.value.copy(
                currentTrack = track,
                currentPositionMs = if (startPositionMs > 0L) startPositionMs else _state.value.currentPositionMs,
                isBuffering = true
            )

            if (startPositionMs > 0L) {
                player.setMediaItem(mediaItem, startPositionMs)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.playWhenReady = true
            player.prepare()
            player.play()

            startPositionTracker()
            try {
                MediaPlaybackService.startService(context)
            } catch (t: Throwable) {
                // Non-fatal if foreground service is restricted
            }
        } catch (e: Throwable) {
            bufferingWatchdogJob?.cancel()
            isRetryingOrLoading = false
            Log.e(TAG, "setupAndPlaySource failed", e)
            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        }
    }

    private fun startPositionTracker() {
        positionJob?.cancel()
        positionJob = coroutineScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    val isPastGracePeriod = (System.currentTimeMillis() - lastUserSeekTimestamp) > 700L
                    if (player.isPlaying && isPastGracePeriod) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = player.duration
                        val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs

                        checkSponsorSkip(pos)

                        val currentLyrics = _state.value.lyricLines
                        val adjustedPos = pos + _state.value.lyricOffsetMs
                        val activeIdx = currentLyrics.indexOfLast { it.timestampMs <= adjustedPos }

                        _state.value = _state.value.copy(
                            currentPositionMs = pos,
                            durationMs = if (safeDur > 0L) safeDur else _state.value.durationMs,
                            activeLyricIndex = activeIdx
                        )

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
                        resolverEngine.resolveTrack(nextTrack, getPreferredAudioQuality())
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

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, isPlaylist: Boolean = true) {
        if (tracks.isEmpty()) return
        trackRetryCount = 0
        lastFailedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
        val validIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val isOfflineQueue = tracks.isNotEmpty() && (tracks.any { it.isDownloaded || !it.localPath.isNullOrBlank() } || tracks[validIndex].isDownloaded || !tracks[validIndex].localPath.isNullOrBlank())
        if (isOfflineQueue) {
            isAutoplayEnabled = false
        }
        _state.value = _state.value.copy(
            queue = tracks,
            currentIndex = validIndex,
            currentTrack = tracks[validIndex],
            isSingleTrackPlayback = !isPlaylist,
            isPartOfPlaylist = isPlaylist
        )
        loadAndPlayCurrent()
    }

    fun playTrack(track: Track) {
        trackRetryCount = 0
        lastFailedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
        if (track.isDownloaded || !track.localPath.isNullOrBlank()) {
            isAutoplayEnabled = false
        }
        val currentQueue = _state.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == track.id }
        if (index != -1) {
            _state.value = _state.value.copy(
                currentIndex = index,
                currentTrack = track,
                isSingleTrackPlayback = true,
                isPartOfPlaylist = false
            )
        } else {
            currentQueue.add(0, track)
            _state.value = _state.value.copy(
                queue = currentQueue,
                currentIndex = 0,
                currentTrack = track,
                isSingleTrackPlayback = true,
                isPartOfPlaylist = false
            )
        }
        loadAndPlayCurrent()
    }

    fun playQueueIndex(index: Int) {
        val q = _state.value.queue
        if (index in q.indices) {
            trackRetryCount = 0
            lastFailedTrackId = null
            loadTrackJob?.cancel()
            retryJob?.cancel()
            bufferingWatchdogJob?.cancel()
            _state.value = _state.value.copy(currentIndex = index, currentTrack = q[index])
            loadAndPlayCurrent()
        }
    }

    fun updateQueuePreservingPlayback(newQueue: List<Track>) {
        if (newQueue.isEmpty()) return
        val current = _state.value.currentTrack
        if (current == null) {
            playQueue(newQueue, 0, isPlaylist = false)
            return
        }
        val currentIdx = newQueue.indexOfFirst { it.id == current.id }
        if (currentIdx >= 0) {
            _state.value = _state.value.copy(
                queue = newQueue,
                currentIndex = currentIdx
            )
        } else {
            val merged = listOf(current) + newQueue.filter { it.id != current.id }
            _state.value = _state.value.copy(
                queue = merged,
                currentIndex = 0
            )
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

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        val q = _state.value.queue.toMutableList()
        if (fromIndex !in q.indices || toIndex !in q.indices || fromIndex == toIndex) return
        val currentIdx = _state.value.currentIndex
        val movedTrack = q.removeAt(fromIndex)
        q.add(toIndex, movedTrack)
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
        } catch (_: Throwable) {}
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
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = false
        positionJob?.cancel()
        loadTrackJob?.cancel()
        retryJob?.cancel()
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (_: Throwable) {}
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = false,
            currentPositionMs = 0L,
            currentTrack = null,
            queue = emptyList(),
            currentIndex = 0
        )
        try {
            MediaPlaybackService.stopService(context)
        } catch (_: Throwable) {}
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
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = true
        startBufferingWatchdog(current.id)

        val stopImmediately = settingsManager.state.value.stopImmediatelyOnNewTrack
        if (stopImmediately) {
            try {
                exoPlayer?.pause()
            } catch (_: Throwable) {}
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = true
            )
        } else {
            _state.value = _state.value.copy(isBuffering = true)
        }

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

                // Fetch synchronized lyrics asynchronously without delaying stream resolution
                if (localLyrics.isEmpty()) {
                    coroutineScope.launch(Dispatchers.IO) {
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

                // Perform all file-system checks & stream resolution off the Main thread with a 12s timeout bound
                val preferredQuality = getPreferredAudioQuality()
                val (safeTrack, stream) = withContext(Dispatchers.IO) {
                    val resolvedLocalTrack = resolveLocalTrackIfAvailable(current)

                    // Save checkpoint & history asynchronously on IO
                    launch {
                        runCatching {
                            musicRepository.saveCheckpoint(
                                trackId = resolvedLocalTrack.id,
                                index = _state.value.currentIndex,
                                posMs = 0L,
                                queueIds = _state.value.queue.map { it.id },
                                shuffle = _state.value.isShuffle,
                                repeat = _state.value.repeatMode.ordinal
                            )
                            activeSponsorSegments = musicRepository.getSponsorSegments(resolvedLocalTrack.id)
                            musicRepository.recordPlayHistory(resolvedLocalTrack)
                        }
                    }

                    val resolvedStream = withTimeout(12_000L) {
                        resolverEngine.resolveTrack(resolvedLocalTrack, preferredQuality)
                    }
                    resolvedLocalTrack to resolvedStream
                }

                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch

                _state.value = _state.value.copy(activeStream = stream, isBuffering = true)
                setupAndPlaySource(stream.streamUrl, safeTrack)
            } catch (e: Exception) {
                if (!isActive || _state.value.currentTrack?.id != current.id) return@launch
                bufferingWatchdogJob?.cancel()
                isRetryingOrLoading = false
                Log.e(TAG, "Error loading track: ${e.message}", e)
                try {
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                } catch (_: Throwable) {}
                _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                val hasNextTrack = !_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex
                val friendlyMessage = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, current.title, hasNextTrack)
                _skipEvent.emit(friendlyMessage)
                if (hasNextTrack) {
                    next()
                }
            }
        }
    }

    private suspend fun resolveLocalTrackIfAvailable(current: Track): Track {
        val downloadDir = File(context.filesDir, "downloads")
        val safeId = current.id.replace(Regex("[^A-Za-z0-9_]"), "_")
        val cleanYtId = InnerTubeStreamExtractor.extractVideoId(current.id)
        val isContentUri = current.localPath?.startsWith("content://") == true
        val isContentReadable = if (isContentUri) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(current.localPath), "r")?.use { true } ?: false
            }.getOrDefault(false)
        } else false

        if (isContentReadable) {
            return current.copy(isDownloaded = true, localPath = current.localPath)
        }

        val dbTrack = runCatching {
            musicRepository.getTrackById(current.id) ?: cleanYtId?.let { musicRepository.getTrackById(it) }
        }.getOrNull()
        val dbPath = dbTrack?.takeIf { it.isDownloaded }?.localPath
        val discoverDownloadPath = runCatching {
            com.seitharol.data.download.DownloadPreferencesAndService.getInstance(context)
                .completedDownloads.value.firstOrNull {
                    it.sourceId == current.id ||
                    (cleanYtId != null && InnerTubeStreamExtractor.extractVideoId(it.sourceId) == cleanYtId) ||
                    (it.title.equals(current.title, ignoreCase = true) && (current.artist.isBlank() || it.artists.any { a -> current.artist.contains(a, ignoreCase = true) }))
                }?.localPath
        }.getOrNull()
        val discoverDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "Seitharol"
        )

        val localCandidates = mutableListOf<File>()
        current.localPath?.takeIf { !it.startsWith("content://") }?.let { localCandidates.add(File(it.removePrefix("file://"))) }
        dbPath?.takeIf { !it.startsWith("content://") }?.let { localCandidates.add(File(it.removePrefix("file://"))) }
        discoverDownloadPath?.takeIf { !it.startsWith("content://") }?.let { localCandidates.add(File(it.removePrefix("file://"))) }
        localCandidates.add(File(downloadDir, "$safeId.audio"))
        localCandidates.add(File(downloadDir, "${current.id}.audio"))
        localCandidates.add(File(downloadDir, "${current.id}_high.opus"))
        localCandidates.add(File(downloadDir, "${current.id}_high.m4a"))
        localCandidates.add(File(downloadDir, "${current.id}_original.opus"))
        localCandidates.add(File(downloadDir, "${current.id}_original.m4a"))
        localCandidates.add(File(downloadDir, "${current.id}_standard.opus"))
        localCandidates.add(File(downloadDir, "${current.id}_standard.m4a"))
        localCandidates.add(File(downloadDir, "${current.id}_data saver.m4a"))
        localCandidates.add(File(downloadDir, "${current.id}_data_saver.opus"))
        if (cleanYtId != null) {
            localCandidates.add(File(downloadDir, "${cleanYtId}.audio"))
            localCandidates.add(File(downloadDir, "${cleanYtId}_high.opus"))
            localCandidates.add(File(downloadDir, "${cleanYtId}_standard.opus"))
            localCandidates.add(File(downloadDir, "${cleanYtId}_standard.m4a"))
        }
        if (discoverDir.exists() && discoverDir.isDirectory) {
            discoverDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 10_240L && !f.name.endsWith(".tmp")) {
                    if ((cleanYtId != null && f.name.contains(cleanYtId)) ||
                        (current.title.isNotBlank() && f.nameWithoutExtension.contains(current.title, ignoreCase = true))
                    ) {
                        localCandidates.add(f)
                    }
                }
            }
        }

        val foundLocal = localCandidates.firstOrNull { it.exists() && it.length() > 0L }
        return when {
            foundLocal != null -> current.copy(isDownloaded = true, localPath = foundLocal.absolutePath)
            dbPath?.startsWith("content://") == true -> current.copy(isDownloaded = true, localPath = dbPath)
            discoverDownloadPath?.startsWith("content://") == true -> current.copy(isDownloaded = true, localPath = discoverDownloadPath)
            else -> current.copy(isDownloaded = false, localPath = null)
        }
    }

    private fun initEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        val persisted = settingsManager.state.value
        if (boundAudioSessionId == audioSessionId && equalizer != null) {
            applyPersistedEqualizerAndEffects(
                eqEnabled = persisted.eqEnabled,
                bandsDb = persisted.eqBandsDb,
                normalizeVolume = persisted.audioNormalization
            )
            return
        }
        try {
            boundAudioSessionId = audioSessionId
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = persisted.eqEnabled
            }
            loudnessEnhancer?.release()
            loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            applyPersistedEqualizerAndEffects(
                eqEnabled = persisted.eqEnabled,
                bandsDb = persisted.eqBandsDb,
                normalizeVolume = persisted.audioNormalization
            )
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
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = false
        loadTrackJob?.cancel()
        retryJob?.cancel()
        val player = exoPlayer ?: return
        player.playWhenReady = false
        player.pause()
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (_state.value.isPlaying || player.isPlaying) {
            pause()
        } else {
            if (_state.value.currentTrack != null) {
                if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) {
                    loadAndPlayCurrent()
                } else {
                    player.playWhenReady = true
                    player.play()
                    _state.value = _state.value.copy(isPlaying = true)
                    MediaPlaybackService.startService(context)
                }
            } else if (_state.value.queue.isNotEmpty()) {
                loadAndPlayCurrent()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        lastUserSeekTimestamp = System.currentTimeMillis()
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
        _state.value = _state.value.copy(currentIndex = nextIndex, currentTrack = queue[nextIndex])
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
        _state.value = _state.value.copy(currentIndex = prevIndex, currentTrack = queue[prevIndex])
        loadAndPlayCurrent()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.value = _state.value.copy(repeatMode = mode)
        exoPlayer?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
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
            exoPlayer?.pause()
            _state.value = _state.value.copy(isPlaying = false, sleepTimerMinutesLeft = 0)
            _skipEvent.emit("Sleep timer finished. Playback paused.")
        }
    }

    fun setPreset(preset: EqualizerPreset) {
        _state.value = _state.value.copy(currentPresetName = preset.name)
        settingsManager.setEqEnabled(true)
        preset.bandLevels.forEachIndexed { idx, db ->
            settingsManager.setEqBand(idx, db)
        }
        equalizer?.let { eq ->
            try {
                eq.enabled = true
                val numBands = eq.numberOfBands.toInt()
                val minLevel = eq.bandLevelRange[0].toInt()
                val maxLevel = eq.bandLevelRange[1].toInt()
                for (i in 0 until numBands) {
                    val db = preset.bandLevels.getOrNull(i) ?: 0f
                    val targetMilliBels = (db * 100).toInt().coerceIn(minLevel, maxLevel)
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
        equalizer?.let { eq ->
            try {
                if (eq.numberOfBands > 0) {
                    val level = ((clamped / 100f) * 1000).toInt().toShort()
                    eq.setBandLevel(0, level)
                }
            } catch (_: Exception) {}
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) = setPreset(preset)

    fun setLoudnessGain(gainDb: Float) {
        _state.value = _state.value.copy(loudnessGainDb = gainDb)
        val persisted = settingsManager.state.value
        applyPersistedEqualizerAndEffects(persisted.eqEnabled, persisted.eqBandsDb, persisted.audioNormalization)
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
                    val current = _state.value.currentTrack
                    val isCurrentOffline = current?.isDownloaded == true || !current?.localPath.isNullOrBlank() || _state.value.queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
                    if (isLast) {
                        if (isAutoplayEnabled && !isCurrentOffline) {
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
        val isOffline = current.isDownloaded || !current.localPath.isNullOrBlank() || _state.value.queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
        if (isOffline || !isAutoplayEnabled) return
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
        val isOffline = current.isDownloaded || !current.localPath.isNullOrBlank() || _state.value.queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
        if (isOffline || !isAutoplayEnabled) {
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

    private var temporaryTrackQuality: com.seitharol.data.resolver.AudioQuality? = null

    fun getTemporaryTrackQuality(): com.seitharol.data.resolver.AudioQuality? = temporaryTrackQuality

    fun setTemporaryTrackQuality(quality: com.seitharol.data.resolver.AudioQuality) {
        temporaryTrackQuality = quality
        val current = _state.value.currentTrack ?: return
        val currentPos = exoPlayer?.currentPosition ?: 0L
        coroutineScope.launch {
            try {
                isRetryingOrLoading = true
                _state.value = _state.value.copy(isBuffering = true)
                val stream = resolverEngine.resolveTrack(current.copy(audioUrl = ""), quality, forceRefresh = true)
                _state.value = _state.value.copy(activeStream = stream)
                setupAndPlaySource(stream.streamUrl, current, startPositionMs = currentPos)
            } catch (e: Exception) {
                isRetryingOrLoading = false
                Log.w(TAG, "Failed to apply temporary track quality: ${e.message}")
                _state.value = _state.value.copy(isBuffering = false)
            }
        }
    }

    private fun getPreferredAudioQuality(): com.seitharol.data.resolver.AudioQuality {
        return temporaryTrackQuality ?: runCatching {
            settingsManager.state.value.streamingQuality
        }.getOrDefault(com.seitharol.data.resolver.AudioQuality.NORMAL)
    }

    fun release() {
        stopPlayback()
        bufferingWatchdogJob?.cancel()
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        equalizer?.release()
        equalizer = null
        boundAudioSessionId = -1
        exoPlayer?.release()
        exoPlayer = null
        coroutineScope.cancel()
    }
}
