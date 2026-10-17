package com.example.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
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
    val sleepTimerMinutesLeft: Int = 0
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
                                    val safeDur = if (dur > 0L) dur else _state.value.durationMs
                                    _state.value = _state.value.copy(
                                        isBuffering = false,
                                        durationMs = safeDur
                                    )
                                    initEqualizer(audioSessionId)
                                }
                                Player.STATE_ENDED -> {
                                    onTrackCompleted()
                                }
                                Player.STATE_IDLE -> {
                                    _state.value = _state.value.copy(isBuffering = false)
                                }
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _state.value = _state.value.copy(isPlaying = isPlaying)
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
                            coroutineScope.launch {
                                _skipEvent.emit("Playback note: ${error.localizedMessage ?: "Format switching"}")
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
            val mediaItem = MediaItem.Builder()
                .setUri(parsedUri)
                .setMediaId(track.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(if (track.artUrl.isNotBlank()) Uri.parse(track.artUrl) else null)
                        .build()
                )
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            _state.value = _state.value.copy(
                isPlaying = true,
                isBuffering = false
            )
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
                        val safeDur = if (dur > 0L) dur else _state.value.durationMs

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
                    }
                }
                delay(200)
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
        _state.value = _state.value.copy(
            queue = tracks,
            currentIndex = startIndex.coerceIn(0, tracks.lastIndex)
        )
        loadAndPlayCurrent()
    }

    fun playTrack(track: Track) {
        val currentQueue = _state.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == track.id }
        if (index != -1) {
            _state.value = _state.value.copy(currentIndex = index)
        } else {
            currentQueue.add(0, track)
            _state.value = _state.value.copy(queue = currentQueue, currentIndex = 0)
        }
        loadAndPlayCurrent()
    }

    fun playQueueIndex(index: Int) {
        val q = _state.value.queue
        if (index in q.indices) {
            _state.value = _state.value.copy(currentIndex = index)
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

    fun clearQueue() {
        val current = _state.value.currentTrack
        if (current != null) {
            _state.value = _state.value.copy(queue = listOf(current), currentIndex = 0)
        } else {
            _state.value = _state.value.copy(queue = emptyList(), currentIndex = 0)
        }
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
        coroutineScope.launch {
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
                val localCandidates = listOfNotNull(
                    current.localPath?.let { File(it) },
                    File(downloadDir, "${current.id}_high.opus"),
                    File(downloadDir, "${current.id}_high.m4a"),
                    File(downloadDir, "${current.id}_original.opus"),
                    File(downloadDir, "${current.id}_original.m4a"),
                    File(downloadDir, "${current.id}_standard.opus"),
                    File(downloadDir, "${current.id}_standard.m4a"),
                    File(downloadDir, "${current.id}_data saver.m4a")
                )
                val foundLocal = localCandidates.firstOrNull { it.exists() && it.length() > 10_240L }
                val safeTrack = if (foundLocal != null) {
                    current.copy(isDownloaded = true, localPath = foundLocal.absolutePath)
                } else {
                    current.copy(isDownloaded = false, localPath = null)
                }

                // Record listening history in Room
                musicRepository.recordPlayHistory(safeTrack)

                // Resolve Stream using ResolverEngine
                val stream = resolverEngine.resolveTrack(safeTrack)
                _state.value = _state.value.copy(activeStream = stream)

                setupAndPlaySource(stream.streamUrl, safeTrack)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading track: ${e.message}", e)
                _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                _skipEvent.emit("Playback note: ${e.localizedMessage ?: "Playing next track"}")
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
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        _state.value = _state.value.copy(currentPositionMs = positionMs)
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
                    _state.value = _state.value.copy(isPlaying = false, currentPositionMs = 0L)
                } else {
                    next()
                }
            }
        }
    }

    private fun parseLrc(lrcText: String): List<LyricsLine> {
        if (lrcText.isBlank()) return emptyList()
        val lines = mutableListOf<LyricsLine>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        lrcText.lineSequence().forEach { line ->
            val match = regex.find(line.trim())
            if (match != null) {
                val (m, s, ms, text) = match.destructured
                val min = m.toLongOrNull() ?: 0L
                val sec = s.toLongOrNull() ?: 0L
                val milli = if (ms.length == 2) (ms.toLongOrNull() ?: 0L) * 10 else ms.toLongOrNull() ?: 0L
                val timestampMs = (min * 60 + sec) * 1000L + milli
                if (text.isNotBlank()) {
                    lines.add(LyricsLine(timestampMs, text.trim()))
                }
            }
        }
        return lines.sortedBy { it.timestampMs }
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
