package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.seitharol.ui.components.FullscreenExpandSvgIcon
import com.seitharol.ui.components.InstrumentalGapMusicNoteIndicator
import coil.compose.AsyncImage
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.player.RepeatMode
import com.example.ui.CascadeViewModel
import com.example.ui.components.PlaybackQueueModal
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SvgChevronIcon(
    isExpanded: Boolean,
    modifier: Modifier = Modifier.size(26.dp),
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (isExpanded) {
                // Downward collapse chevron
                moveTo(w * 0.22f, h * 0.36f)
                lineTo(w * 0.50f, h * 0.64f)
                lineTo(w * 0.78f, h * 0.36f)
            } else {
                // Upward expand chevron
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
 * YouTube Music & YouTube mobile app inspired Full Player Screen:
 * 1. Holding/dragging down from the top area smoothly minimizes the Player into MiniPlayer.
 * 2. Lyrics / Artwork Toggle Swap: "Show Artwork" toggles back to album cover;
 *    Fullscreen Expand SVG Maximize button opens the immersive Fullscreen Expanded Lyrics view.
 * 3. Fullscreen Expanded Lyrics view with top bar (artwork, title, artist, "><" minimize button),
 *    time-synced scrolling lyrics with active-line glow and scale, and bottom playback bar.
 * 4. Dark theme palette: #1B1C1C background, #232526 elevated surfaces, #E3E4E2 primary text, #FFB74D accent.
 */
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
    var isUserDraggingSlider by remember { mutableStateOf(false) }
    var userSeekingPosition by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var showExpandedLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Conditional "Up Next" bottom bar:
    // If played as a single track, hide until user clicks Queue button at least once.
    // Can be swiped down to hide, and reappears when clicking Queue button.
    var hasClickedQueueButton by remember(track.id, playerState.isSingleTrackPlayback) {
        mutableStateOf(!playerState.isSingleTrackPlayback)
    }
    var isUpNextVisible by remember(track.id, playerState.isSingleTrackPlayback) {
        mutableStateOf(!playerState.isSingleTrackPlayback)
    }

    val inlineLyricListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

    // Smooth auto-scroll inline lyrics with 2500ms idle debounce on user touch/drag
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
        if (showLyrics && !isUserScrollingInlineLyrics && playerState.activeLyricIndex in 0 until playerState.lyricLines.size) {
            val target = (playerState.activeLyricIndex - 2).coerceAtLeast(0)
            inlineLyricListState.animateScrollToItem(target)
        }
    }

    val displayPosition = (if (isUserDraggingSlider) userSeekingPosition?.toLong() else null)
        ?: playerState.currentPositionMs.coerceAtLeast(0L)
    val totalDuration = if (playerState.durationMs > 0L) playerState.durationMs else (track.durationMs.takeIf { it > 0 } ?: 210000L)
    val sliderProgress = (displayPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val isLiked = track.id in likedTrackIds || track.isLiked

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragOffsetY.value.coerceAtLeast(0f)
                alpha = (1f - (dragOffsetY.value / 1200f)).coerceIn(0.2f, 1f)
            }
            .background(Color(0xFF0F0F0F)) // Pure Dark theme background
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("full_player_screen")
    ) {
        // ────────────────────────────────────────────────────────────
        // Top Area with Smooth Spring Drag Gesture to Minimize
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
                    .background(Color(0xFF282A2A))
                    .testTag("player_collapse_btn")
            ) {
                SvgChevronIcon(isExpanded = true, color = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "PLAYING FROM SEITHAROL",
                    color = Color(0xFF9EA1A1),
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (track.isDownloaded) "Offline Opus • Direct Audio" else "Online Pure Stream",
                    color = Color.White,
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
                    .background(Color(0xFF282A2A))
                    .testTag("player_like_btn")
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) Color(0xFFFF5252) else Color(0xFFE3E4E2),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Middle Area: Album Cover Artwork OR Inline Lyrics View
        // ────────────────────────────────────────────────────────────
        if (showLyrics) {
            // Inline Lyrics Mode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // Header with "Show Artwork" (left) & "<>" Maximize button (right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Replaced "Synchronized Lyrics" button text with "Show Artwork"
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF282A2A),
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
                                tint = Color(0xFFE3E4E2),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Show Artwork",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFFE3E4E2)
                            )
                        }
                    }

                    // Fullscreen Expand SVG Maximize Button (opens full-screen Expanded Lyrics)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF282A2A),
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
                                tint = Color(0xFFE3E4E2),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Fullscreen",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFFE3E4E2)
                            )
                        }
                    }
                }

                if (playerState.lyricLines.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No lyrics available for this track",
                            color = Color(0xFF9EA1A1),
                            fontFamily = OutfitFontFamily,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = inlineLyricListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)
                        ) {
                            itemsIndexed(playerState.lyricLines) { index, line ->
                                val isCurrent = index == playerState.activeLyricIndex
                                if (line.isInstrumental) {
                                    // Instrumental gap — show animated pulsing Music Note SVG indicator
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        InstrumentalGapMusicNoteIndicator(
                                            tint = if (isCurrent) Color.White else Color(0xFF555555),
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
                                            color = if (isCurrent) Color.White else Color(0xFF757575),
                                            fontSize = if (isCurrent) 20.sp else 16.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) Color(0xFFB0B0B0) else Color(0xFF505050),
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

                        // Top Cloudy Gradient Mask (soft atmospheric fog)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF0F0F0F),
                                            Color(0xFF0F0F0F).copy(alpha = 0.8f),
                                            Color(0xFF0F0F0F).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // Bottom Cloudy Gradient Mask (soft atmospheric fog)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color(0xFF0F0F0F).copy(alpha = 0.25f),
                                            Color(0xFF0F0F0F).copy(alpha = 0.8f),
                                            Color(0xFF0F0F0F)
                                        )
                                    )
                                )
                        )
                    }
                }
            }
        } else {
            // High-Resolution Rounded Album Artwork Container (No circle div!)
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
                    color = Color(0xFF232526)
                ) {
                    AsyncImage(
                        model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // ────────────────────────────────────────────────────────────
        // Track Title & Artist Details
        // ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Text(
                text = track.title,
                color = Color(0xFFE3E4E2),
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            // Artist name is clickable — isolated to artist only (not album)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = track.artist,
                    color = Color(0xFF9EA1A1),
                    fontFamily = OutfitFontFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (track.artist.isNotBlank()) {
                                onDismiss()
                                viewModel.navigateToArtist(track.artist)
                            }
                        }
                        .testTag("player_artist_name")
                )
                if (track.album.isNotBlank() && track.album != track.title && track.album != "Single") {
                    Text(
                        text = "• ${track.album}",
                        color = Color(0xFF757575),
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ────────────────────────────────────────────────────────────
        // Secondary Actions Row (EQ, Timer, Download, Share, Lyrics, Queue)
        // ────────────────────────────────────────────────────────────
        val downloads by viewModel.activeDownloads.collectAsState()
        val trackDownload = downloads.firstOrNull { it.trackId == track.id }
        val isDownloaded = track.isDownloaded || trackDownload?.state == com.example.data.model.DownloadState.COMPLETED
        val isDownloading = trackDownload?.state == com.example.data.model.DownloadState.DOWNLOADING || trackDownload?.state == com.example.data.model.DownloadState.FINALIZING
        val downloadProgress = trackDownload?.progress ?: 0f

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenEqualizer) {
                Icon(Icons.Default.Tune, contentDescription = "Equalizer", tint = Color(0xFFE3E4E2))
            }
            IconButton(onClick = onOpenSleepTimer) {
                Icon(Icons.Outlined.Timer, contentDescription = "Sleep Timer", tint = Color(0xFFE3E4E2))
            }

            // Real Download Button
            IconButton(
                onClick = {
                    if (!isDownloaded && !isDownloading) {
                        viewModel.startDownload(track, com.example.data.model.DownloadTier.STANDARD)
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
                                color = Color.White,
                                trackColor = Color(0xFF282A2A)
                            )
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3E4E2),
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download track",
                            tint = Color(0xFFE3E4E2),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            IconButton(onClick = { onShareTrack(track.title) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color(0xFFE3E4E2))
            }
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(
                    imageVector = Icons.Outlined.Subtitles,
                    contentDescription = "Lyrics",
                    tint = if (showLyrics) Color.White else Color(0xFF9EA1A1)
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
                    tint = if (showQueue) Color.White else Color(0xFFE3E4E2)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Scrubber Progress Slider
        // ────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Slider(
                value = sliderProgress,
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
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.testTag("player_scrubber")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(displayPosition),
                    color = Color(0xFF9EA1A1),
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily
                )
                Text(
                    text = formatMs(totalDuration),
                    color = Color(0xFF9EA1A1),
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Playback Controls Row: Shuffle, Prev, Big Play, Next, Repeat
        // ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Shuffle
            IconButton(
                onClick = { viewModel.toggleShuffle() },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playerState.isShuffle) Color.White else Color(0xFF888888)
                )
            }

            // Previous
            IconButton(
                onClick = { viewModel.previous() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color(0xFFE3E4E2),
                    modifier = Modifier.size(32.dp)
                )
            }

            // Big Play / Pause FAB (64.dp circle)
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.togglePlayPause() }
                    .testTag("player_main_play_btn"),
                shape = CircleShape,
                color = Color(0xFFE3E4E2),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playerState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color(0xFF1B1C1C),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color(0xFF1B1C1C),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            // Next
            IconButton(
                onClick = { viewModel.next() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color(0xFFE3E4E2),
                    modifier = Modifier.size(32.dp)
                )
            }

            // Repeat Mode
            IconButton(
                onClick = { viewModel.cycleRepeatMode() },
                modifier = Modifier.size(44.dp)
            ) {
                val icon = when (playerState.repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Repeat",
                    tint = if (playerState.repeatMode != RepeatMode.OFF) Color.White else Color(0xFF888888)
                )
            }
        }

        // ────────────────────────────────────────────────────────────
        // Up Next / Queue Card Button
        // ────────────────────────────────────────────────────────────
        val nextTrack = if (playerState.currentIndex in 0 until playerState.queue.size - 1) {
            playerState.queue[playerState.currentIndex + 1]
        } else null

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
                color = Color(0xFF232526),
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
                                .background(Color(0xFF2E3030)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Queue",
                                tint = Color.White,
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
                                    color = Color(0xFF9EA1A1),
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• ${playerState.queue.size} in queue",
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = Color(0xFF757575)
                                )
                            }
                            Text(
                                text = nextTrack?.let { "${it.title} • ${it.artist}" } ?: "Tap to view upcoming tracks",
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE3E4E2),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF282A2A),
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
                                color = Color(0xFFE3E4E2)
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = Color(0xFFE3E4E2),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    // ────────────────────────────────────────────────────────────
    // Playback Queue Modal
    // ────────────────────────────────────────────────────────────
    if (showQueue) {
        PlaybackQueueModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showQueue = false }
        )
    }

    // ────────────────────────────────────────────────────────────
    // Fullscreen Expanded Lyrics Modal ("<>" Maximize Mode)
    // ────────────────────────────────────────────────────────────
    if (showExpandedLyrics) {
        ExpandedLyricsModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showExpandedLyrics = false }
        )
    }
}

/**
 * Fullscreen Immersive Expanded Lyrics View ("<>" View):
 * - Top bar: song artwork, title, artist, and "><" minimize button to return to PlayerScreen.
 * - Time-synced scrolling lyrics with active-line glow and 1.08x scale.
 * - Bottom playback bar with seekable slider and playback controls (Prev, Play/Pause, Next).
 */
@Composable
fun ExpandedLyricsModal(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onDismiss: () -> Unit
) {
    val track = playerState.currentTrack ?: return
    val lyricListState = rememberLazyListState()
    var userSeekingPosition by remember { mutableStateOf<Float?>(null) }
    var isUserDraggingSlider by remember { mutableStateOf(false) }

    val displayPosition = (userSeekingPosition?.toLong() ?: playerState.currentPositionMs).coerceAtLeast(0L)
    val totalDuration = playerState.durationMs.coerceAtLeast(1000L)
    val sliderProgress = (displayPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    // Smooth auto-scroll expanded lyrics with 2500ms idle debounce on user drag
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
        if (!isUserScrollingExpandedLyrics && playerState.activeLyricIndex in 0 until playerState.lyricLines.size) {
            val target = (playerState.activeLyricIndex - 3).coerceAtLeast(0)
            lyricListState.animateScrollToItem(target)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF141414)),
            color = Color(0xFF141414)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ────────────────────────────────────────────────────────────
                // Top Bar: Artwork, Title, Artist, & "><" Minimize Button
                // ────────────────────────────────────────────────────────────
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
                                .background(Color(0xFF282A2A))
                        ) {
                            AsyncImage(
                                model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = Color(0xFFE3E4E2),
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = Color(0xFF9EA1A1),
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Minimize Button (CloseFullscreen)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF282A2A),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onDismiss)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloseFullscreen,
                                contentDescription = "Minimize Lyrics",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // Time-Synced Scrolling Lyrics with Active-Line Glow & Scale
                // ────────────────────────────────────────────────────────────
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
                                color = Color(0xFF9EA1A1),
                                fontFamily = OutfitFontFamily,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = lyricListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 40.dp, bottom = 80.dp)
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
                                            tint = if (isCurrent) Color.White else Color(0xFF555555),
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
                                            color = if (isCurrent) Color.White else Color(0xFF6B6E6E),
                                            fontSize = if (isCurrent) 24.sp else 18.sp,
                                            fontFamily = SyneFontFamily,
                                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                            lineHeight = if (isCurrent) 32.sp else 26.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (!line.translation.isNullOrBlank()) {
                                            Text(
                                                text = line.translation,
                                                color = if (isCurrent) Color(0xFFB0B0B0) else Color(0xFF454848),
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

                        // Top Cloudy Gradient Mask (soft atmospheric fog)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF141414),
                                            Color(0xFF141414).copy(alpha = 0.8f),
                                            Color(0xFF141414).copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // Bottom Cloudy Gradient Mask (soft atmospheric fog)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color(0xFF141414).copy(alpha = 0.25f),
                                            Color(0xFF141414).copy(alpha = 0.8f),
                                            Color(0xFF141414)
                                        )
                                    )
                                )
                        )
                    }
                }

                // ────────────────────────────────────────────────────────────
                // Bottom Playback Bar (Curved Aesthetic Container with Top Border Glow)
                // ────────────────────────────────────────────────────────────
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = Color(0xFF1B1D1D),
                    tonalElevation = 8.dp,
                    shadowElevation = 24.dp,
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color.White.copy(alpha = 0.02f)
                            )
                        )
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF222425),
                                        Color(0xFF151617)
                                    )
                                )
                            )
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .navigationBarsPadding()
                    ) {
                        Slider(
                            value = sliderProgress,
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
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatMs(displayPosition),
                                color = Color(0xFF9EA1A1),
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily
                            )
                            Text(
                                text = formatMs(totalDuration),
                                color = Color(0xFF9EA1A1),
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

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
                                    tint = Color(0xFFE3E4E2),
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .clickable { viewModel.togglePlayPause() },
                                shape = CircleShape,
                                color = Color(0xFFE3E4E2)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (playerState.isBuffering) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color(0xFF1B1C1C),
                                            strokeWidth = 2.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = Color(0xFF1B1C1C),
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
                                    tint = Color(0xFFE3E4E2),
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
