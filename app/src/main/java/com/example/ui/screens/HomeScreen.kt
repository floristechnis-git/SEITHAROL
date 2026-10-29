package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.ManipurCalendarNotificationEngine
import com.example.ui.components.SeitharolPullToRefreshBox
import com.example.ui.components.SuggestedArtistItem
import com.example.ui.theme.*
import com.seitharol.ui.components.HeadphoneSvgIcon
import com.seitharol.ui.components.NotificationBellAlertSvgIcon
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
    val cleanAlbum = album.takeUnless {
        it.equals("Single", ignoreCase = true) ||
            it.equals("YouTube Video", ignoreCase = true) ||
            it.equals("YouTube Videos", ignoreCase = true) ||
            it.equals("Discover Downloads", ignoreCase = true) ||
            it.equals("Shared Track", ignoreCase = true) ||
            it.equals("Local Storage", ignoreCase = true) ||
            it.equals(title, ignoreCase = true)
    }.orEmpty()
    return HomeMediaItem(
        id = id,
        title = title,
        subtitle = if (cleanAlbum.isNotBlank()) "$artist • $cleanAlbum" else artist,
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
 * - Strictly excludes local device tracks (`isLocalOnlyTrack`) from online Home feed suggestions and Quick Dial.
 * - Ensures 2-row titles in QuickDialBentoSection never overflow or hide the artist subtitle.
 * - Uses the Seitharol app logo (`R.drawable.ic_splash_logo`) as placeholder/error/fallback for thumbnails.
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
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()
    val freshReleases by viewModel.freshReleases.collectAsState()
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val suggestedYoutubeMixes by viewModel.suggestedYoutubeMixes.collectAsState()
    val suggestedYoutubePlaylists by viewModel.suggestedYoutubePlaylists.collectAsState()
    val selectedMood by viewModel.selectedMood.collectAsState()
    val isLoadingHome by viewModel.isLoadingHome.collectAsState()
    val hasCompletedInitialHomeLoad by viewModel.hasCompletedInitialHomeLoad.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val activeHistory by viewModel.activeHistory.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val hasUnreadNotifications by ManipurCalendarNotificationEngine.hasUnreadAlerts.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appPreferences = remember { com.example.data.AppPreferences(context) }
    val userSettings by appPreferences.settings.collectAsState()
    val recentSearches by appPreferences.recentSearches.collectAsState()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(context) }
        }
    }

    // Deduplicate pools to ensure no duplicate songs appear; in Online Mode strictly exclude local-only files
    val deduplicatedAllTracks = remember(allTracks, downloadedTracks, isOfflineMode) {
        val basePool = if (isOfflineMode) {
            downloadedTracks.filterNot { it.isLocalOnlyTrack }
        } else {
            allTracks.filterNot { it.isLocalOnlyTrack }
        }
        basePool.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }
    val deduplicatedTrendingHits = remember(trendingHits, downloadedTracks, isOfflineMode) {
        val basePool = if (isOfflineMode) {
            downloadedTracks.filterNot { it.isLocalOnlyTrack }
        } else {
            trendingHits.filterNot { it.isLocalOnlyTrack }
        }
        basePool.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }

    val excludedHeroIds = remember {
        emptySet<String>()
    }

    val bannedKeywords = remember {
        setOf(
            "local artist", "local audio", "local video", "device storage", "downloaded in seitharol",
            "<unknown>", "unknown artist", "unknown", "various artists", "various", "download", "seitharol"
        )
    }

    // Prepare Spotify-Style 2-Column "Speed Dial / Quick Picks" Bento Items (2x3 grid = 6 items)
    // Dynamically blends the user's most recent online plays, liked tracks, and personalized recommendations
    // so Quick Dial updates immediately as the user plays or likes music!
    val quickDialItems = remember(personalizedTracks, activeHistory, likedTracks, deduplicatedTrendingHits, downloadedTracks, isOfflineMode, recentSearches, excludedHeroIds) {
        if (isOfflineMode) {
            return@remember downloadedTracks
                .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
                .map { it.toHomeMediaItem() }
                .distinctBy { it.id }
                .take(6)
        }

        fun isAuthenticCleanSong(item: HomeMediaItem): Boolean {
            if (item.originalTrack?.isLocalOnlyTrack == true) return false
            val a = item.subtitle.lowercase().trim()
            val t = item.title.lowercase().trim()
            val id = item.id.lowercase().trim()
            if (item.isMix || item.isAlbumOrPlaylist) return false
            if (id.startsWith("local_") || id.startsWith("file_") || id.startsWith("vidfile_") || id.startsWith("content://") || id.startsWith("file://")) return false
            if (t.contains("supermix") || t.contains("radio") || t.contains("mix")) return false
            if (a.contains("supermix") || a.contains("radio") || a.contains("mix")) return false
            if (id.startsWith("rdamvm") || id.startsWith("vl") || id.startsWith("pl") || id.startsWith("mpreb_") || id.startsWith("olak5uy_")) return false
            if (bannedKeywords.any { b -> a.contains(b) || t.contains(b) }) return false
            return true
        }

        val items = mutableListOf<HomeMediaItem>()
        fun addUniqueItem(cand: HomeMediaItem) {
            if (items.size >= 6) return
            val exists = items.any {
                it.id == cand.id || (it.title.equals(cand.title, ignoreCase = true) && it.subtitle.equals(cand.subtitle, ignoreCase = true))
            }
            if (!exists) items.add(cand)
        }

        // 1. Most recently played online tracks (up to 2 slots so playing a song immediately updates Quick Dial)
        activeHistory
            .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
            .map { it.toHomeMediaItem() }
            .filter(::isAuthenticCleanSong)
            .take(2)
            .forEach(::addUniqueItem)

        // 2. Most recently liked online track (up to 1 slot)
        likedTracks
            .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
            .map { it.toHomeMediaItem() }
            .filter(::isAuthenticCleanSong)
            .take(1)
            .forEach(::addUniqueItem)

        // 3. Personalized recommendations from live YTM + automix + artist/search seeds
        personalizedTracks
            .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
            .map { it.toHomeMediaItem() }
            .filter(::isAuthenticCleanSong)
            .forEach(::addUniqueItem)

        // 4. Fallback to remaining active history or trending hits to guarantee 6 clean playable tracks
        if (items.size < 6) {
            activeHistory
                .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
                .map { it.toHomeMediaItem() }
                .filter(::isAuthenticCleanSong)
                .forEach(::addUniqueItem)
        }
        if (items.size < 6) {
            deduplicatedTrendingHits
                .filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
                .map { it.toHomeMediaItem() }
                .filter(::isAuthenticCleanSong)
                .forEach(::addUniqueItem)
        }

        items.take(6)
    }

    val quickDialIdSet = remember(quickDialItems) {
        quickDialItems.map { it.id }.toSet()
    }

    // Prepare Trending Items (Deduplicated; when a category chip like Energize or Trending is selected, never mix personal history!)
    val trendingItems = remember(deduplicatedTrendingHits, personalizedTracks, deduplicatedAllTracks, excludedHeroIds, quickDialIdSet, selectedMood) {
        val isAll = selectedMood.equals("All", ignoreCase = true)
        val basePool = if (!isAll && deduplicatedTrendingHits.isNotEmpty()) {
            deduplicatedTrendingHits
        } else if (deduplicatedTrendingHits.isNotEmpty()) {
            (deduplicatedTrendingHits + personalizedTracks).distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
        } else {
            deduplicatedAllTracks
        }
        val filtered = if (isAll) {
            basePool.filterNot { it.id in excludedHeroIds || it.id in quickDialIdSet || it.isLocalOnlyTrack }
        } else {
            basePool.filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
        }
        val effectivePool = if (filtered.size >= 8) filtered else basePool.filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }
        effectivePool.take(24).map { it.toHomeMediaItem() }
    }

    // Prepare Fresh New Music Items (Deduplicated, excluding Quick Dial items & local songs)
    val freshItems = remember(freshReleases, deduplicatedTrendingHits, deduplicatedAllTracks, excludedHeroIds, quickDialIdSet, isOfflineMode) {
        val pool = if (isOfflineMode) {
            deduplicatedAllTracks.take(12)
        } else if (freshReleases.isNotEmpty()) {
            freshReleases
                .filterNot { it.isLocalOnlyTrack || it.id in quickDialIdSet }
                .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
                .ifEmpty { freshReleases.filterNot { it.isLocalOnlyTrack } }
        } else if (deduplicatedTrendingHits.isNotEmpty()) {
            deduplicatedTrendingHits.filterNot { it.id in quickDialIdSet }.take(12)
        } else {
            deduplicatedAllTracks.take(12)
        }
        pool.filterNot { it.id in excludedHeroIds || it.isLocalOnlyTrack }.take(12).map { it.toHomeMediaItem() }
    }

    // Prepare YouTube Mixes Shelf (authentic continuous mixes from YouTube Music + fallback seed mixes when online)
    val youtubeMixes = remember(suggestedYoutubeMixes, personalizedTracks, deduplicatedTrendingHits, isOfflineMode, selectedMood) {
        if (isOfflineMode) emptyList()
        else {
            val fromSuggested = suggestedYoutubeMixes.map { col ->
                HomeMediaItem(
                    id = col.browseId.ifBlank { col.playlistId.orEmpty() },
                    title = col.title,
                    subtitle = col.subtitle,
                    thumbnailUrl = col.thumbnailUrl,
                    isMix = true,
                    isAlbumOrPlaylist = true
                )
            }
            if (fromSuggested.isNotEmpty()) {
                fromSuggested.distinctBy { it.id }.take(10)
            } else {
                val seedPool = if (selectedMood.equals("All", ignoreCase = true)) {
                    (personalizedTracks + deduplicatedTrendingHits)
                } else {
                    deduplicatedTrendingHits
                }
                seedPool
                    .filterNot { it.isLocalOnlyTrack }
                    .distinctBy { it.artist.lowercase().trim() }
                    .take(6)
                    .map { track ->
                        HomeMediaItem(
                            id = "RDAMVM${track.id}",
                            title = "${track.artist} Mix",
                            subtitle = "YouTube Mix • Based on ${track.title}",
                            thumbnailUrl = track.artUrl,
                            isMix = true,
                            isAlbumOrPlaylist = true,
                            originalTrack = track
                        )
                    }
            }
        }
    }

    // Prepare Featured Albums & Curated YouTube Playlists (Liked Songs on far left when in All, plus local & online YouTube playlists)
    val albumsAndPlaylists = remember(playlists, likedTracks, suggestedYoutubePlaylists, isOfflineMode, selectedMood) {
        val isAll = selectedMood.equals("All", ignoreCase = true)
        val list = mutableListOf<HomeMediaItem>()
        if (isAll && likedTracks.isNotEmpty()) {
            list.add(
                HomeMediaItem(
                    id = "liked_songs",
                    title = "Liked Songs",
                    subtitle = "${likedTracks.size} songs",
                    thumbnailUrl = likedTracks.first().artUrl,
                    isAlbumOrPlaylist = true
                )
            )
        }
        if (isAll) {
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
        }
        if (!isOfflineMode) {
            list.addAll(
                suggestedYoutubePlaylists.map { col ->
                    HomeMediaItem(
                        id = col.browseId.ifBlank { col.playlistId.orEmpty() },
                        title = col.title,
                        subtitle = col.subtitle,
                        thumbnailUrl = col.thumbnailUrl,
                        isMix = col.type.equals("MIX", ignoreCase = true),
                        isAlbumOrPlaylist = true
                    )
                }
            )
        }
        list.distinctBy { it.id }.take(14)
    }

    // Dynamic suggested artists strictly personalized from online listening history and liked songs (never local songs!)
    val dynamicArtists = remember(activeHistory, likedTracks, trendingHits, downloadedTracks) {
        val bannedArtistKeywords = setOf(
            "local artist", "local audio", "local video", "device storage", "downloaded in seitharol",
            "<unknown>", "unknown artist", "unknown", "various artists", "various", "download", "seitharol",
            "video", "official", "audio", "records", "music", "entertainment", "channel", "production",
            "vevo", "topic", "remix", "slowed", "reverb", "mashup", "playlist", "compilation", "soundtrack",
            "theme", "bgm", "status", "lyrics", "cover", "extended", "1 hour", "8d", "nightcore", "workout",
            "relaxing", "sleep", "beats", "lofi", "lo-fi", "chill", "trap", "bass", "boosted"
        )
        fun isValidArtist(a: String): Boolean {
            val trimmed = a.trim()
            if (trimmed.length < 2 || trimmed.length > 32) return false
            if (trimmed.all { it.isDigit() || it.isWhitespace() }) return false
            val lower = trimmed.lowercase()
            if (bannedArtistKeywords.any { lower.contains(it) }) return false
            if (lower.contains("http") || lower.contains(".com") || lower.contains(".net") || lower.contains("www.")) return false
            if (lower.contains("?") || lower.contains("!") || lower.contains("/") || lower.contains("\\") || lower.contains("|")) return false
            return true
        }

        val artistScores = mutableMapOf<String, Int>()
        likedTracks.filterNot { it.isLocalOnlyTrack }.forEach { track ->
            val a = track.artist.trim()
            if (isValidArtist(a)) {
                artistScores[a] = (artistScores[a] ?: 0) + 3
            }
        }
        activeHistory.filterNot { it.isLocalOnlyTrack }.forEach { track ->
            val a = track.artist.trim()
            if (isValidArtist(a)) {
                artistScores[a] = (artistScores[a] ?: 0) + 2
            }
        }
        downloadedTracks.filterNot { it.isLocalOnlyTrack }.forEach { track ->
            val a = track.artist.trim()
            if (isValidArtist(a)) {
                artistScores[a] = (artistScores[a] ?: 0) + 1
            }
        }

        val personalized = artistScores.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }

        if (personalized.isNotEmpty()) {
            personalized.take(4)
        } else {
            trendingHits
                .filterNot { it.isLocalOnlyTrack }
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
            "Relax",
            "Energize",
            "Workout",
            "Commute",
            "Focus",
            "Chill",
            "Feel Good",
            "Party",
            "Romantic",
            "Sad",
            "Trending",
            "Manipuri Song",
            "Lo-Fi",
            "Sleep"
        )
    }

    SeitharolPullToRefreshBox(
        isRefreshing = isLoadingHome && hasCompletedInitialHomeLoad,
        onRefresh = {
            viewModel.loadHomeScreenMusic()
            viewModel.loadDiscoverFeed()
        },
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("home_screen")
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // ────────────────────────────────────────────────────────────
            // Top Navigation Bar: "SEITHAROL"
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
                                NotificationBellAlertSvgIcon(
                                    tint = if (hasUnreadNotifications) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    hasUnreadAlert = hasUnreadNotifications,
                                    alertDotColor = Color(0xFFE53935),
                                    modifier = Modifier.size(21.dp)
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
                                text = if (isOfflineMode) "Search your downloaded offline songs..." else "Search songs, artists, albums, or mixes...",
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

            if (isOfflineMode) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
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
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AirplanemodeActive,
                                    contentDescription = "Airplane Mode Offline",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Airplane Mode Offline Active",
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${downloadedTracks.size} cached tracks ready for pure offline playback",
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.setOfflineMode(false) }) {
                                Text(
                                    text = "Go Online",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────
            // 1. OuterTune / YouTube Music Category Filter Chip Bar (Google Pixel Material 3 UI)
            // ────────────────────────────────────────────────────────────
            item {
                val isAllSelected = selectedMood.equals("All", ignoreCase = true)
                val orderedChips = remember(selectedMood, moodChips) {
                    if (isAllSelected) moodChips
                    else listOf(selectedMood) + moodChips.filterNot { it.equals(selectedMood, ignoreCase = true) }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isAllSelected) {
                        item(key = "clear_category_chip") {
                            Surface(
                                onClick = { viewModel.selectHomeMood("All") },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear Category Filter",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    items(orderedChips, key = { "chip_$it" }) { mood ->
                        val isSelected = mood.equals(selectedMood, ignoreCase = true)
                        Surface(
                            onClick = {
                                if (isSelected) {
                                    viewModel.selectHomeMood("All")
                                } else {
                                    viewModel.selectHomeMood(mood)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                                Text(
                                    text = mood,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
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

            if (trendingItems.isEmpty() && freshItems.isEmpty() && quickDialItems.isEmpty()) {
                if (!hasCompletedInitialHomeLoad || isLoadingHome) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Curating your music feed...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                } else if (isOfflineMode) {
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = "No offline songs downloaded yet",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    text = "Disable Airplane Mode Offline or download tracks first to listen offline.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(24.dp))
                                        .clickable { viewModel.setOfflineMode(false) }
                                        .testTag("home_retry_btn"),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "  Go Online  ",
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
                } else {
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
                                Text(
                                    text = "Swipe down or tap below to refresh your feed",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(24.dp))
                                        .clickable { viewModel.loadHomeScreenMusic() }
                                        .testTag("home_retry_btn"),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "  Refresh Feed  ",
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
            }

            val isAllMood = selectedMood.equals("All", ignoreCase = true)

            if (isAllMood) {
                // ────────────────────────────────────────────────────────────
                // DEFAULT HOME VIEW (OuterTune + YouTube Music Structure)
                // ────────────────────────────────────────────────────────────
                if (quickDialItems.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = "Listen Again",
                                subtitle = "Your speed dial & quick favorites"
                            )
                            Spacer(modifier = Modifier.height(12.dp))
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
                }

                if (trendingItems.isNotEmpty()) {
                    item {
                        val shelfTitle = if (isOfflineMode) "Offline Library Tracks" else "Quick Picks"
                        val shelfSubtitle = if (isOfflineMode) "Ready for zero-data playback" else "Start a radio or swipe through top tracks"
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
                            onStartRadio = { item ->
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.startRadioMix(track)
                            },
                            onToggleLike = { item ->
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.toggleLike(track)
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

                if (youtubeMixes.isNotEmpty()) {
                    item {
                        MediaCarouselShelf(
                            title = "Mixed for You",
                            subtitle = "Continuous YouTube Music stations & personalized mixes",
                            items = youtubeMixes,
                            badgeText = "MIX",
                            onItemClick = { item ->
                                onNavigateToPlaylist(item.id)
                            }
                        )
                    }
                }

                if (albumsAndPlaylists.isNotEmpty()) {
                    item {
                        MediaCarouselShelf(
                            title = "Featured Playlists & Albums",
                            subtitle = "Curated community playlists and full releases",
                            items = albumsAndPlaylists,
                            badgeText = "PLAYLIST",
                            onItemClick = { item ->
                                onNavigateToPlaylist(item.id)
                            }
                        )
                    }
                }

                // OuterTune / YouTube Music "Moods & Genres" Google Pixel Grid Section
                item {
                    OuterTuneMoodsAndGenresSection(
                        categories = moodChips,
                        selectedCategory = selectedMood,
                        onSelectCategory = { cat -> viewModel.selectHomeMood(cat) }
                    )
                }

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
            } else {
                // ────────────────────────────────────────────────────────────
                // CATEGORY ACTIVE VIEW (Exact OuterTune & YouTube Music Category Page Structure)
                // 1. Google Pixel Category Hero Card (Title + Vibe Subtitle + Play All / Shuffle)
                // 2. Featured <Category> Playlists Carousel (Top Shelf, just like YT Music / OuterTune!)
                // 3. <Category> Quick Picks (4-Row Horizontal Snap Grid)
                // 4. <Category> Mixes & Stations Carousel
                // 5. <Category> New & Popular Releases Shelf
                // 6. Explore Other Moods & Genres Grid
                // ────────────────────────────────────────────────────────────
                item {
                    val categorySubtitle = when {
                        selectedMood.equals("Relax", true) -> "Unwind with soothing acoustic, ambient & calm melodies"
                        selectedMood.equals("Energize", true) -> "High-energy pop, upbeat dance & power anthems"
                        selectedMood.equals("Workout", true) -> "High-tempo gym phonk, cardio & training motivation"
                        selectedMood.equals("Commute", true) -> "Road trip favorites, driving hits & singalong classics"
                        selectedMood.equals("Focus", true) -> "Deep concentration beats, study lofi & ambient piano"
                        selectedMood.equals("Chill", true) -> "Laid-back indie, R&B & late-night chillout vibes"
                        selectedMood.equals("Feel Good", true) -> "Sunshine pop, uplifting hooks & positive energy"
                        selectedMood.equals("Party", true) -> "Club bangers, weekend dancefloor & remix hits"
                        selectedMood.equals("Romantic", true) -> "Heartfelt love ballads, acoustic duets & soulful melodies"
                        selectedMood.equals("Sad", true) -> "Emotional ballads, acoustic heartbreak & late-night reflection"
                        selectedMood.equals("Trending", true) -> "Top viral chart hits & fastest-rising global and regional tracks"
                        selectedMood.equals("Manipuri Song", true) -> "Official Manipuri hits, modern folk & Imphal chart toppers"
                        selectedMood.equals("Lo-Fi", true) -> "Cozy late-night lofi beats, chill hop & rainy aesthetics"
                        selectedMood.equals("Sleep", true) -> "Peaceful bedtime ambient, soft piano & restful soundscapes"
                        else -> "Curated playlists, mixes, and top songs for $selectedMood"
                    }

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "MOODS & GENRES",
                                        fontSize = 10.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.8.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    onClick = { viewModel.selectHomeMood("All") },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Back to All",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "All Home",
                                            fontSize = 11.5.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = selectedMood,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 26.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = categorySubtitle,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (trendingItems.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = {
                                            val queueTracks = trendingItems.map { it.originalTrack ?: it.toTrack() }
                                            viewModel.playQueue(queueTracks, 0, isPlaylist = true)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play Category",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Play $selectedMood",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            val queueTracks = trendingItems.map { it.originalTrack ?: it.toTrack() }.shuffled()
                                            viewModel.playQueue(queueTracks, 0, isPlaylist = true)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Shuffle,
                                                contentDescription = "Shuffle Category",
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Text(
                                                text = "Shuffle",
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 1. Featured Playlists for Category (First Shelf on YT Music / OuterTune Category pages!)
                if (albumsAndPlaylists.isNotEmpty()) {
                    item {
                        MediaCarouselShelf(
                            title = "Featured $selectedMood Playlists",
                            subtitle = "Curated YouTube Music playlists for $selectedMood",
                            items = albumsAndPlaylists,
                            badgeText = "PLAYLIST",
                            onItemClick = { item ->
                                onNavigateToPlaylist(item.id)
                            }
                        )
                    }
                }

                // 2. Category Quick Picks (4-Row Horizontal Snap Grid)
                if (trendingItems.isNotEmpty()) {
                    item {
                        TrendingSongsHorizontalShelf(
                            tracks = trendingItems,
                            title = "$selectedMood Quick Picks",
                            subtitle = "Top songs & community favorites in $selectedMood",
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
                            onStartRadio = { item ->
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.startRadioMix(track)
                            },
                            onToggleLike = { item ->
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.toggleLike(track)
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

                // 3. Category Mixes & Continuous Stations
                if (youtubeMixes.isNotEmpty()) {
                    item {
                        MediaCarouselShelf(
                            title = "$selectedMood Mixes & Stations",
                            subtitle = "Non-stop radio mixes tuned to $selectedMood",
                            items = youtubeMixes,
                            badgeText = "MIX",
                            onItemClick = { item ->
                                onNavigateToPlaylist(item.id)
                            }
                        )
                    }
                }

                // 4. Category Fresh Releases & More Hits
                if (freshItems.isNotEmpty()) {
                    item {
                        FreshNewMusicShelf(
                            tracks = freshItems,
                            title = "More $selectedMood Releases",
                            subtitle = "Fresh tracks and popular picks in $selectedMood",
                            onTrackClick = { item ->
                                val track = item.originalTrack ?: item.toTrack()
                                viewModel.playTrack(track)
                            }
                        )
                    }
                }

                // 5. Browse Other Moods & Genres
                item {
                    OuterTuneMoodsAndGenresSection(
                        categories = moodChips,
                        selectedCategory = selectedMood,
                        onSelectCategory = { cat -> viewModel.selectHomeMood(cat) }
                    )
                }
            }

            // ────────────────────────────────────────────────────────────
            // 7. "Suggested Artists" + Kangla Gate Aesthetic Footer Image
            // ────────────────────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HomeScreenKanglaFooterImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .zIndex(0f)
                    )

                    if (dynamicArtists.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 195.dp)
                                .zIndex(1f)
                        ) {
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
                    } else {
                        Spacer(modifier = Modifier.height(195.dp))
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
                viewModel.addTrackToPlaylist(playlistId, track)
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

    var showDeveloperWelcomePopup by remember(userSettings.hasSeenDeveloperWelcomePopup) {
        mutableStateOf(!userSettings.hasSeenDeveloperWelcomePopup)
    }
    if (showDeveloperWelcomePopup) {
        DeveloperWelcomePopupModal(
            userName = userSettings.userName.trim().ifBlank { "Friend" },
            onDismiss = {
                showDeveloperWelcomePopup = false
                appPreferences.markDeveloperWelcomePopupSeen()
            }
        )
    }
}

@Composable
fun DeveloperWelcomePopupModal(
    userName: String,
    onDismiss: () -> Unit
) {
    var countdownSeconds by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (countdownSeconds > 0) {
            kotlinx.coroutines.delay(1000L)
            countdownSeconds--
        }
    }
    val canDismissWithCross = countdownSeconds <= 0

    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            if (canDismissWithCross) {
                onDismiss()
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = canDismissWithCross,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 430.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_splash_logo),
                                contentDescription = "Seitharol Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Text(
                                text = "SEITHAROL",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .then(
                                    if (canDismissWithCross) {
                                        Modifier.clickable { onDismiss() }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (canDismissWithCross) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${countdownSeconds}s",
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Welcome $userName, I'm Yumkhaibam Johnson Singh. I dedicated 71 days to developing this app for you. My hope is to provide an ad-free and limitless experience, serving as a supportive background companion for your work and an emotional stabilizer for those with broken hearts.",
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    if (canDismissWithCross) {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Get Started",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Please wait ${countdownSeconds}s...",
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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
    val cleanBentoItems = remember(trendingSongs) {
        trendingSongs.filter { !it.isMix && !it.isAlbumOrPlaylist && !it.title.contains("SuperMix", true) && !it.title.contains("Radio", true) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
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

        item {
            TrendingSongsHorizontalShelf(
                tracks = trendingSongs,
                onTrackClick = onTrackClick,
                onMoreClick = onMoreClick
            )
        }

        item {
            FreshNewMusicShelf(
                tracks = freshNewMusic,
                onTrackClick = onTrackClick
            )
        }

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

        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(195.dp))
                HomeScreenKanglaFooterImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                )
            }
        }
    }
}

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
    onStartRadio: (HomeMediaItem) -> Unit = {},
    onToggleLike: (HomeMediaItem) -> Unit = {},
    onAddToPlaylist: (HomeMediaItem) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onDownload: (HomeMediaItem, DownloadTier) -> Unit = { _, _ -> }
) {
    val gridState = rememberLazyGridState()
    val snapBehavior = rememberSnapFlingBehavior(lazyGridState = gridState)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            subtitle = subtitle
        )

        Spacer(modifier = Modifier.height(12.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = if (maxWidth >= 600.dp) {
                (maxWidth * 0.45f).coerceIn(280.dp, 360.dp)
            } else {
                (maxWidth - 40.dp).coerceIn(260.dp, 344.dp)
            }
            LazyHorizontalGrid(
                rows = GridCells.Fixed(4),
                state = gridState,
                flingBehavior = snapBehavior,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                itemsIndexed(tracks, key = { idx, item -> "${item.id}_$idx" }) { _, track ->
                    val isPlaying = currentPlayingId == track.id
                    var showMenu by remember { mutableStateOf(false) }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .width(cardWidth)
                            .height(62.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTrackClick(track) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp)
                    ) {
                        val thumbUrl = upgradeThumbnail(track.thumbnailUrl)
                        if (thumbUrl.isBlank()) {
                            Image(
                                painter = appLogoPainter,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            AsyncImage(
                                model = thumbUrl,
                                contentDescription = track.title,
                                placeholder = appLogoPainter,
                                error = appLogoPainter,
                                fallback = appLogoPainter,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

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
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500)
                            )
                            Text(
                                text = track.subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            var showDownloadSheet by remember { mutableStateOf(false) }
                            val context = androidx.compose.ui.platform.LocalContext.current

                            Surface(
                                onClick = { onTrackClick(track) },
                                shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )

                            Box {
                                Surface(
                                    onClick = {
                                        if (onMoreClick != null) {
                                            onMoreClick(track)
                                        } else {
                                            showMenu = true
                                        }
                                    },
                                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 10.dp, bottomEnd = 10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "More Options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                if (onMoreClick == null) {
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        modifier = Modifier
                                            .widthIn(min = 235.dp, max = 290.dp)
                                            .background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = track.title,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontFamily = SyneFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp,
                                                lineHeight = 18.sp,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = track.subtitle,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = OutfitFontFamily,
                                                fontSize = 11.5.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                        DropdownMenuItem(
                                            text = { Text("Play Now", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                                            onClick = {
                                                showMenu = false
                                                onTrackClick(track)
                                            }
                                        )
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
                                            text = { Text("Start Radio Mix", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                onStartRadio(track)
                                            }
                                        )
                                        val isLiked = track.originalTrack?.isLiked == true
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                                    null,
                                                    tint = if (isLiked) SeitharolRedHeart else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onToggleLike(track)
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
                                        val isNavigable = track.originalTrack?.isNavigableArtist ?: (
                                            artistName.isNotBlank() &&
                                                !artistName.equals("Unknown Artist", true) &&
                                                !artistName.equals("Local Audio", true) &&
                                                !artistName.equals("Local Video", true)
                                            )
                                        if (isNavigable) {
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
                                            text = { Text("Download (Format & Quality)", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) },
                                            onClick = {
                                                showMenu = false
                                                showDownloadSheet = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Share", color = MaterialTheme.colorScheme.onSurface) },
                                            leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {
                                                showMenu = false
                                                runCatching {
                                                    val domTrack = track.originalTrack ?: track.toTrack()
                                                    val msg = com.example.ui.components.SeitharolDeepLinkHelper.buildTrackShareMessage(domTrack)
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(android.content.Intent.EXTRA_TEXT, msg)
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent, "Share Track"))
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            if (showDownloadSheet) {
                                val domTrack = track.originalTrack ?: track.toTrack()
                                com.example.ui.components.TrackDownloadFormatQualitySheet(
                                    track = domTrack,
                                    onDismiss = { showDownloadSheet = false },
                                    onConfirmDownload = { chosenTier ->
                                        showDownloadSheet = false
                                        onDownload(track, chosenTier)
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

@Composable
fun FreshNewMusicShelf(
    tracks: List<HomeMediaItem>,
    title: String = "Fresh New Music",
    subtitle: String = "Latest releases in high definition",
    onTrackClick: (HomeMediaItem) -> Unit
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            subtitle = subtitle
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(tracks.distinctBy { it.id }, key = { idx, item -> "${item.id}_$idx" }) { _, track ->
                Column(
                    modifier = Modifier
                        .width(196.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTrackClick(track) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(196.dp)
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        val thumbUrl = upgradeThumbnail(track.thumbnailUrl)
                        if (thumbUrl.isBlank()) {
                            Image(
                                painter = appLogoPainter,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            AsyncImage(
                                model = thumbUrl,
                                contentDescription = track.title,
                                placeholder = appLogoPainter,
                                error = appLogoPainter,
                                fallback = appLogoPainter,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

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

                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                HeadphoneSvgIcon(
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
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
 * Spotify-style 2-Column "Speed Dial / Quick Picks" Bento Grid:
 * - Uses 76.dp card height with tight lineHeight (15.sp title, 13.sp subtitle) so a 2-row song title
 *   NEVER overflows or clips the artist name below it.
 * - Uses Seitharol app logo (`R.drawable.ic_splash_logo`) as placeholder/error/fallback.
 */
@Composable
fun QuickDialBentoSection(
    items: List<HomeMediaItem>,
    onItemClick: (HomeMediaItem) -> Unit
) {
    val topItems = items.take(12)
    val columns = topItems.chunked(2)
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = if (maxWidth >= 600.dp) {
            (maxWidth * 0.44f).coerceIn(260.dp, 340.dp)
        } else {
            (maxWidth * 0.74f).coerceIn(248.dp, 320.dp)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(columns.size) { colIdx ->
                val colItems = columns[colIdx]
                Column(
                    modifier = Modifier.width(cardWidth),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    colItems.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onItemClick(item) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val thumbUrl = upgradeThumbnail(item.thumbnailUrl)
                                if (thumbUrl.isBlank()) {
                                    Image(
                                        painter = appLogoPainter,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(76.dp)
                                    )
                                } else {
                                    AsyncImage(
                                        model = thumbUrl,
                                        contentDescription = item.title,
                                        placeholder = appLogoPainter,
                                        error = appLogoPainter,
                                        fallback = appLogoPainter,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(76.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        lineHeight = 15.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.subtitle.isNotBlank() && !item.subtitle.equals(item.title, ignoreCase = true)) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.subtitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            fontSize = 11.sp,
                                            lineHeight = 13.sp,
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
}

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

@Composable
fun HomeScreenKanglaFooterImage(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawableResId = remember(context) {
        val directRes = runCatching {
            com.example.R.drawable.homescreen_footer
        }.getOrDefault(0)
        if (directRes != 0) return@remember directRes

        val pkg = context.packageName
        val candidates = listOf("homescreen_footer", "home_screen_footer", "HomeScreen_footer", "kangla_gate")
        candidates.firstNotNullOfOrNull { name ->
            context.resources.getIdentifier(name, "drawable", pkg).takeIf { it != 0 }
        } ?: 0
    }

    Box(
        modifier = modifier
            .zIndex(0f)
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Bottom),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (drawableResId != 0) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = drawableResId),
                contentDescription = "Kangla Gate Footer",
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.BottomCenter,
                alpha = 0.88f,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )
        } else {
            AsyncImage(
                model = "file:///android_asset/homescreen_footer.png",
                contentDescription = "Kangla Gate Footer",
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.BottomCenter,
                alpha = 0.88f,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )
        }
    }
}


/**
 * OuterTune / YouTube Music "Moods & Genres" 2-Row Horizontal Grid Section
 * built with Google Pixel Material 3 tonal cards and color-coded accent bars.
 */
@Composable
fun OuterTuneMoodsAndGenresSection(
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit
) {
    val columns = remember(categories) { categories.chunked(2) }
    val accentColors = remember {
        listOf(
            Color(0xFFFF5252),
            Color(0xFF448AFF),
            Color(0xFF00E676),
            Color(0xFFFFAB40),
            Color(0xFFE040FB),
            Color(0xFF18FFFF),
            Color(0xFFFFD740),
            Color(0xFFFF4081)
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Moods & Genres",
            subtitle = "Explore YouTube Music & OuterTune category feeds"
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(columns) { colIdx, pair ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.width(168.dp)
                ) {
                    pair.forEachIndexed { rowIdx, category ->
                        val isSelected = category.equals(selectedCategory, ignoreCase = true)
                        val stripeColor = accentColors[(colIdx * 2 + rowIdx) % accentColors.size]
                        Surface(
                            onClick = {
                                if (isSelected) onSelectCategory("All") else onSelectCategory(category)
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .fillMaxHeight()
                                        .background(stripeColor)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = category,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
