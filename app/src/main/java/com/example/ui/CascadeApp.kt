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
import com.example.ui.theme.*
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
    data class Playlist(val playlistId: String) : SubScreen
    data object SoundSettings : SubScreen
}

@Composable
fun CascadeApp(viewModel: CascadeViewModel) {
    val playerState by viewModel.playerState.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val context = LocalContext.current

    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var currentSubScreen by remember { mutableStateOf<SubScreen>(SubScreen.None) }
    var isPlayerExpanded by remember { mutableStateOf(false) }

    // Modals state
    var showSleepTimer by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var shareTargetArtist by remember { mutableStateOf<String?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.skipNotice.collectLatest { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(KangleiBg)
    ) {
        val isTablet = maxWidth >= 600.dp

        if (isTablet) {
            // Adaptive Tablet Layout
            Row(modifier = Modifier.fillMaxSize()) {
                // Navigation Rail
                NavigationRail(
                    containerColor = KangleiNavBg,
                    contentColor = Color.White,
                    header = {
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp, bottom = 12.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF333333)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxHeight().testTag("tablet_nav_rail")
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AppDestination.values().forEach { dest ->
                        NavigationRailItem(
                            selected = currentDestination == dest && currentSubScreen is SubScreen.None,
                            onClick = {
                                currentSubScreen = SubScreen.None
                                currentDestination = dest
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Color.White,
                                indicatorColor = KangleiNavActive,
                                unselectedIconColor = Color(0xFFAAAAAA)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    NavigationRailItem(
                        selected = currentSubScreen is SubScreen.SoundSettings,
                        onClick = { currentSubScreen = SubScreen.SoundSettings },
                        icon = { Icon(Icons.Default.Tune, contentDescription = "Sound Settings") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            indicatorColor = KangleiNavActive,
                            unselectedIconColor = Color(0xFFAAAAAA)
                        )
                    )
                }

                // Center Content Area
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (val sub = currentSubScreen) {
                        is SubScreen.Artist -> ArtistScreen(
                            artistName = sub.artistName,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onShareArtist = { shareTargetArtist = it }
                        )
                        is SubScreen.Playlist -> PlaylistScreen(
                            playlistId = sub.playlistId,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                        is SubScreen.SoundSettings -> SoundSettingsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None }
                        )
                        is SubScreen.None -> when (currentDestination) {
                            AppDestination.HOME -> HomeScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToSearch = { currentDestination = AppDestination.SEARCH },
                                onNavigateToProfile = { currentDestination = AppDestination.PROFILE },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                onOpenNotifications = { showNotifications = true }
                            )
                            AppDestination.SEARCH -> SearchScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToHome = {
                                    currentSubScreen = SubScreen.None
                                    currentDestination = AppDestination.HOME
                                },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) }
                            )
                            AppDestination.LIBRARY -> LibraryScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onCreatePlaylist = { showCreatePlaylistDialog = true }
                            )
                            AppDestination.PROFILE -> ProfileScreen(
                                viewModel = viewModel,
                                onNavigateToSoundSettings = { currentSubScreen = SubScreen.SoundSettings },
                                onOpenNotifications = { showNotifications = true },
                                onOpenSleepTimer = { showSleepTimer = true }
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

                // Right Pane: Now Playing on Tablet
                Surface(
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 400.dp)
                        .fillMaxHeight(),
                    color = KangleiBg,
                    tonalElevation = 2.dp
                ) {
                    if (playerState.currentTrack != null) {
                        PlayerScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onDismiss = { /* Visible on tablet */ },
                            onOpenEqualizer = { currentSubScreen = SubScreen.SoundSettings },
                            onOpenSleepTimer = { showSleepTimer = true },
                            onShareTrack = { title ->
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Listen to $title on Seitharol!")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Select a song to start playback",
                                color = KangleiTextSecondary,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        } else {
            // Mobile Phone Handheld Layout
            Box(modifier = Modifier.fillMaxSize()) {
                // Main Screens Stack
                when (val sub = currentSubScreen) {
                    is SubScreen.Artist -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        ArtistScreen(
                            artistName = sub.artistName,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onShareArtist = { shareTargetArtist = it }
                        )
                    }
                    is SubScreen.Playlist -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        PlaylistScreen(
                            playlistId = sub.playlistId,
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None },
                            onAddToPlaylist = { trackToAddToPlaylist = it }
                        )
                    }
                    is SubScreen.SoundSettings -> {
                        BackHandler { currentSubScreen = SubScreen.None }
                        SoundSettingsScreen(
                            viewModel = viewModel,
                            playerState = playerState,
                            onBack = { currentSubScreen = SubScreen.None }
                        )
                    }
                    is SubScreen.None -> {
                        when (currentDestination) {
                            AppDestination.HOME -> HomeScreen(
                                viewModel = viewModel,
                                playerState = playerState,
                                onNavigateToSearch = { currentDestination = AppDestination.SEARCH },
                                onNavigateToProfile = { currentDestination = AppDestination.PROFILE },
                                onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                onOpenNotifications = { showNotifications = true }
                            )
                            AppDestination.SEARCH -> {
                                BackHandler {
                                    if (viewModel.searchQuery.value.isNotEmpty()) {
                                        viewModel.searchQuery.value = ""
                                    } else {
                                        currentDestination = AppDestination.HOME
                                    }
                                }
                                SearchScreen(
                                    viewModel = viewModel,
                                    playerState = playerState,
                                    onNavigateToHome = {
                                        currentSubScreen = SubScreen.None
                                        currentDestination = AppDestination.HOME
                                    },
                                    onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                    onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) }
                                )
                            }
                            AppDestination.LIBRARY -> {
                                BackHandler { currentDestination = AppDestination.HOME }
                                LibraryScreen(
                                    viewModel = viewModel,
                                    playerState = playerState,
                                    onNavigateToPlaylist = { currentSubScreen = SubScreen.Playlist(it) },
                                    onNavigateToArtist = { currentSubScreen = SubScreen.Artist(it) },
                                    onCreatePlaylist = { showCreatePlaylistDialog = true }
                                )
                            }
                            AppDestination.PROFILE -> {
                                BackHandler { currentDestination = AppDestination.HOME }
                                ProfileScreen(
                                    viewModel = viewModel,
                                    onNavigateToSoundSettings = { currentSubScreen = SubScreen.SoundSettings },
                                    onOpenNotifications = { showNotifications = true },
                                    onOpenSleepTimer = { showSleepTimer = true }
                                )
                            }
                        }
                    }
                }

                // Floating MiniPlayer & Bottom Nav Container
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Floating Mini Player (if playing & player not expanded)
                    if (playerState.currentTrack != null && !isPlayerExpanded) {
                        MiniPlayer(
                            playerState = playerState,
                            onExpand = { isPlayerExpanded = true },
                            onTogglePlay = { viewModel.togglePlayPause() },
                            onPrevious = { viewModel.previous() },
                            onNext = { viewModel.next() }
                        )
                    }

                    // Floating Pill Navigation Bar (matching Home board style: 230dp wide, 60dp high, radius 30dp, #0D0D0D)
                    if (currentSubScreen is SubScreen.None) {
                        Surface(
                            modifier = Modifier
                                .padding(bottom = 16.dp, top = 4.dp)
                                .width(230.dp)
                                .height(60.dp)
                                .testTag("mobile_floating_nav"),
                            shape = RoundedCornerShape(30.dp),
                            color = KangleiNavBg,
                            shadowElevation = 10.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppDestination.values().forEach { dest ->
                                    val isSelected = currentDestination == dest
                                    Surface(
                                        modifier = Modifier.size(44.dp),
                                        shape = CircleShape,
                                        color = if (isSelected) KangleiNavActive else Color.Transparent,
                                        onClick = {
                                            currentSubScreen = SubScreen.None
                                            currentDestination = dest
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = dest.icon,
                                                contentDescription = dest.label,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
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

                // Full Player Screen Modal on Mobile
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
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "Listen to $title on Seitharol!")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                        }
                    )
                }
            }
        }

        // Modals
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
                    viewModel.addTrackToPlaylist(plId, track.id)
                },
                onCreateNewPlaylist = {
                    trackToAddToPlaylist = null
                    showCreatePlaylistDialog = true
                },
                onDismiss = { trackToAddToPlaylist = null }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onConfirm = { name ->
                    viewModel.createPlaylist(name, "User created playlist")
                },
                onDismiss = { showCreatePlaylistDialog = false }
            )
        }
    }
}
