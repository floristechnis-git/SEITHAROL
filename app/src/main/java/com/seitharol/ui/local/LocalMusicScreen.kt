// seitharol/ui/local/LocalMusicScreen.kt
package com.seitharol.ui.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scannedDeviceTracks by remember { mutableStateOf<List<CandidateStream>>(emptyList()) }
    var trackPendingPlaylistAdd by remember { mutableStateOf<CandidateStream?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStoragePermission = granted
    }

    // Automatically request permission on first visit if not granted
    LaunchedEffect(Unit) {
        if (!hasStoragePermission) {
            permissionLauncher.launch(requiredPermission)
        }
    }

    // Scan device audio folders once permission is granted
    LaunchedEffect(hasStoragePermission) {
        if (hasStoragePermission) {
            scannedDeviceTracks = scanDeviceAudioFiles(context)
        }
    }

    // Tier 1: Songs downloaded from Discover & Download are ALWAYS available immediately
    // even before folder scan permission is granted!
    val defaultAppDownloads = remember(appDownloadedTracks) {
        appDownloadedTracks.map { t ->
            CandidateStream(
                videoId = t.localPath ?: t.sourceId,
                title = t.title,
                artist = t.artists.joinToString(", "),
                channelName = "Downloaded in Seitharol",
                albumName = t.album,
                durationSeconds = (t.durationMs / 1000L).toInt(),
                videoType = "LOCAL_AUDIO",
                thumbnailUrl = t.artUrl,
                isVerifiedArtist = true
            )
        }
    }

    val mergedLocalTracks = remember(defaultAppDownloads, scannedDeviceTracks) {
        (defaultAppDownloads + scannedDeviceTracks).distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
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
                    text = "Local Music",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${mergedLocalTracks.size} songs on device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasStoragePermission) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Scan Device Folders",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Seitharol downloads are shown below. Grant audio permission to scan all device folders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { permissionLauncher.launch(requiredPermission) }) {
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
            itemsIndexed(mergedLocalTracks.distinctBy { it.videoId }, key = { idx, track -> "local_${track.videoId}_$idx" }) { _, track ->
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_music_note),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
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
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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

private suspend fun scanDeviceAudioFiles(context: Context): List<CandidateStream> =
    withContext(Dispatchers.IO) {
        val list = mutableListOf<CandidateStream>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"
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
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                val title = cursor.getString(titleCol).orEmpty().ifBlank { "Unknown Track" }
                val artist = cursor.getString(artistCol).orEmpty().replace("<unknown>", "").trim().ifBlank { "Unknown Artist" }
                val album = cursor.getString(albumCol).orEmpty()
                val durationSec = (cursor.getLong(durCol) / 1000L).toInt()
                list.add(
                    CandidateStream(
                        videoId = contentUri,
                        title = title,
                        artist = artist,
                        channelName = "Device Storage",
                        albumName = album,
                        durationSeconds = durationSec,
                        videoType = "LOCAL_AUDIO",
                        thumbnailUrl = "",
                        isVerifiedArtist = false
                    )
                )
            }
        }
        // Scan device MP4 Video files in Movies/Seitharol & device storage
        runCatching {
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.DURATION
            )
            val videoSelection = "${MediaStore.Video.Media.DURATION} >= 10000"
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val title = cursor.getString(titleCol).orEmpty().ifBlank { "Local MP4 Video" }
                    val artist = cursor.getString(artistCol).orEmpty().replace("<unknown>", "").trim().ifBlank { "Local Video" }
                    val durationSec = (cursor.getLong(durCol) / 1000L).toInt()
                    list.add(
                        CandidateStream(
                            videoId = contentUri,
                            title = title,
                            artist = artist,
                            channelName = "Device Video Storage",
                            albumName = "YouTube Video",
                            durationSeconds = durationSec,
                            videoType = "MUSIC_VIDEO_TYPE_OMV",
                            thumbnailUrl = "",
                            isVerifiedArtist = false
                        )
                    )
                }
            }
        }
        list
    }
