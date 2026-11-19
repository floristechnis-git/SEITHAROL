package com.example.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.launch

enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.VideoLibrary),
    PROFILE("Profile", Icons.Default.Person)
}

sealed interface SubScreen {
    data object None : SubScreen
    data class Artist(val artistName: String, val isChannel: Boolean = false) : SubScreen
    data class Playlist(val playlistId: String, val returnSubScreen: SubScreen? = null) : SubScreen
    data object History : SubScreen
    data object Downloads : SubScreen
    data object LikedSongs : SubScreen
    data class InfoDetail(val pageType: InfoPageType) : SubScreen
    data object SoundSettings : SubScreen
    data object Discover : SubScreen
    data object LocalMusic : SubScreen
    data class NotFound(val queryOrUrl: String = "", val title: String = "404 — Page Not Found") : SubScreen
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
        val compactLoadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 18_000, 350, 800)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(compactLoadControl)
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
    LaunchedEffect(Unit) {
        viewModel.loadDiscoverFeed()
    }
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
    onNotificationsOpened: () -> Unit = {},
    isInPiPMode: Boolean = false
) {
    val playerState by viewModel.playerState.collectAsState()
    val isFullscreenVideo by viewModel.isFullscreenVideo.collectAsState()
    val isAutoplayEnabled by viewModel.isAutoSuggestEnabled.collectAsState()

    // When Activity enters native Android Picture-in-Picture mode, display 100% video surface
    // without status bar, navigation bar, or extra UI overlays so it floats cleanly outside the app.
    if (isInPiPMode) {
        val pipRatio = playerState.videoAspectRatio.takeIf { !it.isNaN() && !it.isInfinite() && it in 0.25f..4.0f } ?: (16f / 9f)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else (16f / 9f)
            val matchHeightFirst = containerRatio > pipRatio
            val pipAspectMod = if (matchHeightFirst) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(pipRatio, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(pipRatio, matchHeightConstraintsFirst = false)
            }
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        viewModel.attachVideoTextureView(this)
                    }
                },
                update = { tv ->
                    viewModel.attachVideoTextureView(tv)
                },
                modifier = pipAspectMod
            )
        }
        return
    }

    val playlists by viewModel.playlists.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val context = LocalContext.current
    val compositionStartMs = remember { System.currentTimeMillis() }
    val appScope = rememberCoroutineScope()

    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var previousDestination by remember { mutableStateOf(AppDestination.HOME) }
    var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var previousSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Continuous morph progress (0f = MiniPlayer, 1f = Full PlayerScreen) so sliding down or clicking
    // minimize directly converts PlayerScreen into MiniPlayer without hiding and then popping up!
    val playerMorphProgress = remember { Animatable(0f) }
    var activeSheetDragFraction by remember { mutableFloatStateOf(0f) }

    val expandFullPlayer: () -> Unit = {
        activeSheetDragFraction = 0f
        isPlayerExpanded = true
        appScope.launch {
            playerMorphProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    val minimizeToMiniPlayer: () -> Unit = {
        appScope.launch {
            playerMorphProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
            activeSheetDragFraction = 0f
            isPlayerExpanded = false
        }
    }

    // When user plays a video, automatically expand full video player screen so video is immediately visible
    LaunchedEffect(playerState.currentTrack?.id, playerState.isVideoMode) {
        val current = playerState.currentTrack
        if (current != null && (current.isVideoTrack || playerState.isVideoMode)) {
            expandFullPlayer()
        }
    }

    // Modals state
    var showSleepTimer by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
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
            minimizeToMiniPlayer()
            currentSubScreen = SubScreen.Artist(
                artistName = artistName,
                isChannel = viewModel.isArtistNavigationChannel.value
            )
        }
    }

    // Auto-expand PlayerScreen when opening a shared track via HTTPS deep link or seitharol://
    LaunchedEffect(Unit) {
        viewModel.playerExpandEvent.collectLatest {
            currentSubScreen = SubScreen.None
            expandFullPlayer()
        }
    }

    // Navigate to imported or shared playlist when resolved
    LaunchedEffect(Unit) {
        viewModel.playlistNavigationEvent.collectLatest { playlistId ->
            minimizeToMiniPlayer()
            currentSubScreen = SubScreen.Playlist(playlistId)
        }
    }

    // Navigate to 404 Not Found screen when an invalid link or missing resource is opened
    LaunchedEffect(Unit) {
        viewModel.notFoundNavigationEvent.collectLatest { rawQueryOrUrl ->
            minimizeToMiniPlayer()
            currentSubScreen = SubScreen.NotFound(queryOrUrl = rawQueryOrUrl)
        }
    }

    val rootFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val rootKeyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val dismissGlobalKeyboard = {
        rootFocusManager.clearFocus(force = true)
        rootKeyboardController?.hide()
        runCatching {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val token = (context as? android.app.Activity)?.currentFocus?.windowToken
                ?: (context as? android.app.Activity)?.window?.decorView?.windowToken
            if (token != null) {
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }
    }

    // Always close keyboard & unfocus search input container when navigating across tabs, subscreens, or player sheet
    LaunchedEffect(currentSubScreen, currentDestination, isPlayerExpanded) {
        if (currentDestination != AppDestination.SEARCH || currentSubScreen !is SubScreen.None || isPlayerExpanded) {
            dismissGlobalKeyboard()
        }
        if (currentSubScreen is SubScreen.Discover) {
            viewModel.pause()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        // Tablet Display Fix:
        // Instead of a broken 3-column tablet split layout, render a fixed mobile-display-like container
        // (max width 480.dp centered on large tablet screens, full width on mobile phones) so every screen,
        // card, player, and modal has an identical, unbreakable layout on both phones and tablets!
        val isLargeTabletViewport = maxWidth > 520.dp && !isFullscreenVideo
        val mobileContainerModifier = if (isLargeTabletViewport) {
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = mobileContainerModifier.background(MaterialTheme.colorScheme.background)) {
            when (val sub = currentSubScreen) {
                is SubScreen.Artist -> {
                    BackHandler { currentSubScreen = SubScreen.None }
                    ArtistScreen(
                        artistName = sub.artistName,
                        isChannel = sub.isChannel,
                        viewModel = viewModel,
                        playerState = playerState,
                        onBack = { currentSubScreen = SubScreen.None },
                        onShareArtist = { artistName ->
                            SeitharolDeepLinkHelper.shareArtistSystemChooser(context, artistName)
                        },
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
                    fun candidateToDomainTrack(candidate: com.seitharol.data.resolver.CandidateStream): Track {
                        val isVideo = candidate.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                            candidate.videoId.lowercase().endsWith(".mp4") ||
                            candidate.videoId.lowercase().contains("/video/")
                        val cleanAlbum = candidate.albumName?.takeUnless {
                            it.equals("YouTube Video", ignoreCase = true) || it.equals("Local Storage", ignoreCase = true)
                        }.orEmpty()
                        val id = if (isVideo) "vidfile_${candidate.videoId.hashCode()}" else "local_${candidate.videoId.hashCode()}"
                        return Track(
                            id = id,
                            title = candidate.title,
                            artist = candidate.artist,
                            album = if (isVideo) "MP4 Video" else cleanAlbum,
                            durationMs = candidate.durationSeconds * 1000L,
                            artUrl = candidate.thumbnailUrl,
                            genre = if (isVideo) "Local Video" else "Local Audio",
                            isDownloaded = true,
                            isLiked = id in likedTrackIds,
                            localPath = candidate.videoId,
                            itag = if (isVideo) 18 else 251,
                            codec = if (isVideo) "MP4" else "Opus"
                        )
                    }
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
                                localPath = it.localPath,
                                isLiked = it.id in likedTrackIds || it.isLiked
                            )
                        },
                        availablePlaylists = playlists.map { it.id to it.title },
                        likedTrackIds = likedTrackIds,
                        onPlayLocalTrack = { candidate ->
                            val localTrack = candidateToDomainTrack(candidate)
                            viewModel.playQueue(listOf(localTrack), 0, isPlaylist = true)
                            expandFullPlayer()
                        },
                        onPlayLocalQueue = { candidates, startIndex ->
                            val mappedQueue = candidates.map { candidateToDomainTrack(it) }
                            if (mappedQueue.isNotEmpty()) {
                                viewModel.playQueue(mappedQueue, startIndex.coerceIn(0, mappedQueue.lastIndex), isPlaylist = true)
                                expandFullPlayer()
                            }
                        },
                        onPlayNextLocalTrack = { candidate ->
                            viewModel.playNext(candidateToDomainTrack(candidate))
                        },
                        onAddLocalTrackToQueue = { candidate ->
                            viewModel.addToQueue(candidateToDomainTrack(candidate))
                        },
                        onToggleLikeLocalTrack = { candidate ->
                            viewModel.toggleLike(candidateToDomainTrack(candidate))
                        },
                        onAddLocalTrackToPlaylist = { plId, candidate ->
                            viewModel.addTrackToPlaylist(plId, candidateToDomainTrack(candidate))
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
                            expandFullPlayer()
                        },
                        onNavigateBack = { currentSubScreen = SubScreen.None }
                    )
                }
                is SubScreen.NotFound -> {
                    BackHandler { currentSubScreen = SubScreen.None }
                    NotFound404Screen(
                        title = sub.title,
                        message = "The requested page, playlist, or link could not be found.",
                        attemptedPathOrQuery = sub.queryOrUrl,
                        onBack = { currentSubScreen = SubScreen.None },
                        onGoHome = {
                            currentSubScreen = SubScreen.None
                            currentDestination = AppDestination.HOME
                        }
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
                                dismissGlobalKeyboard()
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
                                onNavigateToArtist = {
                                    dismissGlobalKeyboard()
                                    currentSubScreen = SubScreen.Artist(it)
                                },
                                onNavigateToPlaylist = {
                                    dismissGlobalKeyboard()
                                    currentSubScreen = SubScreen.Playlist(it)
                                },
                                onNavigateToHome = {
                                    dismissGlobalKeyboard()
                                    if (previousSubScreen !is SubScreen.None) {
                                        currentSubScreen = previousSubScreen
                                        previousSubScreen = SubScreen.None
                                    } else {
                                        currentDestination = if (previousDestination != AppDestination.SEARCH) previousDestination else AppDestination.HOME
                                    }
                                },
                                onOpenDiscover = {
                                    dismissGlobalKeyboard()
                                    currentSubScreen = SubScreen.Discover
                                },
                                onPlayTrackAndMaximize = { track ->
                                    dismissGlobalKeyboard()
                                    viewModel.playTrack(track)
                                    expandFullPlayer()
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

            // Effective expand progress combining animatable state + real-time user drag gesture
            val effectiveExpandProgress = if (isPlayerExpanded) {
                (playerMorphProgress.value * (1f - activeSheetDragFraction)).coerceIn(0f, 1f)
            } else {
                0f
            }

            // MiniPlayer + Floating Navigation Dock
            // MiniPlayer remains mounted during player slide-down and smoothly morphs in as effectiveExpandProgress -> 0f,
            // so the full player directly converts into the MiniPlayer div without hiding and popping up!
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .zIndex(10f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (playerState.currentTrack != null && currentSubScreen !is SubScreen.Discover && !isInPiPMode) {
                    val miniAlpha = (1f - (effectiveExpandProgress * 1.15f)).coerceIn(0f, 1f)
                    val miniScale = 0.96f + (0.04f * miniAlpha)
                    if (miniAlpha > 0.01f || !isPlayerExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    alpha = if (!isPlayerExpanded) 1f else miniAlpha
                                    scaleX = if (!isPlayerExpanded) 1f else miniScale
                                    scaleY = if (!isPlayerExpanded) 1f else miniScale
                                }
                        ) {
                            MiniPlayer(
                                playerState = playerState,
                                onExpand = {
                                    dismissGlobalKeyboard()
                                    expandFullPlayer()
                                },
                                onTogglePlay = { viewModel.togglePlayPause() },
                                onPrevious = { viewModel.previous() },
                                onNext = { viewModel.next() },
                                onDismissPlayback = { viewModel.stopPlayback() }
                            )
                        }
                    }
                }

                if (currentSubScreen is SubScreen.None) {
                    // Floating Navigation Bar Div: SVG icons are strictly #FFFFFF (Color(0xFFFFFFFF)) instead of red!
                    val isDarkTheme = LocalIsDarkTheme.current
                    val navBarContainerColor = if (isDarkTheme) Color(0xFF1E2022) else Color(0xFF1B1C1E)
                    Surface(
                        modifier = Modifier
                            .padding(bottom = 16.dp, top = 4.dp)
                            .width(236.dp)
                            .height(60.dp)
                            .testTag("mobile_floating_nav"),
                        shape = RoundedCornerShape(30.dp),
                        color = navBarContainerColor,
                        shadowElevation = 12.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppDestination.values().forEach { dest ->
                                val isSelected = currentDestination == dest
                                // Strictly #FFFFFF white SVG tint in nav div (never red!)
                                val iconTint = if (isSelected) {
                                    Color(0xFFFFFFFF)
                                } else {
                                    Color(0xFFFFFFFF).copy(alpha = 0.65f)
                                }
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    onClick = {
                                        dismissGlobalKeyboard()
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

            // Full Player Sheet: Directly morphs down to the MiniPlayer dock position without hiding off-screen first
            if (isPlayerExpanded || playerMorphProgress.value > 0.01f) {
                val density = LocalDensity.current
                val containerHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
                val miniDockBottomOffsetPx = with(density) {
                    (if (currentSubScreen is SubScreen.None) 144.dp else 72.dp).toPx()
                }
                val targetMorphTranslateY = (containerHeightPx - miniDockBottomOffsetPx).coerceAtLeast(1f)
                val currentTranslateY = targetMorphTranslateY * (1f - effectiveExpandProgress)
                val sheetCorner = (22f * (1f - effectiveExpandProgress)).dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(30f)
                        .graphicsLayer {
                            translationY = currentTranslateY
                            alpha = (effectiveExpandProgress * 1.15f).coerceIn(0f, 1f)
                            val scale = 0.94f + (0.06f * effectiveExpandProgress)
                            scaleX = scale
                            scaleY = scale
                            shape = RoundedCornerShape(sheetCorner)
                            clip = effectiveExpandProgress < 0.99f
                        }
                ) {
                    BackHandler(enabled = isPlayerExpanded) {
                        minimizeToMiniPlayer()
                    }
                    PlayerScreen(
                        viewModel = viewModel,
                        playerState = playerState,
                        onDismiss = { minimizeToMiniPlayer() },
                        onDragProgressChanged = { dragFrac ->
                            activeSheetDragFraction = dragFrac.coerceIn(0f, 1f)
                        },
                        onOpenEqualizer = {
                            minimizeToMiniPlayer()
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
                        },
                        onAddToPlaylist = { trackToAddToPlaylist = it },
                        onNavigateToPlaylist = { plId ->
                            minimizeToMiniPlayer()
                            currentSubScreen = SubScreen.Playlist(plId)
                        }
                    )
                }
            }

            // High-contrast Snackbar alert positioned above MiniPlayer with zIndex(100f)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (playerState.currentTrack != null && !isPlayerExpanded) 152.dp else 92.dp
                    )
                    .zIndex(100f),
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xFF222426),
                        contentColor = Color.White,
                        actionColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = Color.White,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            )
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

        // Full-bleed root landscape video overlay (zIndex 500f):
        // Rotates the Activity to SCREEN_ORIENTATION_SENSOR_LANDSCAPE and fills 100% of the screen frame like YouTube.
        var isFullscreenZoomCrop by remember { mutableStateOf(false) }
        if (isFullscreenVideo && playerState.currentTrack != null) {
            val fsTrack = playerState.currentTrack!!
            val totalDur = (if (playerState.durationMs > 0L) playerState.durationMs else (fsTrack.durationMs.takeIf { it > 0L } ?: 210_000L)).coerceAtLeast(1000L)
            val dispPos = playerState.currentPositionMs.coerceAtLeast(0L)
            val isSingleLoop = playerState.repeatMode == com.example.player.RepeatMode.ONE
            val fullscreenNextTrack = when {
                !isAutoplayEnabled || isSingleLoop -> null
                playerState.currentIndex in 0 until playerState.queue.size - 1 ->
                    playerState.queue[playerState.currentIndex + 1]
                playerState.repeatMode == com.example.player.RepeatMode.ALL && playerState.queue.size > 1 ->
                    playerState.queue.firstOrNull()
                else -> null
            }
            val fullscreenRemainingMs = (totalDur - dispPos).coerceAtLeast(0L)
            var dismissedFullscreenCountdown by remember(fsTrack.id, playerState.repeatMode) { mutableStateOf(false) }
            val showFullscreenCountdown = isAutoplayEnabled &&
                playerState.isPlaying &&
                !playerState.isBuffering &&
                !isSingleLoop &&
                playerState.durationMs >= 15_000L &&
                totalDur >= 15_000L &&
                fullscreenRemainingMs in 1L..10_000L &&
                fullscreenNextTrack != null &&
                fullscreenNextTrack.id != fsTrack.id &&
                !dismissedFullscreenCountdown
            val fullscreenCountdownSec = ((fullscreenRemainingMs + 999L) / 1000L).toInt().coerceIn(1, 10)
            val fullscreenCountdownProg = (fullscreenRemainingMs.toFloat() / 10_000f).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(500f)
                    .background(Color.Black)
            ) {
                SeitharolFullscreenVideoModal(
                    viewModel = viewModel,
                    playerState = playerState,
                    isZoomCrop = isFullscreenZoomCrop,
                    onToggleZoomCrop = { isFullscreenZoomCrop = !isFullscreenZoomCrop },
                    nextTrack = fullscreenNextTrack,
                    showNextCountdown = showFullscreenCountdown,
                    countdownSeconds = fullscreenCountdownSec,
                    countdownProgress = fullscreenCountdownProg,
                    onDismissCountdown = { dismissedFullscreenCountdown = true },
                    onOpenVideoDescription = {},
                    onEnterPiP = {
                        viewModel.setFullscreenVideo(false)
                        (context.findActivity() as? com.example.MainActivity)?.enterPiPMode()
                    },
                    onDismiss = { viewModel.setFullscreenVideo(false) }
                )
            }
        }
    }
}
