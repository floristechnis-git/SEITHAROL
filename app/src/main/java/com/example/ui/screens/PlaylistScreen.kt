package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DownloadTier
import com.example.data.model.Playlist
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

@Composable
fun PlaylistScreen(
    playlistId: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val playlists by viewModel.playlists.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()

    val currentPlaylist = remember(playlistId, playlists) {
        playlists.firstOrNull { it.id == playlistId } ?: Playlist(
            id = playlistId,
            title = if (playlistId.contains("rock", ignoreCase = true)) "Imphal Rock" else "Pena Sessions",
            description = "The best curated tracks every week"
        )
    }

    val playlistTracks = remember(playlistId, allTracks) {
        if (playlistId.contains("rock", ignoreCase = true)) {
            val rock = allTracks.filter { it.album.contains("Rock") || it.genre.contains("Rock") }
            if (rock.isNotEmpty()) rock else allTracks.take(6)
        } else if (playlistId.contains("pena", ignoreCase = true)) {
            val folk = allTracks.filter { it.album.contains("Pena") || it.genre.contains("Folk") || it.genre.contains("Acoustic") }
            if (folk.isNotEmpty()) folk else allTracks.take(6)
        } else {
            allTracks
        }
    }

    var isLiked by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .testTag("playlist_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Hero Header (340dp height with dark gradient)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF5A5A5A), Color(0xFF0D0D0D))
                        )
                    )
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = KangleiGlassDark,
                        onClick = onBack
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // More button
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = KangleiGlassDark,
                        onClick = { /* More actions */ }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Playlist Info & Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentPlaylist.title,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = currentPlaylist.description.ifBlank { "The best tracks every week" },
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = Color(0xFFD0D0D0)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons row: Download (48dp pill), Play (64dp circle), Like (48dp pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        // Download All
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                playlistTracks.forEach { viewModel.startDownload(it, DownloadTier.HIGH) }
                            }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Download",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        // Big Play Button (64dp white circle)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (playlistTracks.isNotEmpty()) viewModel.playQueue(playlistTracks, 0)
                            }
                        ) {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                color = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Playlist",
                                        tint = KangleiDark,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Play",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        // Like Playlist
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { isLiked = !isLiked }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "Like",
                                        tint = if (isLiked) KangleiRedHeart else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isLiked) "Liked" else "Like",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        }

        // Section Title
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Tracks (${playlistTracks.size})",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // Playlist tracks
        itemsIndexed(playlistTracks) { index, track ->
            TrackCard(
                track = track,
                isPlaying = playerState.currentTrack?.id == track.id,
                rankNumber = index + 1,
                onTrackClick = { viewModel.playTrack(track) },
                onLikeClick = { viewModel.toggleLike(track) },
                onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                onOptionsClick = { onAddToPlaylist(track) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
    }
}
