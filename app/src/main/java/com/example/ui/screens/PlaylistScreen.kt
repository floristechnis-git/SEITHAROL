package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DownloadTier
import com.example.data.model.Playlist
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current

    val playlists by viewModel.playlists.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()

    val savedPlaylist = remember(playlistId, playlists) {
        playlists.firstOrNull { it.id == playlistId }
    }

    var remotePlaylistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoadingPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        isLoadingPlaylist = true
        viewModel.loadPlaylistTracks(playlistId) { tracks ->
            remotePlaylistTracks = tracks
            isLoadingPlaylist = false
        }
    }

    val displayTitle = remember(savedPlaylist, remotePlaylistTracks, playlistId) {
        savedPlaylist?.title
            ?: remotePlaylistTracks.firstOrNull()?.album?.takeIf { it.isNotBlank() && it != "Single" }
            ?: remotePlaylistTracks.firstOrNull()?.let { "${it.artist} Collection" }
            ?: when {
                playlistId.startsWith("RD") -> "YouTube Mix"
                playlistId.startsWith("MPREb_") || playlistId.startsWith("OLAK5uy_") -> "Album"
                else -> "Playlist"
            }
    }

    val displayDescription = remember(savedPlaylist, remotePlaylistTracks, isLoadingPlaylist) {
        savedPlaylist?.description?.takeIf { it.isNotBlank() }
            ?: if (isLoadingPlaylist) "Loading tracks..."
            else if (remotePlaylistTracks.isNotEmpty()) "${remotePlaylistTracks.size} tracks • Official Release"
            else "Curated Collection"
    }

    val currentPlaylist = remember(savedPlaylist, playlistId, displayTitle, displayDescription, remotePlaylistTracks) {
        savedPlaylist ?: Playlist(
            id = playlistId,
            title = displayTitle,
            description = displayDescription,
            coverUrl = remotePlaylistTracks.firstOrNull()?.artUrl,
            trackCount = remotePlaylistTracks.size
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(remotePlaylistTracks, searchQuery) {
        if (searchQuery.isBlank()) remotePlaylistTracks
        else remotePlaylistTracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    var showMoreSheet by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isLoadingPlaylist,
        onRefresh = {
            isLoadingPlaylist = true
            viewModel.loadPlaylistTracks(playlistId) { tracks ->
                remotePlaylistTracks = tracks
                isLoadingPlaylist = false
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .testTag("playlist_screen")
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                    // Back button
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = SeitharolGlassDark,
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

                    // More button -> triggers ModalBottomSheet
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = SeitharolGlassDark,
                        onClick = { showMoreSheet = true }
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

                // Playlist Info & Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentPlaylist.title,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = currentPlaylist.description.ifBlank { "The best tracks every week" },
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = Color(0xFFD0D0D0)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons row: Download (48dp pill), Play (64dp circle), Like (48dp pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        // Download All
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                filteredTracks.forEach { viewModel.startDownload(it, DownloadTier.STANDARD) }
                            }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = SeitharolGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Download",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        // Big Play Button (64dp white circle)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (filteredTracks.isNotEmpty()) viewModel.playQueue(filteredTracks, 0)
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
                                        contentDescription = "Play Playlist",
                                        tint = Color(0xFF0D0D0D),
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

                        // Like Playlist (Persisted in Room)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                viewModel.togglePlaylistLike(currentPlaylist)
                            }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = SeitharolGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (currentPlaylist.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "Like",
                                        tint = if (currentPlaylist.isLiked) SeitharolRedHeart else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (currentPlaylist.isLiked) "Liked" else "Like",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        }

        // Dedicated Search Bar for this Playlist
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search in ${currentPlaylist.title}...") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Section Title
        item {
            Text(
                text = "Tracks (${filteredTracks.size})",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = SeitharolDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        // Playlist tracks
        if (filteredTracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingPlaylist) {
                        CircularProgressIndicator(
                            color = SeitharolDark,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No tracks matched '$searchQuery'" else "This playlist is empty.\nAdd tracks from Search, Home, or Player to build your playlist.",
                            color = SeitharolTextSecondary,
                            fontFamily = OutfitFontFamily,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            itemsIndexed(filteredTracks, key = { _, track -> "pl_tr_${track.id}" }) { index, track ->
                ClampedSwipeToDeleteRow(onDelete = { viewModel.removeTrackFromPlaylist(currentPlaylist.id, track.id) }) {
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
                        onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    // More Options Bottom Sheet
    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            containerColor = SeitharolWhite,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = currentPlaylist.title,
                    fontFamily = SyneFontFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SeitharolDark
                )
                Text(
                    text = "${remotePlaylistTracks.size} tracks",
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = SeitharolTextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = SeitharolBorder)
                Spacer(modifier = Modifier.height(8.dp))

                // Play Next
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            remotePlaylistTracks.reversed().forEach { viewModel.playNext(it) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = SeitharolDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Play Next", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                }

                // Add all to Queue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            remotePlaylistTracks.forEach { viewModel.addToQueue(it) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = SeitharolDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Add all to Queue", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                }

                // Download Playlist
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            remotePlaylistTracks.forEach { viewModel.startDownload(it, DownloadTier.STANDARD) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = SeitharolDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Download Playlist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                }

                // Share
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, currentPlaylist.title)
                                putExtra(Intent.EXTRA_TEXT, "Listen to '${currentPlaylist.title}' on Seitharol!")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Playlist"))
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = SeitharolDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Share Playlist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                }
            }
        }
    }

    // Modal to add track to playlist
    trackToAddToPlaylist?.let { track ->
        AddToPlaylistModal(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { pId ->
                viewModel.addTrackToPlaylist(pId, track.id)
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
