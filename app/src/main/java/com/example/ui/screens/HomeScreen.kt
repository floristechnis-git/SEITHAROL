package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.SuggestedArtistItem
import com.example.ui.theme.*
import java.util.Calendar

// UI Data Model for Home Screen Tracks / Mixes / Albums
data class HomeMediaItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val isMix: Boolean = false,
    val isAlbumOrPlaylist: Boolean = false,
    val originalTrack: Track? = null
)

// High-res thumbnail upgrader
fun upgradeThumbnail(url: String): String {
    if (url.isBlank()) return ""
    return url
        .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w544-h544-l90-rj")
        .replace(Regex("=s\\d+[^&\\s]*"), "=s544")
        .replace("/default.jpg", "/hqdefault.jpg")
        .replace("/mqdefault.jpg", "/hqdefault.jpg")
}

fun Track.toHomeMediaItem(isMix: Boolean = false, isAlbumOrPlaylist: Boolean = false): HomeMediaItem {
    return HomeMediaItem(
        id = id,
        title = title,
        subtitle = if (album.isNotBlank() && album != "Single" && album != title) "$artist • $album" else artist,
        thumbnailUrl = artUrl,
        isMix = isMix || id.startsWith("RD") || title.contains("Mix", ignoreCase = true),
        isAlbumOrPlaylist = isAlbumOrPlaylist || id.startsWith("MPREb_") || id.startsWith("OLAK5uy_") || id.startsWith("VL") || id.startsWith("PL"),
        originalTrack = this
    )
}

fun HomeMediaItem.toTrack(): Track {
    return originalTrack ?: Track(
        id = id,
        title = title,
        artist = subtitle.split("•").firstOrNull()?.trim() ?: subtitle,
        album = subtitle.split("•").getOrNull(1)?.trim() ?: "",
        durationMs = 0L,
        artUrl = thumbnailUrl,
        genre = "Music",
        isLiked = false
    )
}

/**
 * Modern, multi-tiered streaming home screen architecture inspired by YouTube Music, Spotify, and Amazon Music.
 * Uses the Kanglei Tune dark theme palette:
 * - Background: #1B1C1C
 * - Elevated surfaces: #232526 / #282A2A
 * - Primary text: #E3E4E2
 * - Accent: #FFB74D
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val isDarkTheme = LocalIsDarkTheme.current

    // Prepare Trending Items
    val trendingItems = remember(trendingHits, allTracks) {
        val pool = if (trendingHits.isNotEmpty()) trendingHits else allTracks
        pool.take(24).map { it.toHomeMediaItem() }
    }

    // Prepare Fresh New Music Items
    val freshItems = remember(freshReleases, trendingHits, allTracks) {
        val pool = if (freshReleases.isNotEmpty()) freshReleases
        else if (trendingHits.isNotEmpty()) trendingHits.take(12)
        else allTracks.take(12)
        pool.take(12).map { it.toHomeMediaItem() }
    }

    // Prepare YouTube Mixes Shelf (non-filtered RD mixes + dynamic station cards)
    val youtubeMixes = remember(personalizedTracks, activeHistory, trendingHits) {
        val mixes = mutableListOf<HomeMediaItem>()
        personalizedTracks.filter { it.id.startsWith("RD") || it.title.contains("Mix", ignoreCase = true) }
            .forEach { mixes.add(it.toHomeMediaItem(isMix = true)) }

        val seedTracks = (activeHistory.take(3) + trendingHits.take(3)).distinctBy { it.id }.take(4)
        seedTracks.forEach { track ->
            mixes.add(
                HomeMediaItem(
                    id = if (track.id.startsWith("RD")) track.id else "RDAMVM${track.id}",
                    title = "${track.title} Mix",
                    subtitle = "${track.artist}, ${track.genre} & more",
                    thumbnailUrl = track.artUrl,
                    isMix = true,
                    originalTrack = track
                )
            )
        }
        mixes.distinctBy { it.id }
    }

    // Prepare Featured Albums & Playlists
    val albumsAndPlaylists = remember(playlists) {
        playlists.map { playlist ->
            HomeMediaItem(
                id = playlist.id,
                title = playlist.title,
                subtitle = "${playlist.trackCount} tracks",
                thumbnailUrl = playlist.coverUrl.orEmpty(),
                isAlbumOrPlaylist = true
            )
        }
    }

    // Prepare Spotify-Style 2-Column "Speed Dial / Quick Picks" Bento Items (2x3 grid = 6 items)
    val quickDialItems = remember(youtubeMixes, activeHistory, trendingItems) {
        val items = mutableListOf<HomeMediaItem>()
        items.addAll(youtubeMixes.take(2))
        activeHistory.take(4).forEach { items.add(it.toHomeMediaItem()) }
        if (items.size < 6) {
            trendingItems.take(6 - items.size).forEach { items.add(it) }
        }
        items.take(6)
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

    // Modal state for Add to Playlist
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // Time-of-day greeting
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11  -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else      -> "Good Night"
        }
    }

    val moodChips = remember {
        listOf(
            "All",
            "For You",
            "Energize",
            "Relax",
            "Workout",
            "Focus",
            "Mixes",
            "New Releases"
        )
    }

    PullToRefreshBox(
        isRefreshing = isLoadingHome,
        onRefresh = { viewModel.loadHomeScreenMusic() },
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1B1C1C)) // Dark theme palette background
            .statusBarsPadding()
            .testTag("home_screen")
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // ────────────────────────────────────────────────────────────
            // Top Navigation & Action Bar
            // ────────────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val isAutoSuggest by viewModel.isAutoSuggestEnabled.collectAsState()
                    FilterChip(
                        selected = isAutoSuggest,
                        onClick = { viewModel.toggleAutoSuggest() },
                        label = {
                            Text(
                                "Auto-Suggest",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = if (isAutoSuggest) Color(0xFF1B1C1C) else Color(0xFFE3E4E2)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Auto-Suggest",
                                tint = if (isAutoSuggest) Color(0xFF1B1C1C) else Color(0xFFE3E4E2),
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE3E4E2),
                            containerColor = Color(0xFF282A2A)
                        ),
                        modifier = Modifier.height(36.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_notifications_btn"),
                            shape = CircleShape,
                            color = Color(0xFF242626),
                            onClick = onOpenNotifications
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "Notifications",
                                    tint = Color(0xFFE3E4E2),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_dark_mode_btn"),
                            shape = CircleShape,
                            color = Color(0xFF242626),
                            onClick = { viewModel.toggleDarkMode(isDarkTheme) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Theme Toggle",
                                    tint = Color(0xFFE3E4E2),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_profile_avatar_btn"),
                            shape = CircleShape,
                            color = Color(0xFF242626),
                            onClick = onNavigateToProfile
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile",
                                    tint = Color(0xFFE3E4E2),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // Time-of-day Greeting & Search Bar
            // ────────────────────────────────────────────────────────────
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = greeting,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = Color(0xFFE3E4E2)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(onClick = onNavigateToSearch)
                            .testTag("home_search_bar"),
                        color = Color(0xFF242626),
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
                                tint = Color(0xFF9EA1A1),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Search songs, artists, albums, or mixes...",
                                color = Color(0xFF9EA1A1),
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // 1. Sticky Mood & Genre Filter Bar (Top)
            // ────────────────────────────────────────────────────────────
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(moodChips) { mood ->
                        val isSelected = mood.equals(selectedMood, ignoreCase = true) || (mood == "All" && selectedMood == "All")
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFFE3E4E2) else Color(0xFF282A2A),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { viewModel.selectHomeMood(mood) }
                        ) {
                            Text(
                                text = mood,
                                color = if (isSelected) Color(0xFF1B1C1C) else Color(0xFFE3E4E2),
                                fontFamily = OutfitFontFamily,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            if (isLoadingHome) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = Color(0xFFFFB74D),
                            trackColor = Color(0xFF282A2A)
                        )
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // 2. Spotify-Style 2-Column "Speed Dial / Quick Picks" Bento Grid
            // ────────────────────────────────────────────────────────────
            if (quickDialItems.isNotEmpty()) {
                item {
                    QuickDialBentoSection(
                        items = quickDialItems,
                        onItemClick = { item ->
                            if (item.isMix || item.isAlbumOrPlaylist) {
                                viewModel.playPlaylistOrMix(item.id)
                            } else {
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.playTrack(track)
                            }
                        }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 3. "Trending Songs for You" — YouTube Music 4-Row Horizontal Snap Grid
            // ────────────────────────────────────────────────────────────
            if (trendingItems.isNotEmpty()) {
                item {
                    TrendingSongsHorizontalShelf(
                        tracks = trendingItems,
                        currentPlayingId = playerState.currentTrack?.id,
                        onTrackClick = { item ->
                            val track = item.originalTrack ?: item.toTrack()
                            viewModel.playTrack(track)
                        },
                        onPlayNext = { item ->
                            val track = item.originalTrack ?: item.toTrack()
                            viewModel.playNext(track)
                        },
                        onAddToQueue = { item ->
                            val track = item.originalTrack ?: item.toTrack()
                            viewModel.addToQueue(track)
                        },
                        onAddToPlaylist = { item ->
                            trackToAddToPlaylist = item.originalTrack ?: item.toTrack()
                        },
                        onGoToArtist = { artistName -> onNavigateToArtist(artistName) },
                        onDownload = { item, tier ->
                            val track = item.originalTrack ?: item.toTrack()
                            viewModel.startDownload(track, tier)
                        }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 4. "Fresh New Music" — High-Res Editorial Cards (No Circle Div!)
            // ────────────────────────────────────────────────────────────
            if (freshItems.isNotEmpty()) {
                item {
                    FreshNewMusicShelf(
                        tracks = freshItems,
                        onTrackClick = { item ->
                            val track = item.originalTrack ?: item.toTrack()
                            viewModel.playTrack(track)
                        }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 5. "Mixed for You" (YouTube Mixes Carousel)
            // ────────────────────────────────────────────────────────────
            if (youtubeMixes.isNotEmpty()) {
                item {
                    MediaCarouselShelf(
                        title = "Mixed for You",
                        subtitle = "Non-stop YouTube Music radio & personalized blend",
                        items = youtubeMixes,
                        badgeText = "MIX",
                        onItemClick = { item ->
                            viewModel.playPlaylistOrMix(item.id)
                        }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 6. "Featured Albums & Curated Playlists"
            // ────────────────────────────────────────────────────────────
            if (albumsAndPlaylists.isNotEmpty()) {
                item {
                    MediaCarouselShelf(
                        title = "Featured Albums & Playlists",
                        subtitle = "Full albums and curated tracklists",
                        items = albumsAndPlaylists,
                        badgeText = null,
                        onItemClick = { item ->
                            if (item.id.startsWith("RD") || item.id.startsWith("PL") || item.id.startsWith("VL") || item.id.startsWith("MPREb_") || item.id.startsWith("OLAK5uy_")) {
                                viewModel.playPlaylistOrMix(item.id)
                            } else {
                                onNavigateToPlaylist(item.id)
                            }
                        }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 7. "Suggested Artists"
            // ────────────────────────────────────────────────────────────
            if (dynamicArtists.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(
                            title = "Suggested Artists",
                            subtitle = "Based on your music taste & trending charts"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
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
            }
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
 * Standalone AestheticMusicHomeScreen implementation as requested in the design specification.
 */
@Composable
fun AestheticMusicHomeScreen(
    trendingSongs: List<HomeMediaItem>,
    freshNewMusic: List<HomeMediaItem>,
    youtubeMixes: List<HomeMediaItem>,
    albumsAndPlaylists: List<HomeMediaItem>,
    onTrackClick: (HomeMediaItem) -> Unit,
    onMixOrPlaylistClick: (HomeMediaItem) -> Unit,
    onMoreClick: (HomeMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val moods = listOf("All", "Energize", "Chill", "Workout", "Focus", "Mixes", "New Releases")
    var selectedMood by remember { mutableStateOf("All") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1B1C1C)),
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1. Mood & Genre Filter Chips (YouTube Music / Spotify style)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moods) { mood ->
                    val isSelected = mood == selectedMood
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFFE3E4E2) else Color(0xFF282A2A),
                        modifier = Modifier.clickable { selectedMood = mood }
                    ) {
                        Text(
                            text = mood,
                            color = if (isSelected) Color(0xFF1B1C1C) else Color(0xFFE3E4E2),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 2. Spotify-Style 2-Column Quick Dial Bento Grid
        if (trendingSongs.size >= 4) {
            item {
                QuickDialBentoSection(
                    items = (youtubeMixes.take(2) + trendingSongs.take(4)).take(6),
                    onItemClick = { item ->
                        if (item.isMix || item.isAlbumOrPlaylist) onMixOrPlaylistClick(item)
                        else onTrackClick(item)
                    }
                )
            }
        }

        // 3. TRENDING SONGS FOR YOU — 4-Row Horizontal Snap Pager (YT Music Architecture)
        item {
            TrendingSongsHorizontalShelf(
                tracks = trendingSongs,
                onTrackClick = onTrackClick,
                onMoreClick = onMoreClick
            )
        }

        // 4. FRESH NEW MUSIC — High-Res Editorial Cards (No Circle Div!)
        item {
            FreshNewMusicShelf(
                tracks = freshNewMusic,
                onTrackClick = onTrackClick
            )
        }

        // 5. YOUTUBE MIXES — Dedicated Unfiltered Mixes Shelf
        if (youtubeMixes.isNotEmpty()) {
            item {
                MediaCarouselShelf(
                    title = "Mixed for You",
                    subtitle = "Non-stop YouTube Music radio & mixes",
                    items = youtubeMixes,
                    badgeText = "MIX",
                    onItemClick = onMixOrPlaylistClick
                )
            }
        }

        // 6. FEATURED ALBUMS & PLAYLISTS
        if (albumsAndPlaylists.isNotEmpty()) {
            item {
                MediaCarouselShelf(
                    title = "Albums & Curated Playlists",
                    subtitle = "Full albums and community playlists",
                    items = albumsAndPlaylists,
                    badgeText = null,
                    onItemClick = onMixOrPlaylistClick
                )
            }
        }
    }
}

/**
 * YouTube Music / Amazon Music style 4-row horizontal snap grid for "Trending Songs for You"
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrendingSongsHorizontalShelf(
    tracks: List<HomeMediaItem>,
    currentPlayingId: String? = null,
    onTrackClick: (HomeMediaItem) -> Unit,
    onMoreClick: ((HomeMediaItem) -> Unit)? = null,
    onPlayNext: (HomeMediaItem) -> Unit = {},
    onAddToQueue: (HomeMediaItem) -> Unit = {},
    onAddToPlaylist: (HomeMediaItem) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onDownload: (HomeMediaItem, DownloadTier) -> Unit = { _, _ -> }
) {
    val gridState = rememberLazyGridState()
    val snapBehavior = rememberSnapFlingBehavior(lazyGridState = gridState)

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Trending Songs for You",
            subtitle = "Swipe for top charts & viral tracks"
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            state = gridState,
            flingBehavior = snapBehavior,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp) // Comfortably fits 4 rows of 60.dp cards with 10.dp spacing
        ) {
            itemsIndexed(tracks, key = { idx, item -> "${item.id}_$idx" }) { index, track ->
                val rank = index + 1
                val isTopThree = rank <= 3
                val isPlaying = currentPlayingId == track.id
                var showMenu by remember { mutableStateOf(false) }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isPlaying) Color(0xFF2E3030) else Color(0xFF232526),
                    modifier = Modifier
                        .width(320.dp) // Leaves right-edge peek inviting horizontal swipe
                        .height(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTrackClick(track) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp)
                    ) {
                        // Crisp High-Res Album Art (52.dp square, RoundedCornerShape(10.dp))
                        AsyncImage(
                            model = upgradeThumbnail(track.thumbnailUrl),
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )

                        // Stylized 2-digit rank badge (01, 02, 03...)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(36.dp)
                        ) {
                            Text(
                                text = rank.toString().padStart(2, '0'),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isTopThree) Color(0xFFFFB74D) else Color(0xFF9E9E9E),
                                textAlign = TextAlign.Center
                            )
                            // Subtle trend indicator dot
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isTopThree) Color(0xFFFFB74D) else Color(0xFF4CAF50)
                                    )
                            )
                        }

                        // Song Title & Artist / Album
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                        ) {
                            Text(
                                text = track.title,
                                color = if (isPlaying) Color(0xFFFFB74D) else Color(0xFFE3E4E2),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.subtitle,
                                color = Color(0xFF9EA1A1),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Trailing Play / More Options Action
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onTrackClick(track) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = if (isPlaying) Color(0xFFFFB74D) else Color(0xFFE3E4E2),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = {
                                        if (onMoreClick != null) {
                                            onMoreClick(track)
                                        } else {
                                            showMenu = true
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        tint = Color(0xFF9EA1A1),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (onMoreClick == null) {
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        modifier = Modifier.background(Color(0xFF282A2A))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Play Next", color = Color(0xFFE3E4E2)) },
                                            leadingIcon = { Icon(Icons.Default.PlaylistPlay, null, tint = Color(0xFF9EA1A1)) },
                                            onClick = {
                                                showMenu = false
                                                onPlayNext(track)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Queue", color = Color(0xFFE3E4E2)) },
                                            leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = Color(0xFF9EA1A1)) },
                                            onClick = {
                                                showMenu = false
                                                onAddToQueue(track)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Playlist", color = Color(0xFFE3E4E2)) },
                                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = Color(0xFF9EA1A1)) },
                                            onClick = {
                                                showMenu = false
                                                onAddToPlaylist(track)
                                            }
                                        )
                                        val artistName = track.subtitle.split("•").firstOrNull()?.trim() ?: track.subtitle
                                        if (artistName.isNotBlank() && artistName != "Unknown Artist") {
                                            DropdownMenuItem(
                                                text = { Text("Go to Artist", color = Color(0xFFE3E4E2)) },
                                                leadingIcon = { Icon(Icons.Default.Person, null, tint = Color(0xFF9EA1A1)) },
                                                onClick = {
                                                    showMenu = false
                                                    onGoToArtist(artistName)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Download High (160k)", color = Color(0xFFE3E4E2)) },
                                            leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = Color(0xFF9EA1A1)) },
                                            onClick = {
                                                showMenu = false
                                                onDownload(track, DownloadTier.HIGH)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Redesigned "Fresh New Music" Shelf — High-Res Square Cards (176.dp, NO Circle Div!)
 */
@Composable
fun FreshNewMusicShelf(
    tracks: List<HomeMediaItem>,
    onTrackClick: (HomeMediaItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Fresh New Music",
            subtitle = "Latest releases in high definition"
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                Column(
                    modifier = Modifier
                        .width(176.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTrackClick(track) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(176.dp)
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        // Full-bleed High-Res Cover Art (No circular clip!)
                        AsyncImage(
                            model = upgradeThumbnail(track.thumbnailUrl),
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Bottom gradient scrim for depth
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.55f)
                                        ),
                                        startY = 200f
                                    )
                                )
                        )

                        // "NEW" Pill Badge (Top-Left)
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "NEW",
                                color = Color(0xFFE3E4E2),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        // Floating Rounded Square Play Action (Bottom-Right, NO Circle)
                        Surface(
                            color = Color(0xFFE3E4E2),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color(0xFF1B1C1C),
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = track.title,
                        color = Color(0xFFE3E4E2),
                        fontSize = 14.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.subtitle,
                        color = Color(0xFF9EA1A1),
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Spotify-style 2-Column "Speed Dial / Quick Picks" Bento Grid (56.dp height cards, 2x3 compact grid)
 */
@Composable
fun QuickDialBentoSection(
    items: List<HomeMediaItem>,
    onItemClick: (HomeMediaItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.take(6).chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF282A2A),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onItemClick(item) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = upgradeThumbnail(item.thumbnailUrl),
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = item.title,
                                color = Color(0xFFE3E4E2),
                                fontSize = 13.sp,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .weight(1f)
                            )
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Reusable Carousel for YouTube Mixes ("Mixed for You") and Featured Albums/Playlists
 */
@Composable
fun MediaCarouselShelf(
    title: String,
    subtitle: String,
    items: List<HomeMediaItem>,
    badgeText: String?,
    onItemClick: (HomeMediaItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = subtitle)
        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .width(164.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemClick(item) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(164.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        AsyncImage(
                            model = upgradeThumbnail(item.thumbnailUrl),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (badgeText != null) {
                            Surface(
                                color = Color(0xFF1B1C1C).copy(alpha = 0.85f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = Color(0xFFFFB74D),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.title,
                        color = Color(0xFFE3E4E2),
                        fontSize = 14.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        color = Color(0xFF9EA1A1),
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            color = Color(0xFFE3E4E2),
            fontSize = 20.sp,
            fontFamily = SyneFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
        Text(
            text = subtitle,
            color = Color(0xFF9EA1A1),
            fontFamily = OutfitFontFamily,
            fontSize = 12.sp
        )
    }
}
