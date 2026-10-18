package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*

/**
 * Playback Queue Modal with real drag-and-drop reordering.
 *
 * Header: Down arrow (left), "Playback Queue" (center), Close 'X' (right).
 * Items: Trash delete icon (left), Album art, Title, Artist,
 *        DragHandle (right) — long-press + drag to reorder.
 */
@Composable
fun PlaybackQueueModal(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue = playerState.queue
    val currentIndex = playerState.currentIndex

    // Drag state
    var draggingFromIndex by remember { mutableStateOf<Int?>(null) }
    var draggingToIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    val listState = rememberLazyListState()

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("playback_queue_modal"),
        color = Color(0xFF141414)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header: Down arrow, "Playback Queue", Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse Queue",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Playback Queue",
                    color = Color.White,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Queue",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Subtitle / Track count info + Auto-Suggest toggle
            val isAutoSuggest by viewModel.isAutoSuggestEnabled.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${queue.size} TRACKS • UP NEXT",
                    color = Color(0xFF888888),
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Auto-Suggest chip
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { viewModel.toggleAutoSuggest() }
                            .testTag("queue_auto_suggest_btn"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAutoSuggest) KangleiGreen.copy(alpha = 0.25f) else Color(0xFF2A2A2A)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = "✦ Auto-Suggest",
                                color = if (isAutoSuggest) KangleiGreen else Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (queue.size > 1) {
                        Text(
                            text = "Clear",
                            color = Color(0xFFFF5252),
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.clearQueue() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty.\nAdd tracks from Search or Home to play.",
                        color = Color(0xFF888888),
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(queue, key = { index, track -> "${track.id}_$index" }) { index, track ->
                        val isPlaying = index == currentIndex
                        val isDragging = draggingFromIndex == index

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { viewModel.playQueueIndex(index) }
                                .testTag("queue_item_$index")
                                // Visually lift the dragged row
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragOffsetY
                                        shadowElevation = 16f
                                        alpha = 0.95f
                                    }
                                },
                            color = when {
                                isDragging -> Color(0xFF303030)
                                isPlaying -> Color(0xFF262626)
                                else -> Color(0xFF1E1E1E)
                            },
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = if (isDragging) 8.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Trash icon on the left (delete track from queue)
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(index) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove from Queue",
                                        tint = Color(0xFF999999),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Artwork thumbnail (rounded 8dp)
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2E2E2E))
                                ) {
                                    AsyncImage(
                                        model = track.artUrl,
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Track Title & Artist
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = track.title,
                                            color = if (isPlaying) KangleiGreen else Color.White,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isPlaying) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(KangleiGreen.copy(alpha = 0.2f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "PLAYING",
                                                    color = KangleiGreen,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = track.artist,
                                        color = Color(0xFFAAAAAA),
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Real drag handle — long-press to drag and reorder
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .pointerInput(index) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingFromIndex = index
                                                    draggingToIndex = index
                                                    dragOffsetY = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y

                                                    // Estimate the item height (approx 66dp = 66 * density)
                                                    val itemHeightPx = 66 * density
                                                    val rawTargetIndex = index + (dragOffsetY / itemHeightPx).toInt()
                                                    draggingToIndex = rawTargetIndex
                                                        .coerceIn(0, queue.lastIndex)
                                                },
                                                onDragEnd = {
                                                    val from = draggingFromIndex
                                                    val to = draggingToIndex
                                                    if (from != null && to != null && from != to) {
                                                        viewModel.moveTrack(from, to)
                                                    }
                                                    draggingFromIndex = null
                                                    draggingToIndex = null
                                                    dragOffsetY = 0f
                                                },
                                                onDragCancel = {
                                                    draggingFromIndex = null
                                                    draggingToIndex = null
                                                    dragOffsetY = 0f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = Color(0xFF777777),
                                        modifier = Modifier.size(24.dp)
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
