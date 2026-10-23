package com.example.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.Track
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.LikedSongsScreen
import com.example.ui.theme.*
import com.seitharol.ui.components.HomeNavSvgIcon
import com.seitharol.ui.settings.InfoDetailScreen
import com.seitharol.ui.settings.InfoPageType
import kotlinx.coroutines.flow.collectLatest

enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.VideoLibrary),
    PROFILE("Profile", Icons.Default.Person)
}

sealed interface SubScreen {
    data object None : SubScreen
    data class Artist(val artistName: String) : SubScreen
    data class Playlist(val playlistId: String, val returnSubScreen: SubScreen? = null) : SubScreen
    data object History : SubScreen
    data object Downloads : SubScreen
    data object LikedSongs : SubScreen
    data class InfoDetail(val pageType: InfoPageType) : SubScreen
    data object SoundSettings : SubScreen
    data object Discover : SubScreen
    data object LocalMusic : SubScreen
}

@Composable
private fun DiscoverSubScreenHost(
    viewModel: CascadeViewModel,
    onVisitArtist: (String) -> Unit,
    onOpenFullSongMaximized: (com.seitharol.domain.model.NormalizedTrack) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val downloadService = remember { com.seitharol.data.download.DownloadPreferencesAndService.getInstance(context) }
    val previewExoPlayer = remember {
        val resolvingFactory = com.seitharol.player.OuterTuneResolvingDataSource.createFactory {
            com.seitharol.data.resolver.AudioQuality.LOW
        }
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, resolvingFactory)
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    val seitharolPlayerManager = remember(previewExoPlayer) {
        val innerTube = com.seitharol.data.resolver.DefaultInnerTubeProvider(viewModel.resolverEngine.youtubeInnerTubeProvider)
        val jioSaavn = com.seitharol.data.resolver.DefaultJioSaavnProvider(viewModel.resolverEngine.jioSaavnProvider)
        val matcher = com.seitharol.data.resolver.TrackMatcher(innerTube, jioSaavn)
        val engine = com.seitharol.data.resolver.ResolverEngine(context, matcher, innerTube, jioSaavn)
        com.seitharol.player.AudioPlayerManager(previewExoPlayer, engine, context.applicationContext)
    }
    DisposableEffect(previewExoPlayer) {
        onDispose {
            runCatching { seitharolPlayerManager.stopPreviewAndClear() }
            runCatching { previewExoPlayer.release() }
        }
    }

    val feedTracks by viewModel.discoverTracks.collectAsState()
    com.seitharol.ui.discover.DiscoverScreen(
        feedTracks = feedTracks,
        audioPlayerManager = seitharolPlayerManager,
        downloadService = downloadService,
        onSearchPreviewTracks = { query -> viewModel.searchDiscoverPreviews(query) },
        onToggleLike = { track ->
            viewModel.toggleLike(
                Track(
                    id = track.sourceId,
                    title = track.title,
                    artist = track.artists.firstOrNull() ?: "",
                    album = track.album ?: "",
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isLiked = track.isLiked
                )
            )
        },
        onVisitArtist = { name, _ ->
            seitharolPlayerManager.stopPreviewAndClear()
            onVisitArtist(name)
        },
        onOpenFullSongMaximized = { resolvedTrack ->
            seitharolPlayerManager.stopPreviewAndClear()
            onOpenFullSongMaximized(resolvedTrack)
        },
        onTrackDownloadedLocally = { localNormalizedTrack ->
            viewModel.registerLocallyDownloadedTrack(localNormalizedTrack)
        },
        onNavigateBack = {
            seitharolPlayerManager.stopPreviewAndClear()
            onNavigateBack()
        }
    )
}

@Composable
fun CascadeApp(
    viewModel: CascadeViewModel,
    openNotificationsRequest: Boolean = false,
    onNotificationsOpened: () -> Unit = {}
) {
    val playerState by viewModel.playerState.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val context = LocalContext.current
    val compositionStartMs = remember { System.currentTimeMillis() }

    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var previousDestination by remember { mutableStateOf(AppDestination.HOME) }
    var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var previousSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Modals state
    var showSleepTimer by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var shareTargetArtist by remember { mutableStateOf<String?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var pendingTrackForNewPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openNotificationsRequest) {
        if (openNotificationsRequest) {
            showNotifications = true
            onNotificationsOpened()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.skipNotice.collectLatest { msg ->
            val professionalMsg = com.example.util.ErrorFormatter.sanitizeNotice(msg, context)
            val isColdStart = (System.currentTimeMillis() - compositionStartMs) < 4000L
            if (isColdStart && professionalMsg.contains("No internet connection", ignoreCase = true)) {
                return@collectLatest
            }
            snackbarHostState.showSnackbar(message = professionalMsg, duration = SnackbarDuration.Short)
        }
    }

    // Collect artist navigation events from PlayerScreen artist name clicks & deep links
    LaunchedEffect(Unit) {
        viewModel.artistNavigationEvent.collectLatest { artistName ->
            isPlayerExpanded = false
            currentSubScreen = SubScreen.Artist(artistName)
        }
    }

    // Auto-expand PlayerScreen when opening a shared track via HTTPS deep link or seitharol://
    LaunchedEffect(Unit) {
        viewModel.playerExpandEvent.collectLatest {
            currentSubScreen = SubScreen.None
            isPlayerExpanded = true
        }
    }

    // On visit to Discover and Download page, pause current playing music
    LaunchedEffect(currentSubScreen) {
        if (currentSubScreen is SubScreen.Discover) {
            viewModel.pause()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isTablet = maxWidth >= 600.dp

        if (isTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    header = {
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp, bottom = 12.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxHeight().testTag("tablet_nav_rail")
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AppDestination.values().forEach { dest ->
                        val isSelected = currentDestination == dest && currentSubScreen is SubScreen.None
                        val iconTint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        NavigationRailItem(
                            selected = isSelected,
                            onClick = {
                                if (dest == AppDestination.SEARCH) {
                                    previousSubScreen = currentSubScreen
                                }
                                currentSubScreen = SubScreen.None
                                if (currentDestination != dest) {
                                    previousDestination = currentDestination
                                    currentDestination = dest
                                }
                            },
                            icon = {
                                if (dest == AppDestination.HOME) {
                                    HomeNavSvgIcon(tint = iconTint, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(
                                        dest.icon,
                                        contentDescription = dest.label,
                                        tint = iconTint
                                    )
                                }
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    val isSettingsSelected = currentSubScreen is SubScreen.SoundSettings
                    val settingsIconTint = if (isSettingsSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    NavigationRailItem(
                        selected = isSettingsSelected,
                        onClick = { currentSubScreen = SubScreen.SoundSettings },
                        icon = {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = settingsIconTint
                            )
                        },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (val sub = currentSubScreen) {
                        is SubScreen.Artist -> ArtistScreen(
                            artistName = sub.artistName,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onShareArtist = { shareTargetArtist = it },
                            onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it, returnSubScreen = currentSubScreen) }
                        )
                        is SubScreen.Playlist -> PlaylistScreen(
                            playlistId = sub.playlistId,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = sub.returnSubScreen ?: SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                        is SubScreen.History -> HistoryScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                        is SubScreen.Downloads -> DownloadsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                        is SubScreen.LikedSongs -> LikedSongsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                        is SubScreen.InfoDetail -> InfoDetailScreen(
                            pageType = sub.pageType,
                            onBack = { currentSubScreen = SubScreen.None }
                        )
                        is SubScreen.SoundSettings -> com.seitharol.ui.settings.GeneralSettingsScreen(
                            settingsManager = com.seitharol.data.preferences.AppSettingsManager.getInstance(context),
                            onOpenInfoPage = { currentSubScreen = SubScreen.InfoDetail(it) },
                            onNavigateBack = { currentSubScreen = SubScreen.None }
                        )
                        is SubScreen.LocalMusic -> {
                            val downloadedTracks by viewModel.downloadedTracks.collectAsState()
                            com.seitharol.ui.local.LocalMusicScreen(
                                appDownloadedTracks = downloadedTracks.map {
                                    com.seitharol.domain.model.NormalizedTrack(
                                        source = com.seitharol.domain.model.TrackSource.LOCAL_FILE,
                                        sourceId = it.id,
                                        title = it.title,
                                        artists = listOf(it.artist),
                                        album = it.album,
                                        durationMs = it.durationMs,
                                        artUrl = it.artUrl,
                                        isPlayableNatively = true,
                                        localPath = it.localPath
                                    )
                                },
                                availablePlaylists = playlists.map { it.id to it.title },
                                onPlayLocalTrack = { candidate ->
                                    val localTrack = Track(
                                        id = "local_${candidate.videoId.hashCode()}",
                                        title = candidate.title,
                                        artist = candidate.artist,
                                        album = candidate.albumName ?: "Local Storage",
                                        durationMs = candidate.durationSeconds * 1000L,
                                        artUrl = candidate.thumbnailUrl,
                                        genre = "Local Audio",
                                        isDownloaded = true,
                                        localPath = candidate.videoId
                                    )
                                    viewModel.playTrack(localTrack)
                                },
                                onAddLocalTrackToPlaylist = { plId, candidate ->
                                    val localTrack = Track(
                                        id = "local_${candidate.videoId.hashCode()}",
                                        title = candidate.title,
                                        artist = candidate.artist,
                                        album = candidate.albumName ?: "Local Storage",
                                        durationMs = candidate.durationSeconds * 1000L,
                                        artUrl = candidate.thumbnailUrl,
                                        genre = "Local Audio",
                                        isDownloaded = true,
                                        localPath = candidate.videoId
                                    )
                                    viewModel.addTrackToPlaylist(plId, localTrack)
                                },
                                onBack = { currentSubScreen = SubScreen.None }
                            )
                        }
                        is SubScreen.Discover -> {
                            DiscoverSubScreenHost(
                                viewModel = viewModel,
                                onVisitArtist = { name -> currentSubScreen = SubScreen.Artist(name) },
                                onOpenFullSongMaximized = { resolvedTrack ->
                                    currentSubScreen = SubScreen.None
                                    viewModel.playNormalizedTrackFull(resolvedTrack)
                                    isPlayerExpanded = true
                                },
                                onNavigateBack = { currentSubScreen = SubScreen.None }
                            )
                        }
                        is SubScreen.None -> when (currentDestination) {
                            AppDestination.HOME -> HomeScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToSearch = {
                                    previousSubScreen = currentSubScreen
                                    if (currentDestination != AppDestination.SEARCH) {
                                        previousDestination = currentDestination
                                        currentDestination = AppDestination.SEARCH
                                    }
                                },
                                onNavigateToProfile = {
                                    if (currentDestination != AppDestination.PROFILE) {
                                        previousDestination = currentDestination
                                        currentDestination = AppDestination.PROFILE
                                    }
                                },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = {
                                    if (it == "liked_songs") currentSubScreen = SubScreen.LikedSongs
                                    else currentSubScreen = SubScreen.Playlist(it)
                                },
                                onOpenNotifications = { showNotifications = true }
                            )
                            AppDestination.SEARCH -> SearchScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                onNavigateToHome = {
                                    if (previousSubScreen !is SubScreen.None) {
                                        currentSubScreen = previousSubScreen
                                        previousSubScreen = SubScreen.None
                                    } else {
                                        currentDestination = if (previousDestination != AppDestination.SEARCH) previousDestination else AppDestination.HOME
                                    }
                                },
                                onOpenDiscover = { currentSubScreen = SubScreen.Discover },
                                onPlayTrackAndMaximize = { track ->
                                    viewModel.playTrack(track)
                                    isPlayerExpanded = true
                                }
                            )
                            AppDestination.LIBRARY -> LibraryScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToHistory = { currentSubScreen = SubScreen.History },
                                onNavigateToDownloads = { currentSubScreen = SubScreen.Downloads },
                                onNavigateToLikedSongs = { currentSubScreen = SubScreen.LikedSongs },
                                onNavigateToLocalMusic = { currentSubScreen = SubScreen.LocalMusic },
                                onCreatePlaylist = { showCreatePlaylistDialog = true }
                            )
                            AppDestination.PROFILE -> ProfileScreen(
                                viewModel = viewModel,
                                onNavigateToSoundSettings = { currentSubScreen = SubScreen.SoundSettings },
                                onOpenNotifications = { showNotifications = true },
                                onOpenSleepTimer = { showSleepTimer = true },
                                onOpenInfoPage = { currentSubScreen = SubScreen.InfoDetail(it) }
                            )
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 400.dp)
                        .fillMaxHeight(),
                    color = SeitharolBg,
                    tonalElevation = 2.dp
                ) {
                    if (playerState.currentTrack != null) {
                        PlayerScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onDismiss = { },
                            onOpenEqualizer = { currentSubScreen = SubScreen.SoundSettings },
                            onOpenSleepTimer = { showSleepTimer = true },
                            onShareTrack = { title ->
                                runCatching {
                                    val shareMsg = playerState.currentTrack?.let {
                                        SeitharolDeepLinkHelper.buildTrackShareMessage(it)
                                    } ?: "Listen to $title on Seitharol!"
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareMsg)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Select a song to start playback",
                                color = SeitharolTextSecondary,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val sub = currentSubScreen) {
                    is SubScreen.Artist -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        ArtistScreen(
                            artistName = sub.artistName,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onShareArtist = { shareTargetArtist = it },
                            onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it, returnSubScreen = currentSubScreen) }
                        )
                    }
                    is SubScreen.Playlist -> {
                        BackHandler { currentSubScreen = sub.returnSubScreen ?: SubScreen.None }
                        PlaylistScreen(
                            playlistId = sub.playlistId,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = sub.returnSubScreen ?: SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                    }
                    is SubScreen.History -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        HistoryScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                    }
                    is SubScreen.Downloads -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        DownloadsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                    }
                    is SubScreen.LikedSongs -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        LikedSongsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                    }
                    is SubScreen.InfoDetail -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        InfoDetailScreen(
                            pageType = sub.pageType,
                            onBack = { currentSubScreen = SubScreen.None }
                        )
                    }
                    is SubScreen.SoundSettings -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        com.seitharol.ui.settings.GeneralSettingsScreen(
                            settingsManager = com.seitharol.data.preferences.AppSettingsManager.getInstance(context),
                            onOpenInfoPage = { currentSubScreen = SubScreen.InfoDetail(it) },
                            onNavigateBack = { currentSubScreen = SubScreen.None }
                        )
                    }
                    is SubScreen.LocalMusic -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        val downloadedTracks by viewModel.downloadedTracks.collectAsState()
                        com.seitharol.ui.local.LocalMusicScreen(
                            appDownloadedTracks = downloadedTracks.map {
                                com.seitharol.domain.model.NormalizedTrack(
                                    source = com.seitharol.domain.model.TrackSource.LOCAL_FILE,
                                    sourceId = it.id,
                                    title = it.title,
                                    artists = listOf(it.artist),
                                    album = it.album,
                                    durationMs = it.durationMs,
                                    artUrl = it.artUrl,
                                    isPlayableNatively = true,
                                    localPath = it.localPath
                                )
                            },
                            availablePlaylists = playlists.map { it.id to it.title },
                            onPlayLocalTrack = { candidate ->
                                val localTrack = Track(
                                    id = "local_${candidate.videoId.hashCode()}",
                                    title = candidate.title,
                                    artist = candidate.artist,
                                    album = candidate.albumName ?: "Local Storage",
                                    durationMs = candidate.durationSeconds * 1000L,
                                    artUrl = candidate.thumbnailUrl,
                                    genre = "Local Audio",
                                    isDownloaded = true,
                                    localPath = candidate.videoId
                                )
                                viewModel.playTrack(localTrack)
                            },
                            onAddLocalTrackToPlaylist = { plId, candidate ->
                                val localTrack = Track(
                                    id = "local_${candidate.videoId.hashCode()}",
                                    title = candidate.title,
                                    artist = candidate.artist,
                                    album = candidate.albumName ?: "Local Storage",
                                    durationMs = candidate.durationSeconds * 1000L,
                                    artUrl = candidate.thumbnailUrl,
                                    genre = "Local Audio",
                                    isDownloaded = true,
                                    localPath = candidate.videoId
                                )
                                viewModel.addTrackToPlaylist(plId, localTrack)
                            },
                            onBack = { currentSubScreen = SubScreen.None }
                        )
                    }
                    is SubScreen.Discover -> {
                        DiscoverSubScreenHost(
                            viewModel = viewModel,
                            onVisitArtist = { name -> currentSubScreen = SubScreen.Artist(name) },
                            onOpenFullSongMaximized = { resolvedTrack ->
                                currentSubScreen = SubScreen.None
                                viewModel.playNormalizedTrackFull(resolvedTrack)
                                isPlayerExpanded = true
                            },
                            onNavigateBack = { currentSubScreen = SubScreen.None }
                        )
                    }
                    is SubScreen.None -> {
                        when (currentDestination) {
                            AppDestination.HOME -> HomeScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToSearch = {
                                    previousSubScreen = currentSubScreen
                                    if (currentDestination != AppDestination.SEARCH) {
                                        previousDestination = currentDestination
                                        currentDestination = AppDestination.SEARCH
                                    }
                                },
                                onNavigateToProfile = {
                                    if (currentDestination != AppDestination.PROFILE) {
                                        previousDestination = currentDestination
                                        currentDestination = AppDestination.PROFILE
                                    }
                                },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = {
                                    if (it == "liked_songs") currentSubScreen = SubScreen.LikedSongs
                                    else currentSubScreen = SubScreen.Playlist(it)
                                },
                                onOpenNotifications = { showNotifications = true }
                            )
                            AppDestination.SEARCH -> {
                                BackHandler {
                                    if (viewModel.searchQuery.value.isNotEmpty()) {
                                        viewModel.searchQuery.value = ""
                                    } else {
                                        if (previousSubScreen !is SubScreen.None) {
                                            currentSubScreen = previousSubScreen
                                            previousSubScreen = SubScreen.None
                                        } else {
                                            currentDestination = if (previousDestination != AppDestination.SEARCH) previousDestination else AppDestination.HOME
                                        }
                                    }
                                }
                                SearchScreen(
                                    viewModel = viewModel,
                                    playerState = playerState,
                                    onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                    onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                    onNavigateToHome = {
                                        if (previousSubScreen !is SubScreen.None) {
                                            currentSubScreen = previousSubScreen
                                            previousSubScreen = SubScreen.None
                                        } else {
                                            currentDestination = if (previousDestination != AppDestination.SEARCH) previousDestination else AppDestination.HOME
                                        }
                                    },
                                    onOpenDiscover = { currentSubScreen = SubScreen.Discover },
                                    onPlayTrackAndMaximize = { track ->
                                        viewModel.playTrack(track)
                                        isPlayerExpanded = true
                                    }
                                )
                            }
                            AppDestination.LIBRARY -> {
                                BackHandler { currentDestination = AppDestination.HOME }
                                LibraryScreen(
                                    viewModel = viewModel,
                                    playerState = playerState,
                                    onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                    onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                    onNavigateToHistory = { currentSubScreen = SubScreen.History },
                                    onNavigateToDownloads = { currentSubScreen = SubScreen.Downloads },
                                    onNavigateToLikedSongs = { currentSubScreen = SubScreen.LikedSongs },
                                    onNavigateToLocalMusic = { currentSubScreen = SubScreen.LocalMusic },
                                    onCreatePlaylist = { showCreatePlaylistDialog = true }
                                )
                            }
                            AppDestination.PROFILE -> {
                                BackHandler { currentDestination = AppDestination.HOME }
                                ProfileScreen(
                                    viewModel = viewModel,
                                    onNavigateToSoundSettings = { currentSubScreen = SubScreen.SoundSettings },
                                    onOpenNotifications = { showNotifications = true },
                                    onOpenSleepTimer = { showSleepTimer = true },
                                    onOpenInfoPage = { currentSubScreen = SubScreen.InfoDetail(it) }
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (playerState.isVideoMode && playerState.currentTrack != null && !isPlayerExpanded && currentSubScreen !is SubScreen.Discover) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Surface(
                                modifier = Modifier
                                    .width(196.dp)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { isPlayerExpanded = true },
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black,
                                shadowElevation = 14.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            android.view.TextureView(ctx).also { tv ->
                                                viewModel.attachVideoTextureView(tv)
                                            }
                                        },
                                        update = { tv ->
                                            viewModel.attachVideoTextureView(tv)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .clickable { isPlayerExpanded = true }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.OpenInFull,
                                                    contentDescription = "Expand Video",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .clickable { viewModel.setVideoMode(false) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Switch to Audio",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (playerState.currentTrack != null && !isPlayerExpanded && currentSubScreen !is SubScreen.Discover) {
                        MiniPlayer(
                            playerState = playerState,
                            onExpand = { isPlayerExpanded = true },
                            onTogglePlay = { viewModel.togglePlayPause() },
                            onPrevious = { viewModel.previous() },
                            onNext = { viewModel.next() },
                            onDismissPlayback = { viewModel.stopPlayback() }
                        )
                    }

                    if (currentSubScreen is SubScreen.None) {
                        Surface(
                            modifier = Modifier
                                .padding(bottom = 16.dp, top = 4.dp)
                                .width(230.dp)
                                .height(60.dp)
                                .testTag("mobile_floating_nav"),
                            shape = RoundedCornerShape(30.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 10.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppDestination.values().forEach { dest ->
                                    val isSelected = currentDestination == dest
                                    val iconTint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        modifier = Modifier.size(44.dp),
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                        onClick = {
                                            if (dest == AppDestination.SEARCH) {
                                                previousSubScreen = currentSubScreen
                                            }
                                            currentSubScreen = SubScreen.None
                                            if (currentDestination != dest) {
                                                previousDestination = currentDestination
                                                currentDestination = dest
                                            }
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (dest == AppDestination.HOME) {
                                                HomeNavSvgIcon(tint = iconTint, modifier = Modifier.size(20.dp))
                                            } else {
                                                Icon(
                                                    imageVector = dest.icon,
                                                    contentDescription = dest.label,
                                                    tint = iconTint,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp)
                )

                AnimatedVisibility(
                    visible = isPlayerExpanded,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    BackHandler { isPlayerExpanded = false }
                    PlayerScreen(
                        viewModel = viewModel,
                        playerState = playerState,
                        onDismiss = { isPlayerExpanded = false },
                        onOpenEqualizer = {
                            isPlayerExpanded = false
                            currentSubScreen = SubScreen.SoundSettings
                        },
                        onOpenSleepTimer = { showSleepTimer = true },
                        onShareTrack = { title ->
                            runCatching {
                                val shareMsg = playerState.currentTrack?.let {
                                    SeitharolDeepLinkHelper.buildTrackShareMessage(it)
                                } ?: "Listen to $title on Seitharol!"
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareMsg)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                            }
                        }
                    )
                }
            }
        }

        if (showSleepTimer) {
            SleepTimerModal(
                currentMinutesLeft = playerState.sleepTimerMinutesLeft,
                onSetTimer = { viewModel.setSleepTimer(it) },
                onDismiss = { showSleepTimer = false }
            )
        }

        if (showNotifications) {
            NotificationsModal(
                onDismiss = { showNotifications = false }
            )
        }

        shareTargetArtist?.let { artistName ->
            ShareArtistModal(
                artistName = artistName,
                onDismiss = { shareTargetArtist = null }
            )
        }

        trackToAddToPlaylist?.let { track ->
            AddToPlaylistModal(
                track = track,
                playlists = playlists,
                onSelectPlaylist = { plId ->
                    viewModel.addTrackToPlaylist(plId, track)
                    trackToAddToPlaylist = null
                },
                onCreateNewPlaylist = {
                    pendingTrackForNewPlaylist = track
                    trackToAddToPlaylist = null
                    showCreatePlaylistDialog = true
                },
                onDismiss = { trackToAddToPlaylist = null }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    viewModel.createPlaylist(name, "User created playlist", pendingTrackForNewPlaylist)
                    pendingTrackForNewPlaylist = null
                    showCreatePlaylistDialog = false
                },
                onDismiss = {
                    showCreatePlaylistDialog = false
                    pendingTrackForNewPlaylist = null
                }
            )
        }
    }
}
