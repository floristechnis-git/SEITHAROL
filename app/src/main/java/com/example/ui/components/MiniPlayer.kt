package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*
import com.seitharol.ui.components.UniversalMediaThumbnail
import kotlin.math.roundToInt

/**
 * YouTube / YouTube Music Floating MiniPlayer Pill:
 * - Height: 60.dp, pill rounded 22.dp, adaptive surfaceVariant container with crisp contrast in Light & Dark themes.
 * - Uses smooth basicMarquee on the song title so long song names are always readable in full.
 * - Uses UniversalMediaThumbnail so local MP4 video files show their real video frame preface/thumbnail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    playerState: PlayerState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismissPlayback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack ?: return
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val progress = if (playerState.durationMs > 0) {
        (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val density = LocalDensity.current
    val miniHeight = 60.dp
    val miniHeightPx = with(density) { miniHeight.toPx() }

    // Keyed by track.id so dismissing a track and starting a new song always resets the MiniPlayer visibility
    var dragOffsetY by remember(track.id) { mutableFloatStateOf(0f) }
    var isDismissed by remember(track.id) { mutableStateOf(false) }

    val animatedOffsetY by animateFloatAsState(
        targetValue = if (isDismissed) miniHeightPx * 2.5f else dragOffsetY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "mini_player_dismiss"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(miniHeight)
            .graphicsLayer {
                translationY = animatedOffsetY.coerceAtLeast(0f)
                alpha = if (isDismissed) 0f else (1f - (animatedOffsetY / (miniHeightPx * 2f))).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(22.dp))
            .pointerInput(track.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetY >= miniHeightPx * 0.45f) {
                            isDismissed = true
                            onDismissPlayback()
                        } else {
                            dragOffsetY = 0f
                        }
                    },
                    onDragCancel = {
                        dragOffsetY = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        val nextOffset = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        if (nextOffset > 0f) {
                            change.consume()
                            dragOffsetY = nextOffset
                        }
                    }
                )
            }
            .testTag("mini_player_bar"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 10.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Artwork + Title/Artist area to expand Full Player without stealing IconButton taps
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onExpand()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                        UniversalMediaThumbnail(
                            artUrl = upgradedArt,
                            localPath = track.localPath,
                            isVideo = track.isVideoTrack || playerState.isVideoMode,
                            contentDescription = "Cover for ${track.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = track.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 4000,
                                    repeatDelayMillis = 4000,
                                    velocity = 28.dp
                                )
                        )
                        Text(
                            text = track.artist,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 4000,
                                    repeatDelayMillis = 4000,
                                    velocity = 26.dp
                                )
                        )
                    }
                }

                // Previous
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("mini_player_prev")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play / Pause Button (Square with border radius)
                Surface(
                    onClick = onTogglePlay,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("mini_player_play_pause")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playerState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    playerState.isPlaying -> Icons.Default.Pause
                                    playerState.isEnded -> Icons.Default.Replay
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = when {
                                    playerState.isPlaying -> "Pause"
                                    playerState.isEnded -> "Replay"
                                    else -> "Play"
                                },
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Next Button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("mini_player_next")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Subtle bottom progress line
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

/**
 * Default Floating & Adjustable Picture-in-Picture (PiP) Window for Minimized Videos:
 * - Automatically displays when any video (Music Video, Movie/TV Series, Local MP4, or pasted YouTube Video) is minimized.
 * - Freely draggable anywhere on the screen and adjustable in size via pinch-to-zoom OR the quick-resize toggle button
 *   (cycles through Compact 176dp -> Medium 228dp -> Large 288dp, or any custom pinched width in 150dp..320dp).
 */
@Composable
fun SeitharolFloatingVideoPiP(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onExpandFullPlayer: () -> Unit,
    onEnterSystemPiP: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack ?: return
    val isVideo = playerState.isVideoMode ||
        track.isVideoTrack ||
        track.isMovieTrack ||
        track.genre.equals("Music Video", ignoreCase = true) ||
        track.genre.equals("Video", ignoreCase = true) ||
        track.genre.equals("Local Video", ignoreCase = true) ||
        track.album.equals("YouTube Video", ignoreCase = true) ||
        track.album.equals("MP4 Video", ignoreCase = true)
    if (!isVideo) return

    var isPipHiddenForTrack by remember(track.id) { mutableStateOf(false) }
    if (isPipHiddenForTrack) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthDp = configuration.screenWidthDp.toFloat().coerceAtLeast(320f)
    val screenHeightDp = configuration.screenHeightDp.toFloat().coerceAtLeast(540f)

    // Adjustable width in dp (supports both preset cycle button and smooth pinch-to-resize)
    var pipWidthDp by remember { mutableFloatStateOf(216f) }
    val pipHeightDp = (pipWidthDp * 9f / 16f).coerceIn(84f, 195f)

    val maxOffsetXPx = with(density) { (screenWidthDp - pipWidthDp - 16f).coerceAtLeast(12f).dp.toPx() }
    val maxOffsetYPx = with(density) { (screenHeightDp - pipHeightDp - 175f).coerceAtLeast(48f).dp.toPx() }
    val minOffsetXPx = with(density) { 12.dp.toPx() }
    val minOffsetYPx = with(density) { 56.dp.toPx() }

    var offsetXPx by remember(screenWidthDp) { mutableFloatStateOf(maxOffsetXPx) }
    var offsetYPx by remember(screenHeightDp) { mutableFloatStateOf((maxOffsetYPx * 0.72f).coerceIn(minOffsetYPx, maxOffsetYPx)) }
    var showOverlayControls by remember { mutableStateOf(true) }

    val activeCaptionText = remember(
        playerState.captionsEnabled,
        playerState.videoCaptions,
        playerState.currentPositionMs
    ) {
        if (!playerState.captionsEnabled || playerState.videoCaptions.isEmpty()) {
            null
        } else {
            val pos = playerState.currentPositionMs
            playerState.videoCaptions.firstOrNull { cue ->
                pos >= cue.startTimeMs && pos <= cue.endTimeMs
            }?.text
        }
    }

    val progress = if (playerState.durationMs > 0) {
        (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    offsetXPx.coerceIn(minOffsetXPx, maxOffsetXPx).roundToInt(),
                    offsetYPx.coerceIn(minOffsetYPx, maxOffsetYPx).roundToInt()
                )
            }
            .width(pipWidthDp.dp)
            .height(pipHeightDp.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (kotlin.math.abs(zoom - 1f) > 0.01f) {
                        pipWidthDp = (pipWidthDp * zoom).coerceIn(150f, (screenWidthDp - 24f).coerceAtMost(320f))
                    }
                    offsetXPx = (offsetXPx + pan.x).coerceIn(minOffsetXPx, maxOffsetXPx)
                    offsetYPx = (offsetYPx + pan.y).coerceIn(minOffsetYPx, maxOffsetYPx)
                }
            }
            .clickable {
                showOverlayControls = !showOverlayControls
            }
            .testTag("floating_video_pip")
    ) {
        // Live hardware TextureView video surface
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    viewModel.attachVideoTextureView(this)
                }
            },
            update = { tv ->
                viewModel.attachVideoTextureView(tv)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Thumbnail fallback while buffering
        if (!playerState.isVideoSurfaceReady) {
            val upgradedArt = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
            UniversalMediaThumbnail(
                artUrl = upgradedArt,
                localPath = track.localPath,
                isVideo = true,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Active caption cue inside floating PiP
        if (!activeCaptionText.isNullOrBlank()) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(
                    text = activeCaptionText,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        if (showOverlayControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
            ) {
                // Top Row: Expand to Full Player, Resize Preset Button, System PiP, and Minimize/Hide PiP
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            onClick = onExpandFullPlayer,
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.OpenInFull,
                                    contentDescription = "Expand Video Player",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                pipWidthDp = when {
                                    pipWidthDp < 195f -> 228f
                                    pipWidthDp < 255f -> 284f
                                    else -> 172f
                                }
                            },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Resize Floating PiP",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            onClick = onEnterSystemPiP,
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "System Picture in Picture",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Surface(
                            onClick = { isPipHiddenForTrack = true },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hide Floating Video",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Center Row: Play/Pause & Next
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { viewModel.togglePlayPause() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (playerState.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Surface(
                        onClick = { viewModel.next() },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.2f)
        )
    }
}
