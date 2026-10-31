// seitharol/ui/local/LocalMusicScreen.kt
package com.seitharol.ui.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.domain.model.NormalizedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalMusicScreen(
    appDownloadedTracks: List<NormalizedTrack> = emptyList(),
    availablePlaylists: List<Pair<String, String>> = emptyList(),
    onPlayLocalTrack: (CandidateStream) -> Unit,
    onPlayLocalQueue: ((List<CandidateStream>, Int) -> Unit)? = null,
    onAddLocalTrackToPlaylist: (playlistId: String, track: CandidateStream) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

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

    fun checkAnyMediaPermissionGranted(ctx: Context): Boolean {
        val permsToCheck = buildList {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }
        return permsToCheck.any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(checkAnyMediaPermissionGranted(context))
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
        if (!hasStoragePermission) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Always scan MediaStore & device folders on entry and whenever permission or rescan is triggered
    LaunchedEffect(hasStoragePermission, scanTriggerCount) {
        isScanningDevice = true
        scannedDeviceTracks = scanDeviceMediaFiles(context)
        isScanningDevice = false
    }

    val defaultAppDownloads = remember(appDownloadedTracks) {
        appDownloadedTracks.map { t ->
            val pathOrId = (t.localPath ?: t.sourceId).lowercase()
            val isMp4Video = pathOrId.endsWith(".mp4") ||
                pathOrId.endsWith(".mkv") ||
                pathOrId.endsWith(".webm") ||
                pathOrId.contains("/video/") ||
                pathOrId.contains("_video_mp4") ||
                t.album?.contains("Video", ignoreCase = true) == true
            CandidateStream(
                videoId = t.localPath ?: t.sourceId,
                title = t.title,
                artist = t.artists.joinToString(", ").ifBlank { if (isMp4Video) "Local Video" else "Unknown Artist" },
                channelName = if (isMp4Video) "Downloaded Video" else "Downloaded in Seitharol",
                albumName = if (isMp4Video) "MP4 Video" else t.album,
                durationSeconds = (t.durationMs / 1000L).toInt(),
                videoType = if (isMp4Video) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                thumbnailUrl = t.artUrl,
                isVerifiedArtist = false
            )
        }
    }

    val mergedLocalTracks = remember(defaultAppDownloads, scannedDeviceTracks) {
        fun normalizeSongName(raw: String): String {
            return raw.lowercase()
                .substringBeforeLast(".mp4")
                .substringBeforeLast(".m4a")
                .substringBeforeLast(".mp3")
                .substringBeforeLast(".opus")
                .substringBeforeLast(".flac")
                .substringBeforeLast(".webm")
                .substringBeforeLast(".mkv")
                .map { ch -> if (ch.isLetterOrDigit()) ch else ' ' }
                .joinToString("")
                .split(" ").filter { it.isNotBlank() }.joinToString(" ")
                .trim()
        }

        val appExactTitles = HashSet<String>()
        val appNormalizedTitles = HashSet<String>()
        val appLocalPaths = HashSet<String>()

        defaultAppDownloads.forEach { appItem ->
            val rawLower = appItem.title.lowercase().trim()
            val normTitle = normalizeSongName(appItem.title)
            val normWithArtist = normalizeSongName("${appItem.artist} - ${appItem.title}")
            if (rawLower.isNotBlank()) appExactTitles.add(rawLower)
            if (normTitle.isNotBlank()) appNormalizedTitles.add(normTitle)
            if (normWithArtist.isNotBlank()) appNormalizedTitles.add(normWithArtist)
            if (appItem.videoId.isNotBlank()) appLocalPaths.add(appItem.videoId.lowercase().trim())
        }

        val filteredDeviceTracks = scannedDeviceTracks.filterNot { dev ->
            val devRawTitle = dev.title.lowercase().trim()
            val devNormTitle = normalizeSongName(dev.title)
            val devAfterDash = if (dev.title.contains(" - ")) dev.title.substringAfter(" - ").trim() else ""
            val devNormAfterDash = if (devAfterDash.isNotBlank()) normalizeSongName(devAfterDash) else ""
            val devWithArtistNorm = normalizeSongName("${dev.artist} - ${dev.title}")

            appLocalPaths.contains(dev.videoId.lowercase().trim()) ||
                appExactTitles.contains(devRawTitle) ||
                (devAfterDash.isNotBlank() && appExactTitles.contains(devAfterDash.lowercase())) ||
                appNormalizedTitles.contains(devNormTitle) ||
                (devNormAfterDash.isNotBlank() && appNormalizedTitles.contains(devNormAfterDash)) ||
                appNormalizedTitles.contains(devWithArtistNorm) ||
                defaultAppDownloads.any { appItem ->
                    val appT = appItem.title.lowercase().trim()
                    appT.isNotBlank() && (
                        devRawTitle == appT ||
                            devRawTitle == "${appItem.artist.lowercase().trim()} - $appT" ||
                            devRawTitle.endsWith(" - $appT")
                        )
                }
        }

        val combined = ArrayList<CandidateStream>(defaultAppDownloads.size + filteredDeviceTracks.size)
        val seenNames = HashSet<String>()
        for (item in defaultAppDownloads) {
            val key = normalizeSongName(item.title).ifBlank { item.videoId }
            if (seenNames.add(key)) {
                combined.add(item)
            }
        }
        for (item in filteredDeviceTracks) {
            val key = normalizeSongName(item.title).ifBlank { item.videoId }
            if (seenNames.add(key)) {
                combined.add(item)
            }
        }
        combined
    }

    val displayedTracks = remember(mergedLocalTracks, selectedFilter) {
        when (selectedFilter) {
            "Audio" -> mergedLocalTracks.filter { it.videoType != "MUSIC_VIDEO_TYPE_OMV" }
            "MP4 Videos" -> mergedLocalTracks.filter { it.videoType == "MUSIC_VIDEO_TYPE_OMV" }
            "Download Folder" -> mergedLocalTracks.filter {
                it.albumName?.contains("Download", ignoreCase = true) == true ||
                    it.channelName.contains("Download", ignoreCase = true)
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "Local Music & Videos",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onBackground
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
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = tab },
                    label = {
                        Text(
                            text = tab,
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

        if (!hasStoragePermission) {
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
                            text = "Scan Device Folders",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Grant media permission to scan audio and MP4 video files in Download and Music folders.",
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

        if (isScanningDevice && orderedDisplayedTracks.isEmpty()) {
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
        } else if (orderedDisplayedTracks.isEmpty()) {
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
                    OutlinedButton(onClick = { scanTriggerCount++ }) {
                        Text("Rescan Device Storage")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 215.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(orderedDisplayedTracks, key = { idx, track -> "local_${track.videoId}_$idx" }) { index, track ->
                val isVideoItem = track.videoType == "MUSIC_VIDEO_TYPE_OMV"
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
                                    .widthIn(min = 225.dp, max = 280.dp)
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

private suspend fun scanDeviceMediaFiles(context: Context): List<CandidateStream> =
    withContext(Dispatchers.IO) {
        val list = mutableListOf<CandidateStream>()
        val seenUrisOrPaths = HashSet<String>()
        val seenTitleKeys = HashSet<String>()

        fun addCandidateIfUnique(candidate: CandidateStream, filePathHint: String = "") {
            val uriKey = candidate.videoId.lowercase().trim()
            val pathKey = filePathHint.lowercase().trim()
            if (uriKey.isNotBlank() && !seenUrisOrPaths.add(uriKey)) return
            if (pathKey.isNotBlank() && !seenUrisOrPaths.add(pathKey)) return
            val normTitle = candidate.title.lowercase().trim()
            if (normTitle.isNotBlank()) {
                seenTitleKeys.add(normTitle)
            }
            list.add(candidate)
        }

        // 1. Query MediaStore.Audio.Media (External Content URI) without dropping files where DURATION is 0 or null
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
            )
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

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    // Skip only tiny notification/ringtone clips (< 3s) when duration is known; keep 0/null durations!
                    if (durMs in 1L..2999L) continue

                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty().lowercase() else ""
                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                    val title = rawTitle
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { filePath.substringAfterLast('/').substringBeforeLast('.') }
                        .ifBlank { "Track $id" }

                    val lowerName = displayName.ifBlank { filePath }.lowercase()
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
                    val cleanAlbum = when {
                        isMp4OrVideo && (rawAlbum.equals("Download", true) || rawAlbum.equals("Downloads", true) || filePath.contains("/Download", true)) ->
                            "Download • MP4 Video"
                        isMp4OrVideo -> "MP4 Video"
                        rawAlbum.equals("Download", true) || rawAlbum.equals("Downloads", true) || filePath.contains("/Download", true) ->
                            "Download Folder"
                        else -> rawAlbum
                    }
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
                    val albumArtUri = if (albumId > 0L) {
                        "content://media/external/audio/albumart/$albumId"
                    } else ""
                    val durationSec = if (durMs > 0L) (durMs / 1000L).toInt().coerceAtLeast(1) else 210

                    addCandidateIfUnique(
                        CandidateStream(
                            videoId = contentUri,
                            title = title,
                            artist = artist,
                            channelName = if (isMp4OrVideo) "Device Video Storage" else "Device Storage",
                            albumName = cleanAlbum,
                            durationSeconds = durationSec,
                            videoType = if (isMp4OrVideo) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                            thumbnailUrl = albumArtUri,
                            isVerifiedArtist = false
                        ),
                        filePathHint = filePath
                    )
                }
            }
        }

        // 2. Query MediaStore.Video.Media (External Content URI) using safe standard columns
        runCatching {
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA
            )
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

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val durMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (durMs in 1L..2999L) continue

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val rawTitle = (if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else "")
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { filePath.substringAfterLast('/').substringBeforeLast('.') }
                        .ifBlank { "Local MP4 Video" }
                    val rawArtist = (if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else "")
                        .replace("<unknown>", "", ignoreCase = true)
                        .trim()
                        .ifBlank { "Unknown Artist" }
                    val durationSec = if (durMs > 0L) (durMs / 1000L).toInt().coerceAtLeast(1) else 210
                    val isDownloadFolder = filePath.contains("/Download", ignoreCase = true)

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
                            isVerifiedArtist = false
                        ),
                        filePathHint = filePath
                    )
                }
            }
        }

        // 3. Direct File System Fallback Scan across Music, Download, Movies, Podcasts & root storage folders
        runCatching {
            val rootDirs = listOfNotNull(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PODCASTS),
                runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ).distinctBy { it.absolutePath }

            val audioExts = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "wma")
            val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v")

            fun scanDirectoryBounded(dir: java.io.File, depth: Int) {
                if (depth > 2 || !dir.exists() || !dir.isDirectory) return
                val dirNameLower = dir.name.lowercase()
                if (dirNameLower.startsWith(".") || dirNameLower == "android") return
                val isDownloadDir = dir.absolutePath.contains("Download", ignoreCase = true)

                val children = dir.listFiles() ?: return
                for (file in children) {
                    if (file.isDirectory) {
                        if (depth < 2) scanDirectoryBounded(file, depth + 1)
                        continue
                    }
                    if (!file.isFile || file.length() <= 8_192L || file.name.startsWith(".")) continue
                    val ext = file.extension.lowercase()
                    val isVid = ext in videoExts
                    val isAud = ext in audioExts
                    if (!isVid && !isAud) continue

                    val cleanTitle = file.nameWithoutExtension.trim().ifBlank { file.name }
                    if (seenTitleKeys.contains(cleanTitle.lowercase())) continue
                    val fileUri = android.net.Uri.fromFile(file).toString()

                    addCandidateIfUnique(
                        CandidateStream(
                            videoId = fileUri,
                            title = cleanTitle,
                            artist = "Unknown Artist",
                            channelName = if (isDownloadDir) "Download Folder" else dir.name,
                            albumName = when {
                                isVid && isDownloadDir -> "Download • MP4 Video"
                                isVid -> "MP4 Video"
                                isDownloadDir -> "Download Folder"
                                else -> "Local Audio"
                            },
                            durationSeconds = 210,
                            videoType = if (isVid) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                            thumbnailUrl = "",
                            isVerifiedArtist = false
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
