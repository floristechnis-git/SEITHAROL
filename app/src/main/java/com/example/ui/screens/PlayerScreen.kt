package com.example.ui.screens

import android.app.Activity
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.MainActivity
import com.example.R
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.player.RepeatMode
import com.example.ui.CascadeViewModel
import com.example.ui.components.PlaybackQueueModal
import com.example.ui.theme.*
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.VideoDescriptionMetadata
import com.seitharol.ui.components.FullscreenExpandSvgIcon
import com.seitharol.ui.components.FullscreenMinimizeSvgIcon
import com.seitharol.ui.components.InstrumentalGapMusicNoteIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SvgChevronIcon(
    isExpanded: Boolean,
    modifier: Modifier = Modifier.size(26.dp),
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (isExpanded) {
                moveTo(w * 0.22f, h * 0.36f)
                lineTo(w * 0.50f, h * 0.64f)
                lineTo(w * 0.78f, h * 0.36f)
            } else {
                moveTo(w * 0.22f, h * 0.64f)
                lineTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.78f, h * 0.64f)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Professional vector-based ±10s Skip Visual Feedback Badge (replaces emojis).
 */
@Composable
fun SeitharolSkip10sBadgeOverlay(
    isForward: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xD916181A)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isForward) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "Rewind 10 seconds",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = if (isForward) "+10s" else "-10s",
                color = Color.White,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp
            )
            if (isForward) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Ultimate Studio Progress Scrubber Bar for PlayerScreen & Fullscreen Video:
 * - Smoothly expands track height and thumb radius on touch/drag
 * - Displays buffered ahead progress and floating time-preview pill while scrubbing
 */
@Composable
fun SeitharolProProgressBar(
    progress: Float,
    bufferedFraction: Float = 0f,
    displayPositionMs: Long,
    totalDurationMs: Long,
    isDragging: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    isFullscreenDark: Boolean = false
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = if (isFullscreenDark) {
        Color.White.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val bufferedColor = if (isFullscreenDark) {
        Color.White.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f)
    }
    val timeTextColor = if (isFullscreenDark) {
        Color.White.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val safeProgress = progress.coerceIn(0f, 1f)
    val safeBuffered = if (bufferedFraction.isNaN() || bufferedFraction.isInfinite()) 0f else bufferedFraction.coerceIn(0f, 1f)
    val bufferedProgress = safeBuffered.coerceIn(safeProgress, 1f)

    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 7.dp else 4.5.dp,
        animationSpec = tween(150),
        label = "scrubber_track_height"
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDragging) 9.dp else 6.5.dp,
        animationSpec = tween(150),
        label = "scrubber_thumb_radius"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = isDragging,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xEE1D1F21),
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .border(1.dp, activeColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                Text(
                    text = "${formatMs(displayPositionMs)} / ${formatMs(totalDurationMs)}",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(totalDurationMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onValueChange(frac)
                        onValueChangeFinished()
                    }
                }
                .pointerInput(totalDurationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onValueChange(frac)
                        },
                        onDragEnd = {
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            onValueChangeFinished()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onValueChange(frac)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                val trackH = trackHeightDp.toPx()
                val cy = size.height / 2f
                val corner = CornerRadius(trackH / 2f, trackH / 2f)

                // Inactive background track
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = corner
                )

                // Buffered ahead track
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(size.width * bufferedProgress, trackH),
                    cornerRadius = corner
                )

                // Active played track
                val activeW = size.width * safeProgress
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(activeW, trackH),
                    cornerRadius = corner
                )

                // Outer thumb halo when dragging
                val thumbX = activeW.coerceIn(thumbRadiusDp.toPx(), (size.width - thumbRadiusDp.toPx()).coerceAtLeast(thumbRadiusDp.toPx()))
                if (isDragging) {
                    drawCircle(
                        color = activeColor.copy(alpha = 0.28f),
                        radius = thumbRadiusDp.toPx() * 1.7f,
                        center = Offset(thumbX, cy)
                    )
                }

                // Scrubber thumb
                drawCircle(
                    color = Color.White,
                    radius = thumbRadiusDp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = activeColor,
                    radius = (thumbRadiusDp.toPx() - 2.dp.toPx()).coerceAtLeast(2f),
                    center = Offset(thumbX, cy)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMs(displayPositionMs),
                color = timeTextColor,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatMs(totalDurationMs),
                color = timeTextColor,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Slide-up Modal Bottom Sheet displaying the full untruncated video title, creator/channel,
 * duration/format details, and video description when the user clicks on the video name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailsDescriptionSheet(
    track: com.example.data.model.Track,
    onVisitChannel: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var details by remember(track.id) { mutableStateOf<VideoDescriptionMetadata?>(null) }
    var isLoading by remember(track.id) { mutableStateOf(!track.isLocalOnlyTrack) }

    LaunchedEffect(track.id) {
        if (!track.isLocalOnlyTrack) {
            isLoading = true
            details = InnerTubeStreamExtractor.fetchVideoDetailsDescription(
                videoId = track.id,
                fallbackTitle = track.title,
                fallbackArtist = track.artist
            )
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 22.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video Details",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full untruncated video title
            Text(
                text = details?.title?.takeIf { it.isNotBlank() } ?: track.title,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = if (track.isLocalOnlyTrack) "LOCAL MP4 VIDEO" else "HD VIDEO",
                        fontSize = 10.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = details?.author?.takeIf { it.isNotBlank() } ?: track.artist,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onVisitChannel != null && track.isNavigableArtist) {
                        Modifier.clickable { onVisitChannel() }
                    } else Modifier
                )

                Text(
                    text = "• ${track.durationFormatted}",
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }

            val viewCount = details?.viewCountText?.takeIf { it.isNotBlank() }
            if (viewCount != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$viewCount views",
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                val descText = details?.description?.trim().orEmpty()
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Loading video description...",
                                fontFamily = OutfitFontFamily,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    descText.isNotBlank() -> {
                        Text(
                            text = descText,
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    track.isLocalOnlyTrack -> {
                        Text(
                            text = "Playing directly from on-device local video storage (${track.album.ifBlank { "MP4 Video" }}).",
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Text(
                            text = "No additional description provided for this video.",
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun PlayerScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onShareTrack: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack ?: return
    val context = LocalContext.current
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    var isUserDraggingSlider by remember { mutableStateOf(false) }
    var userSeekingPosition by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var showExpandedLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showAudioAdjustSheet by remember { mutableStateOf(false) }
    var showDownloadOptionsSheet by remember { mutableStateOf(false) }
    var showVideoDescriptionSheet by remember { mutableStateOf(false) }
    val showFullscreenVideo by viewModel.isFullscreenVideo.collectAsState()
    var isVideoZoomCrop by remember { mutableStateOf(false) }
    var playerSkipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(playerSkipFeedbackIsForward) {
        if (playerSkipFeedbackIsForward != null) {
            delay(650L)
            playerSkipFeedbackIsForward = null
        }
    }

    val isVideoTrack = playerState.isVideoMode ||
        track.isVideoTrack ||
        track.genre.equals("YouTube Video", ignoreCase = true) ||
        track.genre.equals("Local Video", ignoreCase = true) ||
        track.album.equals("YouTube Video", ignoreCase = true) ||
        track.album.equals("MP4 Video", ignoreCase = true)

    var hasClickedQueueButton by remember(track.id, playerState.isPartOfPlaylist) {
        mutableStateOf(playerState.isPartOfPlaylist && !playerState.isSingleTrackPlayback)
    }
    var isUpNextVisible by remember(track.id, playerState.isPartOfPlaylist) {
        mutableStateOf(playerState.isPartOfPlaylist && !playerState.isSingleTrackPlayback)
    }

    val inlineLyricListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

    var isUserScrollingInlineLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(inlineLyricListState.isScrollInProgress) {
        if (inlineLyricListState.isScrollInProgress) {
            isUserScrollingInlineLyrics = true
        } else if (isUserScrollingInlineLyrics) {
            delay(2500L)
            isUserScrollingInlineLyrics = false
        }
    }

    LaunchedEffect(playerState.activeLyricIndex, showLyrics, isUserScrollingInlineLyrics) {
        runCatching {
            if (showLyrics && !isUserScrollingInlineLyrics && playerState.activeLyricIndex in 0 until playerState.lyricLines.size) {
                val activeIdx = playerState.activeLyricIndex
                val layoutInfo = inlineLyricListState.layoutInfo
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                if (visibleItem != null && viewportHeight > 0) {
                    val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
                    val viewportCenter = viewportHeight / 2f
                    val delta = itemCenter - viewportCenter
                    inlineLyricListState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                    )
                } else if (viewportHeight > 0) {
                    inlineLyricListState.animateScrollToItem(
                        index = activeIdx,
                        scrollOffset = -(viewportHeight / 2) + 40
                    )
                } else {
                    inlineLyricListState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
                }
            }
        }
    }

    val displayPosition = (if (isUserDraggingSlider) userSeekingPosition?.toLong() else null)
        ?: playerState.currentPositionMs.coerceAtLeast(0L)
    val totalDuration = (if (playerState.durationMs > 0L) playerState.durationMs else (track.durationMs.takeIf { it > 0 } ?: 210000L)).coerceAtLeast(1000L)
    val rawSliderProgress = displayPosition.toFloat() / totalDuration.toFloat()
    val sliderProgress = if (rawSliderProgress.isNaN() || rawSliderProgress.isInfinite()) 0f else rawSliderProgress.coerceIn(0f, 1f)

    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val isLiked = track.id in likedTrackIds || track.isLiked

    if (showFullscreenVideo) {
        val fullscreenNextTrack = when {
            playerState.currentIndex in 0 until playerState.queue.size - 1 ->
                playerState.queue[playerState.currentIndex + 1]
            playerState.repeatMode == RepeatMode.ALL && playerState.queue.size > 1 ->
                playerState.queue.firstOrNull()
            else -> null
        }
        val fullscreenRemainingMs = (totalDuration - displayPosition).coerceAtLeast(0L)
        var dismissedFullscreenCountdown by remember(track.id) { mutableStateOf(false) }
        val showFullscreenCountdown = playerState.isPlaying &&
            !playerState.isBuffering &&
            totalDuration >= 15_000L &&
            fullscreenRemainingMs in 1L..10_000L &&
            fullscreenNextTrack != null &&
            !dismissedFullscreenCountdown
        val fullscreenCountdownSec = ((fullscreenRemainingMs + 999L) / 1000L).toInt().coerceIn(1, 10)
        val fullscreenCountdownProg = (fullscreenRemainingMs.toFloat() / 10_000f).coerceIn(0f, 1f)

        Dialog(
            onDismissRequest = { viewModel.setFullscreenVideo(false) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            SeitharolFullscreenVideoModal(
                viewModel = viewModel,
                playerState = playerState,
                isZoomCrop = isVideoZoomCrop,
                onToggleZoomCrop = { isVideoZoomCrop = !isVideoZoomCrop },
                nextTrack = fullscreenNextTrack,
                showNextCountdown = showFullscreenCountdown,
                countdownSeconds = fullscreenCountdownSec,
                countdownProgress = fullscreenCountdownProg,
                onDismissCountdown = { dismissedFullscreenCountdown = true },
                onOpenVideoDescription = { showVideoDescriptionSheet = true },
                onEnterPiP = {
                    viewModel.setFullscreenVideo(false)
                    (context as? MainActivity)?.enterPiPMode()
                },
                onDismiss = { viewModel.setFullscreenVideo(false) }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragOffsetY.value.coerceAtLeast(0f)
                alpha = (1f - (dragOffsetY.value / 1200f)).coerceIn(0.2f, 1f)
            }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures { /* consume background taps */ }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("full_player_screen")
    ) {
        // ────────────────────────────────────────────────────────────
        // Top Header with Collapse, Playing From Status, and Like
        // ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY.value > 250f) {
                                scope.launch {
                                    dragOffsetY.animateTo(
                                        targetValue = 1200f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    dragOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onVerticalDrag = { change: PointerInputChange, dragAmount: Float ->
                            change.consume()
                            scope.launch {
                                dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceAtLeast(0f))
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("player_collapse_btn")
            ) {
                SvgChevronIcon(isExpanded = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "PLAYING FROM SEITHAROL",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                val currentTempQuality = viewModel.getTemporaryTrackQuality()
                val qualityText = when {
                    isVideoTrack && track.isLocalOnlyTrack -> "Local MP4 Video"
                    isVideoTrack && track.isDownloaded -> "Offline MP4 Video"
                    isVideoTrack && playerState.isStreamCachedOnDisk -> "Cached MP4 Video"
                    isVideoTrack -> "HD Video Stream"
                    track.isLocalOnlyTrack -> "Local Device Audio"
                    track.isDownloaded -> "Offline Opus • Direct Audio"
                    playerState.isStreamCachedOnDisk -> "Cached Audio • Instant Play"
                    currentTempQuality != null -> "Quality: ${currentTempQuality.label} (Temp)"
                    else -> "Online Pure Stream"
                }
                Text(
                    text = qualityText,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = { viewModel.toggleLike(track) },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("player_like_btn")
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Middle Area: Lyrics OR 100%-Width Video Player OR Album Artwork (with App Logo Fallback)
        // ────────────────────────────────────────────────────────────
        if (showLyrics) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showLyrics = false }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isVideoTrack) "Show Video" else "Show Artwork",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showExpandedLyrics = true }
                            .testTag("maximize_lyrics_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            FullscreenExpandSvgIcon(
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Fullscreen",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (playerState.lyricLines.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No lyrics available for this track",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = OutfitFontFamily,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = inlineLyricListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 130.dp, bottom = 130.dp)
                        ) {
                            itemsIndexed(playerState.lyricLines) { index, line ->
                                val isCurrent = index == playerState.activeLyricIndex
                                if (line.isInstrumental) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        InstrumentalGapMusicNoteIndicator(
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                            .clickable { viewModel.seekTo(line.timestampMs) }
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = if (isCurrent) 20.sp else 16.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                fontSize = if (isCurrent) 14.sp else 12.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Normal,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                        )
                    }
                }
            }
        } else if (isVideoTrack && !showFullscreenVideo) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                SeitharolRawVideoSurface(
                    viewModel = viewModel,
                    playerState = playerState,
                    isZoomCrop = isVideoZoomCrop,
                    onToggleZoomCrop = { isVideoZoomCrop = !isVideoZoomCrop },
                    onEnterFullscreen = { viewModel.setFullscreenVideo(true) },
                    onEnterPiP = {
                        (context as? MainActivity)?.enterPiPMode()
                    }
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val scale by animateFloatAsState(
                    targetValue = if (playerState.isPlaying) 1f else 0.94f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "artwork_scale"
                )

                Surface(
                    modifier = Modifier
                        .size(290.dp)
                        .scale(scale)
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color(0x55000000),
                            spotColor = Color(0x88000000)
                        )
                        .clip(RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
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

                androidx.compose.animation.AnimatedVisibility(
                    visible = playerSkipFeedbackIsForward != null,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(150))
                ) {
                    playerSkipFeedbackIsForward?.let { isFwd ->
                        SeitharolSkip10sBadgeOverlay(isForward = isFwd)
                    }
                }
            }
        }

        // ────────────────────────────────────────────────────────────
        // Track Title & Artist Details
        // - For videos: clicking the video title slides up the full video name + description sheet
        // - For local videos: hides the "• Channel" button
        // - For local tracks / "Unknown Artist": disables navigating to an Artist page
        // ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isVideoTrack) {
                            Modifier.clickable { showVideoDescriptionSheet = true }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Text(
                    text = track.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("player_song_title")
                )
                if (isVideoTrack) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "View full video title and description",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            @OptIn(ExperimentalMaterial3Api::class)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val canNavigateArtist = track.isNavigableArtist
                    Text(
                        text = track.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (canNavigateArtist) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        onDismiss()
                                        viewModel.navigateToArtist(track.artist, isChannel = isVideoTrack)
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                            .testTag("player_artist_name")
                    )
                    // Show "• Channel" ONLY for online YouTube videos — NEVER on local videos!
                    val showChannelButton = isVideoTrack &&
                        !track.isLocalOnlyTrack &&
                        !track.isDownloaded &&
                        track.localPath.isNullOrBlank() &&
                        canNavigateArtist
                    if (showChannelButton) {
                        Text(
                            text = "• Channel",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.clickable {
                                onDismiss()
                                viewModel.navigateToArtist(track.artist, isChannel = true)
                            }
                        )
                    } else if (!isVideoTrack && track.album.isNotBlank() && track.album != track.title && track.album != "Single" && !track.album.equals("YouTube Video", ignoreCase = true)) {
                        Text(
                            text = "• ${track.album}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontFamily = OutfitFontFamily,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ────────────────────────────────────────────────────────────
        // Secondary Actions Row (EQ, Timer, Download / Cancel, Share, Lyrics, Queue)
        // ────────────────────────────────────────────────────────────
        val downloads by viewModel.activeDownloads.collectAsState()
        val trackDownload = downloads.firstOrNull {
            it.trackId == track.id || (it.title.equals(track.title, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true))
        }
        val isDownloaded = track.isDownloaded || trackDownload?.state == com.example.data.model.DownloadState.COMPLETED
        val isDownloading = trackDownload?.state == com.example.data.model.DownloadState.DOWNLOADING || trackDownload?.state == com.example.data.model.DownloadState.FINALIZING
        val rawDownloadProgress = trackDownload?.progress ?: 0f
        val downloadProgress = if (rawDownloadProgress.isNaN() || rawDownloadProgress.isInfinite()) 0f else rawDownloadProgress.coerceIn(0f, 1f)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showAudioAdjustSheet = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Equalizer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSleepTimer) {
                Icon(Icons.Outlined.Timer, contentDescription = "Sleep Timer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            IconButton(
                onClick = {
                    if (isDownloading) {
                        viewModel.cancelDownload(trackDownload?.trackId ?: track.id)
                    } else if (!isDownloaded && !track.isLocalOnlyTrack) {
                        showDownloadOptionsSheet = true
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                when {
                    isDownloaded -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = Color(0xFF00C853),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    isDownloading -> {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download track",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            IconButton(onClick = { onShareTrack(track.title) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(
                    imageVector = Icons.Outlined.Subtitles,
                    contentDescription = "Lyrics",
                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    hasClickedQueueButton = true
                    isUpNextVisible = true
                    showQueue = true
                },
                modifier = Modifier.testTag("player_queue_button")
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Playback Queue (Up Next)",
                    tint = if (showQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Ultimate Pro Scrubber Progress Bar
        // ────────────────────────────────────────────────────────────
        SeitharolProProgressBar(
            progress = sliderProgress,
            bufferedFraction = playerState.bufferedFraction,
            displayPositionMs = displayPosition,
            totalDurationMs = totalDuration,
            isDragging = isUserDraggingSlider,
            onValueChange = { frac ->
                isUserDraggingSlider = true
                userSeekingPosition = frac * totalDuration
            },
            onValueChangeFinished = {
                userSeekingPosition?.let { pos ->
                    viewModel.seekTo(pos.toLong())
                }
                userSeekingPosition = null
                isUserDraggingSlider = false
            },
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .testTag("player_scrubber")
        )

        // ────────────────────────────────────────────────────────────
        // Playback Controls Row: Shuffle, Prev, -10s, Big Play (White Synced Loader), +10s, Next, Repeat
        // ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = { viewModel.toggleShuffle() },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playerState.isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = { viewModel.previous() },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Dedicated -10s Rewind Button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        val target = (displayPosition - 10_000L).coerceAtLeast(0L)
                        viewModel.seekTo(target)
                        playerSkipFeedbackIsForward = false
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Skip back 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Main Play / Pause Button with Synchronized White Loader
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.togglePlayPause() }
                    .testTag("player_main_play_btn"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playerState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color.White,
                            strokeWidth = 2.8.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            // Dedicated +10s Forward Button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        val target = (displayPosition + 10_000L).coerceAtMost(totalDuration)
                        viewModel.seekTo(target)
                        playerSkipFeedbackIsForward = true
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Skip forward 10 seconds",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            IconButton(
                onClick = { viewModel.next() },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(
                onClick = { viewModel.cycleRepeatMode() },
                modifier = Modifier.size(38.dp)
            ) {
                val icon = when (playerState.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Repeat",
                    tint = if (playerState.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // 10s Countdown Before Next Song/Video & Up Next Queue Card
        // ────────────────────────────────────────────────────────────
        val nextTrack = when {
            playerState.currentIndex in 0 until playerState.queue.size - 1 ->
                playerState.queue[playerState.currentIndex + 1]
            playerState.repeatMode == RepeatMode.ALL && playerState.queue.size > 1 ->
                playerState.queue.firstOrNull()
            else -> null
        }
        val remainingMs = (totalDuration - displayPosition).coerceAtLeast(0L)
        var dismissedCountdownForTrackId by remember(track.id) { mutableStateOf(false) }
        val showNextCountdown = playerState.isPlaying &&
            !playerState.isBuffering &&
            totalDuration >= 15_000L &&
            remainingMs in 1L..10_000L &&
            nextTrack != null &&
            !dismissedCountdownForTrackId
        val countdownSeconds = ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, 10)
        val countdownProgress = (remainingMs.toFloat() / 10_000f).coerceIn(0f, 1f)

        AnimatedVisibility(
            visible = showNextCountdown && !showFullscreenVideo,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            if (nextTrack != null) {
                SeitharolNextTrackCountdownBanner(
                    nextTrack = nextTrack,
                    countdownSeconds = countdownSeconds,
                    countdownProgress = countdownProgress,
                    onPlayNow = { viewModel.next() },
                    onDismiss = { dismissedCountdownForTrackId = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isUpNextVisible && hasClickedQueueButton,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 18f) {
                                isUpNextVisible = false
                            }
                        }
                    }
                    .clickable { showQueue = true }
                    .testTag("player_upnext_button"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Queue",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "UP NEXT",
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• ${playerState.queue.size} in queue",
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Text(
                                text = nextTrack?.let { "${it.title} • ${it.artist}" } ?: "Tap to view upcoming tracks",
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = { showQueue = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Up Next",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showQueue) {
        PlaybackQueueModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showQueue = false }
        )
    }

    if (showAudioAdjustSheet) {
        val settingsManager = remember(context) { AppSettingsManager.getInstance(context) }
        PlayerAudioAdjustmentSheet(
            settingsManager = settingsManager,
            currentTrackQuality = viewModel.getTemporaryTrackQuality(),
            onSetTrackQuality = { quality ->
                viewModel.setTemporaryTrackQuality(quality)
            },
            onDismiss = { showAudioAdjustSheet = false }
        )
    }

    if (showDownloadOptionsSheet) {
        com.example.ui.components.TrackDownloadFormatQualitySheet(
            track = track,
            onDismiss = { showDownloadOptionsSheet = false },
            onConfirmDownload = { chosenTier ->
                viewModel.startDownload(track, chosenTier)
                showDownloadOptionsSheet = false
            }
        )
    }

    if (showVideoDescriptionSheet) {
        VideoDetailsDescriptionSheet(
            track = track,
            onVisitChannel = if (track.isNavigableArtist && !track.isLocalOnlyTrack) {
                {
                    showVideoDescriptionSheet = false
                    viewModel.setFullscreenVideo(false)
                    onDismiss()
                    viewModel.navigateToArtist(track.artist, isChannel = true)
                }
            } else null,
            onDismiss = { showVideoDescriptionSheet = false }
        )
    }

    if (showExpandedLyrics) {
        ExpandedLyricsModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showExpandedLyrics = false }
        )
    }
}

@Composable
fun SeitharolNextTrackCountdownBanner(
    nextTrack: com.example.data.model.Track,
    countdownSeconds: Int,
    countdownProgress: Float,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xE61E2022),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { countdownProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "${countdownSeconds}s",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                ) {
                    AsyncImage(
                        model = InnerTubeResolver.upgradeThumbnailUrl(nextTrack.artUrl).ifBlank { R.drawable.ic_splash_logo },
                        contentDescription = nextTrack.title,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NEXT TRACK IN ${countdownSeconds}S",
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "${nextTrack.title} • ${nextTrack.artist}",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayNow() }
                ) {
                    Text(
                        text = "Play Now",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss countdown",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 100% Full-Width Video Frame:
 * - Removes the duplicate red loader completely!
 * - Synchronizes the white loader (`Color.White`) inside the Play/Pause button with `playerState.isBuffering`.
 * - Replaces emojis on ±10s skip with `SeitharolSkip10sBadgeOverlay`.
 */
@Composable
fun SeitharolRawVideoSurface(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    isZoomCrop: Boolean,
    onToggleZoomCrop: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onEnterPiP: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack
    var showOverlayControls by remember(track?.id) { mutableStateOf(false) }
    var skipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }
    val latestState by rememberUpdatedState(playerState)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) {
            showOverlayControls = false
        }
    }

    LaunchedEffect(showOverlayControls, playerState.isPlaying) {
        if (showOverlayControls && playerState.isPlaying) {
            delay(1800L)
            showOverlayControls = false
        }
    }

    LaunchedEffect(skipFeedbackIsForward) {
        if (skipFeedbackIsForward != null) {
            delay(700L)
            skipFeedbackIsForward = null
        }
    }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            if (!viewModel.isFullscreenVideo.value) {
                viewModel.attachVideoTextureView(null)
            }
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        shape = RectangleShape,
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showOverlayControls = !showOverlayControls
                        },
                        onDoubleTap = { offset ->
                            val isLeftHalf = offset.x < size.width / 2f
                            val delta = if (isLeftHalf) -10_000L else 10_000L
                            val target = (latestState.currentPositionMs + delta).coerceIn(
                                0L,
                                latestState.durationMs.coerceAtLeast(1000L)
                            )
                            viewModel.seekTo(target)
                            skipFeedbackIsForward = !isLeftHalf
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        viewModel.attachVideoTextureView(this)
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            val loc = IntArray(2)
                            v.getLocationOnScreen(loc)
                            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                            (ctx as? MainActivity)?.updateVideoSourceRect(rect)
                        }
                    }
                },
                update = { tv ->
                    viewModel.attachVideoTextureView(tv)
                    val loc = IntArray(2)
                    tv.getLocationOnScreen(loc)
                    if (tv.width > 0 && tv.height > 0) {
                        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height)
                        (context as? MainActivity)?.updateVideoSourceRect(rect)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Artwork backdrop shown ONLY while loading/buffering before first video frame renders
            if (track != null && !playerState.isVideoFrameRendered && (playerState.isBuffering || !playerState.isPlaying)) {
                val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                if (upgradedArt.isNotBlank()) {
                    AsyncImage(
                        model = upgradedArt,
                        contentDescription = null,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 0.55f }
                    )
                }
            }

            // Professional vector ±10s skip feedback badge (no emojis)
            AnimatedVisibility(
                visible = skipFeedbackIsForward != null,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(150))
            ) {
                skipFeedbackIsForward?.let { isFwd ->
                    SeitharolSkip10sBadgeOverlay(isForward = isFwd)
                }
            }

            // Video Overlay Controls (with single synchronized white loader inside Play/Pause button)
            AnimatedVisibility(
                visible = showOverlayControls || playerState.isBuffering,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (showOverlayControls) 0.38f else 0.18f))
                ) {
                    if (showOverlayControls) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.toggleMute() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playerState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = if (playerState.isMuted) "Unmute" else "Mute",
                                        tint = if (playerState.isMuted) MaterialTheme.colorScheme.primary else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { onEnterPiP() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Center: Synchronized Play/Pause + -10s / +10s controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        if (showOverlayControls) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val target = (latestState.currentPositionMs - 10_000L).coerceAtLeast(0L)
                                        viewModel.seekTo(target)
                                        skipFeedbackIsForward = false
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "Rewind 10 seconds",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.62f),
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.togglePlayPause() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (playerState.isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        color = Color.White,
                                        strokeWidth = 2.6.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }

                        if (showOverlayControls) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val dur = latestState.durationMs.coerceAtLeast(1000L)
                                        val target = (latestState.currentPositionMs + 10_000L).coerceAtMost(dur)
                                        viewModel.seekTo(target)
                                        skipFeedbackIsForward = true
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "Forward 10 seconds",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (showOverlayControls) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.58f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { onEnterFullscreen() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Fullscreen Video",
                                    tint = Color.White,
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

/**
 * Fullscreen Landscape Video Modal — rendered inside a full-bleed Dialog so rotating the device
 * horizontally (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) never destroys `PlayerScreen` or loses state.
 */
@Composable
fun SeitharolFullscreenVideoModal(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    isZoomCrop: Boolean,
    onToggleZoomCrop: () -> Unit,
    nextTrack: com.example.data.model.Track? = null,
    showNextCountdown: Boolean = false,
    countdownSeconds: Int = 10,
    countdownProgress: Float = 1f,
    onDismissCountdown: () -> Unit = {},
    onOpenVideoDescription: () -> Unit = {},
    onEnterPiP: () -> Unit,
    onDismiss: () -> Unit
) {
    val track = playerState.currentTrack ?: return
    var showControls by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var seekingPosition by remember { mutableStateOf<Float?>(null) }
    var skipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }
    val latestState by rememberUpdatedState(playerState)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    val displayPosition = (if (isDraggingSlider) seekingPosition?.toLong() else null)
        ?: playerState.currentPositionMs.coerceAtLeast(0L)
    val totalDuration = playerState.durationMs.coerceAtLeast(1000L)
    val progress = (displayPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    LaunchedEffect(showControls, playerState.isPlaying, isDraggingSlider) {
        if (showControls && playerState.isPlaying && !isDraggingSlider) {
            delay(2400L)
            showControls = false
        }
    }

    LaunchedEffect(skipFeedbackIsForward) {
        if (skipFeedbackIsForward != null) {
            delay(700L)
            skipFeedbackIsForward = null
        }
    }

    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val insetsController = if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        } else null
        insetsController?.let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { offset ->
                                val isLeft = offset.x < size.width / 2f
                                val delta = if (isLeft) -10_000L else 10_000L
                                val dur = latestState.durationMs.coerceAtLeast(1000L)
                                viewModel.seekTo(
                                    (latestState.currentPositionMs + delta).coerceIn(0L, dur)
                                )
                                skipFeedbackIsForward = !isLeft
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            viewModel.attachVideoTextureView(this)
                            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                val loc = IntArray(2)
                                v.getLocationOnScreen(loc)
                                val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                                (ctx as? MainActivity)?.updateVideoSourceRect(rect)
                            }
                        }
                    },
                    update = { tv ->
                        viewModel.attachVideoTextureView(tv)
                    },
                    modifier = if (isZoomCrop) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                    }
                )

                if (!playerState.isVideoFrameRendered && (playerState.isBuffering || !playerState.isPlaying)) {
                    val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                    if (upgradedArt.isNotBlank()) {
                        AsyncImage(
                            model = upgradedArt,
                            contentDescription = null,
                            placeholder = appLogoPainter,
                            error = appLogoPainter,
                            fallback = appLogoPainter,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(16f / 9f)
                                .graphicsLayer { alpha = 0.55f }
                        )
                    }
                }

                // Professional vector ±10s skip feedback pill (no emojis)
                AnimatedVisibility(
                    visible = skipFeedbackIsForward != null,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(150))
                ) {
                    skipFeedbackIsForward?.let { isFwd ->
                        SeitharolSkip10sBadgeOverlay(isForward = isFwd)
                    }
                }

                // Fullscreen controls overlay
                AnimatedVisibility(
                    visible = showControls || playerState.isBuffering,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        // Top bar: Clickable Video Title + Fit/Zoom + Mute + PiP + Exit Fullscreen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenVideoDescription() }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Video Details",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = track.artist,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onToggleZoomCrop() }
                                ) {
                                    Text(
                                        text = if (isZoomCrop) "Zoom" else "Fit",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                IconButton(onClick = { viewModel.toggleMute() }) {
                                    Icon(
                                        imageVector = if (playerState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = if (playerState.isMuted) "Unmute" else "Mute",
                                        tint = if (playerState.isMuted) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                }

                                IconButton(onClick = onEnterPiP) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White
                                    )
                                }

                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = "Exit Fullscreen",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        // Center controls: Previous, -10s, Play/Pause (White Synced Loader), +10s, Next
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            IconButton(onClick = { viewModel.previous() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val target = (latestState.currentPositionMs - 10_000L).coerceAtLeast(0L)
                                        viewModel.seekTo(target)
                                        skipFeedbackIsForward = false
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "Rewind 10 seconds",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.togglePlayPause() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (playerState.isBuffering) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = Color.White,
                                            strokeWidth = 2.8.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val dur = latestState.durationMs.coerceAtLeast(1000L)
                                        val target = (latestState.currentPositionMs + 10_000L).coerceAtMost(dur)
                                        viewModel.seekTo(target)
                                        skipFeedbackIsForward = true
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "Forward 10 seconds",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            IconButton(onClick = { viewModel.next() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        // Bottom pro scrubber progress bar
                        SeitharolProProgressBar(
                            progress = progress,
                            bufferedFraction = playerState.bufferedFraction,
                            displayPositionMs = displayPosition,
                            totalDurationMs = totalDuration,
                            isDragging = isDraggingSlider,
                            onValueChange = { frac ->
                                isDraggingSlider = true
                                seekingPosition = frac * totalDuration
                            },
                            onValueChangeFinished = {
                                seekingPosition?.let { viewModel.seekTo(it.toLong()) }
                                seekingPosition = null
                                isDraggingSlider = false
                            },
                            isFullscreenDark = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }

                // 10-Second Countdown Overlay in Fullscreen UI
                AnimatedVisibility(
                    visible = showNextCountdown && nextTrack != null,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = if (showControls) 96.dp else 28.dp)
                ) {
                    if (nextTrack != null) {
                        SeitharolNextTrackCountdownBanner(
                            nextTrack = nextTrack,
                            countdownSeconds = countdownSeconds,
                            countdownProgress = countdownProgress,
                            onPlayNow = { viewModel.next() },
                            onDismiss = onDismissCountdown,
                            modifier = Modifier.widthIn(max = 380.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpandedLyricsModal(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit
) {
    val track = playerState.currentTrack ?: return
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    val lyricListState = rememberLazyListState()
    var userSeekingPosition by remember { mutableStateOf<Float?>(null) }
    var isUserDraggingSlider by remember { mutableStateOf(false) }

    val displayPosition = (userSeekingPosition?.toLong() ?: playerState.currentPositionMs).coerceAtLeast(0L)
    val totalDuration = playerState.durationMs.coerceAtLeast(1000L)
    val rawSliderProgress = displayPosition.toFloat() / totalDuration.toFloat()
    val sliderProgress = if (rawSliderProgress.isNaN() || rawSliderProgress.isInfinite()) 0f else rawSliderProgress.coerceIn(0f, 1f)

    var isUserScrollingExpandedLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(lyricListState.isScrollInProgress) {
        if (lyricListState.isScrollInProgress) {
            isUserScrollingExpandedLyrics = true
        } else if (isUserScrollingExpandedLyrics) {
            delay(2500L)
            isUserScrollingExpandedLyrics = false
        }
    }

    LaunchedEffect(playerState.activeLyricIndex, isUserScrollingExpandedLyrics) {
        runCatching {
            if (!isUserScrollingExpandedLyrics && playerState.activeLyricIndex in 0 until playerState.lyricLines.size) {
                val activeIdx = playerState.activeLyricIndex
                val layoutInfo = lyricListState.layoutInfo
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                if (visibleItem != null && viewportHeight > 0) {
                    val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
                    val viewportCenter = viewportHeight / 2f
                    val delta = itemCenter - viewportCenter
                    lyricListState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                    )
                } else if (viewportHeight > 0) {
                    lyricListState.animateScrollToItem(
                        index = activeIdx,
                        scrollOffset = -(viewportHeight / 2) + 48
                    )
                } else {
                    lyricListState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
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
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            AsyncImage(
                                model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl).ifBlank { R.drawable.ic_splash_logo },
                                contentDescription = track.title,
                                placeholder = appLogoPainter,
                                error = appLogoPainter,
                                fallback = appLogoPainter,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onDismiss)
                            .testTag("minimize_lyrics_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            FullscreenMinimizeSvgIcon(
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Minimize",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    if (playerState.lyricLines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No lyrics found for this track.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = OutfitFontFamily,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = lyricListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 180.dp, bottom = 180.dp)
                        ) {
                            itemsIndexed(playerState.lyricLines) { index, line ->
                                val isCurrent = index == playerState.activeLyricIndex
                                val activeScale by animateFloatAsState(
                                    targetValue = if (isCurrent) 1.08f else 1.0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                    label = "lyric_scale"
                                )

                                if (line.isInstrumental) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .scale(activeScale)
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        InstrumentalGapMusicNoteIndicator(
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .scale(activeScale)
                                            .padding(vertical = 12.dp)
                                            .clickable { viewModel.seekTo(line.timestampMs) }
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = if (isCurrent) 24.sp else 18.sp,
                                            fontFamily = SyneFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                            lineHeight = if (isCurrent) 32.sp else 26.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                                fontSize = if (isCurrent) 16.sp else 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Normal,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 8.dp,
                    shadowElevation = 24.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .navigationBarsPadding()
                    ) {
                        SeitharolProProgressBar(
                            progress = sliderProgress,
                            bufferedFraction = playerState.bufferedFraction,
                            displayPositionMs = displayPosition,
                            totalDurationMs = totalDuration,
                            isDragging = isUserDraggingSlider,
                            onValueChange = { frac ->
                                isUserDraggingSlider = true
                                userSeekingPosition = frac * totalDuration
                            },
                            onValueChangeFinished = {
                                userSeekingPosition?.let { pos ->
                                    viewModel.seekTo(pos.toLong())
                                }
                                userSeekingPosition = null
                                isUserDraggingSlider = false
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.previous() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.togglePlayPause() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (playerState.isBuffering) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color.White,
                                            strokeWidth = 2.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { viewModel.next() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAudioAdjustmentSheet(
    settingsManager: AppSettingsManager,
    currentTrackQuality: com.seitharol.data.resolver.AudioQuality? = null,
    onSetTrackQuality: (com.seitharol.data.resolver.AudioQuality) -> Unit = {},
    onDismiss: () -> Unit
) {
    val state by settingsManager.state.collectAsState()
    val eqFrequencies = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Adjustments",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { settingsManager.resetEqualizer() }) {
                    Text("Reset Flat", color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val switchColors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("5-Band Studio Equalizer", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = state.eqEnabled,
                    onCheckedChange = { settingsManager.setEqEnabled(it) },
                    colors = switchColors
                )
            }

            eqFrequencies.forEachIndexed { idx, freq ->
                val gain = state.eqBandsDb.getOrElse(idx) { 0f }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(freq, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("${"%.1f".format(gain)} dB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = gain.coerceIn(-12f, 12f),
                        onValueChange = { settingsManager.setEqBand(idx, it) },
                        valueRange = -12f..12f,
                        enabled = state.eqEnabled
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Normalize Volume", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = state.audioNormalization,
                    onCheckedChange = { settingsManager.setAudioNormalization(it) },
                    colors = switchColors
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gapless Playback", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Switch(
                    checked = state.gaplessPlayback,
                    onCheckedChange = { settingsManager.setGaplessPlayback(it) },
                    colors = switchColors
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Track Quality (Current Song)",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Temporary override for currently playing track",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val activeQuality = currentTrackQuality ?: state.streamingQuality
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.seitharol.data.resolver.AudioQuality.values().forEach { quality ->
                    val isSelected = quality == activeQuality
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSetTrackQuality(quality) },
                        label = {
                            Text(
                                text = when (quality) {
                                    com.seitharol.data.resolver.AudioQuality.LOW -> "Data Saver"
                                    com.seitharol.data.resolver.AudioQuality.NORMAL -> "Normal"
                                    com.seitharol.data.resolver.AudioQuality.HIGH -> "High (HQ)"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
