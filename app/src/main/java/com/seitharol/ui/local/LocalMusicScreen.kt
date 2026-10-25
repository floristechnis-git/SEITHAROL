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
    onAddLocalTrackToPlaylist: (playlistId: String, track: CandidateStream) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            requiredPermissions.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var scannedDeviceTracks by remember { mutableStateOf<List<CandidateStream>>(emptyList()) }
    var trackPendingPlaylistAdd by remember { mutableStateOf<CandidateStream?>(null) }
    var selectedFilter by remember { mutableStateOf("All") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantMap ->
        hasStoragePermission = grantMap.values.any { it }
    }

    // Automatically request permission on first visit if not granted
    LaunchedEffect(Unit) {
        if (!hasStoragePermission) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Scan device audio and video folders (including Download folder) once permission is granted
    LaunchedEffect(hasStoragePermission) {
        if (hasStoragePermission) {
            scannedDeviceTracks = scanDeviceMediaFiles(context)
        }
    }

    // Tier 1: Songs and MP4 videos downloaded inside the app are ALWAYS classified accurately
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
        (defaultAppDownloads + scannedDeviceTracks).distinctBy {
            "${it.title.lowercase().trim()}|${it.videoType}"
        }
    }

    val audioCount = remember(mergedLocalTracks) {
        mergedLocalTracks.count { it.videoType != "MUSIC_VIDEO_TYPE_OMV" }
    }
    val videoCount = remember(mergedLocalTracks) {
        mergedLocalTracks.count { it.videoType == "MUSIC_VIDEO_TYPE_OMV" }
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
                FilterChip(
                    selected = selectedFilter == tab,
                    onClick = { selectedFilter = tab },
                    label = { Text(tab) },
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
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                        Text("Allow")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(displayedTracks.distinctBy { it.videoId }, key = { idx, track -> "local_${track.videoId}_$idx" }) { _, track ->
                val isVideoItem = track.videoType == "MUSIC_VIDEO_TYPE_OMV"
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayLocalTrack(track) }
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
                        IconButton(onClick = { trackPendingPlaylistAdd = track }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Add to Playlist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    trackPendingPlaylistAdd?.let { selectedTrack ->
        AlertDialog(
            onDismissRequest = { trackPendingPlaylistAdd = null },
            title = { Text("Add to Playlist", fontWeight = FontWeight.Bold) },
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
                            Text(title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trackPendingPlaylistAdd = null }) { Text("Close") }
            }
        )
    }
}

private suspend fun scanDeviceMediaFiles(context: Context): List<CandidateStream> =
    withContext(Dispatchers.IO) {
        val list = mutableListOf<CandidateStream>()
        val seenVideoKeys = HashSet<String>()

        // 1. Scan all device MP4 / Video files first (including Download folder, Movies, Seitharol, etc.)
        runCatching {
            val videoProjection = buildList {
                add(MediaStore.Video.Media._ID)
                add(MediaStore.Video.Media.TITLE)
                add(MediaStore.Video.Media.ARTIST)
                add(MediaStore.Video.Media.DURATION)
                add(MediaStore.Video.Media.DISPLAY_NAME)
                add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            }.toTypedArray()

            val videoSelection = "${MediaStore.Video.Media.DURATION} >= 5000"
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol).orEmpty() else "Device Video"
                    val rawTitle = cursor.getString(titleCol).orEmpty()
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { "Local MP4 Video" }
                    val rawArtist = cursor.getString(artistCol).orEmpty()
                        .replace("<unknown>", "", ignoreCase = true)
                        .trim()
                        .ifBlank { "Unknown Artist" }
                    val durationSec = (cursor.getLong(durCol) / 1000L).toInt()
                    seenVideoKeys.add(rawTitle.lowercase().trim())
                    if (displayName.isNotBlank()) {
                        seenVideoKeys.add(displayName.substringBeforeLast('.').lowercase().trim())
                    }
                    list.add(
                        CandidateStream(
                            videoId = contentUri,
                            title = rawTitle,
                            artist = rawArtist,
                            channelName = bucketName.ifBlank { "Device Video Storage" },
                            albumName = if (bucketName.equals("Download", true) || bucketName.equals("Downloads", true)) {
                                "Download • MP4 Video"
                            } else {
                                "MP4 Video"
                            },
                            durationSeconds = durationSec,
                            videoType = "MUSIC_VIDEO_TYPE_OMV",
                            thumbnailUrl = contentUri,
                            isVerifiedArtist = false
                        )
                    )
                }
            }
        }

        // 2. Scan device Audio files, explicitly detecting and re-classifying any .mp4 / video/* files
        //    (such as MP4 videos in the Download folder indexed by MediaStore.Audio) as MP4 Video!
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE
            )
            val selection = "${MediaStore.Audio.Media.DURATION} >= 10000"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty().lowercase() else ""
                    val title = cursor.getString(titleCol).orEmpty()
                        .ifBlank { displayName.substringBeforeLast('.') }
                        .ifBlank { "Unknown Track" }

                    // Skip if this exact file was already indexed as a video from MediaStore.Video
                    if (seenVideoKeys.contains(title.lowercase().trim())) continue

                    val lowerName = displayName.lowercase()
                    val isMp4OrVideo = mimeType.startsWith("video/") ||
                        mimeType == "application/mp4" ||
                        lowerName.endsWith(".mp4") ||
                        lowerName.endsWith(".mkv") ||
                        lowerName.endsWith(".webm") ||
                        lowerName.endsWith(".mov") ||
                        lowerName.endsWith(".m4v")

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val artist = cursor.getString(artistCol).orEmpty()
                        .replace("<unknown>", "", ignoreCase = true)
                        .trim()
                        .ifBlank { "Unknown Artist" }
                    val rawAlbum = cursor.getString(albumCol).orEmpty()
                    val cleanAlbum = when {
                        isMp4OrVideo && (rawAlbum.equals("Download", true) || rawAlbum.equals("Downloads", true)) ->
                            "Download • MP4 Video"
                        isMp4OrVideo -> "MP4 Video"
                        rawAlbum.equals("Download", true) || rawAlbum.equals("Downloads", true) ->
                            "Download Folder"
                        else -> rawAlbum
                    }
                    val durationSec = (cursor.getLong(durCol) / 1000L).toInt()
                    list.add(
                        CandidateStream(
                            videoId = contentUri,
                            title = title,
                            artist = artist,
                            channelName = if (isMp4OrVideo) "Device Video Storage" else "Device Storage",
                            albumName = cleanAlbum,
                            durationSeconds = durationSec,
                            videoType = if (isMp4OrVideo) "MUSIC_VIDEO_TYPE_OMV" else "LOCAL_AUDIO",
                            thumbnailUrl = "",
                            isVerifiedArtist = false
                        )
                    )
                }
            }
        }

        // 3. Direct filesystem scan of public Download & Movies folders to catch any MP4/video or audio files
        //    not yet indexed by MediaStore (ensuring MP4 in Download folder is always treated as MP4 Video, never audio!)
        runCatching {
            val dirs = listOfNotNull(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            )
            for (dir in dirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val isDownloadDir = dir.name.contains("Download", ignoreCase = true)
                dir.listFiles()?.forEach { file ->
                    if (!file.isFile || file.length() <= 10_240L || file.name.startsWith(".")) return@forEach
                    val ext = file.extension.lowercase()
                    val isVid = ext in setOf("mp4", "mkv", "webm", "mov", "m4v")
                    val isAud = ext in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
                    if (!isVid && !isAud) return@forEach
                    val cleanTitle = file.nameWithoutExtension.trim().ifBlank { file.name }
                    val key = cleanTitle.lowercase()
                    if (seenVideoKeys.contains(key) || list.any { it.title.equals(cleanTitle, ignoreCase = true) }) {
                        return@forEach
                    }
                    val fileUri = android.net.Uri.fromFile(file).toString()
                    list.add(
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
                        )
                    )
                }
            }
        }
        list
    }
