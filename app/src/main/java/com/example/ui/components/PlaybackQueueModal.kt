package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import com.example.player.RepeatMode
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.Track
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.player.QueueRecommendationEngine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * YouTube Music style Playback Queue with:
 * 1. Clean two-column header ("Up Next", "X tracks/videos" on left, "Clear queue" on right).
 * 2. Drag-and-drop reordering with '=' handle, zIndex(10f), 24f elevation, landing indicator line.
 * 3. Autoplay / Auto-suggest toggle card directly below the active queue:
 *    - Suggestions remain visible in the "Suggested for <Current Track>" div even when the toggle is OFF,
 *      allowing manual '+' addition to the queue, while only auto-appending into the queue when the last
 *      item ends if the toggle is ON.
 * 4. Strict Video vs. Song separation:
 *    - When playing a YouTube Video, suggests ONLY YouTube Videos (no audio songs).
 *    - When playing an Audio Song, suggests ONLY Audio Songs (no videos).
 * 5. Suppresses suggestions ONLY when playing a local single file (`isLocalOnlyTrack` with no online queue context).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueModal(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue = playerState.queue
    val currentIndex = playerState.currentIndex
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    // Drag state
    var draggingFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var pointerWindowY by remember { mutableFloatStateOf(-1f) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current

    var listTopWindowY by remember { mutableFloatStateOf(0f) }
    var listHeightPx by remember { mutableFloatStateOf(0f) }

    val isAutoSuggest by viewModel.isAutoSuggestEnabled.collectAsState()
    val isOffline by viewModel.isOfflineMode.collectAsState()
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val activeHistory by viewModel.activeHistory.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()
    val videoSuggestions by viewModel.queueVideoSuggestions.collectAsState()

    val currentTrack = playerState.currentTrack ?: queue.getOrNull(currentIndex) ?: queue.firstOrNull()
    // Always anchor Up Next recommendations on the FIRST song in the queue (not the last playing song)
    val firstAnchorTrack = remember(queue, currentTrack?.id) {
        queue.firstOrNull { !it.isLocalOnlyTrack } ?: currentTrack
    }
    val isCurrentVideo = playerState.isVideoMode ||
        firstAnchorTrack?.isVideoTrack == true ||
        (queue.isNotEmpty() && queue.all { it.isVideoTrack })

    // Suppress suggestions ONLY when offline or when playing a local single file (no online tracks in queue)
    val shouldSuppressSuggestions = isOffline ||
        firstAnchorTrack == null ||
        (firstAnchorTrack.isLocalOnlyTrack && queue.all { it.isLocalOnlyTrack })

    // Fetch YouTube Music RDAMVM Up Next sequence ONLY for the FIRST song (`firstAnchorTrack.id`),
    // so the Up Next sequence stays locked to the first song and never switches as subsequent songs play!
    LaunchedEffect(firstAnchorTrack?.id, isCurrentVideo, shouldSuppressSuggestions) {
        if (shouldSuppressSuggestions || firstAnchorTrack == null) {
            return@LaunchedEffect
        }
        viewModel.refreshQueueSuggestions(firstAnchorTrack)
    }

    val suggestedTracks = remember(
        queue,
        videoSuggestions,
        firstAnchorTrack?.id,
        isCurrentVideo,
        shouldSuppressSuggestions
    ) {
        if (shouldSuppressSuggestions) {
            return@remember emptyList<Track>()
        }
        val queueIds = queue.map { it.id }.toSet()
        val queueTitles = queue.map { it.title.lowercase().trim() }.toSet()

        videoSuggestions
            .filter { cand ->
                cand.id !in queueIds &&
                    cand.title.lowercase().trim() !in queueTitles &&
                    !cand.isLocalOnlyTrack &&
                    if (isCurrentVideo) cand.isVideoTrack else !cand.isVideoTrack
            }
            .distinctBy { it.id }
            .take(18)
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    val targetDropIndex = draggingFromIndex
    val sheetScope = rememberCoroutineScope()
    val sheetDragOffsetY = remember { Animatable(0f) }

    LaunchedEffect(draggingFromIndex) {
        if (draggingFromIndex != null) {
            val edgeZonePx = with(density) { 88.dp.toPx() }
            val maxSpeedPx = with(density) { 24.dp.toPx() }

            while (isActive && draggingFromIndex != null) {
                withFrameMillis { _ ->
                    if (listHeightPx > 0f && pointerWindowY > 0f) {
                        val pointerInListY = pointerWindowY - listTopWindowY
                        if (pointerInListY in 0f..edgeZonePx) {
                            val distance = (edgeZonePx - pointerInListY).coerceIn(0f, edgeZonePx)
                            val intensity = distance / edgeZonePx
                            val scrollAmount = -(intensity * intensity * maxSpeedPx)
                            listState.dispatchRawDelta(scrollAmount)
                        } else if (pointerInListY in (listHeightPx - edgeZonePx)..listHeightPx) {
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

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = sheetDragOffsetY.value },
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 8.dp)
                    .testTag("playback_queue_modal")
            ) {
            // Google Pixel Sheet Header Bar (supports vertical swipe-down to dismiss)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                sheetScope.launch {
                                    if (sheetDragOffsetY.value > 150f) {
                                        onDismiss()
                                    } else {
                                        sheetDragOffsetY.animateTo(
                                            0f,
                                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                sheetScope.launch { sheetDragOffsetY.animateTo(0f) }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (sheetDragOffsetY.value + dragAmount).coerceAtLeast(0f)
                                sheetScope.launch { sheetDragOffsetY.snapTo(next) }
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 6.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse Queue",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Text(
                        text = "Up Next Queue",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Queue",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Playing Queue",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = SyneFontFamily,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = run {
                            val unit = if (isCurrentVideo) "video" else "track"
                            if (queue.size == 1) "1 $unit" else "${queue.size} ${unit}s"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { viewModel.cycleRepeatMode() },
                        shape = RoundedCornerShape(12.dp),
                        color = if (playerState.repeatMode != RepeatMode.OFF) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (playerState.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat Mode",
                                tint = if (playerState.repeatMode != RepeatMode.OFF) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = when (playerState.repeatMode) {
                                    RepeatMode.OFF -> "Repeat Off"
                                    RepeatMode.ALL -> "Repeat All"
                                    RepeatMode.ONE -> "Repeat 1"
                                },
                                color = if (playerState.repeatMode != RepeatMode.OFF) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontFamily = OutfitFontFamily,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (queue.size > 1) {
                        Surface(
                            onClick = { viewModel.clearQueue() },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "Clear",
                                color = Color(0xFF850101),
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            if (playerState.repeatMode == RepeatMode.ONE) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RepeatOne,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Looping current track (Repeat 1 active)",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            onClick = { viewModel.cycleRepeatMode() },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                        ) {
                            Text(
                                text = "Turn off",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty.\nAdd tracks from Search or Home to play.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = draggingFromIndex == null,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            listTopWindowY = coordinates.positionInWindow().y
                            listHeightPx = coordinates.size.height.toFloat()
                        },
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(queue, key = { index, track -> "q_${track.id}_$index" }) { index, track ->
                        val isPlaying = index == currentIndex
                        val isDragging = draggingFromIndex == index
                        val currentIndexState by rememberUpdatedState(index)
                        val currentQueueSizeState by rememberUpdatedState(queue.size)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 10f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                        translationY = dragOffsetY
                                        shadowElevation = 20f
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
                                isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = if (isDragging) {
                                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            } else null,
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = if (isDragging) 14.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(index) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove from Queue",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                                    if (upgradedArt.isBlank()) {
                                        Image(
                                            painter = appLogoPainter,
                                            contentDescription = track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        AsyncImage(
                                            model = upgradedArt,
                                            contentDescription = track.title,
                                            placeholder = appLogoPainter,
                                            error = appLogoPainter,
                                            fallback = appLogoPainter,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = track.title,
                                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "PLAYING",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = track.artist,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (queue.size > 1) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (index > 0) viewModel.moveTrack(index, index - 1)
                                            },
                                            enabled = index > 0,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = "Move Up",
                                                tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (index < queue.lastIndex) viewModel.moveTrack(index, index + 1)
                                            },
                                            enabled = index < queue.lastIndex,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Move Down",
                                                tint = if (index < queue.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Instant Drag Handle ('='): drags immediately on touch without long-press delay and live-swaps items!
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .pointerInput(track.id) {
                                            detectVerticalDragGestures(
                                                onDragStart = { offset: Offset ->
                                                    draggingFromIndex = currentIndexState
                                                    dragOffsetY = 0f
                                                    pointerWindowY = listTopWindowY + offset.y
                                                },
                                                onVerticalDrag = { change: PointerInputChange, dragAmount: Float ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount
                                                    pointerWindowY += dragAmount

                                                    val fromIdx = draggingFromIndex ?: return@detectVerticalDragGestures
                                                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                                                    val currentItemInfo = visibleItems.firstOrNull { it.index == fromIdx }
                                                        ?: return@detectVerticalDragGestures
                                                    val draggedCenterY = currentItemInfo.offset + (currentItemInfo.size / 2f) + dragOffsetY

                                                    val targetItem = visibleItems.firstOrNull { item ->
                                                        item.index in 0 until currentQueueSizeState &&
                                                            item.index != fromIdx &&
                                                            if (dragOffsetY > 0f && item.index > fromIdx) {
                                                                draggedCenterY > item.offset + (item.size / 2f)
                                                            } else if (dragOffsetY < 0f && item.index < fromIdx) {
                                                                draggedCenterY < item.offset + (item.size / 2f)
                                                            } else {
                                                                false
                                                            }
                                                    }

                                                    if (targetItem != null) {
                                                        val offsetAdjustment = currentItemInfo.offset - targetItem.offset
                                                        viewModel.moveTrack(fromIdx, targetItem.index)
                                                        draggingFromIndex = targetItem.index
                                                        dragOffsetY += offsetAdjustment
                                                    }
                                                },
                                                onDragEnd = {
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
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(3.5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!shouldSuppressSuggestions) {
                        item(key = "queue_autoplay_card_item") {
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
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
                                    val mediaPlural = if (isCurrentVideo) "videos" else "songs"
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "Autoplay",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = SyneFontFamily,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (isAutoSuggest) {
                                                "Add similar $mediaPlural to the end of the queue"
                                            } else {
                                                "Autoplay is off • Suggestions below won't auto-play when queue ends"
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 12.sp
                                        )
                                    }

                                    Switch(
                                        checked = isAutoSuggest,
                                        onCheckedChange = { viewModel.toggleAutoSuggest() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            uncheckedBorderColor = MaterialTheme.colorScheme.outline
                                        )
                                    )
                                }
                            }
                        }

                        if (suggestedTracks.isNotEmpty()) {
                            item(key = "queue_suggestions_header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Suggested for ${firstAnchorTrack?.title ?: currentTrack?.title ?: if (isCurrentVideo) "this video" else "this queue"}",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontFamily = SyneFontFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }

                            itemsIndexed(suggestedTracks, key = { idx, track -> "suggested_${track.id}_$idx" }) { _, track ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            val existingIdx = queue.indexOfFirst { it.id == track.id }
                                            if (existingIdx >= 0) {
                                                viewModel.playQueueIndex(existingIdx)
                                            } else {
                                                viewModel.addToQueue(track)
                                                viewModel.playQueueIndex(queue.size)
                                            }
                                            onDismiss()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(if (isCurrentVideo) 56.dp else 42.dp, 42.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        ) {
                                            val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                                            if (upgradedArt.isBlank()) {
                                                Image(
                                                    painter = appLogoPainter,
                                                    contentDescription = track.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                AsyncImage(
                                                    model = upgradedArt,
                                                    contentDescription = track.title,
                                                    placeholder = appLogoPainter,
                                                    error = appLogoPainter,
                                                    fallback = appLogoPainter,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.title,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = track.artist,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                fontFamily = OutfitFontFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                viewModel.addToQueue(track)
                                                
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlaylistAdd,
                                                contentDescription = "Add to Queue",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
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
}

@Composable
private fun LandingDropIndicatorLine() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        )
                    ),
                    shape = RoundedCornerShape(1.25.dp)
                )
        )
    }
}
