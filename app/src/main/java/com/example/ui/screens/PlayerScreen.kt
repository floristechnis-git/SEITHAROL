package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.player.PlayerState
import com.example.player.RepeatMode
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*

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
    var userSeekingPosition by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val lyricListState = rememberLazyListState()

    // Smooth Vinyl rotation animation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Auto-scroll lyrics
    LaunchedEffect(playerState.activeLyricIndex) {
        if (playerState.activeLyricIndex in 0 until playerState.lyricLines.size) {
            val target = (playerState.activeLyricIndex - 2).coerceAtLeast(0)
            lyricListState.animateScrollToItem(target)
        }
    }

    val displayPosition = (userSeekingPosition?.toLong() ?: playerState.currentPositionMs).coerceAtLeast(0L)
    val totalDuration = playerState.durationMs.coerceAtLeast(1000L)
    val sliderProgress = (displayPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("full_player_screen")
    ) {
        // Top Bar: Down arrow, Source badge, Like button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(KangleiWhite)
                    .testTag("player_collapse_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint = KangleiDark,
                    modifier = Modifier.size(26.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "PLAYING FROM SEITHAROL",
                    color = KangleiTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (track.isDownloaded) "Offline Opus • Direct Audio" else "Online Pure Stream",
                    color = KangleiDark,
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
                    .background(KangleiWhite)
                    .testTag("player_like_btn")
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (track.isLiked) KangleiRedHeart else KangleiDark,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (showLyrics) {
            // Lyrics View Mode
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
                    Text(
                        text = "Synchronized Lyrics",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = KangleiDark
                    )
                    TextButton(onClick = { showLyrics = false }) {
                        Text("Show Artwork", fontFamily = OutfitFontFamily, color = KangleiDark)
                    }
                }

                if (playerState.lyricLines.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No lyrics available for this track", color = KangleiTextSecondary, fontFamily = OutfitFontFamily)
                    }
                } else {
                    LazyColumn(
                        state = lyricListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        itemsIndexed(playerState.lyricLines) { index, line ->
                            val isCurrent = index == playerState.activeLyricIndex
                            Text(
                                text = line.text,
                                color = if (isCurrent) KangleiDark else Color(0xFFA0A0A0),
                                fontSize = if (isCurrent) 20.sp else 16.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable { viewModel.seekTo(line.timestampMs) }
                            )
                        }
                    }
                }
            }
        } else {
            // Center Vinyl Album Art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl Disc
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .shadow(elevation = 20.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF222222),
                                    Color(0xFF0D0D0D),
                                    Color(0xFF333333),
                                    Color(0xFF0D0D0D),
                                    Color(0xFF262626),
                                    Color(0xFF0D0D0D)
                                )
                            )
                        )
                        .rotate(if (playerState.isPlaying) rotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Groove Ring
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(CircleShape)
                            .background(Color(0x1AFFFFFF))
                    )

                    // Center Album Artwork Circle
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = track.artUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Center Spindle Hole
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(KangleiBg)
                    )
                }
            }
        }

        // Track Details
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            Text(
                text = track.title,
                color = KangleiDark,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${track.artist} • ${track.album}",
                color = KangleiTextSecondary,
                fontFamily = OutfitFontFamily,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Secondary Actions Row (EQ, Timer, Share, Lyrics)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenEqualizer) {
                Icon(Icons.Default.Tune, contentDescription = "Equalizer", tint = KangleiDark)
            }
            IconButton(onClick = onOpenSleepTimer) {
                Icon(Icons.Outlined.Timer, contentDescription = "Sleep Timer", tint = KangleiDark)
            }
            IconButton(onClick = { onShareTrack(track.title) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = KangleiDark)
            }
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(
                    imageVector = Icons.Outlined.Subtitles,
                    contentDescription = "Lyrics",
                    tint = if (showLyrics) KangleiDark else KangleiTextSecondary
                )
            }
            IconButton(
                onClick = { showQueue = true },
                modifier = Modifier.testTag("player_queue_button")
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Playback Queue (Up Next)",
                    tint = if (showQueue) KangleiGreen else KangleiDark
                )
            }
        }

        // Scrubber Slider
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Slider(
                value = sliderProgress,
                onValueChange = { frac -> userSeekingPosition = frac * totalDuration },
                onValueChangeFinished = {
                    userSeekingPosition?.let { pos ->
                        viewModel.seekTo(pos.toLong())
                        userSeekingPosition = null
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = KangleiDark,
                    activeTrackColor = KangleiDark,
                    inactiveTrackColor = Color(0xFFDCDCDC)
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
                    color = KangleiTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily
                )
                Text(
                    text = formatMs(totalDuration),
                    color = KangleiTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily
                )
            }
        }

        // Playback Controls Row: Shuffle, Prev, Big Play, Next, Repeat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
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
                    tint = if (playerState.isShuffle) KangleiDark else Color(0xFFAAAAAA)
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
                    tint = KangleiDark,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Big Play / Pause FAB (64dp circle)
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.togglePlayPause() }
                    .testTag("player_main_play_btn"),
                shape = CircleShape,
                color = KangleiPillBg,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playerState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = KangleiPillText,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = KangleiPillText,
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
                    tint = KangleiDark,
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
                    tint = if (playerState.repeatMode != RepeatMode.OFF) KangleiDark else Color(0xFFAAAAAA)
                )
            }
        }

        // Up Next / Queue Card Button
        val nextTrack = if (playerState.currentIndex in 0 until playerState.queue.size - 1) {
            playerState.queue[playerState.currentIndex + 1]
        } else null

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable { showQueue = true }
                .testTag("player_upnext_button"),
            shape = RoundedCornerShape(20.dp),
            color = KangleiWhite,
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
                            .background(if (LocalIsDarkTheme.current) Color(0xFF2A2A2A) else Color(0xFFEEEEEE)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Queue",
                            tint = KangleiDark,
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
                                color = KangleiTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• ${playerState.queue.size} in queue",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = KangleiTextTertiary
                            )
                        }
                        Text(
                            text = nextTrack?.let { "${it.title} • ${it.artist}" } ?: "Tap to view upcoming tracks",
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = KangleiDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (LocalIsDarkTheme.current) Color(0xFF2A2A2A) else Color(0xFFF0F0F0),
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
                            color = KangleiDark
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = KangleiDark,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showQueue) {
        com.example.ui.components.PlaybackQueueModal(
            viewModel = viewModel,
            playerState = playerState,
            onDismiss = { showQueue = false }
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
