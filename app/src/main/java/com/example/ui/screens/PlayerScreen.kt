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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.data.model.formatDurationMs
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
import androidx.compose.ui.input.pointer.positionChanged
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
 * YouTube-Style Cumulative Multi-Tap Seek Arc & Badge (`10 seconds`, `20 seconds`, `30 seconds`...):
 * Supports double-tap (10s), triple-tap (20s), quadruple-tap (30s), etc.
 */
@Composable
fun SeitharolSkip10sBadgeOverlay(
    isForward: Boolean,
    skipSeconds: Int = 10,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "yt_skip_arrows")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(340, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "yt_skip_pulse"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xE81E2024),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isForward) {
                Icon(
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = if (isForward) "+${skipSeconds}s" else "-${skipSeconds}s",
                color = Color.White,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.2.sp
            )
            if (isForward) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Ultimate Studio Wave Progress Scrubber Bar for PlayerScreen & Fullscreen Video:
 * - Animated sinusoidal wave on the played segment when actively playing (and not paused),
 *   flattening smoothly into a straight line when paused or scrubbing.
 * - Straight unplayed & buffered track ahead of the circular thumb.
 * - Smoothly expands track height and thumb radius on touch/drag with floating time-preview pill.
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
    isFullscreenDark: Boolean = false,
    isPlaying: Boolean = false
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
        targetValue = if (isDragging) 6.dp else 4.2.dp,
        animationSpec = tween(150),
        label = "scrubber_track_height"
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDragging) 9.5.dp else 7.dp,
        animationSpec = tween(150),
        label = "scrubber_thumb_radius"
    )

    // Smoothly animate wave amplitude when actively playing vs paused/dragging
    val waveAmplitudeDp by animateDpAsState(
        targetValue = if (isPlaying && !isDragging) 4.2.dp else 0.dp,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "scrubber_wave_amplitude"
    )

    // Continuous travelling sine-wave phase while song is actively playing
    val infiniteTransition = rememberInfiniteTransition(label = "wave_scrubber_transition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "scrubber_wave_phase"
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
                .height(32.dp)
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
            Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
                val trackH = trackHeightDp.toPx()
                val cy = size.height / 2f
                val thumbR = thumbRadiusDp.toPx()
                val startX = trackH / 2f
                val endX = (size.width - trackH / 2f).coerceAtLeast(startX)
                val activeW = size.width * safeProgress
                val thumbX = activeW.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
                val bufferedX = (size.width * bufferedProgress).coerceIn(thumbX, endX)

                // 1. Straight unplayed background track to the right of the thumb
                if (endX > thumbX) {
                    drawLine(
                        color = inactiveColor,
                        start = Offset(thumbX, cy),
                        end = Offset(endX, cy),
                        strokeWidth = trackH,
                        cap = StrokeCap.Round
                    )
                }

                // 2. Straight buffered-ahead segment to the right of the thumb
                if (bufferedX > thumbX + 1f) {
                    drawLine(
                        color = bufferedColor,
                        start = Offset(thumbX, cy),
                        end = Offset(bufferedX, cy),
                        strokeWidth = trackH,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Played track on the left of the thumb: sinusoidal wave when actively playing, flat line when paused
                val ampPx = waveAmplitudeDp.toPx()
                val wavelengthPx = 24.dp.toPx().coerceAtLeast(12f)
                if (thumbX > startX) {
                    if (ampPx > 0.25f && (thumbX - startX) > 4f) {
                        val wavePath = Path()
                        val stepPx = 1.5f
                        var x = startX
                        var isFirst = true
                        val twoPi = (2.0 * Math.PI).toFloat()
                        while (x <= thumbX) {
                            // Smoothly taper amplitude near start cap and into the circular thumb center
                            val distFromStart = (x - startX).coerceAtLeast(0f)
                            val distFromThumb = (thumbX - x).coerceAtLeast(0f)
                            val taperZone = 6.dp.toPx()
                            val startEdgeFactor = (distFromStart / taperZone).coerceIn(0.15f, 1f)
                            val thumbEdgeFactor = (distFromThumb / taperZone).coerceIn(0f, 1f)
                            val localAmp = ampPx * startEdgeFactor * thumbEdgeFactor
                            val angle = (x / wavelengthPx) * twoPi - wavePhase
                            val y = cy + localAmp * kotlin.math.sin(angle)
                            if (isFirst) {
                                wavePath.moveTo(x, y)
                                isFirst = false
                            } else {
                                wavePath.lineTo(x, y)
                            }
                            x += stepPx
                        }
                        wavePath.lineTo(thumbX, cy)
                        drawPath(
                            path = wavePath,
                            color = activeColor,
                            style = Stroke(
                                width = trackH,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    } else {
                        drawLine(
                            color = activeColor,
                            start = Offset(startX, cy),
                            end = Offset(thumbX, cy),
                            strokeWidth = trackH,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // 4. Outer thumb halo when dragging
                if (isDragging) {
                    drawCircle(
                        color = activeColor.copy(alpha = 0.28f),
                        radius = thumbR * 1.75f,
                        center = Offset(thumbX, cy)
                    )
                }

                // 5. Solid circular scrubber thumb matching the wave stroke color
                drawCircle(
                    color = activeColor,
                    radius = thumbR,
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

tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onShareTrack: (String) -> Unit = {},
    onAddToPlaylist: (com.example.data.model.Track) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
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
    var showRelated by remember { mutableStateOf(false) }
    var showAudioAdjustSheet by remember { mutableStateOf(false) }
    var showDownloadOptionsSheet by remember { mutableStateOf(false) }
    var showVideoDescriptionSheet by remember { mutableStateOf(false) }
    var showPlayerMoreSheet by remember { mutableStateOf(false) }
    val showFullscreenVideo by viewModel.isFullscreenVideo.collectAsState()
    var isVideoZoomCrop by remember { mutableStateOf(false) }
    var playerSkipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }
    var playerSkipAccumulatedSec by remember { mutableIntStateOf(10) }
    var playerSkipLastTapMs by remember { mutableLongStateOf(0L) }
    var isTitleExpanded by remember(track.id) { mutableStateOf(false) }

    LaunchedEffect(playerSkipFeedbackIsForward, playerSkipAccumulatedSec) {
        if (playerSkipFeedbackIsForward != null) {
            delay(750L)
            playerSkipFeedbackIsForward = null
            playerSkipAccumulatedSec = 10
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
    val sheetDragOffsetY = remember(track.id) { Animatable(0f) }
    var lastVerticalDragDelta by remember(track.id) { mutableFloatStateOf(0f) }

    // Pre-warm Related data in background without affecting Up Next queue
    LaunchedEffect(track.id) {
        viewModel.loadRelatedForTrack(track)
    }

    var lastUserInlineTouchMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playerState.activeLyricIndex, showLyrics, playerState.lyricLines.size) {
        runCatching {
            if (!showLyrics || playerState.activeLyricIndex !in 0 until playerState.lyricLines.size) return@LaunchedEffect
            val now = System.currentTimeMillis()
            if (now - lastUserInlineTouchMs < 2400L) {
                delay((2400L - (now - lastUserInlineTouchMs)).coerceAtLeast(100L))
            }
            val activeIdx = playerState.activeLyricIndex
            // Wait one frame so Compose measures the newly active line's expanded font size before centering
            withFrameNanos { }
            var layoutInfo = inlineLyricListState.layoutInfo
            var visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
            if (visibleItem == null) {
                // Because top contentPadding is (maxHeight / 2 - 24.dp), scrollOffset = 0 places the item right at the vertical center
                inlineLyricListState.animateScrollToItem(
                    index = activeIdx,
                    scrollOffset = 0
                )
                withFrameNanos { }
                layoutInfo = inlineLyricListState.layoutInfo
                visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
            }
            if (visibleItem != null) {
                val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
                // True visual center of the showing div in visibleItem.offset coordinates:
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                val delta = itemCenter - viewportCenter
                if (kotlin.math.abs(delta) > 1f) {
                    inlineLyricListState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                    )
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
    val downloads by viewModel.activeDownloads.collectAsState()
    val trackDownload = downloads.firstOrNull {
        it.trackId == track.id || (it.title.equals(track.title, ignoreCase = true) && it.artist.equals(track.artist, ignoreCase = true))
    }
    val isDownloaded = track.isDownloaded || trackDownload?.state == com.example.data.model.DownloadState.COMPLETED
    val isDownloading = trackDownload?.state == com.example.data.model.DownloadState.DOWNLOADING || trackDownload?.state == com.example.data.model.DownloadState.FINALIZING
    val rawDownloadProgress = trackDownload?.progress ?: 0f
    val downloadProgress = if (rawDownloadProgress.isNaN() || rawDownloadProgress.isInfinite()) 0f else rawDownloadProgress.coerceIn(0f, 1f)

    val dismissPlayerSmoothly: () -> Unit = {
        scope.launch {
            sheetDragOffsetY.animateTo(
                targetValue = 2000f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
            onDismiss()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = sheetDragOffsetY.value.coerceAtLeast(0f)
                val progress = (sheetDragOffsetY.value / 1400f).coerceIn(0f, 1f)
                scaleX = 1f - (progress * 0.03f)
                alpha = (1f - (progress * 0.25f)).coerceIn(0.75f, 1f)
                shape = RoundedCornerShape(
                    topStart = if (sheetDragOffsetY.value > 2f) 28.dp else 0.dp,
                    topEnd = if (sheetDragOffsetY.value > 2f) 28.dp else 0.dp
                )
                clip = sheetDragOffsetY.value > 2f
            }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(track.id, showLyrics, isUserDraggingSlider) {
                if (!showLyrics && !isUserDraggingSlider) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            lastVerticalDragDelta = 0f
                        },
                        onDragEnd = {
                            val currentOffset = sheetDragOffsetY.value
                            val shouldDismiss = currentOffset > 150f || (currentOffset > 45f && lastVerticalDragDelta > 12f)
                            if (shouldDismiss) {
                                dismissPlayerSmoothly()
                            } else {
                                scope.launch {
                                    sheetDragOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                sheetDragOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        },
                        onVerticalDrag = { change: PointerInputChange, dragAmount: Float ->
                            val nextVal = (sheetDragOffsetY.value + dragAmount).coerceIn(0f, 2000f)
                            if (nextVal > 0f || dragAmount > 0f) {
                                change.consume()
                                lastVerticalDragDelta = dragAmount
                                scope.launch {
                                    sheetDragOffsetY.snapTo(nextVal)
                                }
                            }
                        }
                    )
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("full_player_screen")
    ) {
        // Google Pixel Bottom-Sheet Style Top Drag Handle Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f))
            )
        }

        // ────────────────────────────────────────────────────────────
        // Top Header with Collapse, Centered Status/Album Header, and Connected Like + 3-Dot Split Pill
        // ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .pointerInput(track.id) {
                    detectVerticalDragGestures(
                        onDragStart = { lastVerticalDragDelta = 0f },
                        onDragEnd = {
                            val currentOffset = sheetDragOffsetY.value
                            if (currentOffset > 130f || (currentOffset > 40f && lastVerticalDragDelta > 10f)) {
                                dismissPlayerSmoothly()
                            } else {
                                scope.launch {
                                    sheetDragOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                sheetDragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onVerticalDrag = { change: PointerInputChange, dragAmount: Float ->
                            change.consume()
                            lastVerticalDragDelta = dragAmount
                            scope.launch {
                                sheetDragOffsetY.snapTo((sheetDragOffsetY.value + dragAmount).coerceIn(0f, 2000f))
                            }
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { dismissPlayerSmoothly() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("player_collapse_btn")
            ) {
                SvgChevronIcon(isExpanded = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                val headerTopLabel = if (!isVideoTrack && track.album.isNotBlank() &&
                    track.album != track.title &&
                    !track.album.equals("Single", ignoreCase = true) &&
                    !track.album.equals("YouTube Video", ignoreCase = true)
                ) {
                    track.album.uppercase()
                } else {
                    "PLAYING FROM SEITHAROL"
                }
                Text(
                    text = headerTopLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    fontSize = 10.5.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Connected Google Pixel Split-Button Pill: Like + 3-Dot Options with 0.dp center border-radius
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Surface(
                    onClick = { viewModel.toggleLike(track) },
                    shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("player_like_btn")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                )

                Surface(
                    onClick = { showPlayerMoreSheet = true },
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 14.dp, bottomEnd = 14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("player_more_btn")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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

                // Inline Lyric Sync Offset Fine-Tuning Row (-0.2s / +0.2s)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.adjustLyricOffset(-200L) }
                    ) {
                        Text(
                            text = "-0.2s Sync",
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (playerState.lyricOffsetMs == 0L) "Auto Synced"
                        else "Offset: ${if (playerState.lyricOffsetMs > 0) "+" else ""}${playerState.lyricOffsetMs}ms",
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.adjustLyricOffset(200L) }
                    ) {
                        Text(
                            text = "+0.2s Sync",
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
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
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val verticalPad = (maxHeight / 2 - 24.dp).coerceAtLeast(80.dp)
                        LazyColumn(
                            state = inlineLyricListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                            if (event.changes.any { it.positionChanged() }) {
                                                lastUserInlineTouchMs = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                },
                            contentPadding = PaddingValues(top = verticalPad, bottom = verticalPad)
                        ) {
                            itemsIndexed(playerState.lyricLines) { index, line ->
                                val isCurrent = index == playerState.activeLyricIndex
                                val lineScale by animateFloatAsState(
                                    targetValue = if (isCurrent) 1.03f else 0.97f,
                                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                                    label = "inline_lyric_scale"
                                )
                                val lineAlpha by animateFloatAsState(
                                    targetValue = if (isCurrent) 1f else 0.48f,
                                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                                    label = "inline_lyric_alpha"
                                )
                                if (line.isInstrumental) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = lineScale
                                                scaleY = lineScale
                                                alpha = lineAlpha
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        InstrumentalGapMusicNoteIndicator(
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                        )
                                    }
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = lineScale
                                                scaleY = lineScale
                                                alpha = lineAlpha
                                            }
                                            .padding(vertical = 9.dp, horizontal = 8.dp)
                                            .clickable { viewModel.seekTo(line.timestampMs) }
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = if (isCurrent) 20.sp else 16.sp,
                                            lineHeight = if (isCurrent) 27.sp else 22.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                fontSize = if (isCurrent) 14.sp else 12.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Normal,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 3.dp)
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
                    onMinimizePlayer = onDismiss,
                    onEnterFullscreen = { viewModel.setFullscreenVideo(true) },
                    onEnterPiP = {
                        (context.findActivity() as? MainActivity)?.enterPiPMode()
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
                    targetValue = if (playerState.isPlaying) 1f else 0.84f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "artwork_scale"
                )

                val artworkElevation = if (playerState.isPlaying) 24.dp else 8.dp
                val artworkCorner = if (playerState.isPlaying) 24.dp else 30.dp

                Surface(
                    modifier = Modifier
                        .size(290.dp)
                        .scale(scale)
                        .shadow(
                            elevation = artworkElevation,
                            shape = RoundedCornerShape(artworkCorner),
                            ambientColor = Color(0x55000000),
                            spotColor = Color(0x88000000)
                        )
                        .clip(RoundedCornerShape(artworkCorner))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.togglePlayPause()
                        }
                        .testTag("player_artwork_thumbnail"),
                    shape = RoundedCornerShape(artworkCorner),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = playerSkipFeedbackIsForward != null,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(150))
                ) {
                    playerSkipFeedbackIsForward?.let { isFwd ->
                        SeitharolSkip10sBadgeOverlay(isForward = isFwd, skipSeconds = playerSkipAccumulatedSec)
                    }
                }
            }
        }

        // ────────────────────────────────────────────────────────────
        // Track Title & Artist Details (Auto-scrolling Marquee + Tap to Expand Full Name)
        // ────────────────────────────────────────────────────────────
        @OptIn(ExperimentalFoundationApi::class)
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
                    .clickable {
                        if (isVideoTrack) {
                            showVideoDescriptionSheet = true
                        } else {
                            isTitleExpanded = !isTitleExpanded
                        }
                    }
            ) {
                Text(
                    text = track.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isTitleExpanded) 19.sp else 21.sp,
                    lineHeight = 25.sp,
                    maxLines = if (isTitleExpanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (isTitleExpanded) {
                        Modifier
                            .weight(1f)
                            .testTag("player_song_title")
                    } else {
                        Modifier
                            .weight(1f)
                            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200)
                            .testTag("player_song_title")
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isVideoTrack) Icons.Default.KeyboardArrowUp
                    else if (isTitleExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "View full title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
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
        // Secondary Actions Row (EQ, Timer, Download / Cancel, Share, Add to Playlist, Lyrics, Queue)
        // ────────────────────────────────────────────────────────────

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

            val isLocalFileTrack = track.isLocalOnlyTrack
            IconButton(
                onClick = {
                    if (!isLocalFileTrack) {
                        onShareTrack(track.title)
                    }
                },
                enabled = !isLocalFileTrack
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = if (isLocalFileTrack) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = { onAddToPlaylist(track) }) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Add to Playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            isPlaying = playerState.isPlaying && !playerState.isBuffering,
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

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val now = System.currentTimeMillis()
                        if (playerSkipFeedbackIsForward == false && (now - playerSkipLastTapMs) < 900L) {
                            playerSkipAccumulatedSec += 10
                        } else {
                            playerSkipAccumulatedSec = 10
                        }
                        playerSkipLastTapMs = now
                        playerSkipFeedbackIsForward = false
                        val target = (displayPosition - 10_000L).coerceAtLeast(0L)
                        viewModel.seekTo(target)
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

            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { viewModel.togglePlayPause() }
                    .testTag("player_main_play_btn"),
                shape = RoundedCornerShape(18.dp),
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
                            imageVector = when {
                                playerState.isPlaying -> Icons.Default.Pause
                                playerState.isEnded -> Icons.Default.Replay
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = if (playerState.isEnded) "Replay" else "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        val now = System.currentTimeMillis()
                        if (playerSkipFeedbackIsForward == true && (now - playerSkipLastTapMs) < 900L) {
                            playerSkipAccumulatedSec += 10
                        } else {
                            playerSkipAccumulatedSec = 10
                        }
                        playerSkipLastTapMs = now
                        playerSkipFeedbackIsForward = true
                        val target = (displayPosition + 10_000L).coerceAtMost(totalDuration)
                        viewModel.seekTo(target)
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
        // Google Pixel Material 3 Countdown Banner & Up Next Pill Sheet
        // (Strictly suppressed when RepeatMode.ONE is active or single song is looping)
        // ────────────────────────────────────────────────────────────
        val isSingleSongLooping = playerState.repeatMode == RepeatMode.ONE
        val nextTrack = when {
            isSingleSongLooping -> null
            playerState.currentIndex in 0 until playerState.queue.size - 1 ->
                playerState.queue[playerState.currentIndex + 1]
            playerState.repeatMode == RepeatMode.ALL && playerState.queue.size > 1 ->
                playerState.queue.firstOrNull()
            else -> null
        }
        val remainingMs = (totalDuration - displayPosition).coerceAtLeast(0L)
        var dismissedCountdownForTrackId by remember(track.id, playerState.repeatMode) { mutableStateOf(false) }
        val showNextCountdown = playerState.isPlaying &&
            !playerState.isBuffering &&
            !isSingleSongLooping &&
            playerState.durationMs >= 15_000L &&
            totalDuration >= 15_000L &&
            remainingMs in 1L..10_000L &&
            nextTrack != null &&
            nextTrack.id != track.id &&
            !dismissedCountdownForTrackId
        val countdownSeconds = ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, 10)
        val countdownProgress = (remainingMs.toFloat() / 10_000f).coerceIn(0f, 1f)

        AnimatedVisibility(
            visible = showNextCountdown && !showFullscreenVideo,
            enter = fadeIn(tween(220)) + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            if (nextTrack != null && !isSingleSongLooping) {
                SeitharolNextTrackCountdownBanner(
                    nextTrack = nextTrack,
                    countdownSeconds = countdownSeconds,
                    countdownProgress = countdownProgress,
                    onPlayNow = { viewModel.next() },
                    onDismiss = { dismissedCountdownForTrackId = true },
                    isDarkOverlay = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // YouTube Music + Google Pixel Connected Bottom Bar:
        // [ Up Next ] | [ Lyrics ] | [ Related ] (with 0.dp center border-radius)
        // + Optional Next Track Preview Row above when in multi-track queue
        // ────────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(22.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -16f) {
                            showQueue = true
                        }
                    }
                }
                .testTag("player_upnext_button"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isUpNextVisible && hasClickedQueueButton && !isSingleSongLooping && nextTrack != null && !(showNextCountdown && !showFullscreenVideo)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showQueue = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
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
                                text = "Up next • ${nextTrack.title}",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = nextTrack.artist,
                                fontSize = 10.5.sp,
                                fontFamily = OutfitFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Expand Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Connected 3-Segment Google Pixel Pill: [ UP NEXT | LYRICS | RELATED ] (0.dp center border-radius)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // 1. UP NEXT segment
                    Surface(
                        onClick = {
                            hasClickedQueueButton = true
                            isUpNextVisible = true
                            showQueue = true
                        },
                        shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                        color = if (showQueue) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Up Next",
                                tint = if (showQueue) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Up Next",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = if (showQueue) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    )

                    // 2. LYRICS segment (0.dp border radius on both left and right center edges!)
                    Surface(
                        onClick = { showLyrics = !showLyrics },
                        shape = RoundedCornerShape(0.dp),
                        color = if (showLyrics) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Subtitles,
                                contentDescription = "Lyrics",
                                tint = if (showLyrics) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Lyrics",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = if (showLyrics) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    )

                    // 3. RELATED segment (opens YouTube Music-style Related sheet without affecting Up Next!)
                    Surface(
                        onClick = {
                            viewModel.loadRelatedForTrack(track)
                            showRelated = true
                        },
                        shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 14.dp, bottomEnd = 14.dp),
                        color = if (showRelated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .testTag("player_related_button")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "Related",
                                tint = if (showRelated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Related",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = if (showRelated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }

    if (showQueue) {
        PlaybackQueueModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showQueue = false }
        )
    }

    if (showRelated) {
        PlayerRelatedModalSheet(
            viewModel = viewModel,
            currentTrack = track,
            playerState = playerState,
            onAddToPlaylist = onAddToPlaylist,
            onNavigateToPlaylist = { browseId ->
                showRelated = false
                onDismiss()
                onNavigateToPlaylist(browseId)
            },
            onNavigateToArtist = { artistName, isChannel ->
                showRelated = false
                onDismiss()
                viewModel.navigateToArtist(artistName, isChannel = isChannel)
            },
            onDismiss = { showRelated = false }
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

    if (showPlayerMoreSheet) {
        PlayerTrackOptionsSheet(
            track = track,
            playerState = playerState,
            isLiked = isLiked,
            isDownloaded = isDownloaded,
            onToggleLike = { viewModel.toggleLike(track) },
            onAddToPlaylist = {
                showPlayerMoreSheet = false
                onAddToPlaylist(track)
            },
            onCycleRepeatMode = { viewModel.cycleRepeatMode() },
            onOpenQueue = {
                showPlayerMoreSheet = false
                hasClickedQueueButton = true
                isUpNextVisible = true
                showQueue = true
            },
            onOpenRelated = {
                showPlayerMoreSheet = false
                viewModel.loadRelatedForTrack(track)
                showRelated = true
            },
            onOpenAudioAdjust = {
                showPlayerMoreSheet = false
                showAudioAdjustSheet = true
            },
            onOpenSleepTimer = {
                showPlayerMoreSheet = false
                onOpenSleepTimer()
            },
            onOpenDownloadOptions = {
                showPlayerMoreSheet = false
                if (!isDownloaded && !track.isLocalOnlyTrack) {
                    showDownloadOptionsSheet = true
                }
            },
            onOpenDetails = {
                showPlayerMoreSheet = false
                showVideoDescriptionSheet = true
            },
            onNavigateArtist = if (track.isNavigableArtist && !track.isLocalOnlyTrack) {
                {
                    showPlayerMoreSheet = false
                    onDismiss()
                    viewModel.navigateToArtist(track.artist, isChannel = isVideoTrack)
                }
            } else null,
            onShare = if (!track.isLocalOnlyTrack) {
                {
                    showPlayerMoreSheet = false
                    onShareTrack(track.title)
                }
            } else null,
            onDismiss = { showPlayerMoreSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTrackOptionsSheet(
    track: com.example.data.model.Track,
    playerState: PlayerState,
    isLiked: Boolean,
    isDownloaded: Boolean,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenRelated: () -> Unit = {},
    onOpenAudioAdjust: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenDownloadOptions: () -> Unit,
    onOpenDetails: () -> Unit,
    onNavigateArtist: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            // Track Header Card (Google Pixel M3 Tonal Card)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl).ifBlank { R.drawable.ic_splash_logo },
                        contentDescription = track.title,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artist} • ${track.durationFormatted}",
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        onClick = onToggleLike,
                        shape = RoundedCornerShape(12.dp),
                        color = if (isLiked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            @Composable
            fun PixelSheetOptionRow(
                icon: androidx.compose.ui.graphics.vector.ImageVector,
                title: String,
                subtitle: String? = null,
                accentTint: Boolean = false,
                onClick: () -> Unit
            ) {
                Surface(
                    onClick = onClick,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (accentTint) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = title,
                                    tint = if (accentTint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            PixelSheetOptionRow(
                icon = Icons.Default.PlaylistAdd,
                title = "Add to Playlist",
                subtitle = "Save this track to one of your playlists",
                onClick = onAddToPlaylist
            )

            val repeatLabel = when (playerState.repeatMode) {
                RepeatMode.OFF -> "Repeat: Off"
                RepeatMode.ALL -> "Repeat: All Queue Tracks"
                RepeatMode.ONE -> "Repeat: Loop Current Song (Repeat 1)"
            }
            PixelSheetOptionRow(
                icon = if (playerState.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                title = repeatLabel,
                subtitle = "Tap to cycle repeat mode (Off → All → Repeat 1)",
                accentTint = playerState.repeatMode != RepeatMode.OFF,
                onClick = onCycleRepeatMode
            )

            PixelSheetOptionRow(
                icon = Icons.Default.QueueMusic,
                title = "Up Next Queue (${playerState.queue.size})",
                subtitle = "View upcoming tracks and recommendations",
                onClick = onOpenQueue
            )

            PixelSheetOptionRow(
                icon = Icons.Default.Explore,
                title = "Related Songs, Mixes & Artists",
                subtitle = "Explore similar tracks, playlists & albums without changing Up Next",
                onClick = onOpenRelated
            )

            if (onNavigateArtist != null) {
                val isVid = playerState.isVideoMode || track.isVideoTrack
                PixelSheetOptionRow(
                    icon = if (isVid) Icons.Default.Tv else Icons.Default.Person,
                    title = if (isVid) "Visit Channel (${track.artist})" else "Go to Artist (${track.artist})",
                    subtitle = "Explore songs, videos, albums & mixes",
                    onClick = onNavigateArtist
                )
            }

            PixelSheetOptionRow(
                icon = Icons.Default.Tune,
                title = "Equalizer & Audio Adjustments",
                subtitle = "5-band studio EQ, normalization & streaming quality",
                onClick = onOpenAudioAdjust
            )

            PixelSheetOptionRow(
                icon = Icons.Outlined.Timer,
                title = if (playerState.sleepTimerMinutesLeft > 0) "Sleep Timer (${playerState.sleepTimerMinutesLeft}m left)" else "Sleep Timer",
                subtitle = "Automatically pause playback after a set duration",
                accentTint = playerState.sleepTimerMinutesLeft > 0,
                onClick = onOpenSleepTimer
            )

            if (!track.isLocalOnlyTrack) {
                PixelSheetOptionRow(
                    icon = if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                    title = if (isDownloaded) "Downloaded for Offline Playback" else "Download Track / Video",
                    subtitle = if (isDownloaded) "Saved on device storage" else "Save audio or MP4 video for offline listening",
                    accentTint = isDownloaded,
                    onClick = onOpenDownloadOptions
                )
            }

            PixelSheetOptionRow(
                icon = Icons.Outlined.Info,
                title = "Track & Video Details",
                subtitle = "View full title, metadata, and description",
                onClick = onOpenDetails
            )

            if (onShare != null) {
                PixelSheetOptionRow(
                    icon = Icons.Outlined.Share,
                    title = "Share",
                    subtitle = "Share link with friends",
                    onClick = onShare
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * Authentic Google Pixel (Material 3 / Android 15 Media Control) Next-Track Countdown Card:
 * - Uses adaptive Material You tonal container (surfaceContainerHigh) or Pixel dark glass in fullscreen.
 * - Features an unobstructed rounded-square (12.dp) thumbnail on the left, a clean Pixel tonal status chip
 *   ("Up next in Xs"), 1-line title & artist, a rounded-square "Play now" tonal button, and a subtle bottom progress bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeitharolNextTrackCountdownBanner(
    nextTrack: com.example.data.model.Track,
    countdownSeconds: Int,
    countdownProgress: Float,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isDarkOverlay: Boolean = false
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    val accentColor = MaterialTheme.colorScheme.primary
    val containerBg = if (isDarkOverlay) Color(0xF21E2024) else MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = if (isDarkOverlay) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val primaryTextColor = if (isDarkOverlay) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (isDarkOverlay) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val chipBgColor = if (isDarkOverlay) accentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer
    val chipTextColor = if (isDarkOverlay) Color(0xFFFFB4AB) else MaterialTheme.colorScheme.onPrimaryContainer

    val animatedCountdownProgress by animateFloatAsState(
        targetValue = countdownProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "countdown_banner_progress"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable { onPlayNow() }
            .testTag("next_track_countdown_banner"),
        shape = RoundedCornerShape(24.dp),
        color = containerBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Crisp Unobstructed Rounded-Square Thumbnail (Google Pixel Media style)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDarkOverlay) Color(0xFF2C2E33) else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
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

                // Clean Google Pixel Typography Stack
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = chipBgColor
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(accentColor)
                                )
                                Text(
                                    text = "Up next in ${countdownSeconds}s",
                                    color = chipTextColor,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Text(
                        text = nextTrack.title,
                        color = primaryTextColor,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 800)
                    )

                    Text(
                        text = nextTrack.artist,
                        color = secondaryTextColor,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right Action Cluster: Google Pixel Rounded-Square Play Button + Dismiss Squircle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = onPlayNow,
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Next Now",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Play",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDarkOverlay) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss countdown",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Subtle Google Pixel Linear Progress Bar at bottom edge
            LinearProgressIndicator(
                progress = { animatedCountdownProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = accentColor,
                trackColor = if (isDarkOverlay) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeitharolRawVideoSurface(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    isZoomCrop: Boolean,
    onToggleZoomCrop: () -> Unit,
    onMinimizePlayer: () -> Unit = {},
    onEnterFullscreen: () -> Unit,
    onEnterPiP: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack
    var showOverlayControls by remember(track?.id) { mutableStateOf(false) }
    var isControlsLocked by remember(track?.id) { mutableStateOf(false) }
    var skipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }
    var skipAccumulatedSeconds by remember { mutableIntStateOf(10) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapSideLeft by remember { mutableStateOf<Boolean?>(null) }
    var singleTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(playerState)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) {
            showOverlayControls = false
        }
    }

    LaunchedEffect(showOverlayControls, playerState.isPlaying, isControlsLocked) {
        if (showOverlayControls && playerState.isPlaying) {
            delay(if (isControlsLocked) 2200L else 2600L)
            showOverlayControls = false
        }
    }

    LaunchedEffect(skipFeedbackIsForward, skipAccumulatedSeconds) {
        if (skipFeedbackIsForward != null) {
            delay(750L)
            skipFeedbackIsForward = null
            skipAccumulatedSeconds = 10
            lastTapSideLeft = null
        }
    }

    val context = LocalContext.current
    var attachedInlineView by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            if (!viewModel.isFullscreenVideo.value) {
                viewModel.detachVideoTextureView(attachedInlineView)
            }
        }
    }

    val videoRatio = playerState.videoAspectRatio.takeIf { !it.isNaN() && !it.isInfinite() && it in 0.25f..4.0f } ?: (16f / 9f)
    val frameCornerShape = RoundedCornerShape(14.dp)

    // 100% Width Video Frame with subtle 14.dp border-radius
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .clip(frameCornerShape),
        shape = frameCornerShape,
        color = Color.Black
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clip(frameCornerShape)
                .pointerInput(isControlsLocked) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (isControlsLocked) {
                                showOverlayControls = !showOverlayControls
                                return@detectTapGestures
                            }
                            val now = System.currentTimeMillis()
                            val isLeftHalf = offset.x < size.width / 2f
                            if ((now - lastTapTimeMs) < 420L && lastTapSideLeft == isLeftHalf) {
                                // Cumulative multi-tap seek: 2nd tap = 10s, 3rd tap = 20s, 4th tap = 30s...
                                singleTapJob?.cancel()
                                if (skipFeedbackIsForward != null) {
                                    skipAccumulatedSeconds += 10
                                } else {
                                    skipAccumulatedSeconds = 10
                                }
                                lastTapTimeMs = now
                                skipFeedbackIsForward = !isLeftHalf
                                val delta = if (isLeftHalf) -10_000L else 10_000L
                                val target = (latestState.currentPositionMs + delta).coerceIn(
                                    0L,
                                    latestState.durationMs.coerceAtLeast(1000L)
                                )
                                viewModel.seekTo(target)
                            } else {
                                lastTapTimeMs = now
                                lastTapSideLeft = isLeftHalf
                                singleTapJob?.cancel()
                                singleTapJob = scope.launch {
                                    delay(240L)
                                    showOverlayControls = !showOverlayControls
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else (16f / 9f)
            val matchHeightFirst = containerRatio > videoRatio
            val exactAspectModifier = if (isZoomCrop) {
                Modifier.fillMaxSize()
            } else if (matchHeightFirst) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(videoRatio, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoRatio, matchHeightConstraintsFirst = false)
            }

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        attachedInlineView = this
                        viewModel.attachVideoTextureView(this)
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            val loc = IntArray(2)
                            v.getLocationOnScreen(loc)
                            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                            (ctx.findActivity() as? MainActivity)?.updateVideoSourceRect(rect)
                        }
                    }
                },
                update = { tv ->
                    attachedInlineView = tv
                    viewModel.attachVideoTextureView(tv)
                    val loc = IntArray(2)
                    tv.getLocationOnScreen(loc)
                    if (tv.width > 0 && tv.height > 0) {
                        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height)
                        (context.findActivity() as? MainActivity)?.updateVideoSourceRect(rect)
                    }
                },
                modifier = exactAspectModifier
            )

            if (track != null && !playerState.isVideoFrameRendered && (playerState.isBuffering || !playerState.isPlaying)) {
                val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                if (upgradedArt.isNotBlank()) {
                    AsyncImage(
                        model = upgradedArt,
                        contentDescription = null,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Fit,
                        modifier = exactAspectModifier
                            .graphicsLayer { alpha = 0.55f }
                    )
                }
            }

            // YouTube-Style Left / Right Ripple Arc Overlay for Cumulative Multi-Tap Seek (10s, 20s, 30s...)
            AnimatedVisibility(
                visible = skipFeedbackIsForward != null,
                enter = fadeIn(tween(100)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize()
            ) {
                val isFwd = skipFeedbackIsForward ?: true
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.44f)
                            .align(if (isFwd) Alignment.CenterEnd else Alignment.CenterStart)
                            .clip(
                                if (isFwd) RoundedCornerShape(topStart = 120.dp, bottomStart = 120.dp)
                                else RoundedCornerShape(topEnd = 120.dp, bottomEnd = 120.dp)
                            )
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        SeitharolSkip10sBadgeOverlay(
                            isForward = isFwd,
                            skipSeconds = skipAccumulatedSeconds
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showOverlayControls || playerState.isBuffering || playerState.isEnded,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (showOverlayControls) 0.40f else 0.18f))
                ) {
                    if (isControlsLocked) {
                        // Locked Controls Mode: only show "Tap to Unlock" pill at bottom center (like YouTube)
                        Surface(
                            onClick = { isControlsLocked = false },
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Black.copy(alpha = 0.78f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Unlock Controls",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Tap to Unlock Controls",
                                    color = Color.White,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        // Top Bar: YouTube-Style Top-Left Minimize Chevron + Top-Right Lock, Mute, PiP
                        if (showOverlayControls) {
                            Surface(
                                onClick = onMinimizePlayer,
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Minimize Video Player",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = { isControlsLocked = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Lock Controls",
                                            tint = Color.White,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { viewModel.toggleMute() },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.size(36.dp)
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
                                    onClick = onEnterPiP,
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.size(36.dp)
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

                        // Center Transport Controls (Rounded-Square Play Button & Cumulative 10s/20s/30s Skip)
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            if (showOverlayControls) {
                                Surface(
                                    onClick = {
                                        val now = System.currentTimeMillis()
                                        if (skipFeedbackIsForward == false && (now - lastTapTimeMs) < 900L) {
                                            skipAccumulatedSeconds += 10
                                        } else {
                                            skipAccumulatedSeconds = 10
                                        }
                                        lastTapTimeMs = now
                                        skipFeedbackIsForward = false
                                        val target = (latestState.currentPositionMs - 10_000L).coerceAtLeast(0L)
                                        viewModel.seekTo(target)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.size(42.dp)
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
                                onClick = { viewModel.togglePlayPause() },
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier.size(56.dp)
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
                                            imageVector = when {
                                                playerState.isPlaying -> Icons.Default.Pause
                                                playerState.isEnded -> Icons.Default.Replay
                                                else -> Icons.Default.PlayArrow
                                            },
                                            contentDescription = if (playerState.isEnded) "Replay" else "Play/Pause",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }

                            if (showOverlayControls) {
                                Surface(
                                    onClick = {
                                        val now = System.currentTimeMillis()
                                        if (skipFeedbackIsForward == true && (now - lastTapTimeMs) < 900L) {
                                            skipAccumulatedSeconds += 10
                                        } else {
                                            skipAccumulatedSeconds = 10
                                        }
                                        lastTapTimeMs = now
                                        skipFeedbackIsForward = true
                                        val dur = latestState.durationMs.coerceAtLeast(1000L)
                                        val target = (latestState.currentPositionMs + 10_000L).coerceAtMost(dur)
                                        viewModel.seekTo(target)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.size(42.dp)
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

                        // Bottom-Right Fullscreen Button (matching YouTube placement)
                        if (showOverlayControls) {
                            Surface(
                                onClick = onEnterFullscreen,
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.58f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .size(36.dp)
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
}

@OptIn(ExperimentalFoundationApi::class)
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
    val queueSuggestions by viewModel.queueVideoSuggestions.collectAsState()
    var showControls by remember { mutableStateOf(true) }
    var isControlsLocked by remember(track.id) { mutableStateOf(false) }
    var showSuggestionsDrawer by remember(track.id) { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var seekingPosition by remember { mutableStateOf<Float?>(null) }
    var skipFeedbackIsForward by remember { mutableStateOf<Boolean?>(null) }
    var skipAccumulatedSeconds by remember { mutableIntStateOf(10) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapSideLeft by remember { mutableStateOf<Boolean?>(null) }
    var singleTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(playerState)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    val displayPosition = (if (isDraggingSlider) seekingPosition?.toLong() else null)
        ?: playerState.currentPositionMs.coerceAtLeast(0L)
    val totalDuration = playerState.durationMs.coerceAtLeast(1000L)
    val progress = (displayPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    // Ensure YouTube video suggestions are loaded for the slide-up drawer
    LaunchedEffect(track.id) {
        if (queueSuggestions.isEmpty() && !track.isLocalOnlyTrack) {
            viewModel.refreshQueueSuggestions(track)
        }
    }

    val relatedSuggestions = remember(queueSuggestions, playerState.queue, playerState.currentIndex, track.id) {
        val upcomingQueue = if (playerState.currentIndex + 1 < playerState.queue.size) {
            playerState.queue.subList(playerState.currentIndex + 1, playerState.queue.size)
        } else emptyList()
        (upcomingQueue + queueSuggestions)
            .filter { it.id != track.id }
            .distinctBy { it.id }
            .take(18)
    }

    LaunchedEffect(showControls, playerState.isPlaying, isDraggingSlider, showSuggestionsDrawer, isControlsLocked) {
        if (showControls && playerState.isPlaying && !isDraggingSlider && !showSuggestionsDrawer) {
            delay(if (isControlsLocked) 2200L else 3000L)
            showControls = false
        }
    }

    LaunchedEffect(skipFeedbackIsForward, skipAccumulatedSeconds) {
        if (skipFeedbackIsForward != null) {
            delay(750L)
            skipFeedbackIsForward = null
            skipAccumulatedSeconds = 10
            lastTapSideLeft = null
        }
    }

    val context = LocalContext.current
    var attachedFullscreenView by remember { mutableStateOf<TextureView?>(null) }
    var showFullscreenDescriptionSheet by remember { mutableStateOf(false) }
    val videoRatio = playerState.videoAspectRatio.takeIf { !it.isNaN() && !it.isInfinite() && it in 0.25f..4.0f } ?: (16f / 9f)
    val isPortraitVideo = videoRatio < 1.0f

    androidx.activity.compose.BackHandler {
        when {
            showSuggestionsDrawer -> showSuggestionsDrawer = false
            isControlsLocked -> showControls = true
            else -> onDismiss()
        }
    }

    // Orientation Management: If height > width (videoRatio < 1.0f, vertical/portrait video),
    // do NOT rotate to horizontal landscape — keep portrait orientation!
    DisposableEffect(isPortraitVideo) {
        val activity = context.findActivity()
        activity?.requestedOrientation = if (isPortraitVideo) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val window = activity?.window
        val insetsController = if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        } else null
        insetsController?.let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            viewModel.detachVideoTextureView(attachedFullscreenView)
            val isTabletDevice = (activity?.resources?.configuration?.smallestScreenWidthDp ?: 0) >= 600
            activity?.requestedOrientation = if (isTabletDevice) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isControlsLocked, showSuggestionsDrawer) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (showSuggestionsDrawer) {
                                    showSuggestionsDrawer = false
                                    return@detectTapGestures
                                }
                                if (isControlsLocked) {
                                    showControls = !showControls
                                    return@detectTapGestures
                                }
                                val now = System.currentTimeMillis()
                                val isLeft = offset.x < size.width / 2f
                                if ((now - lastTapTimeMs) < 420L && lastTapSideLeft == isLeft) {
                                    singleTapJob?.cancel()
                                    if (skipFeedbackIsForward != null) {
                                        skipAccumulatedSeconds += 10
                                    } else {
                                        skipAccumulatedSeconds = 10
                                    }
                                    lastTapTimeMs = now
                                    skipFeedbackIsForward = !isLeft
                                    val delta = if (isLeft) -10_000L else 10_000L
                                    val dur = latestState.durationMs.coerceAtLeast(1000L)
                                    viewModel.seekTo(
                                        (latestState.currentPositionMs + delta).coerceIn(0L, dur)
                                    )
                                } else {
                                    lastTapTimeMs = now
                                    lastTapSideLeft = isLeft
                                    singleTapJob?.cancel()
                                    singleTapJob = scope.launch {
                                        delay(240L)
                                        showControls = !showControls
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isControlsLocked, showSuggestionsDrawer) {
                        var verticalDragSum = 0f
                        detectVerticalDragGestures(
                            onDragStart = { verticalDragSum = 0f },
                            onDragEnd = {
                                if (!isControlsLocked) {
                                    if (verticalDragSum < -95f && !showSuggestionsDrawer) {
                                        // Slide up on fullscreen opens YouTube Suggestion shelf!
                                        showSuggestionsDrawer = true
                                        showControls = true
                                    } else if (verticalDragSum > 95f) {
                                        if (showSuggestionsDrawer) {
                                            showSuggestionsDrawer = false
                                        } else {
                                            onDismiss()
                                        }
                                    }
                                }
                                verticalDragSum = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                verticalDragSum += dragAmount
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else (16f / 9f)
                val matchHeightFirst = containerRatio > videoRatio
                val exactFullscreenAspectModifier = if (isZoomCrop) {
                    Modifier.fillMaxSize()
                } else if (matchHeightFirst) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(videoRatio, matchHeightConstraintsFirst = true)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoRatio, matchHeightConstraintsFirst = false)
                }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            attachedFullscreenView = this
                            viewModel.attachVideoTextureView(this)
                            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                                val loc = IntArray(2)
                                v.getLocationOnScreen(loc)
                                val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                                (ctx.findActivity() as? MainActivity)?.updateVideoSourceRect(rect)
                            }
                        }
                    },
                    update = { tv ->
                        attachedFullscreenView = tv
                        viewModel.attachVideoTextureView(tv)
                    },
                    modifier = exactFullscreenAspectModifier
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
                            contentScale = ContentScale.Fit,
                            modifier = exactFullscreenAspectModifier
                                .graphicsLayer { alpha = 0.55f }
                        )
                    }
                }

                // YouTube-Style Left / Right Ripple Arc Overlay for Cumulative Multi-Tap Seek (10s, 20s, 30s...)
                AnimatedVisibility(
                    visible = skipFeedbackIsForward != null,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val isFwd = skipFeedbackIsForward ?: true
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.40f)
                                .align(if (isFwd) Alignment.CenterEnd else Alignment.CenterStart)
                                .clip(
                                    if (isFwd) RoundedCornerShape(topStart = 160.dp, bottomStart = 160.dp)
                                    else RoundedCornerShape(topEnd = 160.dp, bottomEnd = 160.dp)
                                )
                                .background(Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            SeitharolSkip10sBadgeOverlay(
                                isForward = isFwd,
                                skipSeconds = skipAccumulatedSeconds
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showControls || playerState.isBuffering || playerState.isEnded || showSuggestionsDrawer,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (showSuggestionsDrawer) 0.65f else 0.45f))
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        if (isControlsLocked) {
                            // YouTube-style Screen Lock Mode: hides all transport controls and shows only the Unlock pill button
                            Surface(
                                onClick = { isControlsLocked = false },
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(alpha = 0.82f),
                                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color.White.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 28.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LockOpen,
                                        contentDescription = "Unlock Screen",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Screen Locked • Tap to Unlock",
                                        color = Color.White,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            // Top Bar: YouTube-Style Top-Left Minimize Button + Marquee Title + Lock/Mute/PiP on Top-Right
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Top-Left Minimize Button (exact YouTube placement)
                                    Surface(
                                        onClick = onDismiss,
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Minimize Fullscreen",
                                                tint = Color.White,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                showFullscreenDescriptionSheet = true
                                                onOpenVideoDescription()
                                            }
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = track.title,
                                                color = Color.White,
                                                fontFamily = SyneFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .weight(1f, fill = false)
                                                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Video Details",
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Text(
                                            text = "${track.artist} • ${track.durationFormatted}",
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 12.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Lock Play Controls Button (like YouTube)
                                    Surface(
                                        onClick = {
                                            showSuggestionsDrawer = false
                                            isControlsLocked = true
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Lock Controls",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        onClick = { viewModel.toggleMute() },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (playerState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                contentDescription = if (playerState.isMuted) "Unmute" else "Mute",
                                                tint = if (playerState.isMuted) MaterialTheme.colorScheme.primary else Color.White,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        onClick = onEnterPiP,
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color.White.copy(alpha = 0.14f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.PictureInPictureAlt,
                                                contentDescription = "Picture in Picture",
                                                tint = Color.White,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Center Transport Controls (hidden when slide-up suggestions drawer is expanded)
                            if (!showSuggestionsDrawer) {
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
                                        onClick = {
                                            val now = System.currentTimeMillis()
                                            if (skipFeedbackIsForward == false && (now - lastTapTimeMs) < 900L) {
                                                skipAccumulatedSeconds += 10
                                            } else {
                                                skipAccumulatedSeconds = 10
                                            }
                                            lastTapTimeMs = now
                                            skipFeedbackIsForward = false
                                            val target = (latestState.currentPositionMs - 10_000L).coerceAtLeast(0L)
                                            viewModel.seekTo(target)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color.White.copy(alpha = 0.16f),
                                        modifier = Modifier.size(46.dp)
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
                                        onClick = { viewModel.togglePlayPause() },
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(64.dp)
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
                                                    imageVector = when {
                                                        playerState.isPlaying -> Icons.Default.Pause
                                                        playerState.isEnded -> Icons.Default.Replay
                                                        else -> Icons.Default.PlayArrow
                                                    },
                                                    contentDescription = "Play/Pause",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(34.dp)
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            val now = System.currentTimeMillis()
                                            if (skipFeedbackIsForward == true && (now - lastTapTimeMs) < 900L) {
                                                skipAccumulatedSeconds += 10
                                            } else {
                                                skipAccumulatedSeconds = 10
                                            }
                                            lastTapTimeMs = now
                                            skipFeedbackIsForward = true
                                            val dur = latestState.durationMs.coerceAtLeast(1000L)
                                            val target = (latestState.currentPositionMs + 10_000L).coerceAtMost(dur)
                                            viewModel.seekTo(target)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color.White.copy(alpha = 0.16f),
                                        modifier = Modifier.size(46.dp)
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
                            }

                            // Bottom Controls Area: Progress Bar + Slide-Up YouTube Suggestions Handle + Bottom-Right Exit Fullscreen
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                // Slide-up YouTube Suggestions Drawer when opened
                                AnimatedVisibility(
                                    visible = showSuggestionsDrawer,
                                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(180)),
                                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(150))
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xEB141619),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.VideoLibrary,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = "Suggested Next on YouTube",
                                                        color = Color.White,
                                                        fontFamily = SyneFontFamily,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                                Surface(
                                                    onClick = { showSuggestionsDrawer = false },
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color.White.copy(alpha = 0.12f)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "Close",
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                            fontFamily = OutfitFontFamily,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Close Suggestions",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            if (relatedSuggestions.isEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(90.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "Loading YouTube suggestions...",
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        fontSize = 13.sp,
                                                        fontFamily = OutfitFontFamily
                                                    )
                                                }
                                            } else {
                                                LazyRow(
                                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    items(relatedSuggestions, key = { "fs_sug_${it.id}" }) { sugTrack ->
                                                        Surface(
                                                            onClick = {
                                                                showSuggestionsDrawer = false
                                                                val videoQueue = relatedSuggestions.map {
                                                                    it.copy(genre = "YouTube Video", album = "YouTube Video", itag = 18, codec = "MP4")
                                                                }
                                                                val targetIdx = videoQueue.indexOfFirst { it.id == sugTrack.id }.coerceAtLeast(0)
                                                                viewModel.playQueue(videoQueue, targetIdx, isPlaylist = true)
                                                            },
                                                            shape = RoundedCornerShape(14.dp),
                                                            color = Color.White.copy(alpha = 0.08f),
                                                            modifier = Modifier.width(186.dp)
                                                        ) {
                                                            Column(modifier = Modifier.padding(8.dp)) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(98.dp)
                                                                        .clip(RoundedCornerShape(10.dp))
                                                                        .background(Color.DarkGray)
                                                                ) {
                                                                    AsyncImage(
                                                                        model = InnerTubeResolver.upgradeThumbnailUrl(sugTrack.artUrl),
                                                                        contentDescription = sugTrack.title,
                                                                        contentScale = ContentScale.Crop,
                                                                        modifier = Modifier.fillMaxSize()
                                                                    )
                                                                    Surface(
                                                                        shape = RoundedCornerShape(8.dp),
                                                                        color = Color.Black.copy(alpha = 0.58f),
                                                                        modifier = Modifier
                                                                            .align(Alignment.Center)
                                                                            .size(30.dp)
                                                                    ) {
                                                                        Box(contentAlignment = Alignment.Center) {
                                                                            Icon(
                                                                                imageVector = Icons.Default.PlayArrow,
                                                                                contentDescription = "Play",
                                                                                tint = Color.White,
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                    if (sugTrack.durationMs > 0L) {
                                                                        Surface(
                                                                            shape = RoundedCornerShape(5.dp),
                                                                            color = Color.Black.copy(alpha = 0.82f),
                                                                            modifier = Modifier
                                                                                .align(Alignment.BottomEnd)
                                                                                .padding(4.dp)
                                                                        ) {
                                                                            Text(
                                                                                text = sugTrack.durationFormatted,
                                                                                color = Color.White,
                                                                                fontSize = 10.sp,
                                                                                fontWeight = FontWeight.Bold,
                                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                Text(
                                                                    text = sugTrack.title,
                                                                    color = Color.White,
                                                                    fontFamily = OutfitFontFamily,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    fontSize = 12.5.sp,
                                                                    maxLines = 2,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = sugTrack.artist,
                                                                    color = Color.White.copy(alpha = 0.68f),
                                                                    fontFamily = OutfitFontFamily,
                                                                    fontSize = 11.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                SeitharolProProgressBar(
                                    progress = progress,
                                    bufferedFraction = playerState.bufferedFraction,
                                    displayPositionMs = displayPosition,
                                    totalDurationMs = totalDuration,
                                    isDragging = isDraggingSlider,
                                    isPlaying = playerState.isPlaying && !playerState.isBuffering,
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
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // Bottom Action Strip: Slide-Up More Videos Pill + Fit/Zoom + Bottom-Right Exit Fullscreen (YouTube layout)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = { showSuggestionsDrawer = !showSuggestionsDrawer },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.14f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (showSuggestionsDrawer) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                contentDescription = "More Videos",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (showSuggestionsDrawer) "Hide Suggestions" else "Swipe Up for More Videos (${relatedSuggestions.size})",
                                                color = Color.White,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            onClick = onToggleZoomCrop,
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.White.copy(alpha = 0.14f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AspectRatio,
                                                    contentDescription = "Zoom / Fit",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (isZoomCrop) "Crop" else "Fit",
                                                    color = Color.White,
                                                    fontSize = 11.5.sp,
                                                    fontFamily = OutfitFontFamily,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        // Bottom-Right Fullscreen Exit Toggle (exact YouTube placement)
                                        Surface(
                                            onClick = onDismiss,
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color.White.copy(alpha = 0.16f),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.FullscreenExit,
                                                    contentDescription = "Exit Fullscreen",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showNextCountdown && playerState.repeatMode != RepeatMode.ONE && nextTrack != null && !showSuggestionsDrawer && !isControlsLocked,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = if (showControls) 110.dp else 32.dp)
                ) {
                    if (nextTrack != null && playerState.repeatMode != RepeatMode.ONE) {
                        SeitharolNextTrackCountdownBanner(
                            nextTrack = nextTrack,
                            countdownSeconds = countdownSeconds,
                            countdownProgress = countdownProgress,
                            onPlayNow = { viewModel.next() },
                            onDismiss = onDismissCountdown,
                            isDarkOverlay = true,
                            modifier = Modifier.widthIn(max = 390.dp)
                        )
                    }
                }
            }
        }

        if (showFullscreenDescriptionSheet) {
            VideoDetailsDescriptionSheet(
                track = track,
                onVisitChannel = if (track.isNavigableArtist && !track.isLocalOnlyTrack) {
                    {
                        showFullscreenDescriptionSheet = false
                        onDismiss()
                        viewModel.navigateToArtist(track.artist, isChannel = true)
                    }
                } else null,
                onDismiss = { showFullscreenDescriptionSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    var lastUserExpandedTouchMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playerState.activeLyricIndex, playerState.lyricLines.size) {
        runCatching {
            if (playerState.activeLyricIndex !in 0 until playerState.lyricLines.size) return@LaunchedEffect
            val now = System.currentTimeMillis()
            if (now - lastUserExpandedTouchMs < 2400L) {
                delay((2400L - (now - lastUserExpandedTouchMs)).coerceAtLeast(100L))
            }
            val activeIdx = playerState.activeLyricIndex
            // Wait one frame so Compose measures the newly active line's expanded font size before centering
            withFrameNanos { }
            var layoutInfo = lyricListState.layoutInfo
            var visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
            if (visibleItem == null) {
                // Because top contentPadding is (maxHeight / 2 - 32.dp), scrollOffset = 0 places the item right at the vertical center
                lyricListState.animateScrollToItem(
                    index = activeIdx,
                    scrollOffset = 0
                )
                withFrameNanos { }
                layoutInfo = lyricListState.layoutInfo
                visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
            }
            if (visibleItem != null) {
                val itemCenter = visibleItem.offset + (visibleItem.size / 2f)
                // True visual center of the showing div in visibleItem.offset coordinates:
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                val delta = itemCenter - viewportCenter
                if (kotlin.math.abs(delta) > 1f) {
                    lyricListState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                                .clickable { viewModel.togglePlayPause() }
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

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    val verticalPad = (maxHeight / 2 - 32.dp).coerceAtLeast(110.dp)
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
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                            if (event.changes.any { it.positionChanged() }) {
                                                lastUserExpandedTouchMs = System.currentTimeMillis()
                                            }
                                        }
                                    }
                                },
                            contentPadding = PaddingValues(top = verticalPad, bottom = verticalPad)
                        ) {
                            itemsIndexed(playerState.lyricLines) { index, line ->
                                val isCurrent = index == playerState.activeLyricIndex
                                val activeScale by animateFloatAsState(
                                    targetValue = if (isCurrent) 1.05f else 0.96f,
                                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                                    label = "lyric_scale"
                                )
                                val activeAlpha by animateFloatAsState(
                                    targetValue = if (isCurrent) 1f else 0.45f,
                                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                                    label = "lyric_alpha"
                                )

                                if (line.isInstrumental) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = activeScale
                                                scaleY = activeScale
                                                alpha = activeAlpha
                                            }
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        InstrumentalGapMusicNoteIndicator(
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                        )
                                    }
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = activeScale
                                                scaleY = activeScale
                                                alpha = activeAlpha
                                            }
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                            .clickable { viewModel.seekTo(line.timestampMs) }
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = if (isCurrent) 24.sp else 18.sp,
                                            fontFamily = SyneFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                            lineHeight = if (isCurrent) 32.sp else 26.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                fontSize = if (isCurrent) 16.sp else 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Normal,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp)
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
                            isPlaying = playerState.isPlaying && !playerState.isBuffering,
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
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.togglePlayPause() },
                                shape = RoundedCornerShape(16.dp),
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
                                            imageVector = when {
                                                playerState.isPlaying -> Icons.Default.Pause
                                                playerState.isEnded -> Icons.Default.Replay
                                                else -> Icons.Default.PlayArrow
                                            },
                                            contentDescription = if (playerState.isEnded) "Replay" else "Play/Pause",
                                            tint = Color.White,
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

private fun formatMs(ms: Long): String = formatDurationMs(ms)

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
                .verticalScroll(rememberScrollState())
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

            // Always On Screen toggles in Adjust sheet (Video default ON, Music default OFF)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Always On Screen: Video", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Keep screen awake during video playback (Default: On)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.alwaysOnScreenVideo,
                    onCheckedChange = { settingsManager.setAlwaysOnScreenVideo(it) },
                    colors = switchColors
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Always On Screen: Music", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Keep screen awake during music playback (Default: Off)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.alwaysOnScreenMusic,
                    onCheckedChange = { settingsManager.setAlwaysOnScreenMusic(it) },
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
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
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


/**
 * YouTube Music-Style "Related" Bottom Sheet in Google Pixel Material 3 Design:
 * Shows Similar Songs/Videos ("You might also like"), Recommended Playlists & Mixes,
 * More from <Artist> (Albums & Singles), Similar Artists ("Fans might also like"),
 * and About <Artist> — all WITHOUT modifying or affecting the active Up Next queue!
 * Uses ModalBottomSheet(skipPartiallyExpanded = true) for silky-smooth slide-down physics
 * identical to the Audio Adjustments sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerRelatedModalSheet(
    viewModel: CascadeViewModel,
    currentTrack: com.example.data.model.Track,
    playerState: PlayerState,
    onAddToPlaylist: (com.example.data.model.Track) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val relatedData by viewModel.playerRelatedData.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    var trackForDownload by remember { mutableStateOf<com.example.data.model.Track?>(null) }

    LaunchedEffect(currentTrack.id) {
        viewModel.loadRelatedForTrack(currentTrack)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .navigationBarsPadding()
                .testTag("player_related_modal")
        ) {
            // Top Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "RELATED",
                                fontSize = 10.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            text = "Up Next queue stays untouched",
                            fontSize = 11.5.sp,
                            fontFamily = OutfitFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Related to ${currentTrack.title}",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { viewModel.loadRelatedForTrack(currentTrack, forceReload = true) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Related",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close Related",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            if (relatedData.isLoading && relatedData.relatedTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.6.dp
                        )
                        Text(
                            text = "Loading related songs, playlists & artists...",
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    // ────────────────────────────────────────────────────────────
                    // 1. "You might also like" — YouTube Music 4-Row Horizontal Snap Grid
                    // ────────────────────────────────────────────────────────────
                    if (relatedData.relatedTracks.isNotEmpty()) {
                        item(key = "rel_you_might_also_like") {
                            val columns = remember(relatedData.relatedTracks) {
                                relatedData.relatedTracks.chunked(4)
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                    Text(
                                        text = "You might also like",
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Similar tracks • Tap to play without clearing Up Next",
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    itemsIndexed(columns) { colIdx, colTracks ->
                                        Column(
                                            modifier = Modifier.width(305.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            colTracks.forEach { relTrack ->
                                                var showItemMenu by remember { mutableStateOf(false) }
                                                val isThisPlaying = playerState.currentTrack?.id == relTrack.id
                                                Surface(
                                                    onClick = {
                                                        viewModel.playRelatedTrackPreservingQueue(relTrack)
                                                    },
                                                    shape = RoundedCornerShape(14.dp),
                                                    color = if (isThisPlaying) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(62.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        AsyncImage(
                                                            model = InnerTubeResolver.upgradeThumbnailUrl(relTrack.artUrl).ifBlank { R.drawable.ic_splash_logo },
                                                            contentDescription = relTrack.title,
                                                            placeholder = appLogoPainter,
                                                            error = appLogoPainter,
                                                            fallback = appLogoPainter,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier
                                                                .size(48.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .padding(end = 6.dp)
                                                        ) {
                                                            Text(
                                                                text = relTrack.title,
                                                                fontFamily = OutfitFontFamily,
                                                                fontWeight = FontWeight.SemiBold,
                                                                fontSize = 13.5.sp,
                                                                color = if (isThisPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = "${relTrack.artist} • ${relTrack.durationFormatted}",
                                                                fontFamily = OutfitFontFamily,
                                                                fontSize = 11.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }

                                                        // Connected Google Pixel Split-Action Pill (Play Next + 3-dot with 0.dp center radius)
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                                                        ) {
                                                            Surface(
                                                                onClick = { viewModel.playNext(relTrack) },
                                                                shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                                                                color = MaterialTheme.colorScheme.surface,
                                                                modifier = Modifier.size(32.dp)
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.PlaylistPlay,
                                                                        contentDescription = "Play Next",
                                                                        tint = MaterialTheme.colorScheme.primary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                }
                                                            }
                                                            Box(
                                                                modifier = Modifier
                                                                    .width(1.dp)
                                                                    .height(18.dp)
                                                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                                            )
                                                            Box {
                                                                Surface(
                                                                    onClick = { showItemMenu = true },
                                                                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 10.dp, bottomEnd = 10.dp),
                                                                    color = MaterialTheme.colorScheme.surface,
                                                                    modifier = Modifier.size(32.dp)
                                                                ) {
                                                                    Box(contentAlignment = Alignment.Center) {
                                                                        Icon(
                                                                            imageVector = Icons.Default.MoreVert,
                                                                            contentDescription = "More Options",
                                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                            modifier = Modifier.size(17.dp)
                                                                        )
                                                                    }
                                                                }

                                                                DropdownMenu(
                                                                    expanded = showItemMenu,
                                                                    onDismissRequest = { showItemMenu = false },
                                                                    modifier = Modifier
                                                                        .widthIn(min = 220.dp, max = 280.dp)
                                                                        .background(MaterialTheme.colorScheme.surface)
                                                                ) {
                                                                    DropdownMenuItem(
                                                                        text = { Text("Play Now (Keep Queue)", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            viewModel.playRelatedTrackPreservingQueue(relTrack)
                                                                        }
                                                                    )
                                                                    DropdownMenuItem(
                                                                        text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            viewModel.playNext(relTrack)
                                                                        }
                                                                    )
                                                                    DropdownMenuItem(
                                                                        text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            viewModel.addToQueue(relTrack)
                                                                        }
                                                                    )
                                                                    DropdownMenuItem(
                                                                        text = { Text("Start Radio Mix", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            viewModel.startRadioMix(relTrack)
                                                                            onDismiss()
                                                                        }
                                                                    )
                                                                    DropdownMenuItem(
                                                                        text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            onAddToPlaylist(relTrack)
                                                                        }
                                                                    )
                                                                    if (relTrack.isNavigableArtist) {
                                                                        DropdownMenuItem(
                                                                            text = { Text("Go to Artist", color = MaterialTheme.colorScheme.onSurface) },
                                                                            leadingIcon = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                                            onClick = {
                                                                                showItemMenu = false
                                                                                onNavigateToArtist(relTrack.artist, relTrack.isVideoTrack)
                                                                            }
                                                                        )
                                                                    }
                                                                    DropdownMenuItem(
                                                                        text = { Text("Download", color = MaterialTheme.colorScheme.onSurface) },
                                                                        leadingIcon = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                                                        onClick = {
                                                                            showItemMenu = false
                                                                            trackForDownload = relTrack
                                                                        }
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
                            }
                        }
                    }

                    // ────────────────────────────────────────────────────────────
                    // 2. "Recommended playlists & mixes" — YouTube Music Carousel
                    // ────────────────────────────────────────────────────────────
                    if (relatedData.relatedPlaylistsAndMixes.isNotEmpty()) {
                        item(key = "rel_playlists_mixes") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                    Text(
                                        text = "Recommended playlists & mixes",
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Curated stations and playlists matching this track",
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(relatedData.relatedPlaylistsAndMixes, key = { "rel_pl_${it.browseId}" }) { col ->
                                        val targetId = col.browseId.ifBlank { col.playlistId.orEmpty() }
                                        val isMix = col.type.equals("MIX", true) || targetId.startsWith("RD") || col.title.contains("Mix", true)
                                        Column(
                                            modifier = Modifier
                                                .width(152.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable {
                                                    if (isMix) {
                                                        viewModel.playPlaylistOrMix(targetId)
                                                        onDismiss()
                                                    } else {
                                                        onNavigateToPlaylist(targetId)
                                                    }
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(152.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            ) {
                                                AsyncImage(
                                                    model = InnerTubeResolver.upgradeThumbnailUrl(col.thumbnailUrl).ifBlank { R.drawable.ic_splash_logo },
                                                    contentDescription = col.title,
                                                    placeholder = appLogoPainter,
                                                    error = appLogoPainter,
                                                    fallback = appLogoPainter,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xD9181A1E),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .padding(8.dp)
                                                ) {
                                                    Text(
                                                        text = if (isMix) "MIX" else "PLAYLIST",
                                                        color = Color.White,
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Surface(
                                                    onClick = {
                                                        viewModel.playPlaylistOrMix(targetId)
                                                        onDismiss()
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(8.dp)
                                                        .size(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = "Play",
                                                            tint = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = col.title,
                                                fontFamily = SyneFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = col.subtitle,
                                                fontFamily = OutfitFontFamily,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ────────────────────────────────────────────────────────────
                    // 3. "More from <Artist>" — Albums & Singles Carousel
                    // ────────────────────────────────────────────────────────────
                    if (relatedData.artistAlbumsAndSingles.isNotEmpty()) {
                        item(key = "rel_more_from_artist") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                    Text(
                                        text = "More from ${relatedData.artistName.ifBlank { currentTrack.artist }}",
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Official albums, singles & EPs",
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(relatedData.artistAlbumsAndSingles, key = { "rel_alb_${it.browseId}" }) { rel ->
                                        val isSingle = rel.type.equals("SINGLE", true) || rel.subtitle.contains("Single", true)
                                        Column(
                                            modifier = Modifier
                                                .width(146.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable {
                                                    if (isSingle) {
                                                        viewModel.playSingleRelease(rel, relatedData.artistName.ifBlank { currentTrack.artist })
                                                    } else {
                                                        onNavigateToPlaylist(rel.browseId)
                                                    }
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(146.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            ) {
                                                AsyncImage(
                                                    model = InnerTubeResolver.upgradeThumbnailUrl(rel.thumbnailUrl).ifBlank { R.drawable.ic_splash_logo },
                                                    contentDescription = rel.title,
                                                    placeholder = appLogoPainter,
                                                    error = appLogoPainter,
                                                    fallback = appLogoPainter,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xD9181A1E),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .padding(8.dp)
                                                ) {
                                                    Text(
                                                        text = if (isSingle) "SINGLE" else "ALBUM",
                                                        color = Color.White,
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = rel.title,
                                                fontFamily = SyneFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = rel.subtitle.ifBlank { if (isSingle) "Single" else "Album" },
                                                fontFamily = OutfitFontFamily,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ────────────────────────────────────────────────────────────
                    // 4. "Similar artists" ("Fans might also like")
                    // ────────────────────────────────────────────────────────────
                    if (relatedData.similarArtists.isNotEmpty()) {
                        item(key = "rel_similar_artists") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                    Text(
                                        text = "Similar artists",
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Fans might also like",
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(relatedData.similarArtists, key = { "rel_sim_${it.browseId}" }) { sim ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(100.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable {
                                                    onNavigateToArtist(sim.name, false)
                                                }
                                                .padding(vertical = 4.dp)
                                        ) {
                                            AsyncImage(
                                                model = InnerTubeResolver.upgradeThumbnailUrl(sim.avatarUrl).ifBlank { R.drawable.ic_splash_logo },
                                                contentDescription = sim.name,
                                                placeholder = appLogoPainter,
                                                error = appLogoPainter,
                                                fallback = appLogoPainter,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(84.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = sim.name,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                            if (sim.subscribers.isNotBlank()) {
                                                Text(
                                                    text = sim.subscribers,
                                                    fontFamily = OutfitFontFamily,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ────────────────────────────────────────────────────────────
                    // 5. "About the artist" — Google Pixel Tonal Bio Card
                    // ────────────────────────────────────────────────────────────
                    if (currentTrack.isNavigableArtist && !currentTrack.isLocalOnlyTrack) {
                        item(key = "rel_about_artist") {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .clickable {
                                        onNavigateToArtist(
                                            relatedData.artistName.ifBlank { currentTrack.artist },
                                            playerState.isVideoMode || currentTrack.isVideoTrack
                                        )
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        AsyncImage(
                                            model = InnerTubeResolver.upgradeThumbnailUrl(relatedData.artistAvatarUrl).ifBlank { R.drawable.ic_splash_logo },
                                            contentDescription = relatedData.artistName,
                                            placeholder = appLogoPainter,
                                            error = appLogoPainter,
                                            fallback = appLogoPainter,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "ABOUT THE ARTIST",
                                                fontSize = 10.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.8.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = relatedData.artistName.ifBlank { currentTrack.artist },
                                                fontFamily = SyneFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (relatedData.artistSubscribers.isNotBlank()) {
                                                Text(
                                                    text = relatedData.artistSubscribers,
                                                    fontFamily = OutfitFontFamily,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "View",
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }

                                    if (relatedData.artistDescription.isNotBlank()) {
                                        Text(
                                            text = relatedData.artistDescription,
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 12.5.sp,
                                            lineHeight = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
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

    trackForDownload?.let { dlTrack ->
        com.example.ui.components.TrackDownloadFormatQualitySheet(
            track = dlTrack,
            onDismiss = { trackForDownload = null },
            onConfirmDownload = { chosenTier ->
                viewModel.startDownload(dlTrack, chosenTier)
                trackForDownload = null
            }
        )
    }
}
