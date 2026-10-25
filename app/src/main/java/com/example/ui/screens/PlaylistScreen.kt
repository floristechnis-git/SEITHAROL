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
import com.example.ui.components.SeitharolDeepLinkHelper
import com.example.ui.components.SeitharolPullToRefreshBox
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

    val savedPlaylist = remember(playlistId, playlists) {
        playlists.firstOrNull { it.id == playlistId }
    }

    var remotePlaylistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var remoteCollectionMeta by remember(playlistId) { mutableStateOf<com.seitharol.data.resolver.YtmCollectionPageData?>(null) }
    var isLoadingPlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        isLoadingPlaylist = true
        viewModel.loadPlaylistTracks(
            playlistId = playlistId,
            onMetadata = { meta -> remoteCollectionMeta = meta }
        ) { tracks ->
            remotePlaylistTracks = tracks
            isLoadingPlaylist = false
        }
    }

    val displayTitle = remember(savedPlaylist, remoteCollectionMeta, remotePlaylistTracks, playlistId) {
        savedPlaylist?.title
            ?: remoteCollectionMeta?.title?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Music Collection", ignoreCase = true) &&
                    !it.equals("YouTube Video", ignoreCase = true) &&
                    !it.equals("YouTube Videos", ignoreCase = true)
            }
            ?: remotePlaylistTracks.firstOrNull()?.album?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Single", ignoreCase = true) &&
                    !it.equals("YouTube Video", ignoreCase = true) &&
                    !it.equals("YouTube Videos", ignoreCase = true) &&
                    !it.equals("Discover Downloads", ignoreCase = true) &&
                    !it.equals("Shared Track", ignoreCase = true) &&
                    !it.equals("Local Storage", ignoreCase = true)
            }
            ?: when {
                playlistId.startsWith("RD") -> "YouTube Mix"
                playlistId.startsWith("MPREb_") || playlistId.startsWith("OLAK5uy_") -> "Album"
                else -> "Playlist"
            }
    }

    val displayDescription = remember(savedPlaylist, remoteCollectionMeta, remotePlaylistTracks, isLoadingPlaylist) {
        savedPlaylist?.description?.takeIf { it.isNotBlank() }
            ?: remoteCollectionMeta?.description?.takeIf { it.isNotBlank() && !it.contains("YouTube Video", ignoreCase = true) }
            ?: if (isLoadingPlaylist) "Loading tracks..."
            else if (remotePlaylistTracks.isNotEmpty()) "${remotePlaylistTracks.size} tracks"
            else "Playlist"
    }

    val currentPlaylist = remember(savedPlaylist, remoteCollectionMeta, playlistId, displayTitle, displayDescription, remotePlaylistTracks) {
        savedPlaylist ?: Playlist(
            id = playlistId,
            title = displayTitle,
            description = displayDescription,
            coverUrl = remoteCollectionMeta?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: remotePlaylistTracks.firstOrNull()?.artUrl,
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

    SeitharolPullToRefreshBox(
        isRefreshing = isLoadingPlaylist,
        onRefresh = {
            isLoadingPlaylist = true
            viewModel.loadPlaylistTracks(
                playlistId = playlistId,
                onMetadata = { meta -> remoteCollectionMeta = meta }
            ) { tracks ->
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
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onBack
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { showMoreSheet = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MoreHoriz,
                                    contentDescription = "More",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val badgeTag = remember(playlistId, displayTitle, displayDescription) {
                            when {
                                playlistId.startsWith("RD") || displayTitle.contains("Mix", ignoreCase = true) -> "MIX"
                                playlistId.startsWith("MPREb_") || playlistId.startsWith("OLAK5uy_") || displayTitle.contains("Album", ignoreCase = true) -> "ALBUM"
                                displayDescription.contains("Single", ignoreCase = true) -> "SINGLE"
                                else -> "PLAYLIST"
                            }
                        }
                        val badgeColor = when (badgeTag) {
                            "MIX" -> Color(0xFFE50914)
                            "ALBUM" -> Color(0xFF8E24AA)
                            "SINGLE" -> Color(0xFF00897B)
                            else -> Color(0xFF2E66FF)
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeColor
                        ) {
                            Text(
                                text = badgeTag,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = currentPlaylist.title,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 26.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = currentPlaylist.description.ifBlank { "The best tracks every week" },
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    viewModel.downloadAllTracks(filteredTracks, DownloadTier.STANDARD)
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Download",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    if (filteredTracks.isNotEmpty()) {
                                        if (filteredTracks.size == 1 && (playlistId.startsWith("RD") || playlistId.startsWith("RDAMVM"))) {
                                            viewModel.startRadioMix(filteredTracks.first())
                                        } else {
                                            viewModel.playQueue(filteredTracks, 0)
                                        }
                                    }
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(64.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Playlist",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Play",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    viewModel.togglePlaylistLike(currentPlaylist)
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (currentPlaylist.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                            contentDescription = "Like",
                                            tint = if (currentPlaylist.isLiked) SeitharolRedHeart else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (currentPlaylist.isLiked) "Liked" else "Like",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search in ${currentPlaylist.title}...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

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
                                color = MaterialTheme.colorScheme.primary,
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
                itemsIndexed(filteredTracks, key = { index, track -> "pl_tr_${track.id}_$index" }) { index, track ->
                    ClampedSwipeToDeleteRow(onDelete = { viewModel.removeTrackFromPlaylist(currentPlaylist.id, track.id) }) {
                        TrackCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            rankNumber = index + 1,
                            onTrackClick = {
                                if (filteredTracks.isNotEmpty()) {
                                    viewModel.playQueue(filteredTracks, index)
                                } else {
                                    viewModel.playTrack(track)
                                }
                            },
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

        if (showMoreSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMoreSheet = false },
                containerColor = SeitharolWhite,
                contentColor = SeitharolDark,
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showMoreSheet = false
                                viewModel.downloadAllTracks(remotePlaylistTracks, DownloadTier.STANDARD)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = SeitharolDark)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Download Playlist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showMoreSheet = false
                                runCatching {
                                    val shareMsg = SeitharolDeepLinkHelper.buildPlaylistShareMessage(
                                        currentPlaylist.title,
                                        currentPlaylist.id
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, currentPlaylist.title)
                                        putExtra(Intent.EXTRA_TEXT, shareMsg)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Playlist"))
                                }
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

        trackToAddToPlaylist?.let { track ->
            AddToPlaylistModal(
                track = track,
                playlists = playlists,
                onSelectPlaylist = { pId ->
                    viewModel.addTrackToPlaylist(pId, track)
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
}
