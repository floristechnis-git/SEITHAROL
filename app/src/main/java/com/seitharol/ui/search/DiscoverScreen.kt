// java/com/seitharol/ui/search/DiscoverScreen.kt
package com.seitharol.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R

data class DiscoverTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationFormatted: String,
    val isLosslessAvailable: Boolean = true
)

@Composable
fun DiscoverScreen(
    tracks: List<DiscoverTrackItem>,
    currentlyPreviewingId: String?,
    isPreviewPlaying: Boolean,
    previewProgress: Float,
    onBack: () -> Unit,
    onTogglePreview: (DiscoverTrackItem) -> Unit,
    onPlayFullTrack: (DiscoverTrackItem) -> Unit,
    onDownloadTrack: (DiscoverTrackItem, Boolean) -> Unit,
    onAddToQueue: (DiscoverTrackItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Header with pure SVG Back Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Discover & Download",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Instant 30s studio previews & direct downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                val isThisPreviewing = currentlyPreviewingId == track.id
                DiscoverTrackCard(
                    track = track,
                    isPreviewing = isThisPreviewing,
                    isPlaying = isThisPreviewing && isPreviewPlaying,
                    progress = if (isThisPreviewing) previewProgress else 0f,
                    onTogglePreview = { onTogglePreview(track) },
                    onPlayFull = { onPlayFullTrack(track) },
                    onDownloadFlac = { onDownloadTrack(track, true) },
                    onDownloadMp3 = { onDownloadTrack(track, false) },
                    onAddToQueue = { onAddToQueue(track) }
                )
            }
        }
    }
}

@Composable
private fun DiscoverTrackCard(
    track: DiscoverTrackItem,
    isPreviewing: Boolean,
    isPlaying: Boolean,
    progress: Float,
    onTogglePreview: () -> Unit,
    onPlayFull: () -> Unit,
    onDownloadFlac: () -> Unit,
    onDownloadMp3: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // High-res Album Art Thumbnail with SVG Fallback (Never an Emoji)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlayFull() },
                    contentAlignment = Alignment.Center
                ) {
                    if (track.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = painterResource(id = R.drawable.ic_music_note),
                            placeholder = painterResource(id = R.drawable.ic_music_note)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${track.durationFormatted}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (track.isLosslessAvailable) R.drawable.ic_flac else R.drawable.ic_mp3
                            ),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (track.isLosslessAvailable) "FLAC / 320kbps" else "320kbps MP3",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 30s Preview Play/Pause SVG Button
                IconButton(
                    onClick = onTogglePreview,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = "Preview",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isPreviewing) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row (All Pure Vector/SVG Icons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add to Queue Button
                OutlinedButton(
                    onClick = onAddToQueue,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    QueueAddSvgIcon(
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Queue", style = MaterialTheme.typography.labelMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // MP3 Download Button
                    OutlinedButton(
                        onClick = onDownloadMp3,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        DownloadSvgIcon(
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MP3", style = MaterialTheme.typography.labelMedium)
                    }

                    // Lossless FLAC Download Button
                    Button(
                        onClick = onDownloadFlac,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        DownloadSvgIcon(
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lossless", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun BackArrowSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(19f * sx, 12f * sy)
            lineTo(5f * sx, 12f * sy)
            moveTo(12f * sx, 19f * sy)
            lineTo(5f * sx, 12f * sy)
            lineTo(12f * sx, 5f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun DownloadSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(12f * sx, 3f * sy)
            lineTo(12f * sx, 15f * sy)
            moveTo(7f * sx, 10f * sy)
            lineTo(12f * sx, 15f * sy)
            lineTo(17f * sx, 10f * sy)
            moveTo(4f * sx, 20f * sy)
            lineTo(20f * sx, 20f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun QueueAddSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(3f * sx, 6f * sy)
            lineTo(15f * sx, 6f * sy)
            moveTo(3f * sx, 10f * sy)
            lineTo(15f * sx, 10f * sy)
            moveTo(3f * sx, 14f * sy)
            lineTo(11f * sx, 14f * sy)
            moveTo(17f * sx, 14f * sy)
            lineTo(17f * sx, 20f * sy)
            moveTo(14f * sx, 17f * sy)
            lineTo(20f * sx, 17f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}
