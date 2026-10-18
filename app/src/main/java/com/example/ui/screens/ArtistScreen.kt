package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistName: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onShareArtist: (String) -> Unit,
    onNavigateToPlaylist: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val allTracks by viewModel.allTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var isFollowing by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // Canonical Artist Profile: group all tracks by normalized name
    // so multiple-source fragments (YouTube + JioSaavn + local) merge into one page.
    val normalizedQuery = artistName.trim().lowercase()

    var remoteArtistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var remoteArtistAlbums by remember { mutableStateOf<List<com.example.domain.Album>>(emptyList()) }
    var isLoadingArtist by remember { mutableStateOf(false) }

    LaunchedEffect(artistName) {
        isLoadingArtist = true
        try {
            val json = com.example.data.remote.InnerTubeResolver.fetchRealArtistCatalog(artistName)
            val parsedTracks = com.example.data.remote.InnerTubeResolver.parseTracksFromResponse(json)
            val parsedAlbums = com.example.data.remote.InnerTubeResolver.fetchRealArtistAlbums(artistName)
            if (parsedTracks.isNotEmpty()) {
                remoteArtistTracks = parsedTracks
            }
            if (parsedAlbums.isNotEmpty()) {
                remoteArtistAlbums = parsedAlbums
            }
        } catch (e: Exception) {
            // fallback
        } finally {
            isLoadingArtist = false
        }
    }

    val artistTracks = remember(artistName, allTracks, remoteArtistTracks) {
        if (remoteArtistTracks.isNotEmpty()) {
            remoteArtistTracks
        } else {
            val matched = allTracks.filter {
                it.artist.trim().lowercase().contains(normalizedQuery)
                        || normalizedQuery.contains(it.artist.trim().lowercase())
            }
            matched.distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
        }
    }

    // Canonical avatar: from the source with the most tracks for this artist
    val canonicalAvatarUrl = remember(artistTracks) {
        artistTracks
            .filter { it.artUrl.isNotBlank() }
            .groupBy { it.artUrl }
            .maxByOrNull { it.value.size }
            ?.key ?: ""
    }

    val playCounts = listOf(
        "16,511,874",
        "599,211,082",
        "405,207,652",
        "34,821,001",
        "28,140,990",
        "19,420,110",
        "12,850,340"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .testTag("artist_screen"),
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
                    // Back button (44dp pill)
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = KangleiGlassDark,
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
                        color = KangleiGlassDark,
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

                // Artist Info & Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = artistName,
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "104,820,119 monthly listeners",
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = Color(0xFFD0D0D0)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons row: Follow (48dp pill), Play (64dp circle), Radio (48dp pill)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        // Follow action
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { isFollowing = !isFollowing }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = if (isFollowing) KangleiGreen else KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isFollowing) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = "Follow",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFollowing) "Following" else "Follow",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        // Big Play Button (64dp white circle)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (artistTracks.isNotEmpty()) viewModel.playQueue(artistTracks, 0)
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
                                        contentDescription = "Play Artist",
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

                        // Radio / Shuffle action
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                if (artistTracks.isNotEmpty()) viewModel.playQueue(artistTracks.shuffled(), 0)
                            }
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = KangleiGlassDarkActive
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "Shuffle Radio",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Shuffle",
                                color = Color(0xFFD0D0D0),
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }
        }

        // Albums & Singles Section
        if (remoteArtistAlbums.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Albums & Singles",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = KangleiDark,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(remoteArtistAlbums) { album ->
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onNavigateToPlaylist?.invoke(album.id)
                                }
                        ) {
                            coil.compose.AsyncImage(
                                model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(album.thumbnailUrl),
                                contentDescription = album.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = album.title,
                                color = KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Popular Section Title
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Popular",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = KangleiDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // Numbered track list
        itemsIndexed(artistTracks) { index, track ->
            val plays = playCounts.getOrElse(index) { "12,410,200" }
            TrackCard(
                track = track,
                isPlaying = playerState.currentTrack?.id == track.id,
                rankNumber = index + 1,
                subtitleText = plays,
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

    // More Options Bottom Sheet
    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            containerColor = KangleiWhite,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = artistName,
                    fontFamily = SyneFontFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KangleiDark
                )
                Text(
                    text = "${artistTracks.size} tracks available",
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    color = KangleiTextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = KangleiBorder)
                Spacer(modifier = Modifier.height(8.dp))

                // Play Next
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            artistTracks.reversed().forEach { viewModel.playNext(it) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = KangleiDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Play Next", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = KangleiDark)
                }

                // Add all to Queue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            artistTracks.forEach { viewModel.addToQueue(it) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = KangleiDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Add all to Queue", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = KangleiDark)
                }

                // Download Artist Tracks
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            artistTracks.forEach { viewModel.startDownload(it, DownloadTier.HIGH) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = KangleiDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Download Artist Tracks", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = KangleiDark)
                }

                // Share Artist
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showMoreSheet = false
                            onShareArtist(artistName)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = KangleiDark)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Share Artist", fontFamily = OutfitFontFamily, fontSize = 15.sp, color = KangleiDark)
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
                viewModel.createPlaylist(name, "User created playlist")
                showCreatePlaylistDialog = false
            },
            onDismiss = {
                showCreatePlaylistDialog = false
            }
        )
    }
}
