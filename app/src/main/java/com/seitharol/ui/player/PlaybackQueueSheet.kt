// java/com/seitharol/ui/player/PlaybackQueueSheet.kt
package com.seitharol.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.player.QueueRecommendationEngine
import com.seitharol.ui.history.TrashSvgIcon
import com.seitharol.ui.search.QueueAddSvgIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    onClose: () -> Unit,
    onMoveTrack: ((fromIndex: Int, toIndex: Int) -> Unit)? = null
) {
    var suggestedTracks by remember { mutableStateOf<List<TrackMetadata>>(emptyList()) }

    // Always fetch context-relevant recommendations whenever the current track or queue changes
    LaunchedEffect(currentTrack?.id, queue.size) {
        val seed = currentTrack ?: queue.firstOrNull() ?: return@LaunchedEffect
        suggestedTracks = QueueRecommendationEngine.fetchRelevantRecommendations(
            currentTrack = seed,
            queueTracks = queue,
            limit = 8
        )
    }

    var sheetDragOffsetY by remember { mutableFloatStateOf(0f) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(0, sheetDragOffsetY.toInt().coerceAtLeast(0)) }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                androidx.compose.foundation.gestures.detectVerticalDragGestures(
                    onDragEnd = {
                        if (sheetDragOffsetY > 160f) onClose()
                        sheetDragOffsetY = 0f
                    },
                    onDragCancel = { sheetDragOffsetY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0f || sheetDragOffsetY > 0f) {
                            change.consume()
                            sheetDragOffsetY = (sheetDragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    }
                )
            }
            .padding(horizontal = 16.dp)
    ) {
        // Queue Header
        Spacer(modifier = Modifier.height(16.dp))
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
        Spacer(modifier = Modifier.height(8.dp))

        // Reorderable Queue with 60fps edge auto-scrolling
        ReorderableQueueLazyColumn(
            queue = queue,
            currentTrackId = currentTrack?.id,
            onMoveTrack = { from, to -> onMoveTrack?.invoke(from, to) },
            onSelectTrack = onSelectQueueTrack,
            onRemoveTrack = onRemoveFromQueue,
            modifier = Modifier.weight(1f)
        ) {
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

            // Suggestions: ALWAYS rendered (whether Autoplay is ON or OFF)
            if (suggestedTracks.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Suggested for ${currentTrack?.title ?: "this queue"}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                items(suggestedTracks.distinctBy { it.id }, key = { "suggest_${it.id}" }) { suggestion ->
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
                            IconButton(onClick = {
                                onAddTrackToQueue(suggestion)
                                suggestedTracks = suggestedTracks.filter { it.id != suggestion.id }
                            }) {
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
}

@Composable
fun ReorderableQueueLazyColumn(
    queue: List<TrackMetadata>,
    currentTrackId: String?,
    onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit,
    onSelectTrack: (TrackMetadata) -> Unit,
    onRemoveTrack: (TrackMetadata) -> Unit,
    modifier: Modifier = Modifier,
    footerContent: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 72.dp.toPx() }
    val maxScrollSpeedPx = with(density) { 18.dp.toPx() }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    val currentQueue by rememberUpdatedState(queue)
    val currentOnMove by rememberUpdatedState(onMoveTrack)

    // Continuous smooth 60fps auto-scroll loop while dragging near top/bottom edges
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            val scrolled = listState.scrollBy(autoScrollSpeed)
            if (scrolled != 0f) {
                val activeIdx = draggingIndex
                if (activeIdx != null) {
                    val layoutInfo = listState.layoutInfo
                    val draggedItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                    if (draggedItemInfo != null) {
                        val itemCenterY = draggedItemInfo.offset + (draggedItemInfo.size / 2f) + draggingOffsetY
                        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            item.index in currentQueue.indices &&
                                itemCenterY.toInt() in item.offset..(item.offset + item.size)
                        }
                        if (targetItem != null && targetItem.index != activeIdx) {
                            currentOnMove(activeIdx, targetItem.index)
                            draggingIndex = targetItem.index
                            draggingOffsetY += (draggedItemInfo.offset - targetItem.offset)
                        }
                    }
                }
            }
            delay(16L)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hitItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            offset.y.toInt() in item.offset..(item.offset + item.size) &&
                                item.index in currentQueue.indices
                        }
                        if (hitItem != null) {
                            draggingIndex = hitItem.index
                            draggingOffsetY = 0f
                        }
                    },
                    onDragEnd = {
                        draggingIndex = null
                        draggingOffsetY = 0f
                        autoScrollSpeed = 0f
                    },
                    onDragCancel = {
                        draggingIndex = null
                        draggingOffsetY = 0f
                        autoScrollSpeed = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val activeIdx = draggingIndex ?: return@detectDragGesturesAfterLongPress
                        draggingOffsetY += dragAmount.y

                        val layoutInfo = listState.layoutInfo
                        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                        val pointerY = change.position.y

                        // Compute smooth edge auto-scroll speed
                        autoScrollSpeed = when {
                            pointerY > viewportHeight - edgeThresholdPx -> {
                                val ratio = ((pointerY - (viewportHeight - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0f, 1f)
                                maxScrollSpeedPx * ratio
                            }
                            pointerY < edgeThresholdPx -> {
                                val ratio = ((edgeThresholdPx - pointerY) / edgeThresholdPx).coerceIn(0f, 1f)
                                -maxScrollSpeedPx * ratio
                            }
                            else -> 0f
                        }

                        val draggedItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                        if (draggedItemInfo != null) {
                            val currentCenterY = draggedItemInfo.offset + (draggedItemInfo.size / 2f) + draggingOffsetY
                            val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                item.index in currentQueue.indices &&
                                    currentCenterY.toInt() in item.offset..(item.offset + item.size)
                            }
                            if (targetItem != null && targetItem.index != activeIdx) {
                                currentOnMove(activeIdx, targetItem.index)
                                draggingIndex = targetItem.index
                                draggingOffsetY += (draggedItemInfo.offset - targetItem.offset)
                            }
                        }
                    }
                )
            },
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(queue, key = { idx, track -> "queue_${track.id}_$idx" }) { index, track ->
            val isDragging = index == draggingIndex
            val isPlaying = track.id == currentTrackId
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 10f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) draggingOffsetY else 0f
                        scaleX = if (isDragging) 1.03f else 1f
                        scaleY = if (isDragging) 1.03f else 1f
                        shadowElevation = if (isDragging) 16f else 0f
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectTrack(track) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onRemoveTrack(track) },
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
                        // Sleek '=' drag handle
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(2.dp)
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(2.dp)
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
        footerContent()
    }
}
