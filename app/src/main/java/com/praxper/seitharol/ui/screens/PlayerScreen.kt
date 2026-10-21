package com.praxper.seitharol.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.praxper.seitharol.data.SongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

@Composable
fun PlayerScreen(
    player: Player,
    currentSong: SongItem,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    savedSleepTimerMinutes: Int,
    onSaveSleepTimer: (Int) -> Unit,
    onPlayNextSafe: () -> Unit,
    onPlayPreviousSafe: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxDragPx = with(density) { (configuration.screenHeightDp.dp - 72.dp).toPx() }

    // Smooth vertical drag offset: 0f = Expanded, maxDragPx = Collapsed MiniPlayer
    val offsetY = remember { Animatable(if (isExpanded) 0f else maxDragPx) }

    LaunchedEffect(isExpanded) {
        offsetY.animateTo(
            targetValue = if (isExpanded) 0f else maxDragPx,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    // Playback state & Progress Bar fix (never stuck at 0)
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember(currentSong.id) {
        mutableLongStateOf(currentSong.durationMs.coerceAtLeast(1L))
    }
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekSliderFraction by remember { mutableFloatStateOf(0f) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // Safe Player Listener that prevents app restart/crash on auto-next
    DisposableEffect(player, currentSong.id) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0) {
                    totalDurationMs = dur
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Safely trigger next song without crashing if queue is at the end
                    runCatching { onPlayNextSafe() }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentPositionMs = 0L
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0) {
                    totalDurationMs = dur
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Continuous 250ms polling loop so the progress bar updates smoothly while playing
    LaunchedEffect(player, isPlaying, isUserSeeking, currentSong.id) {
        while (isActive) {
            if (!isUserSeeking) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration
                val validDuration = if (dur != C.TIME_UNSET && dur > 0L) {
                    dur
                } else {
                    currentSong.durationMs.coerceAtLeast(1L)
                }
                totalDurationMs = validDuration
                currentPositionMs = pos.coerceAtMost(validDuration)
                seekSliderFraction = (currentPositionMs.toFloat() / validDuration.toFloat())
                    .coerceIn(0f, 1f)
            }
            delay(250L)
        }
    }

    // Sleep Timer Countdown
    LaunchedEffect(savedSleepTimerMinutes) {
        if (savedSleepTimerMinutes > 0) {
            delay(savedSleepTimerMinutes * 60_000L)
            if (player.isPlaying) {
                player.pause()
            }
            onSaveSleepTimer(0) // Reset to "Don't stop" after firing
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(maxDragPx) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        val shouldExpand = offsetY.value < maxDragPx * 0.45f
                        onExpandedChange(shouldExpand)
                        scope.launch {
                            offsetY.animateTo(
                                targetValue = if (shouldExpand) 0f else maxDragPx,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val next = (offsetY.value + dragAmount).coerceIn(0f, maxDragPx)
                            offsetY.snapTo(next)
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header with Professional SVG Expand/Collapse Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onExpandedChange(!isExpanded) }) {
                    SvgChevronIcon(isExpanded = isExpanded)
                }

                Text(
                    text = "NOW PLAYING",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                IconButton(onClick = { showSleepTimerDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = "Sleep Timer",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Artwork
            AsyncImage(
                model = currentSong.thumbnailUrl,
                contentDescription = currentSong.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1B1C1C))
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Song Title & Artist
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentSong.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentSong.artist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Pure White Progress Slider
            Slider(
                value = seekSliderFraction,
                onValueChange = { fraction ->
                    isUserSeeking = true
                    seekSliderFraction = fraction
                    currentPositionMs = (fraction * totalDurationMs).toLong()
                },
                onValueChangeFinished = {
                    player.seekTo((seekSliderFraction * totalDurationMs).toLong())
                    isUserSeeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentPositionMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = formatDuration(totalDurationMs),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Playback Controls (Pure White)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { runCatching { onPlayPreviousSafe() } }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                FilledIconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = { runCatching { onPlayNextSafe() } }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }

    // Sleep Timer Dialog with working "Don't stop" & Save
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentMinutes = savedSleepTimerMinutes,
            onDismiss = { showSleepTimerDialog = false },
            onSave = { selectedMinutes ->
                onSaveSleepTimer(selectedMinutes)
                showSleepTimerDialog = false
            }
        )
    }
}

@Composable
fun SleepTimerDialog(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    val options = listOf(
        0 to "Don't stop",
        15 to "15 minutes",
        30 to "30 minutes",
        45 to "45 minutes",
        60 to "60 minutes"
    )
    var selected by remember { mutableIntStateOf(currentMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1C1C),
        title = { Text("Sleep Timer", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (minutes, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = minutes }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selected == minutes),
                            onClick = { selected = minutes },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color.White,
                                unselectedColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selected) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        }
    )
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
