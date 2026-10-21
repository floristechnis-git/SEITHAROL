// java/com/seitharol/ui/history/HistoryScreen.kt
package com.seitharol.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.ui.search.BackArrowSvgIcon

data class HistoryTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyItems: List<HistoryTrackItem>,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
    onPlayTrack: (HistoryTrackItem) -> Unit,
    onToggleFavorite: (HistoryTrackItem) -> Unit,
    onDeleteTrack: (HistoryTrackItem) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Recent, 1 = Archived
    var searchQuery by remember { mutableStateOf("") }

    val recentList = remember(historyItems, searchQuery) {
        historyItems.filter { !it.isArchived && (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)) }
    }
    val archivedList = remember(historyItems, searchQuery) {
        historyItems.filter { it.isArchived && (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)) }
    }
    val displayedList = if (selectedTab == 0) recentList else archivedList

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Listening History",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (displayedList.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text(
                        text = "Clear All",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // Recent / Archived Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Recent (${recentList.size})") }
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("Archived (${archivedList.size})") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search History Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(displayedList, key = { idx, item -> "${item.id}_$idx" }) { _, item ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDeleteTrack(item)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        // Only visible while actively swiping so it NEVER overlaps foreground icons
                        val isSwiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSwiping) MaterialTheme.colorScheme.errorContainer
                                    else Color.Transparent
                                )
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (isSwiping) {
                                TrashSvgIcon(
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                ) {
                    // Opaque Foreground Card (Prevents red background bleed-through)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPlayTrack(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album Art with Music Note SVG fallback (No 'X' icon)
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = item.title,
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
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${item.artist} • ${item.durationFormatted}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Non-overlapping Action Icons (Heart SVG + Trash SVG)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { onToggleFavorite(item) }) {
                                    HeartSvgIcon(
                                        filled = item.isFavorite,
                                        tint = if (item.isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteTrack(item) }) {
                                    TrashSvgIcon(
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeartSvgIcon(
    filled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(12f * sx, 21.35f * sy)
            lineTo(10.55f * sx, 20.03f * sy)
            cubicTo(5.4f * sx, 15.36f * sy, 2f * sx, 12.28f * sy, 2f * sx, 8.5f * sy)
            cubicTo(2f * sx, 5.42f * sy, 4.42f * sy, 3f * sy, 7.5f * sx, 3f * sy)
            cubicTo(9.24f * sx, 3f * sy, 10.91f * sx, 3.81f * sy, 12f * sx, 5.09f * sy)
            cubicTo(13.09f * sx, 3.81f * sy, 14.76f * sx, 3f * sy, 16.5f * sx, 3f * sy)
            cubicTo(19.58f * sx, 3f * sy, 22f * sx, 5.42f * sy, 22f * sx, 8.5f * sy)
            cubicTo(22f * sx, 12.28f * sy, 18.6f * sx, 15.36f * sy, 13.45f * sx, 20.04f * sy)
            close()
        }
        if (filled) {
            drawPath(path = path, color = tint, style = Fill)
        } else {
            drawPath(path = path, color = tint, style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun TrashSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(3f * sx, 6f * sy)
            lineTo(21f * sx, 6f * sy)
            moveTo(8f * sx, 6f * sy)
            lineTo(8f * sx, 4f * sy)
            lineTo(16f * sx, 4f * sy)
            lineTo(16f * sx, 6f * sy)
            moveTo(6f * sx, 6f * sy)
            lineTo(7f * sx, 20f * sy)
            lineTo(17f * sx, 20f * sy)
            lineTo(18f * sx, 6f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}
