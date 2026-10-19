package com.seitharol.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seitharol.data.resolver.ResolvedPlaybackTarget
import com.seitharol.data.resolver.ResolverEngine
import com.seitharol.domain.model.NormalizedTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioPlayerManager(
    val exoPlayer: ExoPlayer,
    private val resolverEngine: ResolverEngine
) {
    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    /**
     * Plays a 30-second Spotify preview URL in loop mode for the active Discover page
     * or focused In-Discover Search card using the single shared ExoPlayer stack.
     */
    fun playPreview(track: NormalizedTrack) {
        val previewUrl = track.previewUrl ?: return
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.isPlaying) return

        _activePreviewTrackId.value = track.sourceId
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        val mediaItem = MediaItem.Builder()
            .setMediaId("preview_${track.sourceId}")
            .setUri(Uri.parse(previewUrl))
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        _isPreviewPlaying.value = true
    }

    /**
     * Pauses the 30-second preview when the user swipes away from a page, opens a modal,
     * or leaves the Discover screen.
     */
    fun pausePreview() {
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            _isPreviewPlaying.value = false
        }
    }

    fun stopPreviewAndClear() {
        if (_activePreviewTrackId.value != null) {
            exoPlayer.stop()
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false
        }
    }

    /**
     * Transitions from a 30s Discover preview to full-length playback via TrackMatcher -> ResolverEngine.
     */
    suspend fun openFullLengthSong(
        track: NormalizedTrack,
        offlineMode: Boolean = false,
        onMaximizePlayer: (NormalizedTrack) -> Unit
    ): Result<Unit> {
        stopPreviewAndClear()
        return resolverEngine.resolveForFullPlayback(track, offlineMode).map { target ->
            when (target) {
                is ResolvedPlaybackTarget.LocalDeviceUri -> {
                    exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    exoPlayer.setMediaItem(MediaItem.fromUri(target.uri))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    onMaximizePlayer(track)
                }
                is ResolvedPlaybackTarget.RemoteStreamUrl -> {
                    exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    exoPlayer.setMediaItem(MediaItem.fromUri(target.url))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    onMaximizePlayer(target.resolvedTrack)
                }
            }
        }
    }
}
