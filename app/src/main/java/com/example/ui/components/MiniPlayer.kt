package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.remote.InnerTubeResolver
import com.example.player.PlayerState
import com.example.ui.theme.*

/**
 * YouTube / YouTube Music Floating MiniPlayer Pill:
 * - Height: 60.dp, pill rounded 30.dp, adaptive surfaceVariant container with crisp contrast in Light & Dark themes.
 * - Tapping the artwork/title area expands the Full Player screen (without intercepting Play/Pause/Skip buttons).
 * - Dragging downward past 45% height dismisses and stops playback cleanly, resetting state whenever track.id changes.
 */
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
            .clip(RoundedCornerShape(30.dp))
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
        shape = RoundedCornerShape(30.dp),
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
                        .clickable(onClick = onExpand),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
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

                // Play / Pause Button
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("mini_player_play_pause")
                ) {
                    if (playerState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
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
                trackColor = Color.Transparent
            )
        }
    }
}
