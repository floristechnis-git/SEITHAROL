package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.DownloadTier
import com.example.data.model.Track

/**
 * TrackRow:
 * Standard track display row with rank, artwork, title, artist, like, and options.
 */
@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean = false,
    rankNumber: Int? = null,
    subtitleText: String? = null,
    onTrackClick: () -> Unit,
    onLikeClick: () -> Unit = {},
    onDownloadClick: (DownloadTier) -> Unit = {},
    onPlayNextClick: () -> Unit = {},
    onAddToQueueClick: () -> Unit = {},
    onAddToPlaylistClick: () -> Unit = {},
    onRemoveDownloadClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    TrackCard(
        track = track,
        isPlaying = isPlaying,
        rankNumber = rankNumber,
        subtitleText = subtitleText,
        onTrackClick = onTrackClick,
        onLikeClick = onLikeClick,
        onDownloadClick = onDownloadClick,
        onPlayNextClick = onPlayNextClick,
        onAddToQueueClick = onAddToQueueClick,
        onAddToPlaylistClick = onAddToPlaylistClick,
        onRemoveDownloadClick = onRemoveDownloadClick,
        modifier = modifier
    )
}
