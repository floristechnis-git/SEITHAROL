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
import kotlinx.coroutines.withContext
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata

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

    private val _isPreviewBuffering = MutableStateFlow(false)
    val isPreviewBuffering: StateFlow<Boolean> = _isPreviewBuffering.asStateFlow()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPreviewPlaying.value = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> _isPreviewBuffering.value = true
                    Player.STATE_READY -> _isPreviewBuffering.value = false
                    Player.STATE_ENDED -> {
                        _isPreviewPlaying.value = false
                        _isPreviewBuffering.value = false
                    }
                    Player.STATE_IDLE -> _isPreviewBuffering.value = false
                }
            }
        })
    }

    fun playPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.isPlaying) return

        previewResolveJob?.cancel()
        _activePreviewTrackId.value = track.sourceId
        _isPreviewBuffering.value = true
        _isPreviewPlaying.value = false

        // Stop prior audio immediately so previous track never bleeds while resolving new track
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val rawPreview = track.previewUrl
        if (rawPreview != null && rawPreview.startsWith("http")) {
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            val mediaItem = MediaItem.Builder()
                .setMediaId("preview_${track.sourceId}")
                .setUri(Uri.parse(rawPreview))
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            _isPreviewPlaying.value = true
            _isPreviewBuffering.value = false
            return
        }

        previewResolveJob = scope.launch(Dispatchers.IO) {
            var streamUrl: String? = null

            if (rawPreview != null && rawPreview.startsWith("ytm_clip:")) {
                val videoId = rawPreview.removePrefix("ytm_clip:")
                streamUrl = InnerTubeStreamExtractor.resolveWorkingStreamUrl(videoId, AudioQuality.LOW).getOrNull()
            } else if (rawPreview != null && rawPreview.startsWith("saavn_clip:")) {
                val saavnId = rawPreview.removePrefix("saavn_clip:")
                val meta = TrackMetadata(
                    id = saavnId,
                    title = track.title,
                    artist = track.artists.firstOrNull().orEmpty(),
                    album = track.album.orEmpty(),
                    durationSeconds = (track.durationMs / 1000L).toInt(),
                    thumbnailUrl = track.artUrl
                )
                streamUrl = StreamResolver.resolveExactStreamUrl(meta, AudioQuality.LOW)
            }

            if (streamUrl.isNullOrBlank()) {
                if (track.source == TrackSource.YOUTUBE && track.sourceId.length == 11) {
                    streamUrl = InnerTubeStreamExtractor.resolveWorkingStreamUrl(track.sourceId, AudioQuality.LOW).getOrNull()
                } else if (track.source == TrackSource.JIOSAAVN) {
                    val meta = TrackMetadata(
                        id = track.sourceId,
                        title = track.title,
                        artist = track.artists.firstOrNull().orEmpty(),
                        album = track.album.orEmpty(),
                        durationSeconds = (track.durationMs / 1000L).toInt(),
                        thumbnailUrl = track.artUrl
                    )
                    streamUrl = StreamResolver.resolveExactStreamUrl(meta, AudioQuality.LOW)
                }
            }

            if (streamUrl.isNullOrBlank()) {
                val q = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
                val match = InnerTubeStreamExtractor.searchOfficialYtmSongs(q, limit = 1).firstOrNull()
                if (match != null) {
                    streamUrl = InnerTubeStreamExtractor.resolveWorkingStreamUrl(match.videoId, AudioQuality.LOW).getOrNull()
                }
            }

            if (!streamUrl.isNullOrBlank() && _activePreviewTrackId.value == track.sourceId) {
                withContext(Dispatchers.Main) {
                    if (_activePreviewTrackId.value != track.sourceId) return@withContext
                    exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    val clipItem = MediaItem.Builder()
                        .setMediaId("preview_${track.sourceId}")
                        .setUri(Uri.parse(streamUrl))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0L)
                                .setEndPositionMs(30_000L)
                                .build()
                        )
                        .build()
                    exoPlayer.setMediaItem(clipItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    _isPreviewPlaying.value = true
                }
            } else if (_activePreviewTrackId.value == track.sourceId) {
                withContext(Dispatchers.Main) {
                    _isPreviewBuffering.value = false
                }
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
        _isPreviewBuffering.value = false
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            _isPreviewPlaying.value = false
        }
    }

    fun stopPreviewAndClear() {
        previewResolveJob?.cancel()
        _isPreviewBuffering.value = false
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
