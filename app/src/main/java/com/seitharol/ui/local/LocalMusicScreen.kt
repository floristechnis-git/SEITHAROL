// seitharol/ui/local/LocalMusicScreen.kt
package com.seitharol.ui.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadState
import com.example.data.model.DownloadTier
import com.example.ui.components.SeitharolPullToRefreshBox
import com.seitharol.data.download.DownloadPreferencesAndService
import com.seitharol.data.download.DownloadTaskState
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.domain.model.NormalizedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun LocalMusicScreen(
    appDownloadedTracks: List<NormalizedTrack> = emptyList(),
    activeDownloads: List<DownloadItem> = emptyList(),
    availablePlaylists: List<Pair<String, String>> = emptyList(),
    onPlayLocalTrack: (CandidateStream) -> Unit,
    onPlayLocalQueue: ((List<CandidateStream>, Int) -> Unit)? = null,
    onAddLocalTrackToPlaylist: (playlistId: String, track: CandidateStream) -> Unit = { _, _ -> },
    onCancelDownload: (String) -> Unit = {},
    onRefreshDownloads: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    val downloadService = remember { DownloadPreferencesAndService.getInstance(context) }
    val discoverTaskState by downloadService.downloadState.collectAsState()

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun checkAllMediaPermissionsGranted(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun checkAnyMediaPermissionGranted(ctx: Context): Boolean {
        return requiredPermissions.any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(checkAllMediaPermissionsGranted(context))
    }
    var scannedDeviceTracks by remember { mutableStateOf<List<CandidateStream>>(emptyList()) }
    var isScanningDevice by remember { mutableStateOf(true) }
    var scanTriggerCount by remember { mutableIntStateOf(0) }
    var trackPendingPlaylistAdd by remember { mutableStateOf<CandidateStream?>(null) }
    var selectedFilter by remember { mutableStateOf("All") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        hasStoragePermission = grantMap.values.any { it } || checkAnyMediaPermissionGranted(context)
        scanTriggerCount++
    }

    LaunchedEffect(Unit) {
        if (!checkAllMediaPermissionsGranted(context)) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Re-check permissions and rescan automatically when user returns from system Settings or file manager
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = checkAnyMediaPermissionGranted(context)
                if (nowGranted != hasStoragePermission) {
                    hasStoragePermission = nowGranted
                }
                scanTriggerCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun triggerFullRefresh() {
        coroutineScope.launch {
            isScanningDevice = true
            onRefreshDownloads()
            triggerMediaScannerIndex(context)
            scannedDeviceTracks = scanDeviceMediaFiles(context)
            isScanningDevice = false
        }
    }

    // Always scan MediaStore & device folders on entry and whenever permission or rescan is triggered
    LaunchedEffect(hasStoragePermission, scanTriggerCount, appDownloadedTracks.size) {
        isScanningDevice = true
        scannedDeviceTracks = scanDeviceMediaFiles(context)
        isScanningDevice = false
    }

    val inProgressDownloads = remember(activeDownloads, discoverTaskState, selectedFilter) {
        val base = activeDownloads.filter {
            it.state == DownloadState.DOWNLOADING ||
                it.state == DownloadState.FINALIZING ||
                it.state == DownloadState.QUEUED
        }.toMutableList()

        val disc = discoverTaskState as? DownloadTaskState.Downloading
        if (disc != null && base.none {
                it.trackId == disc.trackId ||
                    downloadService.canonicalTrackId(it.trackId) == downloadService.canonicalTrackId(disc.trackId)
            }
        ) {
            base.add(
                DownloadItem(
                    trackId = disc.trackId,
                    title = disc.trackTitle,
                    artist = disc.artistName,
                    artUrl = disc.thumbnailUrl,
                    tier = DownloadTier.HIGH,
                    progress = (disc.progressPercent / 100f).coerceIn(0.02f, 0.99f),
                    downloadedBytes = disc.downloadedBytes,
                    totalBytes = disc.totalBytes,
                    state = DownloadState.DOWNLOADING
                )
            )
        }

        when (selectedFilter) {
            "Audio" -> base.filterNot {
                it.tier == DownloadTier.VIDEO_MP4 || it.localFilePath?.endsWith(".mp4", true) == true
            }
            "MP4 Videos" -> base.filter {
                it.tier == DownloadTier.VIDEO_MP4 || it.localFilePath?.endsWith(".mp4", true) == true
            }
            else -> base
        }
    }

    val defaultAppDownloads = remember(appDownloadedTracks) {
        appDownloadedTracks.map { t ->
            val rawAddress = t.localPath?.takeIf { it.isNotBlank() } ?: t.sourceId
            val pathOrId = rawAddress.lowercase()
            val isMp4Video = pathOrId.endsWith(".mp4") ||
                pathOrId.endsWith(".mkv") ||
                pathOrId.endsWith(".webm") ||
                pathOrId.contains("/video/") ||
                pathOrId.contains("_video_mp4") ||
                t.album?.contains("Video", ignoreCase = true) == true
            CandidateStream(
                videoId = rawAddress,
                title = t.title,
                artist = t.artists.joinToString(", ").ifBlank { if (isMp4Video) "Local Video" else "Unknown Artist" },
                channelName = if (isMp4Video) "Download Folder • MP4 Video" else "Download Folder • Seitharol",
                albumName = if (isMp4Video) "Download • MP4 Video" else (t.album?.ifBlank { "Download Folder" } ?: "Download Folder"),
                durationSeconds = (t.durationMs / 1000L).toInt().coerceAtLeast(1),
                videoType = if (isMp4Video) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                thumbnailUrl = t.artUrl,
                isVerifiedArtist = false,
                viewCountText = formatDisplayAddress(rawAddress)
            )
        }
    }

    val mergedLocalTracks = remember(defaultAppDownloads, scannedDeviceTracks) {
        val combined = ArrayList<CandidateStream>(defaultAppDownloads.size + scannedDeviceTracks.size)
        val seenPaths = HashSet<String>()
        val seenExactSignatures = HashSet<String>()

        fun canonicalPathKey(raw: String): String {
            return raw.removePrefix("file://").trim().lowercase(Locale.US)
        }

        fun exactSignature(item: CandidateStream): String {
            val isVid = item.videoType == "MUSIC_VIDEO_TYPE_OMV"
            val cleanTitle = item.title.trim().lowercase(Locale.US)
            val cleanArtist = item.artist.trim().lowercase(Locale.US)
            return "$cleanTitle|$cleanArtist|$isVid"
        }

        for (item in defaultAppDownloads) {
            val pKey = canonicalPathKey(item.videoId)
            if (pKey.isNotBlank()) seenPaths.add(pKey)
            seenExactSignatures.add(exactSignature(item))
            combined.add(item)
        }

        for (dev in scannedDeviceTracks) {
            val pKey = canonicalPathKey(dev.videoId)
            val addrKey = canonicalPathKey(dev.viewCountText)
            if (pKey.isNotBlank() && seenPaths.contains(pKey)) continue
            if (addrKey.isNotBlank() && seenPaths.contains(addrKey)) continue
            val sig = exactSignature(dev)
            // Only skip if both title AND artist AND media type match an already listed app download
            if (seenExactSignatures.contains(sig) && defaultAppDownloads.any {
                    it.title.equals(dev.title, ignoreCase = true) &&
                        (it.videoType == dev.videoType)
                }
            ) {
                continue
            }
            if (pKey.isNotBlank()) seenPaths.add(pKey)
            if (addrKey.isNotBlank()) seenPaths.add(addrKey)
            combined.add(dev)
        }
        combined
    }

    val displayedTracks = remember(mergedLocalTracks, selectedFilter) {
        when (selectedFilter) {
            "Audio" -> mergedLocalTracks.filter { it.videoType != "MUSIC_VIDEO_TYPE_OMV" }
            "MP4 Videos" -> mergedLocalTracks.filter { it.videoType == "MUSIC_VIDEO_TYPE_OMV" }
            "Download Folder" -> mergedLocalTracks.filter {
                it.albumName?.contains("Download", ignoreCase = true) == true ||
                    it.channelName.contains("Download", ignoreCase = true) ||
                    it.viewCountText.contains("Download", ignoreCase = true) ||
                    it.viewCountText.contains("Seitharol", ignoreCase = true) ||
                    it.videoId.contains("downloads", ignoreCase = true)
            }
            else -> mergedLocalTracks
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Local Music & Videos",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val shownAudioCount = displayedTracks.count { it.videoType != "MUSIC_VIDEO_TYPE_OMV" }
                    val shownVideoCount = displayedTracks.count { it.videoType == "MUSIC_VIDEO_TYPE_OMV" }
                    Text(
                        text = buildString {
                            if (shownAudioCount > 0 && shownVideoCount > 0) {
                                append("$shownAudioCount audio • $shownVideoCount MP4 video${if (shownVideoCount > 1) "s" else ""}")
                            } else if (shownVideoCount > 0) {
                                append("$shownVideoCount MP4 video${if (shownVideoCount > 1) "s" else ""}")
                            } else {
                                append("$shownAudioCount audio")
                            }
                            if (inProgressDownloads.isNotEmpty()) {
                                append(" • ${inProgressDownloads.size} downloading")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inProgressDownloads.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalIconButton(
                onClick = { triggerFullRefresh() },
                modifier = Modifier.size(38.dp),
                shape = CircleShape
            ) {
                if (isScanningDevice) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Local Music",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            items(listOf("All", "Audio", "MP4 Videos", "Download Folder")) { tab ->
                val isSelected = selectedFilter == tab
                val labelText = if (tab == "Download Folder" && inProgressDownloads.isNotEmpty()) {
                    "Download Folder (${inProgressDownloads.size})"
                } else tab
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = tab },
                    label = {
                        Text(
                            text = labelText,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }

        if (!checkAllMediaPermissionsGranted(context)) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "Scan All Device Folders",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Grant Audio & Video media permissions to discover all songs and MP4 videos across your phone storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { permissionLauncher.launch(requiredPermissions) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Allow", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        val orderedDisplayedTracks = remember(displayedTracks) {
            displayedTracks.distinctBy { it.videoId }
        }

        SeitharolPullToRefreshBox(
            isRefreshing = isScanningDevice,
            onRefresh = { triggerFullRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 215.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (inProgressDownloads.isNotEmpty()) {
                    item(key = "local_dl_progress_header") {
                        Text(
                            text = "Downloading to Device (${inProgressDownloads.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(inProgressDownloads, key = { "local_active_dl_${it.trackId}" }) { dlItem ->
                        val safeProg = if (dlItem.progress.isNaN() || dlItem.progress.isInfinite()) 0.02f else dlItem.progress.coerceIn(0.02f, 0.99f)
                        val pct = (safeProg * 100).toInt().coerceIn(1, 99)
                        val isVid = dlItem.tier == DownloadTier.VIDEO_MP4 ||
                            dlItem.localFilePath?.endsWith(".mp4", ignoreCase = true) == true
                        val dlMb = dlItem.downloadedBytes.toFloat() / (1024f * 1024f)
                        val totMb = dlItem.totalBytes.toFloat() / (1024f * 1024f)
                        val mbLabel = if (dlItem.totalBytes > 0L) {
                            String.format(Locale.US, " • %.1f / %.1f MB", dlMb, totMb)
                        } else ""

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (dlItem.artUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = dlItem.artUrl,
                                                contentDescription = dlItem.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Image(
                                                painter = appLogoPainter,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dlItem.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${dlItem.artist} • ${if (isVid) "Video (MP4)" else "Audio"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${if (dlItem.state == DownloadState.QUEUED) "Preparing" else "Downloading"} • $pct%$mbLabel",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onCancelDownload(dlItem.trackId) },
                                        shape = RoundedCornerShape(18.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Cancel", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { safeProg },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                    }
                }

                if (isScanningDevice && orderedDisplayedTracks.isEmpty() && inProgressDownloads.isEmpty()) {
                    item(key = "local_scanning_placeholder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Scanning device storage for songs & videos...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (orderedDisplayedTracks.isEmpty() && inProgressDownloads.isEmpty()) {
                    item(key = "local_empty_placeholder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "No songs found in this category",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { triggerFullRefresh() }) {
                                    Text("Rescan Device Storage")
                                }
                            }
                        }
                    }
                }

                itemsIndexed(orderedDisplayedTracks, key = { idx, track -> "local_${track.videoId}_$idx" }) { index, track ->
                    val isVideoItem = track.videoType == "MUSIC_VIDEO_TYPE_OMV"
                    val displayAddress = track.viewCountText.ifBlank { formatDisplayAddress(track.videoId) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (onPlayLocalQueue != null) {
                                    onPlayLocalQueue(orderedDisplayedTracks, index)
                                } else {
                                    onPlayLocalTrack(track)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (track.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = track.thumbnailUrl,
                                        contentDescription = track.title,
                                        placeholder = appLogoPainter,
                                        error = appLogoPainter,
                                        fallback = appLogoPainter,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Image(
                                        painter = appLogoPainter,
                                        contentDescription = "Seitharol Logo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (isVideoItem) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(2.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xCC000000))
                                            .padding(horizontal = 3.dp, vertical = 1.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = "MP4 Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isVideoItem) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "MP4 VIDEO",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    val folderSuffix = track.albumName?.takeIf {
                                        it.isNotBlank() && !it.equals("MP4 Video", true)
                                    }?.let { " • $it" }.orEmpty()
                                    Text(
                                        text = "${track.artist}$folderSuffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (displayAddress.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = displayAddress,
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            var showLocalMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showLocalMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = showLocalMenu,
                                    onDismissRequest = { showLocalMenu = false },
                                    modifier = Modifier
                                        .widthIn(min = 235.dp, max = 295.dp)
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = track.title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = track.artist,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (displayAddress.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "Path: $displayAddress",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                fontSize = 10.5.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    DropdownMenuItem(
                                        text = { Text("Play Now", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showLocalMenu = false
                                            if (onPlayLocalQueue != null) {
                                                onPlayLocalQueue(orderedDisplayedTracks, index)
                                            } else {
                                                onPlayLocalTrack(track)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            showLocalMenu = false
                                            trackPendingPlaylistAdd = track
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    trackPendingPlaylistAdd?.let { selectedTrack ->
        AlertDialog(
            onDismissRequest = { trackPendingPlaylistAdd = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Add to Playlist", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    availablePlaylists.forEach { (playlistId, title) ->
                        TextButton(
                            onClick = {
                                onAddLocalTrackToPlaylist(playlistId, selectedTrack)
                                trackPendingPlaylistAdd = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trackPendingPlaylistAdd = null }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

private fun formatDisplayAddress(rawPath: String): String {
    if (rawPath.isBlank()) return ""
    val clean = rawPath.removePrefix("file://").trim()
    return when {
        clean.contains("/storage/emulated/0/") -> "/Phone/" + clean.substringAfter("/storage/emulated/0/")
        clean.contains("/files/downloads/") -> "/App Storage/downloads/" + clean.substringAfter("/files/downloads/")
        clean.startsWith("content://media/external/audio") -> "Device MediaStore (Audio)"
        clean.startsWith("content://media/external/video") -> "Device MediaStore (Video)"
        else -> clean
    }
}

private suspend fun triggerMediaScannerIndex(context: Context) = withContext(Dispatchers.IO) {
    runCatching {
        val dirsToIndex = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Seitharol"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Seitharol")
        ).filter { it.exists() }.map { it.absolutePath }.distinct().toTypedArray()

        if (dirsToIndex.isNotEmpty()) {
            MediaScannerConnection.scanFile(context.applicationContext, dirsToIndex, null, null)
        }
    }
}

private suspend fun scanDeviceMediaFiles(context: Context): List<CandidateStream> =
    withContext(Dispatchers.IO) {
        val list = mutableListOf<CandidateStream>()
        val seenUrisOrPaths = HashSet<String>()

        fun addCandidateIfUnique(candidate: CandidateStream, filePathHint: String = "") {
            val uriKey = candidate.videoId.removePrefix("file://").lowercase(Locale.US).trim()
            val pathKey = filePathHint.removePrefix("file://").lowercase(Locale.US).trim()
            if (uriKey.isNotBlank() && seenUrisOrPaths.contains(uriKey)) return
            if (pathKey.isNotBlank() && seenUrisOrPaths.contains(pathKey)) return
            if (uriKey.isNotBlank()) seenUrisOrPaths.add(uriKey)
            if (pathKey.isNotBlank()) seenUrisOrPaths.add(pathKey)
            list.add(candidate)
        }

        // 1. Query MediaStore.Audio.Media with safe primary + fallback projections
        fun queryMediaStoreAudio(includeDataAndRelPath: Boolean) {
            val projection = if (includeDataAndRelPath) {
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.ALBUM_ID)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    add(MediaStore.Audio.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE
                )
            }

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (durMs in 1L..1999L) continue

                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val relPath = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty().lowercase(Locale.US) else ""
                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                    val title = rawTitle
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { filePath.substringAfterLast('/').substringBeforeLast('.') }
                        .ifBlank { "Track $id" }

                    val lowerName = displayName.ifBlank { filePath }.lowercase(Locale.US)
                    val isMp4OrVideo = mimeType.startsWith("video/") ||
                        lowerName.endsWith(".mp4") ||
                        lowerName.endsWith(".mkv") ||
                        lowerName.endsWith(".webm") ||
                        lowerName.endsWith(".mov") ||
                        lowerName.endsWith(".m4v")

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else ""
                    val artist = rawArtist
                        .replace("<unknown>", "", ignoreCase = true)
                        .trim()
                        .ifBlank { "Unknown Artist" }
                    val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                    val isDownloadDir = rawAlbum.equals("Download", true) ||
                        rawAlbum.equals("Downloads", true) ||
                        filePath.contains("/Download", true) ||
                        relPath.contains("Download", true) ||
                        filePath.contains("/Seitharol", true) ||
                        relPath.contains("Seitharol", true)
                    val cleanAlbum = when {
                        isMp4OrVideo && isDownloadDir -> "Download • MP4 Video"
                        isMp4OrVideo -> "MP4 Video"
                        isDownloadDir -> "Download Folder"
                        else -> rawAlbum.ifBlank { relPath.trimEnd('/') }
                    }
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
                    val albumArtUri = if (albumId > 0L) {
                        "content://media/external/audio/albumart/$albumId"
                    } else ""
                    val durationSec = if (durMs > 0L) (durMs / 1000L).toInt().coerceAtLeast(1) else 210
                    val resolvedAddress = when {
                        filePath.isNotBlank() -> formatDisplayAddress(filePath)
                        relPath.isNotBlank() -> "/Phone/${relPath.trimStart('/')}$displayName"
                        displayName.isNotBlank() -> displayName
                        else -> contentUri
                    }

                    addCandidateIfUnique(
                        CandidateStream(
                            videoId = contentUri,
                            title = title,
                            artist = artist,
                            channelName = if (isDownloadDir) "Download Folder" else if (isMp4OrVideo) "Device Video Storage" else "Device Storage",
                            albumName = cleanAlbum,
                            durationSeconds = durationSec,
                            videoType = if (isMp4OrVideo) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                            thumbnailUrl = albumArtUri,
                            isVerifiedArtist = false,
                            viewCountText = resolvedAddress
                        ),
                        filePathHint = filePath
                    )
                }
            }
        }

        if (runCatching { queryMediaStoreAudio(includeDataAndRelPath = true) }.isFailure) {
            runCatching { queryMediaStoreAudio(includeDataAndRelPath = false) }
        }

        // 2. Query MediaStore.Video.Media with safe primary + fallback projections
        fun queryMediaStoreVideo(includeDataAndArtist: Boolean) {
            val videoProjection = if (includeDataAndArtist) {
                buildList {
                    add(MediaStore.Video.Media._ID)
                    add(MediaStore.Video.Media.TITLE)
                    add(MediaStore.Video.Media.ARTIST)
                    add(MediaStore.Video.Media.DURATION)
                    add(MediaStore.Video.Media.DISPLAY_NAME)
                    add(MediaStore.Video.Media.DATA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Video.Media.RELATIVE_PATH)
                    }
                }.toTypedArray()
            } else {
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DISPLAY_NAME
                )
            }

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Video.Media.ARTIST)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (durMs in 1L..1999L) continue

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val relPath = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
                    val rawTitle = (if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else "")
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { filePath.substringAfterLast('/').substringBeforeLast('.') }
                        .ifBlank { "Local MP4 Video" }
                    val rawArtist = (if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else "")
                        .replace("<unknown>", "", ignoreCase = true)
                        .trim()
                        .ifBlank { "Unknown Artist" }
                    val durationSec = if (durMs > 0L) (durMs / 1000L).toInt().coerceAtLeast(1) else 210
                    val isDownloadFolder = filePath.contains("/Download", ignoreCase = true) ||
                        relPath.contains("Download", ignoreCase = true) ||
                        filePath.contains("/Seitharol", ignoreCase = true) ||
                        relPath.contains("Seitharol", ignoreCase = true)
                    val resolvedAddress = when {
                        filePath.isNotBlank() -> formatDisplayAddress(filePath)
                        relPath.isNotBlank() -> "/Phone/${relPath.trimStart('/')}$displayName"
                        displayName.isNotBlank() -> displayName
                        else -> contentUri
                    }

                    addCandidateIfUnique(
                        CandidateStream(
                            videoId = contentUri,
                            title = rawTitle,
                            artist = rawArtist,
                            channelName = if (isDownloadFolder) "Download Folder" else "Device Video Storage",
                            albumName = if (isDownloadFolder) "Download • MP4 Video" else "MP4 Video",
                            durationSeconds = durationSec,
                            videoType = "MUSIC_VIDEO_TYPE_OMV",
                            thumbnailUrl = contentUri,
                            isVerifiedArtist = false,
                            viewCountText = resolvedAddress
                        ),
                        filePathHint = filePath
                    )
                }
            }
        }

        if (runCatching { queryMediaStoreVideo(includeDataAndArtist = true) }.isFailure) {
            runCatching { queryMediaStoreVideo(includeDataAndArtist = false) }
        }

        // 3. Direct File System Scan across internal downloads, external app dirs, and public storage folders (up to depth 4)
        runCatching {
            val rootDirs = buildList {
                add(File(context.filesDir, "downloads"))
                context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { add(it) }
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS))
                }
                runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(it) }
            }.distinctBy { it.absolutePath }

            val audioExts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "wma", "m4b")
            val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp")

            fun scanDirectoryBounded(dir: File, depth: Int) {
                if (depth > 4 || !dir.exists() || !dir.isDirectory) return
                val dirNameLower = dir.name.lowercase(Locale.US)
                if (dirNameLower.startsWith(".") || dirNameLower == "android") return
                val isDownloadDir = dir.absolutePath.contains("Download", ignoreCase = true) ||
                    dir.absolutePath.contains("Seitharol", ignoreCase = true)

                val children = dir.listFiles() ?: return
                for (file in children) {
                    if (file.isDirectory) {
                        if (depth < 4) scanDirectoryBounded(file, depth + 1)
                        continue
                    }
                    if (!file.isFile || file.length() <= 8_192L || file.name.startsWith(".") || file.name.endsWith(".tmp")) continue
                    val ext = file.extension.lowercase(Locale.US)
                    val isVid = ext in videoExts
                    val isAud = ext in audioExts
                    if (!isVid && !isAud) continue

                    val rawBase = file.nameWithoutExtension.trim().ifBlank { file.name }
                    val parsedArtist = if (rawBase.contains(" - ")) rawBase.substringBefore(" - ").trim() else "Unknown Artist"
                    val cleanTitle = if (rawBase.contains(" - ")) rawBase.substringAfter(" - ").trim().ifBlank { rawBase } else rawBase
                    val fileUri = android.net.Uri.fromFile(file).toString()

                    addCandidateIfUnique(
                        CandidateStream(
                            videoId = fileUri,
                            title = cleanTitle,
                            artist = parsedArtist,
                            channelName = if (isDownloadDir) "Download Folder" else dir.name,
                            albumName = when {
                                isVid && isDownloadDir -> "Download • MP4 Video"
                                isVid -> "MP4 Video"
                                isDownloadDir -> "Download Folder"
                                else -> dir.name.ifBlank { "Local Audio" }
                            },
                            durationSeconds = 210,
                            videoType = if (isVid) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                            thumbnailUrl = "",
                            isVerifiedArtist = false,
                            viewCountText = formatDisplayAddress(file.absolutePath)
                        ),
                        filePathHint = file.absolutePath
                    )
                }
            }

            for (rootDir in rootDirs) {
                scanDirectoryBounded(rootDir, 0)
            }
        }

        list
    }
