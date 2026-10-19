package com.example.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.model.Track
import com.example.resolver.ResolverEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepTimerRemainingMs: Long? = null
)

class SmartPlayerManager(
    private val context: Context,
    private val resolverEngine: ResolverEngine,
    private val autoSuggestManager: AutoSuggestQueueManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var prefetchedTrackId: String? = null
    private var trackRetryCount = 0
    private var lastFailedTrackId: String? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents = _errorEvents.asSharedFlow()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true) // Auto-pause when headphones unplug
        .build()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startProgressLoop() else progressJob?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.value = _uiState.value.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val track = mediaItem?.localConfiguration?.tag as? Track
                _uiState.value = _uiState.value.copy(
                    currentTrack = track,
                    currentPositionMs = 0L,
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                )
                if (track != null) {
                    resolveAndPrepareIfNeeded(exoPlayer.currentMediaItemIndex, track)
                    triggerAutoSuggestIfNearEnd(track)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentTrack = _uiState.value.currentTrack
                val title = currentTrack?.title?.ifBlank { "Unknown" } ?: "Unknown"
                val artist = currentTrack?.artist?.ifBlank { "Unknown" } ?: "Unknown"

                scope.launch {
                    if (currentTrack != null) {
                        if (lastFailedTrackId != currentTrack.id) {
                            lastFailedTrackId = currentTrack.id
                            trackRetryCount = 0
                        }

                        if (trackRetryCount >= 3) {
                            _errorEvents.emit("Playback failed for '$title' after 3 retry attempts. Skipping to next.")
                            trackRetryCount = 0
                            lastFailedTrackId = null
                            if (exoPlayer.hasNextMediaItem()) {
                                exoPlayer.seekToNextMediaItem()
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                            return@launch
                        }

                        trackRetryCount++
                        _errorEvents.emit("Stream interrupted for '$title' by '$artist' (attempt $trackRetryCount/3). Retrying fallback...")
                        retryCurrentTrackResolution(currentTrack)
                    } else if (exoPlayer.hasNextMediaItem()) {
                        exoPlayer.seekToNextMediaItem()
                        exoPlayer.prepare()
                    }
                }
            }
        })
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(1L)
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = pos,
                    durationMs = dur
                )

                // Prefetch next track stream when current track passes 75%
                if (pos.toFloat() / dur.toFloat() > 0.75f && exoPlayer.hasNextMediaItem()) {
                    val nextIdx = exoPlayer.nextMediaItemIndex
                    val nextItem = exoPlayer.getMediaItemAt(nextIdx)
                    if (prefetchedTrackId != nextItem.mediaId) {
                        prefetchedTrackId = nextItem.mediaId
                        (nextItem.localConfiguration?.tag as? Track)?.let { nextTrack ->
                            resolveAndPrepareIfNeeded(nextIdx, nextTrack, playImmediately = false)
                        }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun resolveAndPrepareIfNeeded(index: Int, track: Track, playImmediately: Boolean = true) {
        val currentUri = exoPlayer.getMediaItemAt(index).localConfiguration?.uri?.toString().orEmpty()
        if (currentUri.startsWith("http")) return

        scope.launch {
            runCatching {
                val resolvedStream = resolverEngine.resolveTrack(track)
                val updatedTrack = track.copy(audioUrl = resolvedStream.streamUrl)
                exoPlayer.replaceMediaItem(index, updatedTrack.toMediaItem())
                if (playImmediately && index == exoPlayer.currentMediaItemIndex) {
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }.onFailure { e ->
                val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(e, context, track.title)
                _errorEvents.emit(friendlyMsg)
                if (playImmediately && exoPlayer.hasNextMediaItem()) {
                    exoPlayer.seekToNextMediaItem()
                }
            }
        }
    }

    private suspend fun retryCurrentTrackResolution(track: Track) {
        runCatching {
            resolverEngine.clearCache()
            val freshStream = resolverEngine.resolveTrack(track.copy(audioUrl = ""))
            val updated = track.copy(audioUrl = freshStream.streamUrl)
            val idx = exoPlayer.currentMediaItemIndex
            exoPlayer.replaceMediaItem(idx, updated.toMediaItem())
            exoPlayer.prepare()
            exoPlayer.play()
        }.onFailure {
            if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNextMediaItem()
        }
    }

    private fun triggerAutoSuggestIfNearEnd(currentTrack: Track) {
        if (exoPlayer.mediaItemCount - exoPlayer.currentMediaItemIndex <= 2) {
            scope.launch {
                val currentQueue = (0 until exoPlayer.mediaItemCount).mapNotNull {
                    exoPlayer.getMediaItemAt(it).localConfiguration?.tag as? Track
                }
                val suggestions = autoSuggestManager.fetchNextSuggestions(currentTrack, currentQueue)
                suggestions.forEach { suggestedTrack ->
                    exoPlayer.addMediaItem(suggestedTrack.toMediaItem())
                }
            }
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = null)
            return
        }
        sleepTimerJob = scope.launch {
            var remainingMs = minutes * 60_000L
            while (remainingMs > 0 && isActive) {
                _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = remainingMs)
                delay(1000L)
                remainingMs -= 1000L
            }
            exoPlayer.pause()
            _uiState.value = _uiState.value.copy(sleepTimerRemainingMs = null)
        }
    }
}
