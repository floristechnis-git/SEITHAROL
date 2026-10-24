package com.example.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
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
import com.example.data.local.CascadeDatabase
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
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
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
    val repeatMode: RepeatMode = RepeatMode.OFF,
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
    val isPartOfPlaylist: Boolean = false,
    val isVideoMode: Boolean = false,
    val isVideoFrameRendered: Boolean = false,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1.0f
)

/**
 * Modern AudioPlayerManager powered by AndroidX Media3 ExoPlayer.
 * Uses OuterTuneResolvingDataSource with client-matched User-Agents, fast error policy,
 * 14s buffering watchdog, non-blocking IO track resolution, persistent playback checkpoint
 * restoration, and strict canonical failover.
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

    private val playbackPrefs = context.applicationContext.getSharedPreferences("seitharol_playback_state", Context.MODE_PRIVATE)
    private var lastCheckpointSaveMs: Long = 0L

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
    private val sessionPlayedIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val sessionPlayedSignatures = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun recordSessionPlayed(track: Track) {
        if (track.id.isNotBlank()) sessionPlayedIds.add(track.id)
        val cleanTitle = track.title.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()
        val cleanArtist = track.artist.lowercase().trim()
        if (cleanTitle.isNotBlank()) {
            sessionPlayedSignatures.add("$cleanTitle|$cleanArtist")
        }
    }
    @Volatile
    private var isRetryingOrLoading: Boolean = false
    @Volatile
    private var userRequestedPlay: Boolean = false
    @Volatile
    private var hasUserInitiatedPlaybackThisSession: Boolean = false

    private val appPreferences = com.example.data.AppPreferences(context)
    private val settingsManager = AppSettingsManager.getInstance(context)
    private var lastUserSeekTimestamp: Long = 0L

    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeCall: Boolean = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val telephonyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                if (exoPlayer?.isPlaying == true || _state.value.isPlaying) {
                    wasPlayingBeforeCall = true
                    userRequestedPlay = false
                    exoPlayer?.pause()
                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                    persistCurrentPlaybackState()
                }
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                if (wasPlayingBeforeCall) {
                    wasPlayingBeforeCall = false
                    userRequestedPlay = true
                    exoPlayer?.playWhenReady = true
                    exoPlayer?.play()
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                exoPlayer?.volume = if (_state.value.isMuted) 0f else 1.0f
                if (wasPlayingBeforeCall) {
                    wasPlayingBeforeCall = false
                    userRequestedPlay = true
                    exoPlayer?.playWhenReady = true
                    exoPlayer?.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPhoneCallActive()) {
                    if (exoPlayer?.isPlaying == true || _state.value.isPlaying) {
                        wasPlayingBeforeCall = true
                        userRequestedPlay = false
                        exoPlayer?.pause()
                        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                        persistCurrentPlaybackState()
                    }
                } else {
                    // Never pause playback on screen lock/unlock chimes or transient system sounds
                    exoPlayer?.volume = if (_state.value.isMuted) 0f else 1.0f
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                exoPlayer?.volume = if (_state.value.isMuted) 0f else 0.6f
            }
        }
    }

    private fun isPhoneCallActive(): Boolean {
        return runCatching {
            val am = audioManager ?: return false
            am.mode == AudioManager.MODE_IN_CALL ||
                am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                am.mode == AudioManager.MODE_RINGTONE
        }.getOrDefault(false)
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Reuse the exact same AudioFocusRequest instance so AudioManager never revokes focus from our own listener
                val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                    .also { audioFocusRequest = it }
                am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Throwable) {}
    }

    private fun registerTelephonyReceiver() {
        try {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(telephonyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(telephonyReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Telephony receiver registration skipped: ${t.message}")
        }
    }

    val playerInstance: ExoPlayer?
        get() = exoPlayer

    fun ensurePlayerInitialized() {
        if (exoPlayer == null) {
            initExoPlayer()
            startPositionTracker()
        }
        if (_state.value.currentTrack == null) {
            restorePersistedPlaybackStateSync()
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
        restorePersistedPlaybackStateSync()
        restoreCheckpointFromDatabaseAsync()
        startPositionTracker()
        observeAudioSettings()
        registerTelephonyReceiver()
    }

    /**
     * Saves the current track, queue, and playback position to SharedPreferences & Room DB
     * so that if the user pauses playback or the OS reclaims the app in the background,
     * reopening the app restores the exact song and position in sync with the notification.
     */
    fun persistCurrentPlaybackState() {
        val s = _state.value
        val current = s.currentTrack ?: run {
            playbackPrefs.edit().remove("saved_session_json").apply()
            return
        }
        val livePos = (exoPlayer?.currentPosition?.takeIf { it > 0L } ?: s.currentPositionMs).coerceAtLeast(0L)
        val queueSnapshot = s.queue.take(20)

        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val root = JSONObject().apply {
                    put("currentIndex", s.currentIndex)
                    put("positionMs", livePos)
                    put("durationMs", s.durationMs.coerceAtLeast(current.durationMs))
                    put("repeatMode", s.repeatMode.name)
                    put("isShuffle", s.isShuffle)
                    put("isSingleTrackPlayback", s.isSingleTrackPlayback)
                    put("isPartOfPlaylist", s.isPartOfPlaylist)
                    put("currentTrack", trackToJson(current, includeLyrics = false))
                    val qArr = JSONArray()
                    queueSnapshot.forEach { t -> qArr.put(trackToJson(t, includeLyrics = false)) }
                    put("queue", qArr)
                }
                playbackPrefs.edit().putString("saved_session_json", root.toString()).apply()

                musicRepository.saveCheckpoint(
                    trackId = current.id,
                    index = s.currentIndex,
                    posMs = livePos,
                    queueIds = s.queue.map { it.id },
                    shuffle = s.isShuffle,
                    repeat = s.repeatMode.ordinal
                )
            }
        }
    }

    private fun trackToJson(t: Track, includeLyrics: Boolean = false): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("artist", t.artist)
        put("album", t.album)
        put("durationMs", t.durationMs)
        put("artUrl", t.artUrl)
        put("isDownloaded", t.isDownloaded)
        put("localPath", t.localPath.orEmpty())
        put("isLiked", t.isLiked)
        if (includeLyrics && t.lyrics.isNotBlank()) {
            put("lyrics", t.lyrics)
        }
        put("genre", t.genre)
    }

    private fun jsonToTrack(o: JSONObject): Track = Track(
        id = o.optString("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optString("album"),
        durationMs = o.optLong("durationMs", 210_000L),
        artUrl = o.optString("artUrl"),
        isDownloaded = o.optBoolean("isDownloaded", false),
        localPath = o.optString("localPath").takeIf { it.isNotBlank() },
        isLiked = o.optBoolean("isLiked", false),
        lyrics = o.optString("lyrics"),
        genre = o.optString("genre", "Music"),
        audioUrl = o.optString("audioUrl")
    )

    private fun restorePersistedPlaybackStateSync() {
        runCatching {
            if (_state.value.currentTrack != null) return
            val raw = playbackPrefs.getString("saved_session_json", null) ?: return
            val root = JSONObject(raw)
            val currentObj = root.optJSONObject("currentTrack") ?: return
            val restoredTrack = jsonToTrack(currentObj)
            if (restoredTrack.id.isBlank()) return

            val qArr = root.optJSONArray("queue")
            val restoredQueue = mutableListOf<Track>()
            if (qArr != null) {
                for (i in 0 until qArr.length()) {
                    qArr.optJSONObject(i)?.let { restoredQueue.add(jsonToTrack(it)) }
                }
            }
            if (restoredQueue.isEmpty()) {
                restoredQueue.add(restoredTrack)
            }

            val savedIdx = root.optInt("currentIndex", 0).coerceIn(0, restoredQueue.lastIndex)
            val savedPos = root.optLong("positionMs", 0L).coerceAtLeast(0L)
            val savedDur = root.optLong("durationMs", restoredTrack.durationMs.takeIf { it > 0L } ?: 210_000L)
            val savedRepeat = runCatching { RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name)) }
                .getOrDefault(RepeatMode.OFF)
            val savedShuffle = root.optBoolean("isShuffle", false)
            val localLyrics = parseLrc(restoredTrack.lyrics)
            val activeIdx = localLyrics.indexOfLast { it.timestampMs <= savedPos }

            _state.value = _state.value.copy(
                currentTrack = restoredTrack,
                queue = restoredQueue,
                currentIndex = savedIdx,
                currentPositionMs = savedPos,
                durationMs = savedDur,
                repeatMode = savedRepeat,
                isShuffle = savedShuffle,
                isPlaying = false,
                isBuffering = false,
                lyricLines = localLyrics,
                activeLyricIndex = activeIdx,
                isSingleTrackPlayback = root.optBoolean("isSingleTrackPlayback", false),
                isPartOfPlaylist = root.optBoolean("isPartOfPlaylist", true),
                isVideoMode = restoredTrack.genre.equals("YouTube Video", ignoreCase = true) ||
                    restoredTrack.album.equals("YouTube Video", ignoreCase = true)
            )
        }
    }

    private fun restoreCheckpointFromDatabaseAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                // Seed sessionPlayedIds from recent history so Autoplay never repeats recently played songs
                musicRepository.getActiveHistory().firstOrNull()?.take(30)?.forEach { histTrack ->
                    recordSessionPlayed(histTrack)
                }
                _state.value.currentTrack?.let { recordSessionPlayed(it) }
                if (_state.value.currentTrack != null) return@launch
                val checkpoint = musicRepository.getCheckpoint() ?: return@launch
                val trackId = checkpoint.currentTrackId ?: return@launch
                val track = musicRepository.getTrackById(trackId) ?: return@launch
                val queueIds = checkpoint.queueIdsJson.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val queueTracks = queueIds.mapNotNull { id -> musicRepository.getTrackById(id) }.ifEmpty { listOf(track) }
                val validIdx = checkpoint.currentIndex.coerceIn(0, queueTracks.lastIndex)
                val posMs = checkpoint.positionMs.coerceAtLeast(0L)
                val repeat = RepeatMode.entries.getOrElse(checkpoint.repeatMode) { RepeatMode.OFF }

                withContext(Dispatchers.Main) {
                    if (_state.value.currentTrack == null) {
                        _state.value = _state.value.copy(
                            currentTrack = track,
                            queue = queueTracks,
                            currentIndex = validIdx,
                            currentPositionMs = posMs,
                            durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L,
                            isShuffle = checkpoint.shuffleMode,
                            repeatMode = repeat,
                            isPlaying = false,
                            isBuffering = false
                        )
                    }
                }
            }
        }
    }

    private fun observeAudioSettings() {
        coroutineScope.launch {
            settingsManager.state.collect { persisted ->
                if (boundAudioSessionId > 0 && boundAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    if (persisted.eqEnabled && equalizer == null) {
                        initEqualizer(boundAudioSessionId)
                    } else {
                        applyPersistedEqualizerAndEffects(persisted.eqEnabled, persisted.eqBandsDb, persisted.audioNormalization)
                    }
                }
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
                if (eqEnabled) {
                    val numBands = eq.numberOfBands.toInt()
                    val range = eq.bandLevelRange
                    if (range != null && range.size >= 2) {
                        val minLevel = range[0].toInt()
                        val maxLevel = range[1].toInt()
                        for (i in 0 until numBands) {
                            val db = bandsDb.getOrNull(i) ?: 0f
                            val targetMilliBels = (db * 100f).toInt().coerceIn(minLevel, maxLevel)
                            eq.setBandLevel(i.toShort(), targetMilliBels.toShort())
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed applying persisted EQ: ${t.message}")
            }
        }

        loudnessEnhancer?.let { enhancer ->
            try {
                val shouldEnable = _state.value.loudnessGainDb != 0f
                enhancer.enabled = shouldEnable
                if (shouldEnable) {
                    val targetGainMb = (_state.value.loudnessGainDb * 100f).toInt()
                    enhancer.setTargetGain(targetGainMb)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LoudnessEnhancer update failed: ${t.message}")
            }
        }
    }

    private suspend fun resolveStreamForTrack(
        track: Track,
        isVideo: Boolean,
        preferredQuality: com.seitharol.data.resolver.AudioQuality,
        forceRefresh: Boolean
    ): ResolvedStream {
        if (isVideo && !track.isDownloaded && track.localPath.isNullOrBlank()) {
            val ytId = InnerTubeStreamExtractor.extractVideoId(track.id) ?: track.id.trim()
            if (forceRefresh) {
                InnerTubeStreamExtractor.invalidateVideoStreamCache(ytId)
            }
            val videoUrl = InnerTubeStreamExtractor.resolveRawVideoStreamById(
                videoId = ytId,
                forceRefresh = forceRefresh,
                allowHlsFallback = true
            ).getOrNull() ?: InnerTubeStreamExtractor.resolveRawVideoStreamById(
                videoId = ytId,
                forceRefresh = true,
                allowHlsFallback = true
            ).getOrNull()

            if (!videoUrl.isNullOrBlank()) {
                val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
                return ResolvedStream(
                    videoId = ytId,
                    streamUrl = videoUrl,
                    itag = 18,
                    codec = if (isHls) "HLS Video" else "MP4",
                    bitrate = "720p / 360p",
                    expiresAtMs = System.currentTimeMillis() + 6 * 3600 * 1000L,
                    isLocal = false,
                    resolvedBy = "Direct Video Stream",
                    latencyMs = 10
                )
            }
            throw IllegalStateException("Unable to resolve video stream for '${track.title}'")
        }
        return resolverEngine.resolveTrack(
            track = if (forceRefresh) track.copy(audioUrl = "") else track,
            preferredQuality = preferredQuality,
            forceRefresh = forceRefresh,
            preferNonYoutubeFallback = false
        )
    }

    private fun startBufferingWatchdog(expectedTrackId: String) {
        bufferingWatchdogJob?.cancel()
        val player = exoPlayer
        if (player != null && player.isPlaying) {
            return
        }

        bufferingWatchdogJob = coroutineScope.launch {
            delay(11_000L)
            if (!isActive || !userRequestedPlay) return@launch
            val current = _state.value.currentTrack ?: return@launch
            val p = exoPlayer ?: return@launch
            if (current.id == expectedTrackId && (_state.value.isBuffering || isRetryingOrLoading) && !p.isPlaying) {
                Log.w(TAG, "Initial load watchdog triggered after 25s for '${current.title}', retrying stream...")
                val resumePos = (p.currentPosition.takeIf { it > 0L } ?: _state.value.currentPositionMs).coerceAtLeast(0L)
                val isVideo = current.isVideoTrack || _state.value.isVideoMode
                resolverEngine.invalidateTrack(current.id)
                InnerTubeStreamExtractor.invalidateVideoStreamCache(current.id)
                try {
                    val freshStream = withContext(Dispatchers.IO) {
                        withTimeout(18_000L) {
                            val localFirst = resolveLocalTrackIfAvailable(current)
                            resolveStreamForTrack(
                                track = localFirst,
                                isVideo = isVideo,
                                preferredQuality = com.seitharol.data.resolver.AudioQuality.LOW,
                                forceRefresh = true
                            )
                        }
                    }
                    if (isActive && userRequestedPlay && _state.value.currentTrack?.id == expectedTrackId) {
                        _state.value = _state.value.copy(activeStream = freshStream, isBuffering = true)
                        setupAndPlaySource(freshStream.streamUrl, current, startPositionMs = resumePos)
                    }
                } catch (e: Exception) {
                    isRetryingOrLoading = false
                    _state.value = _state.value.copy(isBuffering = false)
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
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 1))

            // Buffer tuned for instant startup (0.8s) and continuous streaming on 2GB RAM devices:
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 12_000,
                    /* maxBufferMs = */ 32_000,
                    /* bufferForPlaybackMs = */ 800,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_500
                )
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
                .build()

            val builder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                .setHandleAudioBecomingNoisy(true)

            try {
                builder.setWakeMode(C.WAKE_MODE_NETWORK)
            } catch (t: Throwable) {
                // Ignore wake mode error if restricted in environment
            }

            exoPlayer = builder.build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                if (userRequestedPlay) {
                                    _state.value = _state.value.copy(isBuffering = true)
                                    _state.value.currentTrack?.id?.let { startBufferingWatchdog(it) }
                                } else {
                                    _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                                }
                            }
                            Player.STATE_READY -> {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                val dur = duration.coerceAtLeast(0L)
                                val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs
                                // If the user requested playback and stream resolution just finished,
                                // ensure ExoPlayer immediately starts playing rather than staying paused.
                                if (userRequestedPlay && !playWhenReady) {
                                    playWhenReady = true
                                    play()
                                }
                                val activelyPlaying = userRequestedPlay && playWhenReady
                                _state.value = _state.value.copy(
                                    isBuffering = false,
                                    isPlaying = activelyPlaying,
                                    durationMs = safeDur
                                )
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                initEqualizer(audioSessionId)
                                persistCurrentPlaybackState()
                                if (activelyPlaying) {
                                    startPositionTracker()
                                }
                            }
                            Player.STATE_ENDED -> {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                onTrackCompleted()
                            }
                            Player.STATE_IDLE -> {
                                val loadingActive = userRequestedPlay && (isRetryingOrLoading || loadTrackJob?.isActive == true || retryJob?.isActive == true)
                                if (!loadingActive) {
                                    bufferingWatchdogJob?.cancel()
                                    _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                                }
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (!_state.value.isVideoFrameRendered) {
                            _state.value = _state.value.copy(isVideoFrameRendered = true)
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0 && !_state.value.isVideoFrameRendered) {
                            _state.value = _state.value.copy(isVideoFrameRendered = true)
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        super.onMediaItemTransition(mediaItem, reason)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            bufferingWatchdogJob?.cancel()
                            isRetryingOrLoading = false
                            _state.value = _state.value.copy(isPlaying = true, isBuffering = false)
                            persistCurrentPlaybackState()
                            startPositionTracker()
                            try {
                                MediaPlaybackService.startService(context)
                            } catch (_: Throwable) {}
                            return
                        }

                        if (!userRequestedPlay) {
                            bufferingWatchdogJob?.cancel()
                            isRetryingOrLoading = false
                            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                            persistCurrentPlaybackState()
                            return
                        }

                        val stillLoading = loadTrackJob?.isActive == true || retryJob?.isActive == true || playbackState == Player.STATE_BUFFERING
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = stillLoading
                        )
                        persistCurrentPlaybackState()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                        if (!userRequestedPlay) {
                            isRetryingOrLoading = false
                            _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                            return
                        }
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
                            // Never auto-skip if user was resuming playback or track had already started playing!
                            val isResumingOrAlreadyPlaying = resumePositionMs > 1000L || hasUserInitiatedPlaybackThisSession
                            val hasNextTrack = !isResumingOrAlreadyPlaying && !_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex

                            if (lastFailedTrackId != current.id) {
                                lastFailedTrackId = current.id
                                trackRetryCount = 0
                            }

                            // First check if a local downloaded copy exists on device
                            val localResolved = withContext(Dispatchers.IO) { resolveLocalTrackIfAvailable(current) }
                            if (localResolved.isDownloaded && !localResolved.localPath.isNullOrBlank()) {
                                val localStreamUrl = if (localResolved.localPath.startsWith("content://")) {
                                    localResolved.localPath
                                } else {
                                    Uri.fromFile(File(localResolved.localPath.removePrefix("file://"))).toString()
                                }
                                setupAndPlaySource(localStreamUrl, localResolved, startPositionMs = resumePositionMs)
                                return@launch
                            }

                            if (resolverEngine.isOfflineMode.value || !com.example.util.ErrorFormatter.isOnline(context)) {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                userRequestedPlay = false
                                if (hasUserInitiatedPlaybackThisSession) {
                                    val msg = if (resolverEngine.isOfflineMode.value) {
                                        "Track not available offline. Connect to internet or download first."
                                    } else {
                                        "No internet connection. Please check your network."
                                    }
                                    _skipEvent.emit(msg)
                                }
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                persistCurrentPlaybackState()
                                return@launch
                            }

                            if (trackRetryCount >= 3) {
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                Log.w(TAG, "Retry attempts exhausted for '$title'.")
                                if (hasUserInitiatedPlaybackThisSession) {
                                    val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(error, context, title, hasNextTrack)
                                    _skipEvent.emit(friendlyMsg)
                                }
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                if (hasNextTrack) {
                                    next()
                                } else {
                                    userRequestedPlay = false
                                    try {
                                        exoPlayer?.stop()
                                    } catch (_: Throwable) {}
                                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                    persistCurrentPlaybackState()
                                }
                                return@launch
                            }

                            val isVideo = current.isVideoTrack || _state.value.isVideoMode
                            InnerTubeStreamExtractor.invalidateVideoStreamCache(current.id)
                            trackRetryCount++
                            delay(150L)
                            if (!isActive || !userRequestedPlay || _state.value.currentTrack?.id != current.id) return@launch
                            try {
                                val freshStream = withTimeout(20_000L) {
                                    resolveStreamForTrack(
                                        track = current.copy(audioUrl = ""),
                                        isVideo = isVideo,
                                        preferredQuality = getPreferredAudioQuality(),
                                        forceRefresh = true
                                    )
                                }
                                if (!isActive || !userRequestedPlay || _state.value.currentTrack?.id != current.id) return@launch
                                _state.value = _state.value.copy(activeStream = freshStream, isBuffering = true)
                                setupAndPlaySource(freshStream.streamUrl, current, startPositionMs = resumePositionMs)
                            } catch (e: Exception) {
                                if (!isActive || !userRequestedPlay || _state.value.currentTrack?.id != current.id) return@launch
                                Log.e(TAG, "Retry attempt $trackRetryCount failed: ${e.message}", e)
                                if (trackRetryCount <= 2) {
                                    trackRetryCount++
                                    runCatching {
                                        val secondStream = withTimeout(20_000L) {
                                            resolveStreamForTrack(
                                                track = current.copy(audioUrl = ""),
                                                isVideo = isVideo,
                                                preferredQuality = com.seitharol.data.resolver.AudioQuality.LOW,
                                                forceRefresh = true
                                            )
                                        }
                                        if (isActive && userRequestedPlay && _state.value.currentTrack?.id == current.id) {
                                            _state.value = _state.value.copy(activeStream = secondStream, isBuffering = true)
                                            setupAndPlaySource(secondStream.streamUrl, current, startPositionMs = resumePositionMs)
                                            return@launch
                                        }
                                    }
                                }
                                bufferingWatchdogJob?.cancel()
                                isRetryingOrLoading = false
                                if (hasUserInitiatedPlaybackThisSession) {
                                    val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, title, hasNextTrack)
                                    _skipEvent.emit(friendlyMsg)
                                }
                                trackRetryCount = 0
                                lastFailedTrackId = null
                                if (hasNextTrack) {
                                    next()
                                } else {
                                    userRequestedPlay = false
                                    try {
                                        exoPlayer?.stop()
                                    } catch (_: Throwable) {}
                                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                                    persistCurrentPlaybackState()
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
            if (!userRequestedPlay) {
                isRetryingOrLoading = false
                _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
                return
            }
            ensurePlayerInitialized()
            val player = exoPlayer ?: return
            isRetryingOrLoading = true
            startBufferingWatchdog(track.id)
            val parsedUri = when {
                sourceUrl.startsWith("content://") -> Uri.parse(sourceUrl)
                sourceUrl.startsWith("file://") -> {
                    val rawPath = Uri.parse(sourceUrl).path ?: sourceUrl.removePrefix("file://")
                    val f = File(rawPath)
                    if (f.exists()) Uri.fromFile(f) else Uri.fromFile(File(sourceUrl.removePrefix("file://")))
                }
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

            requestAudioFocus()
            player.volume = if (_state.value.isMuted) 0f else 1.0f
            if (startPositionMs > 0L) {
                player.setMediaItem(mediaItem, startPositionMs)
            } else {
                player.setMediaItem(mediaItem)
            }
            player.playWhenReady = userRequestedPlay
            player.prepare()
            if (userRequestedPlay) {
                player.play()
            }

            startPositionTracker()
            try {
                MediaPlaybackService.startService(context)
            } catch (_: Throwable) {
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
                val player = exoPlayer ?: break
                if (!player.isPlaying && !_state.value.isPlaying) {
                    break
                }
                val isPastGracePeriod = (System.currentTimeMillis() - lastUserSeekTimestamp) > 700L
                if (player.isPlaying && isPastGracePeriod) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration
                    val safeDur = if (dur > 0L && dur != C.TIME_UNSET) dur else _state.value.durationMs

                    checkSponsorSkip(pos)

                    val currentLyrics = _state.value.lyricLines
                    val adjustedPos = pos + _state.value.lyricOffsetMs
                    val activeIdx = currentLyrics.indexOfLast { it.timestampMs <= adjustedPos }

                    val hasVideoSize = player.videoSize.width > 0 && player.videoSize.height > 0
                    _state.value = _state.value.copy(
                        currentPositionMs = pos,
                        durationMs = if (safeDur > 0L) safeDur else _state.value.durationMs,
                        activeLyricIndex = activeIdx,
                        isVideoFrameRendered = _state.value.isVideoFrameRendered || hasVideoSize
                    )

                    val now = System.currentTimeMillis()
                    if (now - lastCheckpointSaveMs > 60_000L) {
                        lastCheckpointSaveMs = now
                        persistCurrentPlaybackState()
                    }

                    val remainingMs = safeDur - pos
                    if (safeDur >= 20_000L && remainingMs in 1L..14_000L) {
                        if (_state.value.currentIndex >= _state.value.queue.lastIndex && isAutoplayEnabled) {
                            prefetchAutoplayRecommendation()
                        }
                        prefetchNextTrack()
                    }
                }
                delay(500L)
            }
        }
    }

    private fun prefetchNextTrack() {
        if (resolverEngine.isOfflineMode.value) return
        val q = _state.value.queue
        val nextIndex = _state.value.currentIndex + 1
        if (nextIndex in q.indices) {
            val nextTrack = q[nextIndex]
            if (prefetchedTrackId != nextTrack.id) {
                prefetchedTrackId = nextTrack.id
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val resolvedLocal = resolveLocalTrackIfAvailable(nextTrack)
                        resolveStreamForTrack(
                            track = resolvedLocal,
                            isVideo = resolvedLocal.isVideoTrack,
                            preferredQuality = getPreferredAudioQuality(),
                            forceRefresh = false
                        )
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
        hasUserInitiatedPlaybackThisSession = true
        userRequestedPlay = true
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
            currentPositionMs = 0L,
            isSingleTrackPlayback = !isPlaylist,
            isPartOfPlaylist = isPlaylist
        )
        loadAndPlayCurrent(resumePositionMs = 0L)
    }

    fun playTrack(track: Track) {
        val current = _state.value.currentTrack
        val p = exoPlayer
        // If tapping the exact song that is already loaded, do NOT restart from 0:00!
        if (current != null && current.id == track.id && p != null && p.playbackState != Player.STATE_IDLE && p.currentMediaItem != null) {
            if (!_state.value.isPlaying) {
                togglePlayPause()
            }
            return
        }

        hasUserInitiatedPlaybackThisSession = true
        userRequestedPlay = true
        trackRetryCount = 0
        lastFailedTrackId = null
        loadTrackJob?.cancel()
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
        if (track.isDownloaded || !track.localPath.isNullOrBlank()) {
            isAutoplayEnabled = false
        }
        recordSessionPlayed(track)
        _state.value = _state.value.copy(
            queue = listOf(track),
            currentIndex = 0,
            currentTrack = track,
            currentPositionMs = 0L,
            isSingleTrackPlayback = true,
            isPartOfPlaylist = false,
            isVideoMode = track.isVideoTrack,
            isVideoFrameRendered = false
        )
        loadAndPlayCurrent(resumePositionMs = 0L)
    }

    fun playQueueIndex(index: Int) {
        val q = _state.value.queue
        if (index in q.indices) {
            val target = q[index]
            val p = exoPlayer
            // If tapping the currently active queue item, resume if paused rather than restarting from 0:00
            if (index == _state.value.currentIndex && _state.value.currentTrack?.id == target.id && p != null && p.playbackState != Player.STATE_IDLE && p.currentMediaItem != null) {
                if (!_state.value.isPlaying) {
                    togglePlayPause()
                }
                return
            }

            hasUserInitiatedPlaybackThisSession = true
            userRequestedPlay = true
            trackRetryCount = 0
            lastFailedTrackId = null
            loadTrackJob?.cancel()
            retryJob?.cancel()
            bufferingWatchdogJob?.cancel()
            recordSessionPlayed(target)
            _state.value = _state.value.copy(
                currentIndex = index,
                currentTrack = target,
                currentPositionMs = 0L,
                isVideoMode = target.isVideoTrack,
                isVideoFrameRendered = false
            )
            loadAndPlayCurrent(resumePositionMs = 0L)
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
        persistCurrentPlaybackState()
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
            persistCurrentPlaybackState()
            if (wasCurrent && q.isNotEmpty()) {
                loadAndPlayCurrent(resumePositionMs = 0L)
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
        persistCurrentPlaybackState()
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
        persistCurrentPlaybackState()
    }

    fun stopPlayback() {
        userRequestedPlay = false
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = false
        positionJob?.cancel()
        loadTrackJob?.cancel()
        retryJob?.cancel()
        abandonAudioFocus()
        try {
            exoPlayer?.playWhenReady = false
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
        playbackPrefs.edit().remove("saved_session_json").apply()
        try {
            MediaPlaybackService.stopService(context)
        } catch (_: Throwable) {}
    }

    /**
     * Called when the user swipes/clears the app from Recents.
     * Saves the current checkpoint, stops audio playback cleanly, and clears active playing/buffering state.
     */
    fun onAppClearedFromRecents() {
        persistCurrentPlaybackState()
        userRequestedPlay = false
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = false
        loadTrackJob?.cancel()
        retryJob?.cancel()
        abandonAudioFocus()
        try {
            exoPlayer?.playWhenReady = false
            exoPlayer?.pause()
            exoPlayer?.stop()
        } catch (_: Throwable) {}
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = false
        )
    }

    fun addToQueue(track: Track) {
        val q = _state.value.queue.toMutableList()
        q.add(track)
        _state.value = _state.value.copy(queue = q)
        persistCurrentPlaybackState()
    }

    fun playNext(track: Track) {
        val q = _state.value.queue.toMutableList()
        val insertIndex = (_state.value.currentIndex + 1).coerceAtMost(q.size)
        q.add(insertIndex, track)
        _state.value = _state.value.copy(queue = q)
        persistCurrentPlaybackState()
    }

    private fun loadAndPlayCurrent(resumePositionMs: Long = 0L) {
        val current = _state.value.queue.getOrNull(_state.value.currentIndex) ?: _state.value.currentTrack ?: return
        prefetchedTrackId = null
        userRequestedPlay = true
        loadTrackJob?.cancel()
        retryJob?.cancel()
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = true
        startBufferingWatchdog(current.id)

        val stopImmediately = settingsManager.state.value.stopImmediatelyOnNewTrack
        val isSameTrack = _state.value.currentTrack?.id == current.id
        if (stopImmediately && !isSameTrack) {
            try {
                // Stop previous stream cleanly without setting playWhenReady = false
                exoPlayer?.stop()
            } catch (_: Throwable) {}
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = true
            )
        } else {
            _state.value = _state.value.copy(isBuffering = true)
        }

        try {
            MediaPlaybackService.startService(context)
        } catch (_: Throwable) {}

        loadTrackJob = coroutineScope.launch {
            try {
                val localLyrics = parseLrc(current.lyrics)
                val expectedDuration = if (current.durationMs > 0L) current.durationMs else 210000L
                val initialPos = resumePositionMs.coerceAtLeast(0L)
                val initialLyricIdx = localLyrics.indexOfLast { it.timestampMs <= initialPos + _state.value.lyricOffsetMs }
                val isVideoTrack = current.isVideoTrack

                recordSessionPlayed(current)
                _state.value = _state.value.copy(
                    currentTrack = current,
                    isBuffering = true,
                    durationMs = expectedDuration,
                    currentPositionMs = initialPos,
                    lyricLines = localLyrics,
                    activeLyricIndex = initialLyricIdx,
                    isVideoMode = isVideoTrack,
                    isVideoFrameRendered = if (isSameTrack) _state.value.isVideoFrameRendered else false
                )

                // Fetch synchronized lyrics asynchronously when online
                if (localLyrics.isEmpty() && !isVideoTrack && !resolverEngine.isOfflineMode.value && com.example.util.ErrorFormatter.isOnline(context)) {
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

                val preferredQuality = getPreferredAudioQuality()
                val (safeTrack, stream) = withContext(Dispatchers.IO) {
                    val resolvedLocalTrack = resolveLocalTrackIfAvailable(current)

                    launch {
                        runCatching {
                            musicRepository.saveCheckpoint(
                                trackId = resolvedLocalTrack.id,
                                index = _state.value.currentIndex,
                                posMs = initialPos,
                                queueIds = _state.value.queue.map { it.id },
                                shuffle = _state.value.isShuffle,
                                repeat = _state.value.repeatMode.ordinal
                            )
                            activeSponsorSegments = musicRepository.getSponsorSegments(resolvedLocalTrack.id)
                            musicRepository.recordPlayHistory(resolvedLocalTrack)
                        }
                    }

                    val resolvedStream = try {
                        withTimeout(18_000L) {
                            resolveStreamForTrack(
                                track = resolvedLocalTrack,
                                isVideo = isVideoTrack,
                                preferredQuality = preferredQuality,
                                forceRefresh = false
                            )
                        }
                    } catch (firstErr: Exception) {
                        // Automatic retry with fresh token & LOW quality so notification resume / slow radio wake never auto-pauses
                        withTimeout(16_000L) {
                            resolveStreamForTrack(
                                track = resolvedLocalTrack,
                                isVideo = isVideoTrack,
                                preferredQuality = com.seitharol.data.resolver.AudioQuality.LOW,
                                forceRefresh = true
                            )
                        }
                    }
                    resolvedLocalTrack to resolvedStream
                }

                if (!isActive || !userRequestedPlay || _state.value.currentTrack?.id != current.id) {
                    if (!userRequestedPlay) {
                        isRetryingOrLoading = false
                        _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                    }
                    return@launch
                }

                _state.value = _state.value.copy(
                    currentTrack = safeTrack,
                    activeStream = stream,
                    isBuffering = true
                )
                persistCurrentPlaybackState()
                setupAndPlaySource(stream.streamUrl, safeTrack, startPositionMs = initialPos)
            } catch (e: CancellationException) {
                if (!userRequestedPlay) {
                    isRetryingOrLoading = false
                    _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                }
                throw e
            } catch (e: Exception) {
                if (!isActive || !userRequestedPlay || _state.value.currentTrack?.id != current.id) return@launch
                bufferingWatchdogJob?.cancel()
                isRetryingOrLoading = false
                userRequestedPlay = false
                Log.e(TAG, "Error loading track: ${e.message}", e)
                try {
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                } catch (_: Throwable) {}
                _state.value = _state.value.copy(isBuffering = false, isPlaying = false)
                persistCurrentPlaybackState()
                val hasNextTrack = !_state.value.isSingleTrackPlayback && _state.value.currentIndex < _state.value.queue.lastIndex
                if (hasUserInitiatedPlaybackThisSession) {
                    val friendlyMessage = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, current.title, hasNextTrack)
                    _skipEvent.emit(friendlyMessage)
                }
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
        val dlService = runCatching {
            com.seitharol.data.download.DownloadPreferencesAndService.getInstance(context)
        }.getOrNull()
        val canonicalId = dlService?.canonicalTrackId(current.id) ?: (cleanYtId ?: current.id)

        fun isContentUriReadable(uriStr: String?): Boolean {
            if (uriStr.isNullOrBlank() || !uriStr.startsWith("content://")) return false
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")?.use { true } ?: false
            }.getOrDefault(false)
        }

        if (isContentUriReadable(current.localPath)) {
            return current.copy(isDownloaded = true, localPath = current.localPath)
        }

        val dbTrack = runCatching {
            musicRepository.getTrackById(current.id)
                ?: (if (canonicalId != current.id) musicRepository.getTrackById(canonicalId) else null)
                ?: (if (cleanYtId != null && cleanYtId != current.id) musicRepository.getTrackById(cleanYtId) else null)
        }.getOrNull()
        val dbPath = dbTrack?.localPath

        val downloadEntityPath = runCatching {
            val downloadDao = CascadeDatabase.getInstance(context).downloadDao()
            (downloadDao.getDownload(current.id)
                ?: downloadDao.getDownload(canonicalId)
                ?: cleanYtId?.let { downloadDao.getDownload(it) })?.localPath
        }.getOrNull()

        val discoverDownloadPath = runCatching {
            dlService?.completedDownloads?.value?.firstOrNull {
                it.sourceId == current.id ||
                    dlService.canonicalTrackId(it.sourceId) == canonicalId ||
                    (cleanYtId != null && InnerTubeStreamExtractor.extractVideoId(it.sourceId) == cleanYtId) ||
                    (it.title.equals(current.title, ignoreCase = true) &&
                        (current.artist.isBlank() || it.artists.any { a -> current.artist.contains(a, ignoreCase = true) || a.contains(current.artist, ignoreCase = true) }))
            }?.localPath
        }.getOrNull()

        listOfNotNull(dbPath, downloadEntityPath, discoverDownloadPath).forEach { candidateUri ->
            if (isContentUriReadable(candidateUri)) {
                return current.copy(isDownloaded = true, localPath = candidateUri)
            }
        }

        val discoverDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "Seitharol"
        )
        val moviesDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
            "Seitharol"
        )

        val localCandidates = mutableListOf<File>()
        listOfNotNull(current.localPath, dbPath, downloadEntityPath, discoverDownloadPath)
            .filter { !it.startsWith("content://") && it.isNotBlank() }
            .forEach { path ->
                val rawPath = if (path.startsWith("file://")) {
                    Uri.parse(path).path ?: path.removePrefix("file://")
                } else path
                localCandidates.add(File(rawPath))
            }

        for (idCandidate in listOfNotNull(current.id, canonicalId, cleanYtId, safeId).distinct()) {
            localCandidates.add(File(downloadDir, "${idCandidate}_video_mp4.mp4"))
            localCandidates.add(File(downloadDir, "$idCandidate.mp4"))
            localCandidates.add(File(downloadDir, "$idCandidate.audio"))
            localCandidates.add(File(downloadDir, "${idCandidate}_high.opus"))
            localCandidates.add(File(downloadDir, "${idCandidate}_high.m4a"))
            localCandidates.add(File(downloadDir, "${idCandidate}_original.opus"))
            localCandidates.add(File(downloadDir, "${idCandidate}_original.m4a"))
            localCandidates.add(File(downloadDir, "${idCandidate}_standard.opus"))
            localCandidates.add(File(downloadDir, "${idCandidate}_standard.m4a"))
            localCandidates.add(File(downloadDir, "${idCandidate}_data_saver.opus"))
            localCandidates.add(File(downloadDir, "${idCandidate}_data_saver.m4a"))
            localCandidates.add(File(downloadDir, "${idCandidate}_discover.m4a"))
            localCandidates.add(File(downloadDir, "${idCandidate}_discover.flac"))
            localCandidates.add(File(downloadDir, "${idCandidate}_discover.opus"))
        }

        val directHit = localCandidates.firstOrNull { it.exists() && it.length() > 10_240L }
        if (directHit != null) {
            return current.copy(isDownloaded = true, localPath = directHit.absolutePath)
        }
        // Only perform expensive directory listFiles() scans if the track was marked downloaded
        if (!current.isDownloaded && dbTrack?.isDownloaded != true && downloadEntityPath.isNullOrBlank() && discoverDownloadPath.isNullOrBlank()) {
            return current.copy(isDownloaded = false, localPath = null)
        }

        if (downloadDir.exists() && downloadDir.isDirectory) {
            downloadDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 10_240L && !f.name.endsWith(".tmp")) {
                    if (f.name.startsWith("${current.id}_") ||
                        f.name.startsWith("${canonicalId}_") ||
                        (cleanYtId != null && f.name.startsWith("${cleanYtId}_"))
                    ) {
                        localCandidates.add(f)
                    }
                }
            }
        }

        if (discoverDir.exists() && discoverDir.isDirectory) {
            discoverDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 10_240L && !f.name.endsWith(".tmp")) {
                    if ((cleanYtId != null && f.name.contains(cleanYtId)) ||
                        f.name.contains(canonicalId, ignoreCase = true) ||
                        (current.title.isNotBlank() && f.nameWithoutExtension.contains(current.title, ignoreCase = true))
                    ) {
                        localCandidates.add(f)
                    }
                }
            }
        }

        if (moviesDir.exists() && moviesDir.isDirectory) {
            moviesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.length() > 10_240L && !f.name.endsWith(".tmp")) {
                    if ((cleanYtId != null && f.name.contains(cleanYtId)) ||
                        f.name.contains(canonicalId, ignoreCase = true) ||
                        (current.title.isNotBlank() && f.nameWithoutExtension.contains(current.title, ignoreCase = true))
                    ) {
                        localCandidates.add(f)
                    }
                }
            }
        }

        val foundLocal = localCandidates.firstOrNull { it.exists() && it.length() > 0L }
        return if (foundLocal != null) {
            current.copy(isDownloaded = true, localPath = foundLocal.absolutePath)
        } else {
            current.copy(isDownloaded = false, localPath = null)
        }
    }

    private fun initEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        boundAudioSessionId = audioSessionId
        val persisted = settingsManager.state.value
        // Only attach hardware Equalizer/LoudnessEnhancer when explicitly enabled by the user
        // to avoid native AudioEffect HAL crashes on devices with restricted DSP sessions.
        if (!persisted.eqEnabled && _state.value.loudnessGainDb == 0f) {
            return
        }
        try {
            if (equalizer == null && persisted.eqEnabled) {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = persisted.eqEnabled
                }
            }
            if (loudnessEnhancer == null && _state.value.loudnessGainDb != 0f) {
                loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            }
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
        userRequestedPlay = false
        bufferingWatchdogJob?.cancel()
        isRetryingOrLoading = false
        loadTrackJob?.cancel()
        retryJob?.cancel()
        val player = exoPlayer
        val currentPos = player?.currentPosition?.takeIf { it > 0L } ?: _state.value.currentPositionMs
        try {
            player?.playWhenReady = false
            player?.pause()
        } catch (_: Throwable) {}
        _state.value = _state.value.copy(
            isPlaying = false,
            isBuffering = false,
            currentPositionMs = currentPos.coerceAtLeast(0L)
        )
        persistCurrentPlaybackState()
    }

    fun togglePlayPause() {
        hasUserInitiatedPlaybackThisSession = true
        ensurePlayerInitialized()
        val player = exoPlayer ?: return
        // Only pause when audio is actively playing!
        // When paused, buffering, or loading, tapping toggle MUST start/resume playback, never pause.
        if (_state.value.isPlaying && player.isPlaying) {
            pause()
        } else {
            userRequestedPlay = true
            val savedResumePos = _state.value.currentPositionMs.coerceAtLeast(0L)
            val current = _state.value.currentTrack

            if (current != null) {
                if (player.playbackState == Player.STATE_BUFFERING) {
                    requestAudioFocus()
                    player.playWhenReady = true
                    player.play()
                    _state.value = _state.value.copy(isBuffering = true)
                    MediaPlaybackService.startService(context)
                    return
                }
                val isPlayerStalledOrNeedsReload = player.playbackState == Player.STATE_IDLE ||
                    player.mediaItemCount == 0 ||
                    player.currentMediaItem == null

                if (isPlayerStalledOrNeedsReload) {
                    loadAndPlayCurrent(resumePositionMs = savedResumePos)
                } else {
                    requestAudioFocus()
                    if (player.playerError != null) {
                        player.prepare()
                    }
                    if (savedResumePos > 0L && kotlin.math.abs(player.currentPosition - savedResumePos) > 1500L) {
                        player.seekTo(savedResumePos)
                    }
                    player.playWhenReady = true
                    player.play()
                    _state.value = _state.value.copy(isPlaying = true, isBuffering = false)
                    persistCurrentPlaybackState()
                    MediaPlaybackService.startService(context)
                }
            } else if (_state.value.queue.isNotEmpty()) {
                loadAndPlayCurrent(resumePositionMs = savedResumePos)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        lastUserSeekTimestamp = System.currentTimeMillis()
        exoPlayer?.seekTo(clamped)
        val activeIdx = _state.value.lyricLines.indexOfLast { it.timestampMs <= clamped + _state.value.lyricOffsetMs }
        _state.value = _state.value.copy(currentPositionMs = clamped, activeLyricIndex = activeIdx)
        persistCurrentPlaybackState()
    }

    fun next() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        hasUserInitiatedPlaybackThisSession = true
        userRequestedPlay = true

        val isLast = _state.value.currentIndex >= queue.lastIndex
        val current = _state.value.currentTrack
        val isCurrentOffline = resolverEngine.isOfflineMode.value || current?.isDownloaded == true || !current?.localPath.isNullOrBlank() || queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }

        if (isLast && _state.value.repeatMode != RepeatMode.ALL) {
            if (isAutoplayEnabled && !isCurrentOffline) {
                triggerAutoplayNext()
                return
            } else if (_state.value.repeatMode == RepeatMode.OFF && !_state.value.isPartOfPlaylist) {
                seekTo(0)
                pause()
                return
            }
        }

        val nextIndex = if (_state.value.isShuffle) {
            (0 until queue.size).filter { it != _state.value.currentIndex }.randomOrNull() ?: 0
        } else {
            (_state.value.currentIndex + 1) % queue.size
        }
        _state.value = _state.value.copy(
            currentIndex = nextIndex,
            currentTrack = queue[nextIndex],
            currentPositionMs = 0L
        )
        loadAndPlayCurrent(resumePositionMs = 0L)
    }

    fun previous() {
        val queue = _state.value.queue
        if (queue.isEmpty()) return
        hasUserInitiatedPlaybackThisSession = true
        userRequestedPlay = true

        if (_state.value.currentPositionMs > 4000) {
            seekTo(0)
            return
        }

        val prevIndex = if (_state.value.currentIndex > 0) {
            _state.value.currentIndex - 1
        } else {
            if (_state.value.repeatMode == RepeatMode.ALL) queue.lastIndex else 0
        }
        _state.value = _state.value.copy(
            currentIndex = prevIndex,
            currentTrack = queue[prevIndex],
            currentPositionMs = 0L
        )
        loadAndPlayCurrent(resumePositionMs = 0L)
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.value = _state.value.copy(repeatMode = mode)
        exoPlayer?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        persistCurrentPlaybackState()
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
        persistCurrentPlaybackState()
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
            pause()
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
        if (equalizer == null && boundAudioSessionId > 0) {
            initEqualizer(boundAudioSessionId)
        }
        equalizer?.let { eq ->
            try {
                eq.enabled = true
                val numBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                if (range != null && range.size >= 2) {
                    val minLevel = range[0].toInt()
                    val maxLevel = range[1].toInt()
                    for (i in 0 until numBands) {
                        val db = preset.bandLevels.getOrNull(i) ?: 0f
                        val targetMilliBels = (db * 100).toInt().coerceIn(minLevel, maxLevel)
                        eq.setBandLevel(i.toShort(), targetMilliBels.toShort())
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed setting preset: ${t.message}")
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
            } catch (_: Throwable) {}
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) = setPreset(preset)

    fun setLoudnessGain(gainDb: Float) {
        _state.value = _state.value.copy(loudnessGainDb = gainDb)
        val persisted = settingsManager.state.value
        if (loudnessEnhancer == null && gainDb != 0f && boundAudioSessionId > 0) {
            loudnessEnhancer = runCatching { LoudnessEnhancer(boundAudioSessionId) }.getOrNull()
        }
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
                    val isCurrentOffline = resolverEngine.isOfflineMode.value || current?.isDownloaded == true || !current?.localPath.isNullOrBlank() || _state.value.queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
                    if (isLast) {
                        if (isAutoplayEnabled && !isCurrentOffline) {
                            triggerAutoplayNext()
                        } else {
                            userRequestedPlay = false
                            _state.value = _state.value.copy(isPlaying = false, isBuffering = false, currentPositionMs = 0L)
                            persistCurrentPlaybackState()
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
        if (isFetchingAutoplay || resolverEngine.isOfflineMode.value) return
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
                    album = if (current.isVideoTrack) "YouTube Video" else current.album,
                    durationSeconds = (current.durationMs / 1000).toInt(),
                    thumbnailUrl = current.artUrl,
                    source = if (current.isVideoTrack) "YOUTUBE_VIDEO" else "YOUTUBE"
                )
                val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    limit = 8,
                    additionalExcludedIds = sessionPlayedIds.toSet(),
                    additionalExcludedSignatures = sessionPlayedSignatures.toSet()
                )
                val isCurrentVideo = current.isVideoTrack
                val nextRec = recommendations.firstOrNull { rec ->
                    _state.value.queue.none { it.id == rec.id } &&
                        (isCurrentVideo || (!rec.title.contains("video", ignoreCase = true) && !rec.album.contains("video", ignoreCase = true)))
                }
                if (nextRec != null) {
                    val newTrack = Track(
                        id = nextRec.id,
                        title = nextRec.title,
                        artist = nextRec.artist,
                        album = if (isCurrentVideo) "YouTube Video" else nextRec.album,
                        durationMs = nextRec.durationSeconds * 1000L,
                        artUrl = nextRec.thumbnailUrl,
                        genre = if (isCurrentVideo) "YouTube Video" else "Track",
                        itag = if (isCurrentVideo) 18 else 251,
                        codec = if (isCurrentVideo) "MP4" else "Opus"
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
        if (isFetchingAutoplay || resolverEngine.isOfflineMode.value) return
        val current = _state.value.currentTrack ?: _state.value.queue.lastOrNull()
        if (current == null) {
            userRequestedPlay = false
            _state.value = _state.value.copy(isPlaying = false, isBuffering = false, currentPositionMs = 0L)
            return
        }
        val isOffline = current.isDownloaded || !current.localPath.isNullOrBlank() || _state.value.queue.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
        if (isOffline || !isAutoplayEnabled) {
            userRequestedPlay = false
            _state.value = _state.value.copy(isPlaying = false, isBuffering = false, currentPositionMs = 0L)
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
                    album = if (current.isVideoTrack) "YouTube Video" else current.album,
                    durationSeconds = (current.durationMs / 1000).toInt(),
                    thumbnailUrl = current.artUrl,
                    source = if (current.isVideoTrack) "YOUTUBE_VIDEO" else "YOUTUBE"
                )
                val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    limit = 8,
                    additionalExcludedIds = sessionPlayedIds.toSet(),
                    additionalExcludedSignatures = sessionPlayedSignatures.toSet()
                )
                val isCurrentVideo = current.isVideoTrack
                val nextRec = recommendations.firstOrNull { rec ->
                    _state.value.queue.none { it.id == rec.id } &&
                        (isCurrentVideo || (!rec.title.contains("video", ignoreCase = true) && !rec.album.contains("video", ignoreCase = true)))
                }
                if (nextRec != null) {
                    val newTrack = Track(
                        id = nextRec.id,
                        title = nextRec.title,
                        artist = nextRec.artist,
                        album = if (isCurrentVideo) "YouTube Video" else nextRec.album,
                        durationMs = nextRec.durationSeconds * 1000L,
                        artUrl = nextRec.thumbnailUrl,
                        genre = if (isCurrentVideo) "YouTube Video" else "Track",
                        itag = if (isCurrentVideo) 18 else 251,
                        codec = if (isCurrentVideo) "MP4" else "Opus"
                    )
                    musicRepository.insertTrack(newTrack)
                    withContext(Dispatchers.Main) {
                        addToQueue(newTrack)
                        next()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        userRequestedPlay = false
                        _state.value = _state.value.copy(isPlaying = false, isBuffering = false, currentPositionMs = 0L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay recommendation fetch failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    userRequestedPlay = false
                    _state.value = _state.value.copy(isPlaying = false, isBuffering = false, currentPositionMs = 0L)
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


    private var attachedTextureView: android.view.TextureView? = null

    fun attachVideoTextureView(textureView: android.view.TextureView?) {
        try {
            val player = exoPlayer ?: return
            if (textureView == null) {
                attachedTextureView?.let { player.clearVideoTextureView(it) }
                attachedTextureView = null
            } else if (attachedTextureView !== textureView) {
                attachedTextureView = textureView
                player.setVideoTextureView(textureView)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attachVideoTextureView error: ${t.message}")
        }
    }

    fun toggleMute() {
        setMuted(!_state.value.isMuted)
    }

    fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(isMuted = muted)
        try {
            exoPlayer?.volume = if (muted) 0f else 1.0f
        } catch (_: Throwable) {}
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _state.value = _state.value.copy(playbackSpeed = clamped)
        try {
            exoPlayer?.setPlaybackSpeed(clamped)
        } catch (_: Throwable) {}
    }

    fun setVideoMode(enabled: Boolean) {
        if (_state.value.isVideoMode == enabled) return
        val current = _state.value.currentTrack ?: run {
            _state.value = _state.value.copy(isVideoMode = enabled)
            return
        }
        val currentPos = (exoPlayer?.currentPosition?.takeIf { it > 0L } ?: _state.value.currentPositionMs).coerceAtLeast(0L)
        _state.value = _state.value.copy(isVideoMode = enabled, isBuffering = true)
        userRequestedPlay = true
        hasUserInitiatedPlaybackThisSession = true

        loadTrackJob?.cancel()
        loadTrackJob = coroutineScope.launch {
            try {
                if (enabled) {
                    val rawStreamUrl = withContext(Dispatchers.IO) {
                        var ytId = InnerTubeStreamExtractor.extractVideoId(current.id)
                        if (ytId.isNullOrBlank()) {
                            val searched = InnerTubeStreamExtractor.searchAllYoutubeVideos("${current.title} ${current.artist}".trim(), limit = 1)
                            ytId = searched.firstOrNull()?.videoId
                        }
                        if (!ytId.isNullOrBlank()) {
                            InnerTubeStreamExtractor.resolveRawVideoStreamById(ytId).getOrNull()
                        } else null
                    }
                    if (!rawStreamUrl.isNullOrBlank() && isActive && _state.value.currentTrack?.id == current.id) {
                        setupAndPlaySource(rawStreamUrl, current, startPositionMs = currentPos)
                        attachedTextureView?.let { tv ->
                            runCatching { exoPlayer?.setVideoTextureView(tv) }
                        }
                    } else {
                        _state.value = _state.value.copy(isBuffering = false)
                    }
                } else {
                    val preferredQuality = getPreferredAudioQuality()
                    val (safeTrack, stream) = withContext(Dispatchers.IO) {
                        val localTrack = resolveLocalTrackIfAvailable(current)
                        localTrack to resolverEngine.resolveTrack(localTrack, preferredQuality)
                    }
                    if (isActive && _state.value.currentTrack?.id == current.id) {
                        _state.value = _state.value.copy(currentTrack = safeTrack, activeStream = stream)
                        setupAndPlaySource(stream.streamUrl, safeTrack, startPositionMs = currentPos)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setVideoMode switch error: ${e.message}")
                _state.value = _state.value.copy(isBuffering = false)
            }
        }
    }

    private var temporaryTrackQuality: com.seitharol.data.resolver.AudioQuality? = null

    fun getTemporaryTrackQuality(): com.seitharol.data.resolver.AudioQuality? = temporaryTrackQuality

    fun setTemporaryTrackQuality(quality: com.seitharol.data.resolver.AudioQuality) {
        temporaryTrackQuality = quality
        val current = _state.value.currentTrack ?: return
        val currentPos = exoPlayer?.currentPosition ?: _state.value.currentPositionMs
        coroutineScope.launch {
            try {
                userRequestedPlay = true
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
        persistCurrentPlaybackState()
        userRequestedPlay = false
        bufferingWatchdogJob?.cancel()
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { equalizer?.release() }
        equalizer = null
        boundAudioSessionId = -1
        try {
            context.unregisterReceiver(telephonyReceiver)
        } catch (_: Throwable) {}
        abandonAudioFocus()
        runCatching { exoPlayer?.release() }
        exoPlayer = null
        coroutineScope.cancel()
    }
}
