package com.example.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.blur
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
import com.seitharol.ui.components.PlaylistThumbnailWithBadge
import java.util.Calendar

@Composable
fun LikedPlaylistSvgThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF262727), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(56.dp)) {
            drawCircle(color = Color.White.copy(alpha = 0.12f))
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Liked Songs",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

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
 * Uses the SEITHAROL dark theme palette:
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val appPreferences = remember { com.example.data.AppPreferences(context) }
    val userSettings by appPreferences.settings.collectAsState()
    val recentSearches by appPreferences.recentSearches.collectAsState()

    // Deduplicate pools to ensure no duplicate songs appear
    val deduplicatedAllTracks = remember(allTracks) {
        allTracks.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }
    val deduplicatedTrendingHits = remember(trendingHits) {
        trendingHits.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }

    val currentlyPlayingSongId = playerState.currentTrack?.id
    val excludedHeroIds = remember(currentlyPlayingSongId) {
        setOfNotNull(currentlyPlayingSongId)
    }

    // Prepare Trending Items (Deduplicated, excluding Hero & Currently Playing)
    val trendingItems = remember(deduplicatedTrendingHits, deduplicatedAllTracks, excludedHeroIds) {
        val pool = if (deduplicatedTrendingHits.isNotEmpty()) deduplicatedTrendingHits else deduplicatedAllTracks
        pool.filterNot { it.id in excludedHeroIds }.take(24).map { it.toHomeMediaItem() }
    }

    // Prepare Fresh New Music Items (Deduplicated, excluding Hero & Currently Playing)
    val freshItems = remember(freshReleases, deduplicatedTrendingHits, deduplicatedAllTracks, excludedHeroIds) {
        val pool = if (freshReleases.isNotEmpty()) freshReleases.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
        else if (deduplicatedTrendingHits.isNotEmpty()) deduplicatedTrendingHits.take(12)
        else deduplicatedAllTracks.take(12)
        pool.filterNot { it.id in excludedHeroIds }.take(12).map { it.toHomeMediaItem() }
    }

    // Prepare YouTube Mixes Shelf (only authentic continuous mixes from YouTube Music, no synthetic mocks)
    val youtubeMixes = remember(personalizedTracks) {
        personalizedTracks
            .filter { it.id.startsWith("RD") }
            .map { it.toHomeMediaItem(isMix = true) }
            .distinctBy { it.id }
            .take(6)
    }

    // Prepare Featured Albums & Playlists (Liked Songs once on far left, non-empty playlists only)
    val albumsAndPlaylists = remember(playlists, likedTracks) {
        val likedItem = if (likedTracks.isNotEmpty()) {
            HomeMediaItem(
                id = "liked_songs",
                title = "Liked Songs",
                subtitle = "${likedTracks.size} songs",
                thumbnailUrl = likedTracks.first().artUrl,
                isAlbumOrPlaylist = true
            )
        } else null
        val list = mutableListOf<HomeMediaItem>()
        if (likedItem != null) list.add(likedItem)
        list.addAll(
            playlists
                .filter { it.trackCount > 0 && !it.coverUrl.isNullOrBlank() }
                .map { playlist ->
                    HomeMediaItem(
                        id = playlist.id,
                        title = playlist.title,
                        subtitle = "${playlist.trackCount} tracks",
                        thumbnailUrl = playlist.coverUrl.orEmpty(),
                        isAlbumOrPlaylist = true
                    )
                }
        )
        list
    }

    val bannedKeywords = remember {
        setOf(
            "local artist", "device storage", "downloaded in seitharol",
            "<unknown>", "unknown artist", "unknown", "various artists", "various", "download", "seitharol"
        )
    }

    // Prepare Spotify-Style 2-Column "Speed Dial / Quick Picks" Bento Items (2x3 grid = 6 items)
    // Suggests genuine playable songs similar to what has been listened to or searched recently
    val quickDialItems = remember(personalizedTracks, activeHistory, trendingItems, recentSearches, excludedHeroIds) {
        fun isAuthenticCleanSong(item: HomeMediaItem): Boolean {
            val a = item.subtitle.lowercase().trim()
            val t = item.title.lowercase().trim()
            val id = item.id.lowercase().trim()
            if (item.isMix || item.isAlbumOrPlaylist) return false
            if (t.contains("supermix") || t.contains("radio") || t.contains("mix")) return false
            if (id.startsWith("rdamvm") || id.startsWith("vl") || id.startsWith("pl") || id.startsWith("mpreb_") || id.startsWith("olak5uy_")) return false
            if (bannedKeywords.any { b -> a.contains(b) || t.contains(b) }) return false
            return true
        }

        val items = mutableListOf<HomeMediaItem>()

        // 1. Songs similar to recent listening & searches (from personalized recommendations)
        personalizedTracks
            .filterNot { it.id in excludedHeroIds }
            .map { it.toHomeMediaItem() }
            .filter(::isAuthenticCleanSong)
            .distinctBy { "${it.title.lowercase().trim()}|${it.subtitle.lowercase().trim()}" }
            .take(6)
            .forEach { items.add(it) }

        // 2. Real songs from recent listening history
        activeHistory
            .distinctBy { it.id }
            .filterNot { it.id in excludedHeroIds }
            .map { it.toHomeMediaItem() }
            .filter(::isAuthenticCleanSong)
            .distinctBy { "${it.title.lowercase().trim()}|${it.subtitle.lowercase().trim()}" }
            .take(4)
            .forEach { histItem ->
                if (!items.any { it.id == histItem.id || (it.title.equals(histItem.title, true) && it.subtitle.equals(histItem.subtitle, true)) }) {
                    items.add(histItem)
                }
            }

        // 3. Fallback to trending hits as fill to guarantee at least 6 clean playable tracks
        if (items.size < 6) {
            trendingItems
                .filterNot { it.id in excludedHeroIds }
                .filter(::isAuthenticCleanSong)
                .distinctBy { "${it.title.lowercase().trim()}|${it.subtitle.lowercase().trim()}" }
                .take(6 - items.size)
                .forEach { trendItem ->
                    if (!items.any { it.id == trendItem.id || (it.title.equals(trendItem.title, true) && it.subtitle.equals(trendItem.subtitle, true)) }) {
                        items.add(trendItem)
                    }
                }
        }

        items.distinctBy { it.id }.take(6)
    }

    // Dynamic suggested artists strictly personalized from user's listening history, liked songs, and recent searches (strictly max 4)
    val dynamicArtists = remember(activeHistory, likedTracks, recentSearches, trendingHits) {
        val artistScores = mutableMapOf<String, Int>()
        fun isValidArtist(a: String): Boolean {
            val lower = a.lowercase().trim()
            return lower.isNotBlank() && !bannedKeywords.any { lower.contains(it) }
        }
        // Strictly score based on listening frequency (weight 2), user likes (weight 3), and searches (weight 2)
        activeHistory.forEach { track ->
            val a = track.artist.trim()
            if (isValidArtist(a)) {
                artistScores[a] = (artistScores[a] ?: 0) + 2
            }
        }
        likedTracks.forEach { track ->
            val a = track.artist.trim()
            if (isValidArtist(a)) {
                artistScores[a] = (artistScores[a] ?: 0) + 3
            }
        }
        recentSearches.forEach { query ->
            val q = query.trim()
            if (isValidArtist(q)) {
                artistScores[q] = (artistScores[q] ?: 0) + 2
            }
        }

        val personalized = artistScores.entries
            .sortedByDescending { it.value }
            .map { it.key }

        // If user has listened/liked/searched artists, show ONLY their top personalized artists (strictly max 4)
        if (personalized.isNotEmpty()) {
            personalized.take(4)
        } else {
            // Only if brand-new user with zero history and zero likes, show at most 4 trending artists
            trendingHits
                .map { it.artist.trim() }
                .filter { isValidArtist(it) }
                .distinct()
                .take(4)
        }
    }

    // Modal state for Add to Playlist
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // Time-of-day greeting with user's name
    val greeting = remember(userSettings.userName) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val baseGreeting = when (hour) {
            in 5..11  -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else      -> "Good Night"
        }
        if (userSettings.userName.isNotBlank()) "$baseGreeting, ${userSettings.userName}" else baseGreeting
    }

    val moodChips = remember {
        listOf(
            "Energize",
            "Trending",
            "Manipuri Song",
            "Sad",
            "Chill",
            "Romantic",
            "Workout",
            "Lo-Fi"
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("home_screen")
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // ────────────────────────────────────────────────────────────
            // Top Navigation Bar: "SEITHAROL" (Auto-suggest removed)
            // ────────────────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SEITHAROL",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        fontFamily = SyneFontFamily
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_search_btn"),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onNavigateToSearch
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_notification_btn"),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onOpenNotifications
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "Notifications",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_profile_avatar_btn"),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onNavigateToProfile
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(onClick = onNavigateToSearch)
                            .testTag("home_search_bar"),
                        color = MaterialTheme.colorScheme.surfaceVariant,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Search songs, artists, albums, or mixes...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
            // 1. YouTube Music Style Category Filter Chips (Pure White active state)
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
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    if (isSelected && mood != "All") {
                                        viewModel.selectHomeMood("All")
                                    } else {
                                        viewModel.selectHomeMood(mood)
                                    }
                                }
                        ) {
                            Text(
                                text = mood,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }



            // ────────────────────────────────────────────────────────────
            // First-launch empty state (when offline or loading hasn't completed yet)
            // ────────────────────────────────────────────────────────────
            if (!isLoadingHome && trendingItems.isEmpty() && freshItems.isEmpty() && quickDialItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = Color(0xFF4A4A4A),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No content loaded yet",
                                color = Color(0xFFB0B0B0),
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "Check your internet connection\nand tap Retry to load music.",
                                color = Color(0xFF757575),
                                fontFamily = OutfitFontFamily,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable { viewModel.loadHomeScreenMusic() }
                                    .testTag("home_retry_btn"),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "  Retry  ",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // 2. Spotify-Style 2-Column "Speed Dial / Quick Picks" Bento Grid (All) OR Category Active Header
            // ────────────────────────────────────────────────────────────
            val isAllMood = selectedMood.equals("All", ignoreCase = true)
            if (isAllMood) {
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
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "$selectedMood Hits",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Filtered specifically for $selectedMood vibes",
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.selectHomeMood("All") }
                        ) {
                            Text(
                                text = "Clear",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // 3. "Trending Songs for You" — YouTube Music 4-Row Horizontal Snap Grid
            // ────────────────────────────────────────────────────────────
            if (trendingItems.isNotEmpty()) {
                item {
                    val shelfTitle = if (isAllMood) "Trending Songs for You" else "$selectedMood Top Songs"
                    val shelfSubtitle = if (isAllMood) "Swipe for top charts & viral tracks" else "Popular releases under $selectedMood"
                    TrendingSongsHorizontalShelf(
                        tracks = trendingItems,
                        title = shelfTitle,
                        subtitle = shelfSubtitle,
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
                            onNavigateToPlaylist(item.id)
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
                        badgeText = "PLAYLIST",
                        onItemClick = { item ->
                            onNavigateToPlaylist(item.id)
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
                            items(dynamicArtists) { artistName ->
                                SuggestedArtistItem(
                                    name = artistName,
                                    onClick = { onNavigateToArtist(artistName) }
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
                viewModel.createPlaylist(name, "User created playlist", trackToAddToPlaylist)
                trackToAddToPlaylist = null
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
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { selectedMood = mood }
                    ) {
                        Text(
                            text = mood,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
        val cleanBentoItems = remember(trendingSongs) {
            trendingSongs.filter { !it.isMix && !it.isAlbumOrPlaylist && !it.title.contains("SuperMix", true) && !it.title.contains("Radio", true) }
        }
        if (cleanBentoItems.size >= 4) {
            item {
                QuickDialBentoSection(
                    items = cleanBentoItems.take(6),
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
    title: String = "Trending Songs for You",
    subtitle: String = "Swipe for top charts & viral tracks",
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
            title = title,
            subtitle = subtitle
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
                    color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
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

                        // Clean space without stray div dot
                        Spacer(modifier = Modifier.width(12.dp))

                        // Song Title & Artist / Album
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                        ) {
                            Text(
                                text = track.title,
                                color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (onMoreClick == null) {
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                onPlayNext(track)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                onAddToQueue(track)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                onAddToPlaylist(track)
                                            }
                                        )
                                        val artistName = track.subtitle.split("•").firstOrNull()?.trim() ?: track.subtitle
                                        if (artistName.isNotBlank() && artistName != "Unknown Artist") {
                                            DropdownMenuItem(
                                                text = { Text("Go to Artist", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    onGoToArtist(artistName)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Download (Standard)", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                onDownload(track, DownloadTier.STANDARD)
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
            itemsIndexed(tracks.distinctBy { it.id }, key = { idx, item -> "${item.id}_$idx" }) { _, track ->
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
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = track.title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val topItems = items.take(12)
    val columns = topItems.chunked(2)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(columns.size) { colIdx ->
            val colItems = columns[colIdx]
            Column(
                modifier = Modifier.width(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                colItems.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onItemClick(item) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = upgradeThumbnail(item.thumbnailUrl),
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(62.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = item.title,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.subtitle.isNotBlank() && !item.subtitle.equals(item.title, ignoreCase = true)) {
                                    Text(
                                        text = item.subtitle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
            itemsIndexed(items.distinctBy { it.id }, key = { idx, item -> "${item.id}_$idx" }) { _, item ->
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
                        PlaylistThumbnailWithBadge(
                            firstTrackThumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() },
                            size = 164.dp,
                            cornerRadius = 12.dp
                        )
                        val badge = if (item.isMix) "MIX" else (badgeText ?: if (item.isAlbumOrPlaylist) "PLAYLIST" else null)
                        if (badge != null) {
                            Surface(
                                color = Color(0xFF1B1C1C).copy(alpha = 0.85f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = badge,
                                    color = Color.White,
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
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontFamily = SyneFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = OutfitFontFamily,
            fontSize = 12.sp
        )
    }
}
