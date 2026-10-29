package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.example.ui.components.DeletePlaylistConfirmDialog
import com.example.ui.components.RenamePlaylistDialog
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
    val isPersonalPlaylist = savedPlaylist != null || playlistId.startsWith("pl_", ignoreCase = true)

    var remotePlaylistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var remoteCollectionMeta by remember(playlistId) { mutableStateOf<com.seitharol.data.resolver.YtmCollectionPageData?>(null) }
    var isLoadingPlaylist by remember { mutableStateOf(true) }
    var hasFinishedLoadAttempt by remember(playlistId) { mutableStateOf(false) }

    val triggerReload = {
        isLoadingPlaylist = true
        viewModel.loadPlaylistTracks(
            playlistId = playlistId,
            onMetadata = { meta -> remoteCollectionMeta = meta }
        ) { tracks ->
            remotePlaylistTracks = tracks
            isLoadingPlaylist = false
            hasFinishedLoadAttempt = true
        }
    }

    LaunchedEffect(playlistId, playlists) {
        triggerReload()
    }

    // Show 404 screen when a non-personal playlist ID doesn't exist or returns 0 tracks after loading
    if (!isPersonalPlaylist && hasFinishedLoadAttempt && !isLoadingPlaylist && remotePlaylistTracks.isEmpty()) {
        NotFound404Screen(
            title = "404 — Playlist Not Found",
            message = "We couldn't find the playlist \"$playlistId\". It may have been deleted, made private, or the link is invalid.",
            onBack = onBack,
            onRetry = triggerReload,
            modifier = modifier
        )
        return
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
            else "Personal playlist"
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

    val shareableOnlineTracks = remember(remotePlaylistTracks) {
        remotePlaylistTracks.filter { SeitharolDeepLinkHelper.isOnlineShareableTrack(it) }
    }

    var showMoreSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val handleSharePlaylist = {
        if (isPersonalPlaylist && shareableOnlineTracks.isEmpty()) {
            Toast.makeText(
                context,
                "Add at least one online song (YouTube / JioSaavn) to share this playlist.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            SeitharolDeepLinkHelper.sharePlaylistSystemChooser(
                context = context,
                playlistTitle = currentPlaylist.title,
                playlistId = currentPlaylist.id,
                tracks = if (isPersonalPlaylist) shareableOnlineTracks else remotePlaylistTracks
            )
        }
    }

    SeitharolPullToRefreshBox(
        isRefreshing = isLoadingPlaylist,
        onRefresh = triggerReload,
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .testTag("playlist_screen")
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 200.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 340.dp)
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
                            shape = RoundedCornerShape(14.dp),
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

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isPersonalPlaylist) {
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    onClick = { showRenameDialog = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename Playlist",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = handleSharePlaylist
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share Playlist",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
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
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .statusBarsPadding()
                            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val badgeTag = remember(playlistId, displayTitle, displayDescription, isPersonalPlaylist) {
                            when {
                                isPersonalPlaylist -> "PERSONAL PLAYLIST"
                                playlistId.startsWith("RD") || displayTitle.contains("Mix", ignoreCase = true) -> "MIX"
                                playlistId.startsWith("MPREb_") || playlistId.startsWith("OLAK5uy_") || displayTitle.contains("Album", ignoreCase = true) -> "ALBUM"
                                displayDescription.contains("Single", ignoreCase = true) -> "SINGLE"
                                else -> "PLAYLIST"
                            }
                        }
                        val badgeColor = when (badgeTag) {
                            "MIX" -> Color(0xFFB71C1C)
                            "ALBUM" -> Color(0xFF8E24AA)
                            "SINGLE" -> Color(0xFF00897B)
                            "PERSONAL PLAYLIST" -> MaterialTheme.colorScheme.primary
                            else -> Color(0xFF2E66FF)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = badgeColor
                        ) {
                            Text(
                                text = badgeTag,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = currentPlaylist.title,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 23.sp,
                            lineHeight = 29.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = currentPlaylist.description.ifBlank { "Curated tracks & releases" },
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    viewModel.downloadAllTracks(filteredTracks, DownloadTier.STANDARD)
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(14.dp),
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
                                    fontFamily = OutfitFontFamily,
                                    modifier = Modifier.padding(horizontal = 4.dp)
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
                                    shape = RoundedCornerShape(18.dp),
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
                                    fontFamily = OutfitFontFamily,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    if (filteredTracks.isNotEmpty()) {
                                        viewModel.playQueue(filteredTracks.shuffled(), 0)
                                    }
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = "Shuffle",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Shuffle",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    if (isPersonalPlaylist) {
                                        handleSharePlaylist()
                                    } else {
                                        viewModel.togglePlaylistLike(currentPlaylist)
                                    }
                                }
                            ) {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isPersonalPlaylist) {
                                                Icons.Default.Share
                                            } else if (currentPlaylist.isLiked) {
                                                Icons.Default.Favorite
                                            } else {
                                                Icons.Outlined.FavoriteBorder
                                            },
                                            contentDescription = if (isPersonalPlaylist) "Share" else "Like",
                                            tint = if (!isPersonalPlaylist && currentPlaylist.isLiked) SeitharolRedHeart else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isPersonalPlaylist) "Share" else if (currentPlaylist.isLiked) "Liked" else "Like",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    modifier = Modifier.padding(horizontal = 4.dp)
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
                    placeholder = {
                        Text(
                            text = "Search in ${currentPlaylist.title}...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            if (filteredTracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingPlaylist) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            Text(
                                text = if (searchQuery.isNotEmpty()) {
                                    "No tracks matched '$searchQuery'"
                                } else {
                                    "This playlist is empty.\nAdd tracks from Search, Home, or Player to build your playlist."
                                },
                                color = SeitharolTextSecondary,
                                fontFamily = OutfitFontFamily,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                            onStartRadioClick = { viewModel.startRadioMix(track) },
                            onAddToPlaylistClick = { trackToAddToPlaylist = track },
                            onGoToArtistClick = { creator ->
                                viewModel.navigateToArtist(creator, isChannel = track.isVideoTrack)
                            },
                            onRemoveFromPlaylistClick = if (isPersonalPlaylist) {
                                {
                                    viewModel.removeTrackFromPlaylist(currentPlaylist.id, track.id)
                                    remotePlaylistTracks = remotePlaylistTracks.filterNot { it.id == track.id }
                                }
                            } else null,
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
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = currentPlaylist.title,
                        fontFamily = SyneFontFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SeitharolDark,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Text(
                        text = "${remotePlaylistTracks.size} tracks" + if (isPersonalPlaylist) " • ${shareableOnlineTracks.size} online shareable" else "",
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        color = SeitharolTextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
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
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                                handleSharePlaylist()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = if (isPersonalPlaylist) "Share Playlist (Online Songs Only)" else "Share Playlist",
                                fontFamily = OutfitFontFamily,
                                fontSize = 15.sp,
                                color = SeitharolDark
                            )
                            if (isPersonalPlaylist) {
                                Text(
                                    text = "Shares ${shareableOnlineTracks.size} YouTube / JioSaavn tracks (excludes offline files)",
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 11.5.sp,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }

                    if (isPersonalPlaylist) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showMoreSheet = false
                                    showRenameDialog = true
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = SeitharolDark)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Rename Playlist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = SeitharolDark)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showMoreSheet = false
                                    showDeleteConfirmDialog = true
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Delete Playlist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (showRenameDialog) {
            RenamePlaylistDialog(
                initialTitle = currentPlaylist.title,
                initialDescription = currentPlaylist.description,
                onConfirm = { newTitle, newDesc ->
                    viewModel.renamePlaylist(currentPlaylist.id, newTitle, newDesc)
                },
                onDismiss = { showRenameDialog = false }
            )
        }

        if (showDeleteConfirmDialog) {
            DeletePlaylistConfirmDialog(
                playlistTitle = currentPlaylist.title,
                onConfirmDelete = {
                    viewModel.deletePlaylist(currentPlaylist.id)
                    onBack()
                },
                onDismiss = { showDeleteConfirmDialog = false }
            )
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
