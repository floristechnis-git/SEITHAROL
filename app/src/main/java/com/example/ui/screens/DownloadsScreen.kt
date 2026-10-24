package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadState
import com.example.ui.CascadeViewModel
import com.example.ui.components.SeitharolPullToRefreshBox
import com.example.ui.theme.*
import com.seitharol.data.download.DownloadTaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: CascadeViewModel,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.activeDownloads.collectAsState()
    val totalBytes by viewModel.totalDownloadedBytes.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val isRefreshingLibrary by viewModel.isRefreshingLibrary.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadService = remember { com.seitharol.data.download.DownloadPreferencesAndService.getInstance(context) }
    val standaloneDownloads by downloadService.completedDownloads.collectAsState()
    val discoverTaskState by downloadService.downloadState.collectAsState()

    val totalMb = totalBytes / (1024 * 1024)
    var showQualitySheet by remember { mutableStateOf(false) }

    val standaloneIds = remember(standaloneDownloads) {
        standaloneDownloads.map { downloadService.canonicalTrackId(it.sourceId) }.toSet()
    }
    val deduplicatedQueueDownloads = remember(downloads, standaloneIds, standaloneDownloads) {
        downloads.filterNot { item ->
            val canon = downloadService.canonicalTrackId(item.trackId)
            item.state == DownloadState.COMPLETED && (canon in standaloneIds || standaloneDownloads.any { s ->
                s.title.equals(item.title, ignoreCase = true)
            })
        }
    }

    val activeDiscoverDownloading = discoverTaskState as? DownloadTaskState.Downloading

    SeitharolPullToRefreshBox(
        isRefreshing = isRefreshingLibrary,
        onRefresh = { viewModel.refreshLibrary() },
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 140.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Downloads & Offline",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Persistent on-device Opus audio storage",
                            fontFamily = OutfitFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { showQualitySheet = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Audio Quality Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp)),
                    color = SeitharolWhite
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Airplane Mode Offline",
                                    color = SeitharolDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (isOfflineMode)
                                        "Playing from local storage only."
                                    else
                                        "Connected. Streaming and downloading enabled.",
                                    color = SeitharolTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Switch(
                                checked = isOfflineMode,
                                onCheckedChange = { viewModel.setOfflineMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = Color.Transparent,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.testTag("downloads_offline_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = SeitharolDivider)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Total Stored", color = SeitharolTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                                Text("$totalMb MB", color = SeitharolDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                            }
                            Column {
                                Text("Downloaded Tracks", color = SeitharolTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                                Text("${downloads.count { it.state == DownloadState.COMPLETED }}", color = SeitharolDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                            }
                            Column {
                                Text("Format", color = SeitharolTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_mp3),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                    )
                                    Text("Opus / MP4", color = SeitharolDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                                }
                            }
                        }
                    }
                }
            }

            if (activeDiscoverDownloading != null) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Currently Downloading",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ActiveProcessingDownloadCard(
                        title = activeDiscoverDownloading.trackTitle,
                        artist = activeDiscoverDownloading.artistName,
                        artUrl = activeDiscoverDownloading.thumbnailUrl,
                        progressFraction = (activeDiscoverDownloading.progressPercent / 100f).coerceIn(0f, 1f),
                        subtitle = "Downloading via ${activeDiscoverDownloading.provider.displayName} • ${activeDiscoverDownloading.progressPercent}%",
                        onCancel = { downloadService.cancelActiveDownload() }
                    )
                }
            }

            if (standaloneDownloads.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Discover & Public Downloads (${standaloneDownloads.size})",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Standalone files",
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(standaloneDownloads) { track ->
                    StandaloneDownloadItemRow(
                        track = track,
                        onClick = {
                            viewModel.playTrack(
                                com.example.data.model.Track(
                                    id = track.sourceId,
                                    title = track.title,
                                    artist = track.artists.joinToString(", "),
                                    album = track.album ?: "Single",
                                    durationMs = track.durationMs,
                                    artUrl = track.artUrl,
                                    isDownloaded = true,
                                    localPath = track.localPath
                                )
                            )
                        },
                        onDelete = { viewModel.deleteDownload(track.sourceId) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Track Download Queue (${deduplicatedQueueDownloads.size})",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (deduplicatedQueueDownloads.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color(0xFFAAAAAA),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No downloaded tracks yet",
                                color = SeitharolTextSecondary,
                                fontFamily = OutfitFontFamily,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(deduplicatedQueueDownloads) { item ->
                    DownloadItemRow(
                        item = item,
                        onClick = {
                            if (item.state == DownloadState.COMPLETED) {
                                viewModel.playDownloadedTrack(item.trackId)
                            }
                        },
                        onCancel = { viewModel.cancelDownload(item.trackId) },
                        onDelete = { viewModel.deleteDownload(item.trackId) },
                        onRetry = { viewModel.retryDownload(item.trackId) }
                    )
                }
            }
        }

        if (showQualitySheet) {
            com.example.ui.components.QualitySettingsBottomSheet(
                onDismiss = { showQualitySheet = false }
            )
        }
    }
}

@Composable
fun DownloadItemRow(
    item: DownloadItem,
    onClick: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    val safeProgress = if (item.progress.isNaN() || item.progress.isInfinite()) 0f else item.progress.coerceIn(0f, 1f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = item.state == DownloadState.COMPLETED, onClick = onClick),
        color = SeitharolWhite
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = SeitharolDark,
                        fontSize = 14.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isMp4Video = item.tier == com.example.data.model.DownloadTier.VIDEO_MP4 ||
                            item.localFilePath?.endsWith(".mp4", ignoreCase = true) == true
                        if (isMp4Video) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "MP4 Video",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp).padding(end = 4.dp)
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_mp3),
                                contentDescription = item.tier.label,
                                modifier = Modifier.size(12.dp).padding(end = 4.dp)
                            )
                        }
                        Text(
                            text = "${item.artist} • ${if (isMp4Video) "MP4 Video" else item.tier.label}",
                            color = SeitharolTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily
                        )
                    }
                }

                when (item.state) {
                    DownloadState.COMPLETED -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x2000E676),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Completed",
                                    tint = Color(0xFF00C853),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Saved", color = Color(0xFF00C853), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                            }
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete download",
                                tint = SeitharolTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DownloadState.ERROR -> {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry download",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete download",
                                tint = SeitharolTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DownloadState.DOWNLOADING, DownloadState.FINALIZING, DownloadState.QUEUED -> {
                        Text(
                            text = "${(safeProgress * 100).toInt()}%",
                            color = SeitharolDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OutfitFontFamily,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, SeitharolTextSecondary.copy(alpha = 0.5f)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = SeitharolDark,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Cancel",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SeitharolDark,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                    DownloadState.IDLE -> {}
                }
            }

            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.FINALIZING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { safeProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = SeitharolDark,
                    trackColor = Color(0xFFE0E0E0)
                )
            } else if (item.state == DownloadState.ERROR) {
                Spacer(modifier = Modifier.height(4.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                val errText = if (!com.example.util.ErrorFormatter.isOnline(context)) {
                    "No internet connection • Tap retry when online"
                } else {
                    "Download error • Tap retry or remove"
                }
                Text(
                    text = errText,
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily
                )
            }
        }
    }
}

@Composable
fun StandaloneDownloadItemRow(
    track: com.seitharol.domain.model.NormalizedTrack,
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.40f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = track.artists.joinToString(", "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "STANDALONE AUDIO",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Public Storage • Offline",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete download",
                    tint = SeitharolTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
