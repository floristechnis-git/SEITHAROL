package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * YouTube Music style Playback Queue with:
 * 1. Clean two-column header ("Up Next", "X tracks" on left, "Clear queue" on right).
 * 2. Drag-and-drop reordering with '=' handle, zIndex(10f), 24f elevation, landing indicator line.
 * 3. Proportional frame-synced edge auto-scrolling when dragged item is within 88.dp of top/bottom.
 * 4. Autoplay toggle card and dynamic "Auto-suggested" section placed below the active queue.
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
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var pointerWindowY by remember { mutableFloatStateOf(-1f) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // LazyColumn measurements for edge auto-scroll
    var listTopWindowY by remember { mutableFloatStateOf(0f) }
    var listHeightPx by remember { mutableFloatStateOf(0f) }

    val isAutoSuggest by viewModel.isAutoSuggestEnabled.collectAsState()
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()

    val suggestedTracks = remember(queue, personalizedTracks, trendingHits) {
        val queueIds = queue.map { it.id }.toSet()
        val pool = (personalizedTracks + trendingHits).distinctBy { it.id }
        pool.filter { it.id !in queueIds }.take(10)
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    // Calculate target drop index based on approximate item height (~72dp)
    val itemHeightPx = with(density) { 72.dp.toPx() }
    val targetDropIndex = remember(draggingFromIndex, dragOffsetY, queue.size) {
        val from = draggingFromIndex ?: return@remember null
        val offsetSlots = (dragOffsetY / itemHeightPx).roundToInt()
        (from + offsetSlots).coerceIn(0, queue.lastIndex)
    }

    // Edge Auto-Scroll Loop (scales quadratically: intensity^2 * maxSpeed)
    LaunchedEffect(draggingFromIndex) {
        if (draggingFromIndex != null) {
            val edgeZonePx = with(density) { 88.dp.toPx() }
            val maxSpeedPx = with(density) { 24.dp.toPx() }

            while (isActive && draggingFromIndex != null) {
                withFrameMillis { _ ->
                    if (listHeightPx > 0f && pointerWindowY > 0f) {
                        val pointerInListY = pointerWindowY - listTopWindowY
                        if (pointerInListY in 0f..edgeZonePx) {
                            // Top edge
                            val distance = (edgeZonePx - pointerInListY).coerceIn(0f, edgeZonePx)
                            val intensity = distance / edgeZonePx
                            val scrollAmount = -(intensity * intensity * maxSpeedPx)
                            listState.dispatchRawDelta(scrollAmount)
                        } else if (pointerInListY in (listHeightPx - edgeZonePx)..listHeightPx) {
                            // Bottom edge
                            val distance = (pointerInListY - (listHeightPx - edgeZonePx)).coerceIn(0f, edgeZonePx)
                            val intensity = distance / edgeZonePx
                            val scrollAmount = (intensity * intensity * maxSpeedPx)
                            listState.dispatchRawDelta(scrollAmount)
                        }
                    }
                }
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("playback_queue_modal"),
        color = Color(0xFF1B1C1C) // SEITHAROL Dark Palette
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ────────────────────────────────────────────────────────────
            // Top Modal Navigation Bar
            // ────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        tint = Color(0xFFE3E4E2),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Playback Queue",
                    color = Color(0xFFE3E4E2),
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
                        tint = Color(0xFFE3E4E2),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // Clean 2-Column Header (YouTube Music Architecture)
            // Left: "Up Next" + "X tracks"
            // Right: Compact "Clear queue" pill button
            // ────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Up Next",
                        color = Color(0xFFE3E4E2),
                        fontFamily = SyneFontFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${queue.size} tracks",
                        color = Color(0xFF9EA1A1),
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (queue.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF282A2A),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { viewModel.clearQueue() }
                    ) {
                        Text(
                            text = "Clear queue",
                            color = Color(0xFFFF5252),
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ────────────────────────────────────────────────────────────
            // Queue List & Drag-and-Drop Area
            // ────────────────────────────────────────────────────────────
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty.\nAdd tracks from Search or Home to play.",
                        color = Color(0xFF9EA1A1),
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
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            listTopWindowY = coordinates.positionInWindow().y
                            listHeightPx = coordinates.size.height.toFloat()
                        },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(queue, key = { index, track -> "${track.id}_$index" }) { index, track ->
                        val isPlaying = index == currentIndex
                        val isDragging = draggingFromIndex == index

                        // Landing indicator line above this item
                        if (draggingFromIndex != null && targetDropIndex == index && targetDropIndex <= draggingFromIndex!!) {
                            LandingDropIndicatorLine()
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 10f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        translationY = dragOffsetY
                                        shadowElevation = 24f
                                    } else {
                                        scaleX = 1.0f
                                        scaleY = 1.0f
                                        translationY = 0f
                                        shadowElevation = 0f
                                    }
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.playQueueIndex(index) }
                                .testTag("queue_item_$index"),
                            color = when {
                                isDragging -> Color(0xFF323535)
                                isPlaying -> Color(0xFF2B2E2E)
                                else -> Color(0xFF232526)
                            },
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = if (isDragging) 16.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Trash icon on the left (delete track from queue)
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(index) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove from Queue",
                                        tint = Color(0xFF9EA1A1),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Artwork thumbnail (50dp square, rounded 8dp)
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2E2E2E))
                                ) {
                                    AsyncImage(
                                        model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Track Title & Artist
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = track.title,
                                            color = if (isPlaying) Color.White else Color(0xFFE3E4E2),
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.SemiBold,
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
                                                    .background(Color.White.copy(alpha = 0.2f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "PLAYING",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = track.artist,
                                        color = Color(0xFF9EA1A1),
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Two-parallel-lines ('=') drag handle with long-press detection
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .pointerInput(index) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    draggingFromIndex = index
                                                    dragOffsetY = 0f
                                                    pointerWindowY = listTopWindowY + offset.y
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    pointerWindowY += dragAmount.y
                                                },
                                                onDragEnd = {
                                                    val from = draggingFromIndex
                                                    val to = targetDropIndex
                                                    if (from != null && to != null && from != to) {
                                                        viewModel.moveTrack(from, to)
                                                    }
                                                    draggingFromIndex = null
                                                    dragOffsetY = 0f
                                                    pointerWindowY = -1f
                                                },
                                                onDragCancel = {
                                                    draggingFromIndex = null
                                                    dragOffsetY = 0f
                                                    pointerWindowY = -1f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Custom '=' icon (two sleek parallel bars)
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(3.5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) Color.White else Color(0xFF888888),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) Color.White else Color(0xFF888888),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        // Landing indicator line below this item
                        if (draggingFromIndex != null && targetDropIndex == index && targetDropIndex > draggingFromIndex!!) {
                            LandingDropIndicatorLine()
                        }
                    }

                    // ────────────────────────────────────────────────────────────
                    // Dedicated Autoplay / Auto-suggest Block at the Bottom
                    // (Placed directly below the last track of the active queue)
                    // ────────────────────────────────────────────────────────────
                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF232526),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("queue_autoplay_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = "Autoplay",
                                        color = Color(0xFFE3E4E2),
                                        fontFamily = SyneFontFamily,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Add similar songs to the end of the queue",
                                        color = Color(0xFF9EA1A1),
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp
                                    )
                                }

                                Switch(
                                    checked = isAutoSuggest,
                                    onCheckedChange = { viewModel.toggleAutoSuggest() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF1B1C1C),
                                        checkedTrackColor = Color.White,
                                        uncheckedThumbColor = Color(0xFF9EA1A1),
                                        uncheckedTrackColor = Color(0xFF2E3030)
                                    )
                                )
                            }
                        }
                    }

                    // Dynamic "Auto-suggested" list section when Autoplay is enabled
                    if (isAutoSuggest && suggestedTracks.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Auto-suggested",
                                color = Color.White,
                                fontFamily = SyneFontFamily,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }

                        itemsIndexed(suggestedTracks, key = { _, track -> "suggested_${track.id}" }) { _, track ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF202222),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.addToQueue(track) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    ) {
                                        AsyncImage(
                                            model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                                            contentDescription = track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            color = Color(0xFFE3E4E2),
                                            fontSize = 13.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            color = Color(0xFF9EA1A1),
                                            fontSize = 11.sp,
                                            fontFamily = OutfitFontFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.addToQueue(track) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlaylistAdd,
                                            contentDescription = "Add to Queue",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Glowing horizontal insertion landing line with a leading dot for real-time drag-and-drop feedback.
 */
@Composable
private fun LandingDropIndicatorLine() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glowing leading dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Glowing 2.5dp insertion line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White,
                            Color.White.copy(alpha = 0.75f),
                            Color.White.copy(alpha = 0.15f)
                        )
                    ),
                    shape = RoundedCornerShape(1.25.dp)
                )
        )
    }
}
