package com.seitharol.player

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.ResolvedPlaybackTarget
import com.seitharol.data.resolver.ResolverEngine
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioPlayerManager(
    val exoPlayer: ExoPlayer,
    private val resolverEngine: ResolverEngine,
    private val appContext: Context? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewResolveJob: Job? = null

    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    fun playPreview(track: NormalizedTrack) {
        val rawPreview = track.previewUrl ?: return
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.isPlaying) return

        previewResolveJob?.cancel()
        _activePreviewTrackId.value = track.sourceId

        if (rawPreview.startsWith("http")) {
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            val mediaItem = MediaItem.Builder()
                .setMediaId("preview_${track.sourceId}")
                .setUri(Uri.parse(rawPreview))
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            _isPreviewPlaying.value = true
        } else if (rawPreview.startsWith("ytm_clip:")) {
            val videoId = rawPreview.removePrefix("ytm_clip:")
            previewResolveJob = scope.launch {
                val streamUrl = InnerTubeStreamExtractor.resolveWorkingStreamUrl(videoId, AudioQuality.NORMAL).getOrNull()
                    ?: return@launch
                if (_activePreviewTrackId.value != track.sourceId) return@launch

                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                // Play a real 30-second chorus segment (35s -> 65s)
                val clipStartMs = if (track.durationMs > 75_000L) 35_000L else 0L
                val clipEndMs = clipStartMs + 30_000L

                val clippedItem = MediaItem.Builder()
                    .setMediaId("preview_${track.sourceId}")
                    .setUri(Uri.parse(streamUrl))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(clipEndMs)
                            .build()
                    )
                    .build()

                exoPlayer.setMediaItem(clippedItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                _isPreviewPlaying.value = true
            }
        }
    }

    fun resumeOrPlayPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.mediaItemCount > 0) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
            _isPreviewPlaying.value = true
        } else {
            playPreview(track)
        }
    }

    fun pausePreview() {
        previewResolveJob?.cancel()
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            _isPreviewPlaying.value = false
        }
    }

    fun stopPreviewAndClear() {
        previewResolveJob?.cancel()
        if (_activePreviewTrackId.value != null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false
        }
    }

    suspend fun openFullLengthSong(
        track: NormalizedTrack,
        offlineMode: Boolean = false,
        onMaximizePlayer: (NormalizedTrack) -> Unit
    ): Result<Unit> {
        stopPreviewAndClear()
        return resolverEngine.resolveForFullPlayback(track, offlineMode).map { target ->
            when (target) {
                is ResolvedPlaybackTarget.LocalDeviceUri -> {
                    onMaximizePlayer(
                        track.copy(
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
                        artUrl = track.artUrl.ifBlank { target.resolvedTrack.artUrl }
                    )
                    onMaximizePlayer(verifiedTrack)
                }
            }
        }
    }

    fun stopAndDismissNotifications() {
        runCatching {
            previewResolveJob?.cancel()
            exoPlayer.playWhenReady = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false
            val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
    }
}
