package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.FreshMusicCard
import com.example.ui.components.HitCard
import com.example.ui.components.SuggestedArtistItem
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onNavigateToSearch: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allTracks by viewModel.allTracks.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()
    val freshReleases by viewModel.freshReleases.collectAsState()
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val selectedMood by viewModel.selectedMood.collectAsState()
    val isLoadingHome by viewModel.isLoadingHome.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    // Priority for Today's Biggest Hits: use real YouTube trendingHits if available, fallback to local
    val displayHits = remember(trendingHits, allTracks) {
        if (trendingHits.isNotEmpty()) trendingHits.take(12) else allTracks.take(8)
    }

    // Priority for Personalized Mix: use personalizedTracks if available, fallback to allTracks
    val displayPersonalized = remember(personalizedTracks, allTracks) {
        if (personalizedTracks.isNotEmpty()) personalizedTracks.take(10) else allTracks
    }

    // Priority for Popular Tracks list: combined real music catalog
    val displayPopular = remember(trendingHits, allTracks) {
        if (trendingHits.isNotEmpty()) {
            val localIds = allTracks.map { it.id }.toSet()
            trendingHits + allTracks.filter { it.id !in trendingHits.map { t -> t.id }.toSet() }
        } else {
            allTracks
        }
    }

    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val moodChips = remember {
        listOf(
            "For You",
            "Energize",
            "Relax",
            "Workout",
            "Focus",
            "Party",
            "Romance",
            "Rock",
            "Folk"
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Top Bar: Vinyl Logo + App Name on left, Refresh + Notifications + Profile on right
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Vinyl Logo + App Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.loadHomeScreenMusic() }
                ) {
                    // Stylized Vinyl Disc Icon matching HTML radial-gradient
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFF2F2F2),
                                        Color(0xFF0D0D0D),
                                        Color(0xFF3A3A3A),
                                        Color(0xFF0D0D0D)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF2F2F2))
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row {
                        Text(
                            text = "Kanglei",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = KangleiDark
                        )
                        Text(
                            text = " Tune",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp,
                            color = KangleiTextSecondary
                        )
                    }
                }

                // Action Buttons: Refresh, Notifications, Profile
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Refresh Button (44x44 white circle)
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("home_refresh_btn"),
                        shape = CircleShape,
                        color = KangleiWhite,
                        onClick = { viewModel.loadHomeScreenMusic() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isLoadingHome) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = KangleiDark
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Music",
                                    tint = KangleiDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Notifications Button (44x44 circle)
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("home_notifications_btn"),
                        shape = CircleShape,
                        color = KangleiWhite,
                        onClick = onOpenNotifications
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Notifications",
                                tint = KangleiDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Dark Mode Toggle Button (44x44 circle)
                    val isDarkTheme = LocalIsDarkTheme.current
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("home_dark_mode_btn"),
                        shape = CircleShape,
                        color = KangleiWhite,
                        onClick = { viewModel.toggleDarkMode() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                                tint = KangleiDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Profile Avatar (44x44 circle)
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("home_profile_avatar_btn"),
                        shape = CircleShape,
                        color = Color(0xFF222222),
                        onClick = onNavigateToProfile
                    ) {
                        AsyncImage(
                            model = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200&auto=format&fit=crop&q=80",
                            contentDescription = "Profile Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Greeting Heading: "Good afternoon"
        item {
            Text(
                text = "Good afternoon",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                color = KangleiDark,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
            )
        }

        // Search Bar (White pill, 48dp height)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onNavigateToSearch)
                    .testTag("home_search_bar"),
                color = KangleiWhite,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = KangleiDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Search YouTube songs, artists, albums",
                        color = KangleiTextSecondary,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily
                    )
                }
            }
        }

        // Personalization & Mood Selector Bar
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Personalize Your Vibe",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = KangleiDark
                )
                Text(
                    text = "Live Stream",
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = KangleiTextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moodChips) { mood ->
                    val isSelected = mood == selectedMood
                    Surface(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .clickable { viewModel.selectMood(mood) },
                        shape = RoundedCornerShape(19.dp),
                        color = if (isSelected) KangleiPillBg else KangleiWhite
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = mood,
                                color = if (isSelected) KangleiPillText else KangleiDark,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // Personalized Mix For You (Live recommendations based on selected mood)
        if (displayPersonalized.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$selectedMood Mix",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = KangleiDark
                    )
                    Text(
                        text = "Play Radio",
                        color = KangleiDark,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            viewModel.playQueue(displayPersonalized, 0)
                        }
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayPersonalized) { track ->
                        HitCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            onClick = { viewModel.playTrack(track) }
                        )
                    }
                }
            }
        }

        // Section: "Today's biggest hits" (Real Trending YouTube Music)
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Today's biggest hits",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = KangleiDark
                )
                Text(
                    text = "View All",
                    color = KangleiDark,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        viewModel.setQueryImmediately("Trending Hits")
                        onNavigateToSearch()
                    }
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayHits) { track ->
                    HitCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        onClick = { viewModel.playTrack(track) }
                    )
                }
            }
        }

        // Section: "Fresh new music"
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Fresh new music & playlists",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    FreshMusicCard(
                        title = "Global Top 50",
                        subtitle = "Trending hits",
                        gradientColors = listOf(Color(0xFF6B6B6B), Color(0xFF161616)),
                        imageUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80",
                        onPlayClick = {
                            if (displayHits.isNotEmpty()) viewModel.playQueue(displayHits, 0)
                        },
                        modifier = Modifier.clickable {
                            if (displayHits.isNotEmpty()) viewModel.playQueue(displayHits, 0)
                        }
                    )
                }
                item {
                    FreshMusicCard(
                        title = "Imphal Rock Sessions",
                        subtitle = "Classic Rock",
                        gradientColors = listOf(Color(0xFF4A4A4A), Color(0xFF0D0D0D)),
                        imageUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80",
                        onPlayClick = {
                            val rockTracks = allTracks.filter { it.album.contains("Rock") || it.genre.contains("Rock") }
                            if (rockTracks.isNotEmpty()) viewModel.playQueue(rockTracks, 0)
                            else onNavigateToPlaylist("pl_imphal_rock")
                        },
                        modifier = Modifier.clickable { onNavigateToPlaylist("pl_imphal_rock") }
                    )
                }
                item {
                    FreshMusicCard(
                        title = "Pena & Folk Waves",
                        subtitle = "Acoustic heritage",
                        gradientColors = listOf(Color(0xFF555555), Color(0xFF222222)),
                        imageUrl = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=600&auto=format&fit=crop&q=80",
                        onPlayClick = {
                            if (allTracks.isNotEmpty()) viewModel.playQueue(allTracks.shuffled(), 0)
                        }
                    )
                }
            }
        }

        // Section: "Suggested artists"
        item {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Suggested artists",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            val suggestedArtists = listOf(
                Pair("Ed Sheeran", "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400&auto=format&fit=crop&q=80"),
                Pair("Coldplay", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&auto=format&fit=crop&q=80"),
                Pair("Taylor Swift", "https://images.unsplash.com/photo-1492562080023-ab3db95bfbce?w=400&auto=format&fit=crop&q=80"),
                Pair("Tapta", "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&auto=format&fit=crop&q=80"),
                Pair("Ronnie", "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=400&auto=format&fit=crop&q=80"),
                Pair("Arijit Singh", "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=400&auto=format&fit=crop&q=80")
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(suggestedArtists) { (name, imageUrl) ->
                    SuggestedArtistItem(
                        name = name,
                        imageUrl = imageUrl,
                        onClick = { onNavigateToArtist(name) }
                    )
                }
            }
        }

        // Popular Song List
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Top Songs & Hits",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = KangleiDark
                )

                Text(
                    text = "Play All",
                    color = KangleiDark,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        if (displayPopular.isNotEmpty()) viewModel.playQueue(displayPopular, 0)
                    }
                )
            }
        }

        itemsIndexed(displayPopular) { index, track ->
            TrackCard(
                track = track,
                isPlaying = playerState.currentTrack?.id == track.id,
                rankNumber = index + 1,
                onTrackClick = { viewModel.playTrack(track) },
                onLikeClick = { viewModel.toggleLike(track) },
                onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                onPlayNextClick = { viewModel.playNext(track) },
                onAddToQueueClick = { viewModel.addToQueue(track) },
                onAddToPlaylistClick = { trackToAddToPlaylist = track },
                onGoToArtistClick = { artistName -> onNavigateToArtist(artistName) },
                onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
    }

    // Modal to add track to playlist
    trackToAddToPlaylist?.let { track ->
        AddToPlaylistModal(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, track.id)
                trackToAddToPlaylist = null
            },
            onCreateNewPlaylist = {
                showCreatePlaylistDialog = true
            },
            onDismiss = {
                trackToAddToPlaylist = null
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistModal(
            onConfirm = { name ->
                viewModel.createPlaylist(name, "User created playlist")
                showCreatePlaylistDialog = false
            },
            onDismiss = {
                showCreatePlaylistDialog = false
            }
        )
    }
}
