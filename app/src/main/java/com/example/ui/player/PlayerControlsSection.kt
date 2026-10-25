package com.example.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.Track
import com.example.player.PlayerState
import java.util.concurrent.TimeUnit

fun formatDuration(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun PlayerControlsSection(
    playerState: PlayerState,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetSleepTimer: (Int) -> Unit
) {
    // Decouple slider position while user is actively scrubbing
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    val displayedPosition = if (isScrubbing) {
        scrubPositionMs
    } else {
        playerState.currentPositionMs.toFloat()
    }
    val totalDuration = playerState.durationMs.coerceAtLeast(1L).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        // Track Title, Artist & Real Like Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playerState.currentTrack?.title ?: "Not Playing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = playerState.currentTrack?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wave Scrubber Progress Bar
        val rawFrac = displayedPosition.coerceIn(0f, totalDuration) / totalDuration
        val safeFrac = if (rawFrac.isNaN() || rawFrac.isInfinite()) 0f else rawFrac.coerceIn(0f, 1f)
        com.example.ui.screens.SeitharolProProgressBar(
            progress = safeFrac,
            bufferedFraction = playerState.bufferedFraction,
            displayPositionMs = displayedPosition.toLong().coerceAtLeast(0L),
            totalDurationMs = playerState.durationMs.coerceAtLeast(1000L),
            isDragging = isScrubbing,
            isPlaying = playerState.isPlaying && !playerState.isBuffering,
            onValueChange = { frac ->
                isScrubbing = true
                scrubPositionMs = frac * totalDuration
            },
            onValueChangeFinished = {
                onSeekTo(scrubPositionMs.toLong())
                isScrubbing = false
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(displayedPosition.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (playerState.sleepTimerMinutesLeft > 0) {
                Text(
                    text = "Sleep in ${playerState.sleepTimerMinutesLeft}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = formatDuration(playerState.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Primary Transport Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playerState.isShuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp)
                )
            }
            FilledIconButton(
                onClick = onPlayPause,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                modifier = Modifier.size(64.dp)
            ) {
                if (playerState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
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
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = { showSleepTimerSheet = true }) {
                Icon(
                    imageVector = Icons.Default.Bedtime,
                    contentDescription = "Sleep Timer",
                    tint = if (playerState.sleepTimerMinutesLeft > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showSleepTimerSheet) {
        AlertDialog(
            onDismissRequest = { showSleepTimerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Sleep Timer", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    listOf(15, 30, 45, 60, 0).forEach { mins ->
                        TextButton(
                            onClick = {
                                onSetSleepTimer(mins)
                                showSleepTimerSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (mins == 0) "Turn Off Timer" else "$mins Minutes",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}
