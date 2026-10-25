package com.seitharol.ui.discover

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import com.seitharol.data.preferences.ThumbnailQuality
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import com.seitharol.ui.history.HeartSvgIcon
import com.seitharol.ui.home.SearchMagnifierSvgIcon
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer
import kotlinx.coroutines.Dispatchers
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
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val activeFeed = remember { mutableStateListOf<NormalizedTrack>() }
    LaunchedEffect(feedTracks) {
        if (activeFeed.isEmpty() && feedTracks.isNotEmpty()) {
            activeFeed.addAll(feedTracks)
        } else {
            feedTracks.forEach { t ->
                if (activeFeed.none { it.sourceId == t.sourceId }) activeFeed.add(t)
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { activeFeed.size })
    var isInDiscoverSearchOpen by remember { mutableStateOf(false) }
    var trackForDownloadSheet by remember { mutableStateOf<NormalizedTrack?>(null) }
    var showQualitySheet by remember { mutableStateOf(false) }

    val downloadTaskState by downloadService.downloadState.collectAsState()

    // Ensure software keyboard from SearchScreen or InDiscoverSearchSurface is dismissed when on the Discover feed
    LaunchedEffect(isInDiscoverSearchOpen) {
        if (!isInDiscoverSearchOpen) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    BackHandler {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (isInDiscoverSearchOpen) {
            isInDiscoverSearchOpen = false
            audioPlayerManager.pausePreview()
        } else {
            audioPlayerManager.stopPreviewAndClear()
            onNavigateBack()
        }
    }

    val currentSettledTrackId = activeFeed.getOrNull(pagerState.settledPage)?.sourceId
    LaunchedEffect(pagerState.settledPage, currentSettledTrackId, activeFeed.size, isInDiscoverSearchOpen) {
        if (isInDiscoverSearchOpen) {
            audioPlayerManager.pausePreview()
        } else if (activeFeed.isNotEmpty()) {
            val activeTrack = activeFeed.getOrNull(pagerState.settledPage)
            if (activeTrack != null) {
                audioPlayerManager.playPreview(activeTrack)
            }
            val nextTrack = activeFeed.getOrNull(pagerState.settledPage + 1)
            if (nextTrack != null) {
                launch(Dispatchers.IO) {
                    val nextYtId = InnerTubeStreamExtractor.extractVideoId(
                        nextTrack.previewUrl?.removePrefix("ytm_clip:") ?: nextTrack.sourceId
                    )
                    if (!nextYtId.isNullOrBlank()) {
                        InnerTubeStreamExtractor.resolveExactYoutubeStreamById(nextYtId, AudioQuality.LOW)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { audioPlayerManager.stopPreviewAndClear() }
    }

    val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()
    val isPreviewBuffering by audioPlayerManager.isPreviewBuffering.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (activeFeed.isEmpty()) {
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
                val track = activeFeed.getOrNull(pageIndex) ?: return@VerticalPager
                val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }

                DiscoverShortPage(
                    track = track,
                    downloadState = downloadTaskState,
                    isPreviewPlaying = isPreviewPlaying && pagerState.settledPage == pageIndex,
                    isPreviewBuffering = isPreviewBuffering && pagerState.settledPage == pageIndex,
                    onTogglePlayPause = {
                        if (isPreviewPlaying) {
                            audioPlayerManager.pausePreview()
                        } else {
                            audioPlayerManager.resumeOrPlayPreview(track)
                        }
                    },
                    onLikeClick = { onToggleLike(track) },
                    onDownloadClick = { trackForDownloadSheet = track },
                    onCancelDownload = { downloadService.cancelActiveDownload() },
                    onVisitArtistClick = {
                        audioPlayerManager.pausePreview()
                        onVisitArtist(primaryArtist, track.primaryArtistBrowseId)
                    },
                    onShareClick = {
                        val shareMsg = com.example.ui.components.SeitharolDeepLinkHelper.buildTrackShareMessage(
                            com.example.data.model.Track(
                                id = track.sourceId,
                                title = track.title,
                                artist = track.artists.joinToString(", "),
                                album = track.album.orEmpty(),
                                durationMs = track.durationMs,
                                artUrl = track.artUrl
                            )
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareMsg)
                        }
                        runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share Track")) }
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
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
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

            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { isInDiscoverSearchOpen = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchMagnifierSvgIcon(tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Search songs & download...",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = { showQualitySheet = true },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Audio Quality Settings",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (isInDiscoverSearchOpen) {
            InDiscoverSearchSurface(
                audioPlayerManager = audioPlayerManager,
                onSearch = onSearchPreviewTracks,
                onOpenDownloadPicker = { selectedTrack ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    trackForDownloadSheet = selectedTrack
                },
                onOpenFullSong = { selectedTrack ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    audioPlayerManager.stopPreviewAndClear()
                    activeFeed.removeAll { it.sourceId == selectedTrack.sourceId }
                    activeFeed.add(0, selectedTrack)
                    scope.launch {
                        pagerState.scrollToPage(0)
                        isInDiscoverSearchOpen = false
                        audioPlayerManager.playPreview(selectedTrack)
                    }
                },
                onVisitArtist = { name, browseId ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    isInDiscoverSearchOpen = false
                    audioPlayerManager.pausePreview()
                    onVisitArtist(name, browseId)
                },
                onClose = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    isInDiscoverSearchOpen = false
                }
            )
        }

        trackForDownloadSheet?.let { targetTrack ->
            DownloadProviderFormatBottomSheet(
                track = targetTrack,
                downloadService = downloadService,
                onDismiss = { trackForDownloadSheet = null },
                onStartDownload = { provider, format, quality ->
                    trackForDownloadSheet = null
                    downloadService.startDownloadInBackground(
                        track = targetTrack,
                        provider = provider,
                        format = format,
                        preferredQuality = quality,
                        onTrackSavedAsLocalMusic = onTrackDownloadedLocally
                    )
                }
            )
        }

        if (showQualitySheet) {
            com.example.ui.components.QualitySettingsBottomSheet(
                onDismiss = { showQualitySheet = false }
            )
        }
    }
}

@Composable
private fun DiscoverShortPage(
    track: NormalizedTrack,
    downloadState: DownloadTaskState,
    isPreviewPlaying: Boolean,
    isPreviewBuffering: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onLikeClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit = {},
    onVisitArtistClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullSongClick: () -> Unit
) {
    val context = LocalContext.current
    val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
    val rawAvatar = track.artistAvatarUrl.ifBlank { track.artUrl }
    val optimizedArtUrl = remember(track.artUrl) {
        ThumbnailUrlOptimizer.optimize(track.artUrl, ThumbnailQuality.MEDIUM, ThumbnailTargetSize.HERO_LARGE, context)
    }
    val avatarModel = remember(rawAvatar) {
        ThumbnailUrlOptimizer.optimize(rawAvatar, ThumbnailQuality.MEDIUM, ThumbnailTargetSize.LIST_THUMB, context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onTogglePlayPause() }
    ) {
        AsyncImage(
            model = optimizedArtUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(30.dp)
        )

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

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-48).dp)
        ) {
            AsyncImage(
                model = optimizedArtUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(284.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
            )
        }

        if (isPreviewBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-48).dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp)
                )
            }
        } else if (!isPreviewPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-48).dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = "Play Preview",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 14.dp, bottom = 28.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Deep obsidian pill with crisp border and high-contrast white text/icon so it is NEVER
                    // white-on-white in Light Theme or under OEM dark-mode inversion!
                    Button(
                        onClick = onOpenFullSongClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF181B20),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.65f)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Play Full",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val isThisDownloading = downloadState is DownloadTaskState.Downloading && downloadState.trackId == track.sourceId
                    Button(
                        onClick = {
                            if (isThisDownloading) onCancelDownload() else onDownloadClick()
                        },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        if (isThisDownloading) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(15.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cancel",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Download",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onVisitArtistClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.45f),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Artist Profile",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Artist",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (downloadState is DownloadTaskState.Downloading && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (downloadState.progressPercent / 100f).coerceIn(0f, 1f) },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Text(
                        text = "Downloading via ${downloadState.provider.displayName} (${downloadState.progressPercent}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                } else if (downloadState is DownloadTaskState.Completed && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Saved to Downloads for offline playback",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00E676)
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
                DiscoverSvgActionButton(label = "Artist", onClick = onVisitArtistClick) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Artist Profile",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
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
    onStartDownload: (DownloadProvider, DownloadFormat, AudioQuality) -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { com.seitharol.data.preferences.AppSettingsManager.getInstance(context) }
    val persistedState by settingsManager.state.collectAsState()

    val sortedProviders = remember { downloadService.getSortedProviders() }
    var selectedProvider by remember { mutableStateOf(sortedProviders.first()) }
    var selectedFormat by remember { mutableStateOf(downloadService.getPreferredFormat()) }
    var selectedQuality by remember { mutableStateOf(persistedState.downloadQuality) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Track",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = selectedQuality.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = "${track.title} • ${track.artists.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Download Format",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DownloadFormat.entries.forEach { format ->
                    val isSelected = selectedFormat == format
                    val isVideoFormat = format == DownloadFormat.VIDEO
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                selectedFormat = format
                                settingsManager.setDownloadVideoFormat(isVideoFormat)
                            },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                        } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = format.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = format.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Download Quality",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            val isVideoSelected = selectedFormat == DownloadFormat.VIDEO
            val qualityTiers = listOf(
                Triple(AudioQuality.HIGH, "High", if (isVideoSelected) "720p HD" else "320 kbps"),
                Triple(AudioQuality.NORMAL, "Normal", if (isVideoSelected) "480p SD" else "160 kbps"),
                Triple(AudioQuality.LOW, "Low", if (isVideoSelected) "360p Saver" else "96 kbps")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualityTiers.forEach { (tier, title, bitrate) ->
                    val isSelected = selectedQuality == tier
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedQuality = tier
                                settingsManager.setDownloadQuality(tier)
                            },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = bitrate,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Download Audio Provider",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
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
                        .padding(vertical = 8.dp, horizontal = 6.dp),
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
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = provider.displayName,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (index == 0 && usageCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Recommended",
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

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { onStartDownload(selectedProvider, selectedFormat, selectedQuality) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedFormat == DownloadFormat.VIDEO) {
                        "Download Video MP4 (${selectedQuality.name.lowercase().replaceFirstChar { it.uppercase() }})"
                    } else {
                        "Download Audio Only (${selectedQuality.maxBitrateKbps} kbps)"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
