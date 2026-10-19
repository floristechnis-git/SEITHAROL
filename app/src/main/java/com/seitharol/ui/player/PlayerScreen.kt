package com.seitharol.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.domain.lyrics.SyncedLyricLine
import com.seitharol.ui.components.FullscreenExpandSvgIcon
import com.seitharol.ui.components.InstrumentalGapMusicNoteIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlaybackSourceType {
    SINGLE_TRACK, // Played as a standalone single song (Search, Quick Pick)
    GROUP_CONTEXT // Played from Mix, Album, or Playlist
}

@Composable
fun PlayerScreen(
    exoPlayer: ExoPlayer,
    currentTrack: CandidateStream,
    sourceType: PlaybackSourceType,
    upNextQueue: List<CandidateStream>,
    syncedLyrics: List<SyncedLyricLine>,
    onArtistClick: (artistName: String, artistBrowseId: String?) -> Unit,
    onCollapsePlayer: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxDismissOffsetPx = with(density) { 420.dp.toPx() }
    val dragOffsetY = remember { Animatable(0f) }

    // Progress Bar State (works smoothly both while playing AND while paused)
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPositionMs by remember { mutableLongStateOf(0L) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember {
        mutableLongStateOf((currentTrack.durationSeconds * 1000L).coerceAtLeast(1L))
    }
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }

    // Up Next Visibility Rules:
    // - If played from a group (Mix/Album/Playlist), visible by default.
    // - If played as a SINGLE song, hidden by default until user clicks the Playback Queue button at least once.
    // - Can be swiped down to hide, and reappears when clicking the Playback Queue button.
    var hasClickedQueueButton by remember(currentTrack.videoId, sourceType) {
        mutableStateOf(sourceType == PlaybackSourceType.GROUP_CONTEXT)
    }
    var isUpNextVisible by remember(currentTrack.videoId, sourceType) {
        mutableStateOf(sourceType == PlaybackSourceType.GROUP_CONTEXT)
    }
    var isLyricsFullscreen by remember { mutableStateOf(false) }

    // Continuous 200ms Progress Sync
    LaunchedEffect(exoPlayer, currentTrack.videoId) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            val playerDur = exoPlayer.duration
            durationMs = if (playerDur > 0 && playerDur != androidx.media3.common.C.TIME_UNSET) {
                playerDur
            } else {
                (currentTrack.durationSeconds * 1000L).coerceAtLeast(1L)
            }
            if (!isDraggingSlider) {
                currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                sliderPositionMs = currentPositionMs
            }
            delay(200L)
        }
    }

    val activeLyric = remember(currentPositionMs, syncedLyrics) {
        syncedLyrics.lastOrNull { currentPositionMs >= it.timestampMs }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragOffsetY.value > maxDismissOffsetPx * 0.35f) {
                                dragOffsetY.animateTo(
                                    targetValue = maxDismissOffsetPx,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                                onCollapsePlayer()
                                dragOffsetY.snapTo(0f)
                            } else {
                                dragOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val next = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                        scope.launch { dragOffsetY.snapTo(next) }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Track Title & Isolated Artist Name Click
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = currentTrack.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Isolated click handler: opens ArtistProfileScreen ONLY, never calls playTrack() or seekToNext()
                Text(
                    text = currentTrack.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple()
                        ) {
                            onArtistClick(currentTrack.artist, currentTrack.artistBrowseId)
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
            }

            // Seek Slider (Works even when song is PAUSED)
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = (sliderPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        isDraggingSlider = true
                        sliderPositionMs = (fraction * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        // Seek works immediately whether playing or paused
                        exoPlayer.seekTo(sliderPositionMs)
                        currentPositionMs = sliderPositionMs
                        isDraggingSlider = false
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatMillis(sliderPositionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatMillis(durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Playback Controls + Queue Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { exoPlayer.seekToPrevious() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_skip_previous),
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        isPlaying = exoPlayer.isPlaying
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { exoPlayer.seekToNext() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_skip_next),
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
                // Playback Queue Button: reveals Up Next bar even for single tracks
                TextButton(
                    onClick = {
                        hasClickedQueueButton = true
                        isUpNextVisible = true
                    }
                ) {
                    Text("Queue (${upNextQueue.size})")
                }
            }

            // Lyrics Card with Diagonal Expand SVG & Instrumental Note Indicator
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lyrics",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        IconButton(onClick = { isLyricsFullscreen = !isLyricsFullscreen }) {
                            FullscreenExpandSvgIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    if (activeLyric == null || activeLyric.isInstrumentalGap) {
                        InstrumentalGapMusicNoteIndicator(tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            text = activeLyric.primaryText,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        activeLyric.translationText?.let { translated ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = translated,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Conditional "Up Next" Bottom Bar (Swipe down to hide, click Queue button to show)
            AnimatedVisibility(
                visible = isUpNextVisible && hasClickedQueueButton,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 18f) {
                                    // Swipe down hides the Up Next bar until Queue button is clicked again
                                    isUpNextVisible = false
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Up Next",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val nextSong = upNextQueue.firstOrNull()
                            Text(
                                text = if (nextSong != null) "${nextSong.title} • ${nextSong.artist}" else "Automix Radio Ready",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Swipe down to hide",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
