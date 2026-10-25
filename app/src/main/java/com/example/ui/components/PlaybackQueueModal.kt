package com.example.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * YouTube Music style Playback Queue with:
 * 1. Clean two-column header ("Up Next", "X tracks" on left, "Clear queue" on right).
 * 2. Drag-and-drop reordering with '=' handle, zIndex(10f), 24f elevation, landing indicator line.
 * 3. Suppresses queue suggestions whenever only a single song is played in the playback queue
 *    or when playing a local song (`isLocalOnlyTrack`).
 * 4. Uses the Seitharol app logo (`R.drawable.ic_splash_logo`) as placeholder/error/fallback for artwork.
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
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()
    val videoSuggestions by viewModel.queueVideoSuggestions.collectAsState()

    // Never generate or show suggestions when a single song is played in the playback queue or when playing a local track
    val shouldSuppressSuggestions = playerState.isSingleTrackPlayback ||
        queue.size <= 1 ||
        playerState.currentTrack?.isLocalOnlyTrack == true

    LaunchedEffect(playerState.currentTrack?.id, queue.size, shouldSuppressSuggestions) {
        if (!shouldSuppressSuggestions) {
            viewModel.refreshQueueSuggestions(playerState.currentTrack)
        }
    }

    val suggestedTracks = remember(queue, personalizedTracks, trendingHits, videoSuggestions, playerState.currentTrack, shouldSuppressSuggestions) {
        if (shouldSuppressSuggestions) {
            return@remember emptyList<Track>()
        }
        val queueIds = queue.map { it.id }.toSet()
        val isCurrentVideo = playerState.currentTrack?.isVideoTrack == true
        val filteredSpecific = videoSuggestions
            .filter { cand ->
                cand.id !in queueIds && !cand.isLocalOnlyTrack && (isCurrentVideo || !cand.isVideoTrack)
            }
            .distinctBy { it.id }

        if (filteredSpecific.isNotEmpty()) {
            filteredSpecific.take(14)
        } else {
            val fallbackPool = if (isCurrentVideo) {
                videoSuggestions.filterNot { it.isLocalOnlyTrack }.distinctBy { it.id }
            } else {
                personalizedTracks
                    .filterNot {
                        it.isLocalOnlyTrack ||
                            it.isVideoTrack ||
                            it.album.equals("YouTube Video", ignoreCase = true) ||
                            it.genre.equals("YouTube Video", ignoreCase = true) ||
                            it.codec.equals("MP4", ignoreCase = true)
                    }
                    .distinctBy { it.id }
            }
            fallbackPool.filter { it.id !in queueIds }.take(14)
        }
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    val itemHeightPx = with(density) { 72.dp.toPx() }
    val targetDropIndex = remember(draggingFromIndex, dragOffsetY, queue.size) {
        val from = draggingFromIndex ?: return@remember null
        val offsetSlots = (dragOffsetY / itemHeightPx).roundToInt()
        (from + offsetSlots).coerceIn(0, queue.lastIndex)
    }

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

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("playback_queue_modal"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
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
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Playback Queue",
                    color = MaterialTheme.colorScheme.onBackground,
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
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(22.dp)
                    )
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
                        text = "Up Next",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = SyneFontFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (queue.size == 1) "1 track" else "${queue.size} tracks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (queue.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
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

                        if ((draggingFromIndex?.let { targetDropIndex == index && targetDropIndex <= it } == true)) {
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
                                isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(index) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove from Queue",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
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

                                Spacer(modifier = Modifier.width(12.dp))

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

                                Spacer(modifier = Modifier.width(8.dp))

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
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(3.5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    RoundedCornerShape(1.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if ((draggingFromIndex?.let { targetDropIndex == index && targetDropIndex > it } == true)) {
                            LandingDropIndicatorLine()
                        }
                    }

                    if (!shouldSuppressSuggestions) {
                        item {
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
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "Autoplay",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = SyneFontFamily,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Add similar songs to the end of the queue",
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
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Auto-suggested",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontFamily = SyneFontFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
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
                                                .size(42.dp)
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
                                            onClick = { viewModel.addToQueue(track) },
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
