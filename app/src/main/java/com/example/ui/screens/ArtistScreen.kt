package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.YtmArtistPageData
import com.seitharol.ui.search.BackArrowSvgIcon

private enum class ChannelVideoSortFilter(val label: String) {
    MOST_RECENT("Most Recent"),
    MOST_VIEWS("Most Views")
}

@OptIn(ExperimentalMaterial3Api::class)
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

    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    var artistData by remember(artistName, isChannel) { mutableStateOf<YtmArtistPageData?>(null) }
    var isLoading by remember(artistName, isChannel) { mutableStateOf(true) }

    var channelVideoSearchQuery by remember(artistName, isChannel) { mutableStateOf("") }
    var channelVideoSortFilter by remember(artistName, isChannel) { mutableStateOf(ChannelVideoSortFilter.MOST_RECENT) }

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
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (artistData == null) {
            // Safe Offline / Error Fallback with Back Button and any downloaded songs by this artist
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
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
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
                        items(offlineArtistTracks, key = { "off_art_${it.id}" }) { track ->
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                    headlineColor = MaterialTheme.colorScheme.onBackground,
                                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                headlineContent = {
                                    Text(
                                        text = track.title,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "${track.artist} • Offline",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    AsyncImage(
                                        model = track.artUrl,
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.playQueue(offlineArtistTracks, offlineArtistTracks.indexOf(track).coerceAtLeast(0), isPlaylist = false)
                                }
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
                                text = "Artist page unavailable offline",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Connect to the internet to browse full discography for $artistName.",
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
            val domainTopSongs = remember(page.topSongs, effectiveIsChannel) {
                page.topSongs.distinctBy { it.videoId }.map { it.toDomainTrack(forceVideo = effectiveIsChannel) }
            }

            // Filtered & sorted channel videos when visiting a Channel
            val filteredChannelCandidates = remember(page.topSongs, channelVideoSearchQuery, channelVideoSortFilter) {
                val distinct = page.topSongs.distinctBy { it.videoId }
                val q = channelVideoSearchQuery.trim().lowercase()
                val searched = if (q.isBlank()) {
                    distinct
                } else {
                    distinct.filter {
                        it.title.lowercase().contains(q) ||
                            it.artist.lowercase().contains(q) ||
                            it.viewCountText.lowercase().contains(q) ||
                            it.publishedTimeText.lowercase().contains(q)
                    }
                }
                when (channelVideoSortFilter) {
                    ChannelVideoSortFilter.MOST_RECENT -> searched.sortedBy { it.publishedAgeSeconds }
                    ChannelVideoSortFilter.MOST_VIEWS -> searched.sortedByDescending { it.viewCount }
                }
            }
            val filteredChannelDomainTracks = remember(filteredChannelCandidates) {
                filteredChannelCandidates.map { it.toDomainTrack(forceVideo = true) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // 1. Artist / Channel Banner + Circular Verified Avatar Header
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                    ) {
                        AsyncImage(
                            model = page.bannerUrl,
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
                                            Color.Black.copy(alpha = 0.25f),
                                            Color.Black.copy(alpha = 0.45f),
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                        )

                        // Top Back & Share Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                            ) {
                                BackArrowSvgIcon(tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            IconButton(
                                onClick = { onShareArtist(page.name) },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                            }
                        }

                        // Avatar + Name + Subscriber Count
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (page.avatarUrl.isNotBlank() && InnerTubeStreamExtractor.isAuthenticArtistAvatar(page.avatarUrl)) {
                                AsyncImage(
                                    model = page.avatarUrl,
                                    contentDescription = page.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        )
                                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val initial = page.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
                                    Text(
                                        text = initial,
                                        color = Color.White,
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = page.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val isOfficial = page.browseId.startsWith("UC") || page.browseId.startsWith("MPLA")
                                val subtitleText = when {
                                    effectiveIsChannel && page.subscriberCountText.isNotBlank() && !page.subscriberCountText.contains("Channel", true) ->
                                        "${page.subscriberCountText} • Channel"
                                    effectiveIsChannel -> "YouTube Channel"
                                    isOfficial && page.subscriberCountText.isNotBlank() && !page.subscriberCountText.contains("Artist", true) ->
                                        "${page.subscriberCountText} • Official Artist"
                                    isOfficial && page.subscriberCountText.isNotBlank() -> page.subscriberCountText
                                    isOfficial -> "Official Artist"
                                    page.subscriberCountText.isNotBlank() -> page.subscriberCountText
                                    else -> "Artist"
                                }
                                Text(
                                    text = subtitleText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 2. Play Top Songs & Radio Mix / Shuffle Action Row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val targetQueue = if (effectiveIsChannel && filteredChannelDomainTracks.isNotEmpty()) {
                                    filteredChannelDomainTracks
                                } else {
                                    domainTopSongs
                                }
                                if (targetQueue.isNotEmpty()) {
                                    viewModel.playQueue(targetQueue, 0, isPlaylist = false)
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (effectiveIsChannel) "Play Videos" else "Play Top",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (effectiveIsChannel) {
                                    val targetQueue = if (filteredChannelDomainTracks.isNotEmpty()) filteredChannelDomainTracks else domainTopSongs
                                    if (targetQueue.isNotEmpty()) {
                                        viewModel.playQueue(targetQueue.shuffled(), 0, isPlaylist = false)
                                    }
                                } else {
                                    val seed = domainTopSongs.firstOrNull()
                                    if (seed != null) {
                                        viewModel.startRadioMix(seed)
                                    } else if (!page.radioPlaylistId.isNullOrBlank()) {
                                        viewModel.playPlaylistOrMix(page.radioPlaylistId)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (effectiveIsChannel) "Shuffle Channel" else "Artist Radio",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3. When visiting a Channel: show Channel Playlists Carousel first so playlists are immediately accessible!
                if (effectiveIsChannel && page.playlists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Channel Playlists (${page.playlists.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(page.playlists.distinctBy { it.browseId }, key = { "ch_pl_${it.browseId}" }) { pl ->
                                Column(
                                    modifier = Modifier
                                        .width(148.dp)
                                        .clickable { onNavigateToPlaylist(pl.browseId) }
                                ) {
                                    Box(modifier = Modifier.size(148.dp)) {
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
                                                .padding(6.dp)
                                        ) {
                                            Text(
                                                text = "PLAYLIST",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = pl.title,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pl.subtitle,
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

                // 4. Channel All Videos Searchable Div (with Most Views / Most Recent filter chips) OR Artist Top Songs
                if (effectiveIsChannel) {
                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "All Videos (${filteredChannelCandidates.size})",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = channelVideoSortFilter.label,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Searchable Input inside All Videos Div
                                OutlinedTextField(
                                    value = channelVideoSearchQuery,
                                    onValueChange = { channelVideoSearchQuery = it },
                                    placeholder = {
                                        Text(
                                            text = "Search videos in ${page.name}...",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search Channel Videos",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (channelVideoSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { channelVideoSearchQuery = "" }) {
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

                                Spacer(modifier = Modifier.height(10.dp))

                                // Filter Chips: Most Recent & Most Views
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ChannelVideoSortFilter.entries.forEach { filterOption ->
                                        val selected = channelVideoSortFilter == filterOption
                                        FilterChip(
                                            selected = selected,
                                            onClick = { channelVideoSortFilter = filterOption },
                                            label = {
                                                Text(
                                                    text = filterOption.label,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                labelColor = MaterialTheme.colorScheme.onSurface,
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (filteredChannelCandidates.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No videos match \"${channelVideoSearchQuery.trim()}\"",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        filteredChannelCandidates.forEachIndexed { index, videoCand ->
                                            val durMin = videoCand.durationSeconds / 60
                                            val durSec = videoCand.durationSeconds % 60
                                            val durFormatted = "%d:%02d".format(durMin, durSec)
                                            val metaParts = buildList {
                                                if (videoCand.viewCountText.isNotBlank()) add(videoCand.viewCountText)
                                                if (videoCand.publishedTimeText.isNotBlank()) add(videoCand.publishedTimeText)
                                                if (isEmpty()) add(page.name)
                                            }
                                            val metaSubtitle = metaParts.joinToString(" • ")

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        viewModel.playQueue(filteredChannelDomainTracks, index, isPlaylist = false)
                                                    }
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(104.dp)
                                                        .height(60.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                                ) {
                                                    AsyncImage(
                                                        model = videoCand.thumbnailUrl,
                                                        contentDescription = videoCand.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                    if (videoCand.durationSeconds > 0) {
                                                        Surface(
                                                            color = Color.Black.copy(alpha = 0.78f),
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .padding(4.dp)
                                                        ) {
                                                            Text(
                                                                text = durFormatted,
                                                                color = Color.White,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = videoCand.title,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = metaSubtitle,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (index < filteredChannelCandidates.lastIndex) {
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (domainTopSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Top Songs (${domainTopSongs.size})",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                    items(domainTopSongs.distinctBy { it.id }.take(20), key = { "art_song_${it.id}" }) { track ->
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = MaterialTheme.colorScheme.onBackground,
                                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            headlineContent = {
                                Text(
                                    text = track.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = track.artist,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = track.artUrl,
                                    contentDescription = track.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            },
                            modifier = Modifier.clickable {
                                val idx = domainTopSongs.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                viewModel.playQueue(domainTopSongs, idx)
                            }
                        )
                    }
                }

                // 4A. Official Multi-Track Albums Carousel (for Music Artists)
                val pureAlbums = if (effectiveIsChannel) {
                    emptyList()
                } else {
                    page.albums.filter {
                        (it.browseId.startsWith("MPREb_") || it.browseId.startsWith("OLAK5uy_")) &&
                            !it.type.equals("SINGLE", true) &&
                            !it.subtitle.contains("Single", true) &&
                            !it.subtitle.contains("1 song", true) &&
                            !it.subtitle.contains("1 track", true) &&
                            !it.title.contains("YouTube Video", true) &&
                            !it.subtitle.contains("YouTube Video", true) &&
                            !it.title.equals("Videos", true) &&
                            !it.title.equals("YouTube Videos", true)
                    }
                }
                if (pureAlbums.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(pureAlbums.distinctBy { it.browseId }, key = { "alb_${it.browseId}" }) { release ->
                                Column(
                                    modifier = Modifier
                                        .width(142.dp)
                                        .clickable { onNavigateToPlaylist(release.browseId) }
                                ) {
                                    Box(modifier = Modifier.size(142.dp)) {
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
                                                .padding(6.dp)
                                        ) {
                                            Text(
                                                text = "ALBUM",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
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

                // 4B. Singles (for Music Artists)
                val cleanSingles = if (effectiveIsChannel) {
                    emptyList()
                } else {
                    page.singles.filterNot {
                        it.title.contains("YouTube Video", true) ||
                            it.subtitle.contains("YouTube Video", true) ||
                            it.title.equals("Videos", true)
                    }
                }
                if (cleanSingles.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Singles",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(cleanSingles.distinctBy { it.title.lowercase().trim() }.take(10), key = { "single_song_${it.browseId}_${it.title}" }) { singleItem ->
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                headlineColor = MaterialTheme.colorScheme.onBackground,
                                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            headlineContent = {
                                Text(
                                    text = singleItem.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "${page.name} • Single Track",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = singleItem.thumbnailUrl,
                                    contentDescription = singleItem.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.playSingleRelease(singleItem, page.name) }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Single",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.playSingleRelease(singleItem, page.name)
                            }
                        )
                    }
                }

                // 5. Artist Playlists & Mixes Carousel (for Music Artists)
                if (!effectiveIsChannel && page.playlists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Artist Playlists & Mixes",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(page.playlists.distinctBy { it.browseId }, key = { "art_pl_${it.browseId}" }) { pl ->
                                Column(
                                    modifier = Modifier
                                        .width(142.dp)
                                        .clickable { onNavigateToPlaylist(pl.browseId) }
                                ) {
                                    Box(modifier = Modifier.size(142.dp)) {
                                        AsyncImage(
                                            model = pl.thumbnailUrl,
                                            contentDescription = pl.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        val tag = if (pl.type.equals("MIX", true) || pl.title.contains("Mix", true) || pl.browseId.startsWith("RD")) "MIX" else "PLAYLIST"
                                        Surface(
                                            color = Color(0xFF1B1C1C).copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(6.dp)
                                        ) {
                                            Text(
                                                text = tag,
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = pl.title,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pl.subtitle,
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

                // 6. Fans Also Like (Similar Artists)
                if (page.similarArtists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Fans Also Like",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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
