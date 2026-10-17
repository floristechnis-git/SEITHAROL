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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val activeHistory by viewModel.activeHistory.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()

    // Real trending hits or local fallback
    val displayHits = remember(trendingHits, allTracks) {
        if (trendingHits.isNotEmpty()) trendingHits.take(15) else allTracks.take(10)
    }

    // Personalized Mix based on mood
    val displayPersonalized = remember(personalizedTracks, allTracks) {
        if (personalizedTracks.isNotEmpty()) personalizedTracks.take(12) else allTracks.take(10)
    }

    // Fresh releases or fallback
    val displayFresh = remember(freshReleases, displayHits, allTracks) {
        if (freshReleases.isNotEmpty()) freshReleases.take(8)
        else if (displayHits.isNotEmpty()) displayHits.take(8)
        else allTracks.take(6)
    }

    // Quick Picks: 4-row grid generated from recent 15-day history and trending
    val quickPicksTracks = remember(activeHistory, displayHits, allTracks) {
        val pool = mutableListOf<Track>()
        pool.addAll(activeHistory)
        val existingIds = pool.map { it.id }.toSet()
        for (t in displayHits) {
            if (t.id !in existingIds) pool.add(t)
        }
        for (t in allTracks) {
            if (t.id !in pool.map { it.id }.toSet()) pool.add(t)
        }
        pool.take(16)
    }

    // Combined catalog for Top Songs list
    val displayPopular = remember(trendingHits, allTracks) {
        if (trendingHits.isNotEmpty()) {
            val hitsIds = trendingHits.map { it.id }.toSet()
            trendingHits + allTracks.filter { it.id !in hitsIds }
        } else {
            allTracks
        }
    }

    // Dynamic suggested artists derived from listening history, likes, and trending
    val dynamicArtists = remember(activeHistory, likedTracks, trendingHits, allTracks) {
        val pool = activeHistory + likedTracks + trendingHits + allTracks
        pool.filter { it.artist.isNotBlank() && it.artist != "Unknown Artist" && it.artist != "Unknown" }
            .groupBy { it.artist }
            .map { (artistName, tracks) ->
                Pair(artistName, tracks.firstOrNull { !it.artUrl.isNullOrBlank() }?.artUrl ?: "")
            }
            .take(12)
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

    val cardGradients = remember {
        listOf(
            listOf(Color(0xFF5A4D8C), Color(0xFF1E1736)),
            listOf(Color(0xFF8C4D5A), Color(0xFF36171E)),
            listOf(Color(0xFF3B6B5D), Color(0xFF122E25)),
            listOf(Color(0xFF6B583B), Color(0xFF2E2212)),
            listOf(Color(0xFF3B5B6B), Color(0xFF12222E)),
            listOf(Color(0xFF6B3B5B), Color(0xFF2E1225))
        )
    }

    val isDarkTheme = LocalIsDarkTheme.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // 1. Top Navigation Bar: Clean, well-spaced, no vinyl icon or text clutter
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Listen Now",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = KangleiDark
                )

                // Action Buttons: Refresh, Notifications, Theme Toggle, Profile Avatar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Refresh Button
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
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

                    // Notifications Button
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
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

                    // Theme Toggle Button
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("home_dark_mode_btn"),
                        shape = CircleShape,
                        color = KangleiWhite,
                        onClick = { viewModel.toggleDarkMode(isDarkTheme) }
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

                    // Profile Avatar (Clean fallback icon instead of hardcoded mock image)
                    Surface(
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("home_profile_avatar_btn"),
                        shape = CircleShape,
                        color = KangleiWhite,
                        onClick = onNavigateToProfile
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = KangleiDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Single-line Search Bar
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
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
                        text = "Search songs, artists, albums...",
                        color = KangleiTextSecondary,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 3. Mood Filter Chips
        item {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moodChips) { mood ->
                    val isSelected = mood == selectedMood
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { viewModel.selectMood(mood) },
                        shape = RoundedCornerShape(18.dp),
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

        // 4. MAIN CONTENT SECTION 1 (AT THE VERY TOP): Fresh new music & playlists
        item {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Fresh new music & playlists",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(displayFresh) { index, track ->
                    FreshMusicCard(
                        title = track.title,
                        subtitle = track.artist,
                        gradientColors = cardGradients[index % cardGradients.size],
                        imageUrl = track.artUrl,
                        onPlayClick = { viewModel.playTrack(track) }
                    )
                }

                items(playlists) { playlist ->
                    FreshMusicCard(
                        title = playlist.title,
                        subtitle = "${playlist.trackCount} songs",
                        gradientColors = listOf(Color(0xFF4A4A4A), Color(0xFF141414)),
                        imageUrl = playlist.coverArtUrl,
                        onPlayClick = { onNavigateToPlaylist(playlist.id) }
                    )
                }
            }
        }

        // 5. QUICK PICKS: 4-Row Horizontal Grid
        if (quickPicksTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "START RADIO FROM A SONG",
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = KangleiTextSecondary
                        )
                        Text(
                            text = "Quick Picks",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = KangleiDark
                        )
                    }

                    Text(
                        text = "Play all",
                        color = KangleiDark,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            viewModel.playQueue(quickPicksTracks, 0)
                        }
                    )
                }

                // Chunk into columns of 4 songs per column
                val columns = remember(quickPicksTracks) { quickPicksTracks.chunked(4) }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(columns) { columnTracks ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            columnTracks.forEach { track ->
                                QuickPickRow(
                                    track = track,
                                    isPlaying = playerState.currentTrack?.id == track.id,
                                    onClick = { viewModel.playTrack(track) },
                                    onLikeClick = { viewModel.toggleLike(track) },
                                    onPlayNextClick = { viewModel.playNext(track) },
                                    onAddToQueueClick = { viewModel.addToQueue(track) },
                                    onAddToPlaylistClick = { trackToAddToPlaylist = track },
                                    onGoToArtistClick = { artistName -> onNavigateToArtist(artistName) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. LISTEN AGAIN: Horizontal carousel from active 15-day Room history
        if (activeHistory.isNotEmpty()) {
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
                        text = "Listen Again",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = KangleiDark
                    )

                    Text(
                        text = "Play all",
                        color = KangleiDark,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            viewModel.playQueue(activeHistory, 0)
                        }
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(activeHistory) { track ->
                        HitCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            onClick = { viewModel.playTrack(track) }
                        )
                    }
                }
            }
        }

        // 7. MIXED FOR YOU / MOOD MIXES
        if (displayPersonalized.isNotEmpty()) {
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
                        text = "$selectedMood Mix",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
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

        // 8. TODAY'S BIGGEST HITS & NEW RELEASES
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
                    text = "Today's Biggest Hits",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
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

        // 9. SUGGESTED ARTISTS: Dynamically derived from listening history, likes, and trending
        if (dynamicArtists.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Suggested artists",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = KangleiDark,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(dynamicArtists) { (name, imageUrl) ->
                        SuggestedArtistItem(
                            name = name,
                            imageUrl = imageUrl,
                            onClick = { onNavigateToArtist(name) }
                        )
                    }
                }
            }
        }

        // 10. TRENDING SONGS FOR YOU: TrackCard rows with 3-dot overflow menus
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
                    text = "Trending Songs For You",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
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

/**
 * Compact Quick Pick Song Item for 4-song-per-column grid
 */
@Composable
private fun QuickPickRow(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onGoToArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .width(320.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (isPlaying) KangleiWhite else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.artUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = KangleiDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = KangleiTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = KangleiTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onPlayNextClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onAddToQueueClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onAddToPlaylistClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Go to artist") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onGoToArtistClick(track.artist)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (track.isLiked) "Unlike" else "Like") },
                        leadingIcon = {
                            Icon(
                                if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onLikeClick()
                        }
                    )
                }
            }
        }
    }
}
