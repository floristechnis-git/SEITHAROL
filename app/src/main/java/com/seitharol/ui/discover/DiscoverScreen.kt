package com.seitharol.ui.discover

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.download.DownloadFormat
import com.seitharol.data.download.DownloadPreferencesAndService
import com.seitharol.data.download.DownloadProvider
import com.seitharol.data.download.DownloadTaskState
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import com.seitharol.ui.history.HeartSvgIcon
import com.seitharol.ui.home.SearchMagnifierSvgIcon
import com.seitharol.ui.search.BackArrowSvgIcon
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
        onDispose { audioPlayerManager.pausePreview() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (feedTracks.isEmpty()) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { pageIndex ->
                val track = feedTracks[pageIndex]
                val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }

                DiscoverShortPage(
                    track = track,
                    downloadState = downloadTaskState,
                    onLikeClick = { onToggleLike(track) },
                    onDownloadClick = { trackForDownloadSheet = track },
                    onVisitArtistClick = {
                        audioPlayerManager.pausePreview()
                        onVisitArtist(primaryArtist, track.primaryArtistBrowseId)
                    },
                    onSearchOnYouTubeClick = {
                        // Searches or opens the track directly on YouTube / YouTube Music
                        val query = Uri.encode("${track.title} $primaryArtist official")
                        runCatching {
                            val ytIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/results?search_query=$query")
                            )
                            context.startActivity(ytIntent)
                        }
                    },
                    onShareClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Listen to \"${track.title}\" by ${track.artists.joinToString(", ")} on SEITHAROL!"
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

        // Floating Top Bar with Search Pill Trigger
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
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                BackArrowSvgIcon(tint = Color.White, modifier = Modifier.size(22.dp))
            }

            // Sleek Glassmorphic Search Pill on Top Bar
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { isInDiscoverSearchOpen = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchMagnifierSvgIcon(tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Search 30s previews & download...",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

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
                onVisitArtist = { name, browseId ->
                    isInDiscoverSearchOpen = false
                    audioPlayerManager.pausePreview()
                    onVisitArtist(name, browseId)
                },
                onClose = { isInDiscoverSearchOpen = false }
            )
        }

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
    onSearchOnYouTubeClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullSongClick: () -> Unit
) {
    val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
    val avatarModel = track.artistAvatarUrl.ifBlank { track.artUrl }

    Box(modifier = Modifier.fillMaxSize()) {
        // Blurred ambient background
        AsyncImage(
            model = track.artUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(30.dp)
        )

        // Cinematic dark gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.92f)
                        )
                    )
                )
        )

        // Center Vinyl / Hero Artwork Card
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-48).dp)
        ) {
            AsyncImage(
                model = track.artUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(284.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
            )
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1DB954))
                    )
                    Text(
                        text = "30s PERSONALIZED PREVIEW",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // Bottom Metadata + Action Rail
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 14.dp, bottom = 28.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // BOTTOM-LEFT: Artist Profile Picture + Song Name + Download + Search on YouTube + Full Song
            Column(modifier = Modifier.weight(1f)) {
                // Artist Profile Pic + Song Name & Artist Name Row at Bottom-Left
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onVisitArtistClick() }
                        .padding(vertical = 4.dp)
                ) {
                    Box(modifier = Modifier.size(54.dp)) {
                        AsyncImage(
                            model = avatarModel,
                            contentDescription = primaryArtist,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artists.joinToString(", ")} • Visit Artist >",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Action Pills Row: Full Song + Download + Search on YouTube
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Play Full Song
                    Button(
                        onClick = onOpenFullSongClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play Full", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // 2. Direct Download Pill Button
                    Button(
                        onClick = onDownloadClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // 3. Search on YouTube Pill Button
                    OutlinedButton(
                        onClick = onSearchOnYouTubeClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        YouTubePlayBadgeSvgIcon(tint = Color(0xFFFF0000), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("YouTube", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Live Download Progress Banner
                if (downloadState is DownloadTaskState.Downloading && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progressPercent / 100f },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(0.9f)
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

            Spacer(modifier = Modifier.width(10.dp))

            // Right Vertical Rail: Like, Download, YouTube, Share
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DiscoverSvgActionButton(label = "Like", onClick = onLikeClick) {
                    HeartSvgIcon(
                        filled = track.isLiked,
                        tint = if (track.isLiked) Color(0xFFFF4E45) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DiscoverSvgActionButton(label = "Save", onClick = onDownloadClick) {
                    com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DiscoverSvgActionButton(label = "YouTube", onClick = onSearchOnYouTubeClick) {
                    YouTubePlayBadgeSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DiscoverSvgActionButton(label = "Share", onClick = onShareClick) {
                    ShareArrowSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun YouTubePlayBadgeSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(2f * sx, 5f * sy),
            size = androidx.compose.ui.geometry.Size(20f * sx, 14f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * sx, 4f * sy),
            style = Stroke(width = 2f * sx)
        )
        val triangle = Path().apply {
            moveTo(10f * sx, 9f * sy)
            lineTo(15.5f * sx, 12f * sy)
            lineTo(10f * sx, 15f * sy)
            close()
        }
        drawPath(triangle, color = tint, style = Fill)
    }
}

@Composable
private fun DiscoverSvgActionButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ArtistUserSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 4f * sx,
            center = androidx.compose.ui.geometry.Offset(12f * sx, 8f * sy),
            style = stroke
        )
        val body = Path().apply {
            moveTo(4f * sx, 20f * sy)
            cubicTo(4f * sx, 16f * sy, 8f * sx, 14f * sy, 12f * sx, 14f * sy)
            cubicTo(16f * sx, 14f * sy, 20f * sx, 16f * sy, 20f * sx, 20f * sy)
        }
        drawPath(body, color = tint, style = stroke)
    }
}

@Composable
fun ShareArrowSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(18f * sx, 6f * sy)
            lineTo(6f * sx, 18f * sy)
            moveTo(8f * sx, 6f * sy)
            lineTo(18f * sx, 6f * sy)
            lineTo(18f * sx, 16f * sy)
        }
        drawPath(path, color = tint, style = stroke)
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
