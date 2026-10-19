package com.seitharol.ui.discover

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.R
import com.seitharol.data.download.DownloadFormat
import com.seitharol.data.download.DownloadPreferencesAndService
import com.seitharol.data.download.DownloadProvider
import com.seitharol.data.download.DownloadTaskState
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    feedTracks: List<NormalizedTrack>,
    audioPlayerManager: AudioPlayerManager,
    downloadService: DownloadPreferencesAndService,
    onSearchPreviewTracks: suspend (String) -> List<NormalizedTrack>,
    onToggleLike: (NormalizedTrack) -> Unit,
    onVisitArtist: (artistName: String, artistBrowseId: String?) -> Unit,
    onOpenFullSongMaximized: (NormalizedTrack) -> Unit,
    onTrackDownloadedLocally: (NormalizedTrack) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { feedTracks.size })

    var isInDiscoverSearchOpen by remember { mutableStateOf(false) }
    var trackForDownloadSheet by remember { mutableStateOf<NormalizedTrack?>(null) }
    val downloadTaskState by downloadService.downloadState.collectAsState()

    // ExoPlayer Lifecycle: Play 30s preview for active page; pause when In-Discover Search opens or screen disposes
    LaunchedEffect(pagerState.settledPage, feedTracks, isInDiscoverSearchOpen) {
        if (isInDiscoverSearchOpen) {
            audioPlayerManager.pausePreview()
        } else {
            val activeTrack = feedTracks.getOrNull(pagerState.settledPage)
            if (activeTrack != null) {
                audioPlayerManager.playPreview(activeTrack)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioPlayerManager.pausePreview()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (feedTracks.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { pageIndex ->
                val track = feedTracks[pageIndex]
                DiscoverShortPage(
                    track = track,
                    downloadState = downloadTaskState,
                    onLikeClick = { onToggleLike(track) },
                    onDownloadClick = { trackForDownloadSheet = track },
                    onVisitArtistClick = {
                        audioPlayerManager.pausePreview()
                        onVisitArtist(track.artists.firstOrNull().orEmpty(), track.primaryArtistBrowseId)
                    },
                    onShareClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Check out \"${track.title}\" by ${track.artists.joinToString(", ")} on SEITHAROL: https://open.spotify.com/track/${track.sourceId}"
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                    },
                    onOpenFullSongClick = {
                        scope.launch {
                            audioPlayerManager.openFullLengthSong(
                                track = track,
                                onMaximizePlayer = onOpenFullSongMaximized
                            )
                        }
                    }
                )
            }
        }

        // Top Bar: Back Button (Left) + Title + In-Discover Search Icon (Top-Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    audioPlayerManager.stopPreviewAndClear()
                    onNavigateBack()
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }

            Text(
                text = "Discover & Download",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            // Top-Right Search Icon -> Opens distinct In-Discover Search UI
            IconButton(
                onClick = { isInDiscoverSearchOpen = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Text("🔍", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // In-Discover Search Surface Overlay (Visually & Behaviorally Distinct from SearchScreen)
        if (isInDiscoverSearchOpen) {
            InDiscoverSearchSurface(
                audioPlayerManager = audioPlayerManager,
                onSearch = onSearchPreviewTracks,
                onOpenDownloadPicker = { selectedTrack -> trackForDownloadSheet = selectedTrack },
                onOpenFullSong = { selectedTrack ->
                    scope.launch {
                        isInDiscoverSearchOpen = false
                        audioPlayerManager.openFullLengthSong(
                            track = selectedTrack,
                            onMaximizePlayer = onOpenFullSongMaximized
                        )
                    }
                },
                onClose = { isInDiscoverSearchOpen = false }
            )
        }

        // Provider + Format Picker Bottom Sheet (Most-frequently-used provider on top)
        trackForDownloadSheet?.let { targetTrack ->
            DownloadProviderFormatBottomSheet(
                track = targetTrack,
                downloadService = downloadService,
                onDismiss = { trackForDownloadSheet = null },
                onStartDownload = { provider, format ->
                    trackForDownloadSheet = null
                    scope.launch {
                        downloadService.downloadTrackToPublicMusicStorage(
                            track = targetTrack,
                            provider = provider,
                            format = format,
                            onTrackSavedAsLocalMusic = onTrackDownloadedLocally
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun DiscoverShortPage(
    track: NormalizedTrack,
    downloadState: DownloadTaskState,
    onLikeClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onVisitArtistClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullSongClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred ambient background art
        AsyncImage(
            model = track.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(28.dp)
        )

        // Dark gradient scrim for legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        // Centered Hero Album Artwork
        AsyncImage(
            model = track.artUrl,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-40).dp)
                .size(290.dp)
                .clip(RoundedCornerShape(24.dp))
        )

        // Bottom Metadata + 5 Actions Rail
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, bottom = 36.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Left Column: 30s Preview Pill, Track Title, Artist, and "Open Full-Length Song" CTA
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "30s Preview",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = track.artists.joinToString(", "),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onVisitArtistClick() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action 1: Open Full-Length Song (Routes through TrackMatcher -> ResolverEngine)
                Button(
                    onClick = onOpenFullSongClick,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("▶ Open Full-Length Song", fontWeight = FontWeight.Bold)
                }

                // Live Download Progress Banner if downloading this track
                if (downloadState is DownloadTaskState.Downloading && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    Text(
                        text = "Downloading via ${downloadState.provider.displayName} (${downloadState.progressPercent}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                } else if (downloadState is DownloadTaskState.Failed && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF5252)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right Vertical Rail: Actions 2..5 (Like, Download, Artist, Share)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Action 5: Like
                DiscoverActionButton(
                    iconText = if (track.isLiked) "♥" else "♡",
                    label = "Like",
                    tint = if (track.isLiked) Color(0xFFFF4E45) else Color.White,
                    onClick = onLikeClick
                )

                // Action 2: Download (Provider + Format Picker)
                DiscoverActionButton(
                    iconText = "⬇",
                    label = "Download",
                    onClick = onDownloadClick
                )

                // Action 3: Visit Artist Page
                DiscoverActionButton(
                    iconText = "👤",
                    label = "Artist",
                    onClick = onVisitArtistClick
                )

                // Action 4: Share
                DiscoverActionButton(
                    iconText = "↗",
                    label = "Share",
                    onClick = onShareClick
                )
            }
        }
    }
}

@Composable
private fun DiscoverActionButton(
    iconText: String,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = iconText, color = tint, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadProviderFormatBottomSheet(
    track: NormalizedTrack,
    downloadService: DownloadPreferencesAndService,
    onDismiss: () -> Unit,
    onStartDownload: (DownloadProvider, DownloadFormat) -> Unit
) {
    val sortedProviders = remember { downloadService.getSortedProviders() }
    var selectedProvider by remember { mutableStateOf(sortedProviders.first()) }
    var selectedFormat by remember { mutableStateOf(downloadService.getPreferredFormat()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Download Full Track",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "${track.title} • ${track.artists.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Select Format",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DownloadFormat.entries.forEach { format ->
                    val formatIconRes = when (format) {
                        DownloadFormat.MP3 -> R.drawable.ic_mp3
                        DownloadFormat.FLAC -> R.drawable.ic_flac
                    }
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        leadingIcon = {
                            Image(
                                painter = painterResource(id = formatIconRes),
                                contentDescription = format.label,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(format.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Select Provider (Most Used First)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            sortedProviders.forEachIndexed { index, provider ->
                val usageCount = downloadService.getUsageCount(provider)
                val providerIconRes = when (provider) {
                    DownloadProvider.AMAZON -> R.drawable.ic_amazon_music
                    DownloadProvider.TIDAL -> R.drawable.ic_tidal
                    DownloadProvider.QOBUZ -> R.drawable.ic_qobuz
                    DownloadProvider.JIOSAAVN -> R.drawable.ic_jiosaavn_icon
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedProvider = provider }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedProvider == provider,
                        onClick = { selectedProvider = provider }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = providerIconRes),
                            contentDescription = provider.displayName,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                            if (index == 0 && usageCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Most Used",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = provider.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onStartDownload(selectedProvider, selectedFormat) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Download ${selectedFormat.ext.uppercase()} via ${selectedProvider.displayName}")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
