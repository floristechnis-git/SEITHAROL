package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

@Composable
fun ArtistScreen(
    artistName: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onShareArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val allTracks by viewModel.allTracks.collectAsState()
    var isFollowing by remember { mutableStateOf(false) }

    // Artist songs or filtered
    val artistTracks = remember(artistName, allTracks) {
        val filtered = allTracks.filter { it.artist.contains(artistName, ignoreCase = true) }
        if (filtered.isNotEmpty()) filtered else allTracks
    }

    val playCounts = listOf(
        "16,511,874",
        "599,211,082",
        "405,207,652",
        "34,821,001",
        "28,140,990",
        "19,420,110",
        "12,850,340"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .testTag("artist_screen"),
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
                    // Back button (44dp pill rgba(255,255,255,0.18))
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
                        onClick = { onShareArtist(artistName) }
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

                // Artist Info & Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = artistName,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "13,994 monthly listeners",
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = Color(0xFFD0D0D0)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons row: Share (48dp pill), Play (64dp circle), Like (48dp pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        // Share
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onShareArtist(artistName) }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Share",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        // Big Play Button (64dp white circle)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (artistTracks.isNotEmpty()) viewModel.playQueue(artistTracks, 0)
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
                                        contentDescription = "Play Artist",
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

                        // Like
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { isFollowing = !isFollowing }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isFollowing) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "Like",
                                        tint = if (isFollowing) KangleiRedHeart else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFollowing) "Liked" else "Like",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        }

        // Popular Section Title
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Popular",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // Numbered track list
        itemsIndexed(artistTracks) { index, track ->
            val plays = playCounts.getOrElse(index) { "12,410,200" }
            TrackCard(
                track = track,
                isPlaying = playerState.currentTrack?.id == track.id,
                rankNumber = index + 1,
                subtitleText = plays,
                onTrackClick = { viewModel.playTrack(track) },
                onLikeClick = { viewModel.toggleLike(track) },
                onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
    }
}
