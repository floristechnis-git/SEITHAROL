package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

enum class LibraryTab(val label: String) {
    PLAYLISTS("Playlists"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    DOWNLOADED("Downloaded")
}

@Composable
fun LibraryScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()

    var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }

    val albums = remember(allTracks) {
        allTracks.groupBy { it.album }.map { (album, tracks) ->
            Triple(album, tracks.first().artist, tracks.first().artUrl)
        }
    }

    val artists = remember(allTracks) {
        listOf(
            Pair("Tapta", "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&auto=format&fit=crop&q=80"),
            Pair("Ronnie", "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400&auto=format&fit=crop&q=80"),
            Pair("Kanglei Folk Project", "https://images.unsplash.com/photo-1492562080023-ab3db95bfbce?w=400&auto=format&fit=crop&q=80"),
            Pair("Eastern Rhythms", "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=400&auto=format&fit=crop&q=80"),
            Pair("Imphal Rock Collective", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&auto=format&fit=crop&q=80")
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("library_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Heading & Action Row: "Your library" + "New" button
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your library",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    color = KangleiDark
                )

                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onCreatePlaylist)
                        .testTag("library_create_playlist_btn"),
                    shape = RoundedCornerShape(18.dp),
                    color = KangleiDark
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Filter Pills: Playlists, Albums, Artists, Downloaded
        item(span = { GridItemSpan(2) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(LibraryTab.values()) { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedTab = tab },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) KangleiDark else KangleiWhite
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = tab.label,
                                color = if (isSelected) Color.White else KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            LibraryTab.PLAYLISTS -> {
                // Liked Songs Card: Black square card matching Kanglei Tune design
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (likedTracks.isNotEmpty()) viewModel.playQueue(likedTracks, 0)
                            }
                            .testTag("library_liked_songs_card"),
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(KangleiDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Liked",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Liked Songs",
                                        color = KangleiDark,
                                        fontSize = 14.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${likedTracks.size.coerceAtLeast(68)} songs",
                                        color = KangleiTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                }

                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = Color(0xFFF2F2F2),
                                    onClick = {
                                        if (likedTracks.isNotEmpty()) viewModel.playQueue(likedTracks, 0)
                                    }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = KangleiDark,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Other Playlists
                items(playlists) { pl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onNavigateToPlaylist(pl.id) }
                            .testTag("library_playlist_${pl.id}"),
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFE0E0E0)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = pl.coverUrl,
                                    contentDescription = pl.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.title,
                                        color = KangleiDark,
                                        fontSize = 14.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${pl.trackCount.coerceAtLeast(18)} songs",
                                        color = KangleiTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                }

                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = Color(0xFFF2F2F2),
                                    onClick = { onNavigateToPlaylist(pl.id) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = KangleiDark,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.ALBUMS -> {
                items(albums) { (album, artist, artUrl) ->

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                val albumTracks = allTracks.filter { it.album == album }
                                if (albumTracks.isNotEmpty()) viewModel.playQueue(albumTracks, 0)
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFE0E0E0))
                            ) {
                                AsyncImage(
                                    model = artUrl,
                                    contentDescription = album,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = album,
                                color = KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                text = artist,
                                color = KangleiTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            LibraryTab.ARTISTS -> {
                items(artists) { (name, img) ->

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToArtist(name) }
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE0E0E0))
                        ) {
                            AsyncImage(
                                model = img,
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = name,
                            color = KangleiDark,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            LibraryTab.DOWNLOADED -> {
                item(span = { GridItemSpan(2) }) {
                    Column {
                        Text(
                            text = "${downloadedTracks.size} Offline Tracks",
                            color = KangleiTextSecondary,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                items(downloadedTracks, span = { GridItemSpan(2) }) { track ->
                    TrackCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        onTrackClick = { viewModel.playTrack(track) },
                        onLikeClick = { viewModel.toggleLike(track) },
                        onDownloadClick = { tier -> viewModel.startDownload(track, tier) }
                    )
                }
            }
        }
    }
}
