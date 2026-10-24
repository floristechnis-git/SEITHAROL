package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
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

@Composable
fun ArtistScreen(
    artistName: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onShareArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    var artistData by remember(artistName) { mutableStateOf<YtmArtistPageData?>(null) }
    var isLoading by remember(artistName) { mutableStateOf(true) }

    LaunchedEffect(artistName, isOfflineMode) {
        isLoading = true
        artistData = if (isOfflineMode) {
            null
        } else {
            runCatching { InnerTubeStreamExtractor.fetchArtistPage(artistName) }.getOrNull()
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
                                headlineContent = { Text(track.title, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("${track.artist} • Offline") },
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
            val domainTopSongs = remember(page.topSongs) { page.topSongs.map { it.toDomainTrack() } }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // 1. Artist Banner + Circular Verified Avatar Header
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

                        // Artist Avatar + Name + Subscriber Count
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
                                        color = MaterialTheme.colorScheme.onPrimary,
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
                                    isOfficial && page.subscriberCountText.isNotBlank() -> "${page.subscriberCountText} • Official Artist"
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

                // 2. Play Top Songs & Radio Mix Action Row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (domainTopSongs.isNotEmpty()) {
                                    viewModel.playQueue(domainTopSongs, 0, isPlaylist = false)
                                }
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Play Top",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val seed = domainTopSongs.firstOrNull()
                                if (seed != null) {
                                    viewModel.startRadioMix(seed)
                                } else if (!page.radioPlaylistId.isNullOrBlank()) {
                                    viewModel.playPlaylistOrMix(page.radioPlaylistId)
                                }
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Artist Radio",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 3. Top Songs Section
                if (domainTopSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Top Songs",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                    items(domainTopSongs.distinctBy { it.id }.take(10), key = { "art_song_${it.id}" }) { track ->
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

                // 4A. Official Multi-Track Albums Carousel
                val pureAlbums = page.albums.filterNot {
                    it.type.equals("SINGLE", true) ||
                        it.subtitle.contains("Single", true) ||
                        it.subtitle.contains("1 song", true) ||
                        it.subtitle.contains("1 track", true)
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

                // 4B. Singles
                if (page.singles.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Singles",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(page.singles.distinctBy { it.title.lowercase().trim() }.take(10), key = { "single_song_${it.browseId}_${it.title}" }) { singleItem ->
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

                // 5. Artist Playlists & Mixes Carousel
                if (page.playlists.isNotEmpty()) {
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

private fun CandidateStream.toDomainTrack(): Track = Track(
    id = this.videoId,
    title = this.title,
    artist = this.artist,
    album = this.albumName.orEmpty(),
    durationMs = this.durationSeconds * 1000L,
    artUrl = this.thumbnailUrl
)
