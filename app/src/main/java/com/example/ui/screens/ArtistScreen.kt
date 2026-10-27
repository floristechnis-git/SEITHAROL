package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.data.model.formatDurationSeconds
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.SeitharolDeepLinkHelper
import com.example.ui.components.TrackCard
import com.example.ui.components.TrackDownloadFormatQualitySheet
import com.example.ui.theme.*
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.YtmArtistPageData
import com.seitharol.ui.search.BackArrowSvgIcon

private enum class ChannelVideoSortFilter(val label: String) {
    MOST_RECENT("Latest"),
    MOST_VIEWS("Popular"),
    OLDEST("Oldest")
}

private enum class ArtistChannelTab(val label: String) {
    ALL("Overview"),
    VIDEOS_OR_TRACKS("All Uploads"),
    PLAYLISTS("Playlists & Mixes"),
    RELEASES("Albums & Singles")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(
    artistName: String,
    isChannel: Boolean = false,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onShareArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()

    var artistData by remember(artistName, isChannel) { mutableStateOf<YtmArtistPageData?>(null) }
    var isLoading by remember(artistName, isChannel) { mutableStateOf(true) }

    var searchQuery by remember(artistName, isChannel) { mutableStateOf("") }
    var sortFilter by remember(artistName, isChannel) { mutableStateOf(ChannelVideoSortFilter.MOST_RECENT) }
    var selectedTab by remember(artistName, isChannel) { mutableStateOf(ArtistChannelTab.ALL) }
    var showFullBio by remember(artistName, isChannel) { mutableStateOf(false) }

    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var trackForDownloadSheet by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(artistName, isChannel, isOfflineMode) {
        isLoading = true
        artistData = if (isOfflineMode) {
            null
        } else {
            runCatching {
                InnerTubeStreamExtractor.fetchArtistPage(
                    artistName = artistName,
                    isChannel = isChannel
                )
            }.getOrNull()
        }
        isLoading = false
    }

    val offlineArtistTracks = remember(downloadedTracks, artistName) {
        downloadedTracks.filter { it.artist.contains(artistName, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLoading && artistData == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isChannel) "Loading channel studio..." else "Loading artist discography...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = OutfitFontFamily,
                    fontSize = 13.sp
                )
            }
        } else if (artistData == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { onShareArtist(artistName) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }

                if (offlineArtistTracks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Downloaded Songs by $artistName (${offlineArtistTracks.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                        itemsIndexed(offlineArtistTracks, key = { _, t -> "off_art_${t.id}" }) { idx, track ->
                            TrackCard(
                                track = track,
                                isPlaying = playerState.currentTrack?.id == track.id,
                                rankNumber = idx + 1,
                                onTrackClick = {
                                    viewModel.playQueue(offlineArtistTracks, idx, isPlaylist = false)
                                },
                                onLikeClick = { viewModel.toggleLike(track) },
                                onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                                onPlayNextClick = { viewModel.playNext(track) },
                                onAddToQueueClick = { viewModel.addToQueue(track) },
                                onAddToPlaylistClick = { trackToAddToPlaylist = track },
                                onRemoveDownloadClick = { viewModel.removeDownload(track.id) }
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Channel / Artist page unavailable offline",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Connect to the internet to explore full videos, songs, and playlists for $artistName.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        } else {
            val page = artistData ?: return@Box
            val effectiveIsChannel = isChannel || page.isChannel
            val downloadedIds = remember(downloadedTracks) { downloadedTracks.map { it.id }.toSet() }

            val allCandidates = remember(page.topSongs) {
                page.topSongs.distinctBy { it.videoId }
            }

            val filteredCandidates = remember(allCandidates, searchQuery, sortFilter, effectiveIsChannel) {
                val q = searchQuery.trim().lowercase()
                val searched = if (q.isBlank()) {
                    allCandidates
                } else {
                    allCandidates.filter {
                        it.title.lowercase().contains(q) ||
                            it.artist.lowercase().contains(q) ||
                            (it.albumName?.lowercase()?.contains(q) == true) ||
                            it.viewCountText.lowercase().contains(q) ||
                            it.publishedTimeText.lowercase().contains(q)
                    }
                }
                if (effectiveIsChannel) {
                    when (sortFilter) {
                        ChannelVideoSortFilter.MOST_RECENT -> searched.sortedBy { it.publishedAgeSeconds }
                        ChannelVideoSortFilter.MOST_VIEWS -> searched.sortedByDescending { it.viewCount }
                        ChannelVideoSortFilter.OLDEST -> searched.sortedByDescending { it.publishedAgeSeconds }
                    }
                } else {
                    when (sortFilter) {
                        ChannelVideoSortFilter.MOST_RECENT -> searched
                        ChannelVideoSortFilter.MOST_VIEWS -> searched.sortedByDescending { it.viewCount }
                        ChannelVideoSortFilter.OLDEST -> searched.reversed()
                    }
                }
            }

            val filteredDomainTracks = remember(filteredCandidates, effectiveIsChannel, likedTrackIds, downloadedIds) {
                filteredCandidates.map { cand ->
                    val base = cand.toDomainTrack(forceVideo = effectiveIsChannel)
                    base.copy(
                        isLiked = likedTrackIds.contains(base.id),
                        isDownloaded = downloadedIds.contains(base.id)
                    )
                }
            }

            val pureAlbums = remember(page.albums, effectiveIsChannel) {
                if (effectiveIsChannel) emptyList()
                else page.albums.filter {
                    (it.browseId.startsWith("MPREb_") || it.browseId.startsWith("OLAK5uy_")) &&
                        !it.type.equals("SINGLE", true) &&
                        !it.subtitle.contains("Single", true) &&
                        !it.title.contains("YouTube Video", true)
                }.distinctBy { it.browseId }
            }

            val cleanSingles = remember(page.singles, effectiveIsChannel) {
                if (effectiveIsChannel) emptyList()
                else page.singles.filterNot {
                    it.title.contains("YouTube Video", true) ||
                        it.subtitle.contains("YouTube Video", true)
                }.distinctBy { it.browseId }
            }

            val availableTabs = remember(effectiveIsChannel, page.playlists, pureAlbums, cleanSingles) {
                buildList {
                    add(ArtistChannelTab.ALL)
                    add(ArtistChannelTab.VIDEOS_OR_TRACKS)
                    if (page.playlists.isNotEmpty()) add(ArtistChannelTab.PLAYLISTS)
                    if (!effectiveIsChannel && (pureAlbums.isNotEmpty() || cleanSingles.isNotEmpty())) {
                        add(ArtistChannelTab.RELEASES)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 135.dp)
            ) {
                // ────────────────────────────────────────────────────────────
                // 1. Immersive Editorial Hero Header + Stats + Action Bar
                // ────────────────────────────────────────────────────────────
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        AsyncImage(
                            model = page.bannerUrl.ifBlank { page.avatarUrl },
                            contentDescription = page.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.35f),
                                            Color.Black.copy(alpha = 0.55f),
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                        )

                        // Top Floating Navigation Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = onBack,
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.50f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    BackArrowSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    onClick = { onShareArtist(page.name) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.50f),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share",
                                            tint = Color.White,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Creator / Artist Identity Card at bottom of Hero
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.size(84.dp)) {
                                    if (page.avatarUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = page.avatarUrl,
                                            contentDescription = page.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(84.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(84.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(
                                                    Brush.linearGradient(
                                                        colors = listOf(
                                                            MaterialTheme.colorScheme.primary,
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        )
                                                    )
                                                )
                                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val initial = page.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
                                            Text(
                                                text = initial,
                                                color = Color.White,
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = SyneFontFamily
                                            )
                                        }
                                    }

                                    // Verified Badge
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (effectiveIsChannel) Icons.Default.Tv else Icons.Default.Verified,
                                                contentDescription = "Verified",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = if (effectiveIsChannel) "YOUTUBE CHANNEL" else "OFFICIAL ARTIST",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.8.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = page.name,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 24.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val subLabel = page.subscriberCountText.ifBlank {
                                            if (effectiveIsChannel) "Creator Channel" else "Verified Artist"
                                        }
                                        Text(
                                            text = "$subLabel • ${allCandidates.size} ${if (effectiveIsChannel) "videos" else "tracks"}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.5.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            if (page.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = page.description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    lineHeight = 16.sp,
                                    maxLines = if (showFullBio) 6 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { showFullBio = !showFullBio }
                                )
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 2. Action Bar: Rounded-Square Play All, Shuffle, & Radio Mix
                // ────────────────────────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (filteredDomainTracks.isNotEmpty()) {
                                    viewModel.playQueue(filteredDomainTracks, 0, isPlaylist = true)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1.1f)
                                .height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (effectiveIsChannel) "Play All Videos" else "Play All",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (filteredDomainTracks.isNotEmpty()) {
                                    viewModel.playQueue(filteredDomainTracks.shuffled(), 0, isPlaylist = true)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Shuffle",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                maxLines = 1
                            )
                        }

                        Surface(
                            onClick = {
                                val seed = filteredDomainTracks.firstOrNull()
                                if (seed != null) {
                                    viewModel.startRadioMix(seed)
                                } else if (!page.radioPlaylistId.isNullOrBlank()) {
                                    viewModel.playPlaylistOrMix(page.radioPlaylistId)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Radio,
                                    contentDescription = "Start Radio Mix",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 3. Navigation Category Tabs (Overview, All Uploads, Playlists, Releases)
                // ────────────────────────────────────────────────────────────
                if (availableTabs.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(availableTabs) { tab ->
                                val isSelected = selectedTab == tab
                                val tabTitle = when (tab) {
                                    ArtistChannelTab.ALL -> "Overview"
                                    ArtistChannelTab.VIDEOS_OR_TRACKS -> if (effectiveIsChannel) "Videos (${allCandidates.size})" else "Songs (${allCandidates.size})"
                                    ArtistChannelTab.PLAYLISTS -> "Playlists (${page.playlists.size})"
                                    ArtistChannelTab.RELEASES -> "Albums & Singles (${pureAlbums.size + cleanSingles.size})"
                                }
                                Surface(
                                    onClick = { selectedTab = tab },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = tabTitle,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 4. Featured Spotlight Card (Most Recent / Top Release) on Overview
                // ────────────────────────────────────────────────────────────
                if (selectedTab == ArtistChannelTab.ALL && filteredCandidates.isNotEmpty() && searchQuery.isBlank()) {
                    val spotlight = filteredCandidates.first()
                    val spotlightTrack = filteredDomainTracks.first()
                    val spotlightDur = formatDurationSeconds(spotlight.durationSeconds)
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    viewModel.playQueue(filteredDomainTracks, 0, isPlaylist = true)
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(136.dp)
                                        .height(78.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    AsyncImage(
                                        model = spotlight.thumbnailUrl,
                                        contentDescription = spotlight.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.Black.copy(alpha = 0.78f),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(5.dp)
                                    ) {
                                        Text(
                                            text = spotlightDur,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (effectiveIsChannel) "FEATURED UPLOAD" else "TOP RELEASE",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.7.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = spotlight.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    val metaInfo = listOfNotNull(
                                        spotlight.viewCountText.takeIf { it.isNotBlank() },
                                        spotlight.publishedTimeText.takeIf { it.isNotBlank() }
                                    ).joinToString(" • ").ifBlank { "${page.name} • $spotlightDur" }
                                    Text(
                                        text = metaInfo,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    onClick = {
                                        viewModel.playQueue(filteredDomainTracks, 0, isPlaylist = true)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Spotlight",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 5. Playlists & Mixes Shelf (shown on Overview & Playlists tab)
                // ────────────────────────────────────────────────────────────
                if ((selectedTab == ArtistChannelTab.ALL || selectedTab == ArtistChannelTab.PLAYLISTS) && page.playlists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (effectiveIsChannel) "Channel Playlists (${page.playlists.size})" else "Artist Playlists & Mixes (${page.playlists.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(page.playlists.distinctBy { it.browseId }, key = { "pl_card_${it.browseId}" }) { pl ->
                                val targetId = pl.browseId.ifBlank { pl.playlistId.orEmpty() }
                                val isMix = pl.type.equals("MIX", true) || pl.title.contains("Mix", true) || targetId.startsWith("RD")
                                Column(
                                    modifier = Modifier
                                        .width(156.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onNavigateToPlaylist(targetId) }
                                ) {
                                    Box(modifier = Modifier.size(156.dp)) {
                                        AsyncImage(
                                            model = pl.thumbnailUrl,
                                            contentDescription = pl.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        Surface(
                                            color = Color(0xFF1B1C1C).copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = if (isMix) "MIX" else "PLAYLIST",
                                                color = Color.White,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            onClick = { viewModel.playPlaylistOrMix(targetId) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Play Playlist",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = pl.title,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pl.subtitle,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 6. Search & Sort Filter Bar + Native Lazy Video / Song Catalog
                // ────────────────────────────────────────────────────────────
                if (selectedTab == ArtistChannelTab.ALL || selectedTab == ArtistChannelTab.VIDEOS_OR_TRACKS) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (effectiveIsChannel) "Channel Videos (${filteredCandidates.size})" else "Songs & Releases (${filteredCandidates.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ChannelVideoSortFilter.entries.forEach { option ->
                                        val selected = sortFilter == option
                                        Surface(
                                            onClick = { sortFilter = option },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = option.label,
                                                fontSize = 11.5.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        text = if (effectiveIsChannel) "Filter ${allCandidates.size} videos in ${page.name}..." else "Filter ${allCandidates.size} songs by ${page.name}...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Filter",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (filteredCandidates.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No items match \"${searchQuery.trim()}\"",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else if (effectiveIsChannel) {
                        // Native LazyColumn items for Channel Videos with 16:9 thumbnail, H:MM:SS duration, and full 3-dot menu!
                        itemsIndexed(
                            items = filteredCandidates,
                            key = { idx, item -> "ch_vid_${item.videoId}_$idx" }
                        ) { index, videoCand ->
                            val domainTrack = filteredDomainTracks.getOrElse(index) {
                                videoCand.toDomainTrack(forceVideo = true)
                            }
                            val isPlaying = playerState.currentTrack?.id == domainTrack.id
                            val durFormatted = formatDurationSeconds(videoCand.durationSeconds)
                            val metaParts = buildList {
                                if (videoCand.viewCountText.isNotBlank()) add(videoCand.viewCountText)
                                if (videoCand.publishedTimeText.isNotBlank()) add(videoCand.publishedTimeText)
                                if (isEmpty()) add(page.name)
                            }
                            val metaSubtitle = metaParts.joinToString(" • ")
                            var showMenu by remember { mutableStateOf(false) }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        viewModel.playQueue(filteredDomainTracks, index, isPlaylist = true)
                                    },
                                color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(128.dp)
                                            .height(72.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = videoCand.thumbnailUrl,
                                            contentDescription = videoCand.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        if (isPlaying) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black.copy(alpha = 0.55f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.GraphicEq,
                                                    contentDescription = "Playing",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color.Black.copy(alpha = 0.50f),
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        if (videoCand.durationSeconds > 0) {
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.82f),
                                                shape = RoundedCornerShape(5.dp),
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(4.dp)
                                            ) {
                                                Text(
                                                    text = durFormatted,
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = videoCand.title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = metaSubtitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Box {
                                        IconButton(
                                            onClick = { showMenu = true },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Video Options",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

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
                                                    text = videoCand.title,
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
                                                    text = "${page.name} • $durFormatted",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontFamily = OutfitFontFamily,
                                                    fontSize = 11.5.sp
                                                )
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                            DropdownMenuItem(
                                                text = { Text("Play Video Now", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.playQueue(filteredDomainTracks, index, isPlaylist = true)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.playNext(domainTrack)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.addToQueue(domainTrack)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Start Radio Mix", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.startRadioMix(domainTrack)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (domainTrack.isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (domainTrack.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                                        null,
                                                        tint = if (domainTrack.isLiked) SeitharolRedHeart else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.toggleLike(domainTrack)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    trackToAddToPlaylist = domainTrack
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download (Format & Quality)", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) },
                                                onClick = {
                                                    showMenu = false
                                                    trackForDownloadSheet = domainTrack
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Quick Download Video (MP4)", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.primary) },
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.startDownload(domainTrack, DownloadTier.VIDEO_MP4)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Share", color = MaterialTheme.colorScheme.onSurface) },
                                                leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = {
                                                    showMenu = false
                                                    runCatching {
                                                        val msg = SeitharolDeepLinkHelper.buildTrackShareMessage(domainTrack)
                                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(Intent.EXTRA_TEXT, msg)
                                                        }
                                                        context.startActivity(Intent.createChooser(intent, "Share Video"))
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Native LazyColumn items for Artist Songs using TrackCard (with full 3-dot menu, like, download, playlist, radio)
                        itemsIndexed(
                            items = filteredDomainTracks,
                            key = { idx, item -> "art_song_${item.id}_$idx" }
                        ) { index, track ->
                            TrackCard(
                                track = track,
                                isPlaying = playerState.currentTrack?.id == track.id,
                                rankNumber = index + 1,
                                onTrackClick = {
                                    viewModel.playQueue(filteredDomainTracks, index, isPlaylist = true)
                                },
                                onLikeClick = { viewModel.toggleLike(track) },
                                onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                                onPlayNextClick = { viewModel.playNext(track) },
                                onAddToQueueClick = { viewModel.addToQueue(track) },
                                onStartRadioClick = { viewModel.startRadioMix(track) },
                                onAddToPlaylistClick = { trackToAddToPlaylist = track },
                                onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 7. Albums & Singles Section (shown on Overview & Releases tab)
                // ────────────────────────────────────────────────────────────
                if (selectedTab == ArtistChannelTab.ALL || selectedTab == ArtistChannelTab.RELEASES) {
                    if (pureAlbums.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Albums (${pureAlbums.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(pureAlbums, key = { "alb_${it.browseId}" }) { release ->
                                    Column(
                                        modifier = Modifier
                                            .width(150.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onNavigateToPlaylist(release.browseId) }
                                    ) {
                                        Box(modifier = Modifier.size(150.dp)) {
                                            AsyncImage(
                                                model = release.thumbnailUrl,
                                                contentDescription = release.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                            Surface(
                                                color = Color(0xFF1B1C1C).copy(alpha = 0.85f),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    text = "ALBUM",
                                                    color = Color.White,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Surface(
                                                onClick = { viewModel.playPlaylistOrMix(release.browseId) },
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(8.dp)
                                                    .size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play Album",
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = release.title,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = release.subtitle,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (cleanSingles.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Singles & EPs (${cleanSingles.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(cleanSingles, key = { "sgl_${it.browseId}_${it.title}" }) { singleItem ->
                                    Column(
                                        modifier = Modifier
                                            .width(144.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { viewModel.playSingleRelease(singleItem, page.name) }
                                    ) {
                                        Box(modifier = Modifier.size(144.dp)) {
                                            AsyncImage(
                                                model = singleItem.thumbnailUrl,
                                                contentDescription = singleItem.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                            Surface(
                                                onClick = { viewModel.playSingleRelease(singleItem, page.name) },
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(8.dp)
                                                    .size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play Single",
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = singleItem.title,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = singleItem.subtitle.ifBlank { "Single • ${page.name}" },
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ────────────────────────────────────────────────────────────
                // 8. Fans Also Like (Similar Artists)
                // ────────────────────────────────────────────────────────────
                if (page.similarArtists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Fans Also Like",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(page.similarArtists.distinctBy { it.browseId }, key = { "sim_${it.browseId}" }) { sim ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(104.dp)
                                        .clickable { viewModel.navigateToArtist(sim.name) }
                                ) {
                                    if (sim.avatarUrl.isNotBlank() && InnerTubeStreamExtractor.isAuthenticArtistAvatar(sim.avatarUrl)) {
                                        AsyncImage(
                                            model = sim.avatarUrl,
                                            contentDescription = sim.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(92.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(92.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(
                                                        colors = listOf(
                                                            MaterialTheme.colorScheme.primaryContainer,
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                        )
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val initial = sim.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
                                            Text(
                                                text = initial,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = sim.name,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
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

    trackForDownloadSheet?.let { track ->
        TrackDownloadFormatQualitySheet(
            track = track,
            onDismiss = { trackForDownloadSheet = null },
            onConfirmDownload = { chosenTier ->
                viewModel.startDownload(track, chosenTier)
                trackForDownloadSheet = null
            }
        )
    }
}

private fun CandidateStream.toDomainTrack(forceVideo: Boolean = false): Track {
    val isVid = forceVideo
    val cleanAlbum = this.albumName
        ?.takeUnless { it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true) }
        .orEmpty()
    return Track(
        id = this.videoId,
        title = this.title,
        artist = this.artist,
        album = cleanAlbum,
        durationMs = this.durationSeconds * 1000L,
        artUrl = this.thumbnailUrl,
        genre = if (isVid) "YouTube Video" else "Music",
        itag = if (isVid) 18 else 251,
        codec = if (isVid) "MP4" else "Opus"
    )
}
