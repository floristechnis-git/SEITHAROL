// java/com/seitharol/ui/player/PlaybackQueueSheet.kt
package com.seitharol.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.player.QueueRecommendationEngine
import com.seitharol.ui.history.TrashSvgIcon
import com.seitharol.ui.search.QueueAddSvgIcon

@Composable
fun PlaybackQueueSheet(
    currentTrack: TrackMetadata?,
    queue: List<TrackMetadata>,
    autoplayEnabled: Boolean,
    onToggleAutoplay: (Boolean) -> Unit,
    onSelectQueueTrack: (TrackMetadata) -> Unit,
    onRemoveFromQueue: (TrackMetadata) -> Unit,
    onAddTrackToQueue: (TrackMetadata) -> Unit,
    onAutoAppendTracks: (List<TrackMetadata>) -> Unit,
    onClose: () -> Unit
) {
    var suggestedTracks by remember { mutableStateOf<List<TrackMetadata>>(emptyList()) }

    // Fetch context-relevant recommendations whenever the current track or queue changes
    LaunchedEffect(currentTrack?.id, queue.size, autoplayEnabled) {
        val seed = currentTrack ?: queue.firstOrNull() ?: return@LaunchedEffect
        val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
            currentTrack = seed,
            queueTracks = queue,
            limit = 6
        )

        if (autoplayEnabled) {
            // Autoplay is ON -> directly auto-add relevant songs to the queue if fewer than 4 upcoming tracks remain
            val currentIndex = queue.indexOfFirst { it.id == seed.id }.coerceAtLeast(0)
            val upcomingCount = (queue.size - 1 - currentIndex).coerceAtLeast(0)
            if (upcomingCount < 3 && recommendations.isNotEmpty()) {
                val toAppend = recommendations.take(3 - upcomingCount)
                onAutoAppendTracks(toAppend)
            }
            suggestedTracks = emptyList()
        } else {
            // Autoplay is OFF -> suggest only, do not auto-add
            suggestedTracks = recommendations
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Queue",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
            Text(
                text = "Up Next • ${queue.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Active Queue Items
        items(queue, key = { "queue_${it.id}" }) { track ->
            val isPlaying = track.id == currentTrack?.id
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectQueueTrack(track) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onRemoveFromQueue(track) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        TrashSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        error = painterResource(id = R.drawable.ic_music_note),
                        placeholder = painterResource(id = R.drawable.ic_music_note)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "PLAYING",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Autoplay Toggle Card
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autoplay",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (autoplayEnabled) {
                                "Automatically adding similar songs to Up Next"
                            } else {
                                "Autoplay is off • Showing manual suggestions below"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoplayEnabled,
                        onCheckedChange = onToggleAutoplay
                    )
                }
            }
        }

        // Manual Suggestions ONLY when Autoplay is OFF
        if (!autoplayEnabled && suggestedTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Suggested for ${currentTrack?.title ?: "this queue"}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(suggestedTracks, key = { "suggest_${it.id}" }) { suggestion ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = suggestion.thumbnailUrl,
                            contentDescription = suggestion.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            error = painterResource(id = R.drawable.ic_music_note),
                            placeholder = painterResource(id = R.drawable.ic_music_note)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = suggestion.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = suggestion.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onAddTrackToQueue(suggestion) }) {
                            QueueAddSvgIcon(
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
