// seitharol/data/download/DownloadPreferencesAndService.kt
package com.seitharol.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

enum class DownloadProvider(val id: String, val displayName: String, val subtitle: String) {
    JIOSAAVN("jiosaavn", "JioSaavn Direct", "320kbps Studio AAC / MP4"),
    AMAZON("amazon", "YouTube ATV Direct", "Canonical Official Art Track Stream"),
    TIDAL("tidal", "Tidal Hi-Fi Relay", "High Bitrate Master Stream"),
    QOBUZ("qobuz", "Qobuz Studio Relay", "High Bitrate Studio Stream")
}

enum class DownloadFormat(val ext: String, val mimeType: String, val label: String, val badge: String) {
    MP3("m4a", "audio/mp4", "Standard Audio", "M4A / AAC (320 kbps)"),
    FLAC("flac", "audio/flac", "Lossless / Master", "Hi-Res FLAC • 24-bit")
}

sealed class DownloadTaskState {
    data object Idle : DownloadTaskState()
    data class Downloading(
        val trackId: String,
        val trackTitle: String,
        val artistName: String,
        val thumbnailUrl: String,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val provider: DownloadProvider
    ) : DownloadTaskState()
    data class Completed(val trackId: String, val publicUri: Uri) : DownloadTaskState()
    data class Canceled(val trackId: String) : DownloadTaskState()
    data class Failed(val trackId: String, val message: String) : DownloadTaskState()
}

class DownloadPreferencesAndService(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("discover_download_prefs", Context.MODE_PRIVATE)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val DOWNLOAD_CHANNEL_ID = "seitharol_download_channel"

    init {
        createDownloadNotificationChannel()
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Seitharol Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress and completion notifications"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateDownloadNotification(title: String, progress: Int, isCompleted: Boolean = false, isFailed: Boolean = false) {
        try {
            val notifId = title.hashCode()
            if (isCompleted || isFailed) {
                notificationManager?.cancel(notifId)
                val completedNotification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(com.example.R.drawable.ic_stat_music_note)
                    .setContentTitle(if (isFailed) "Download Failed" else "Download Completed")
                    .setContentText(if (isFailed) "$title • Tap to retry" else "$title is ready for offline playback")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setProgress(0, 0, false)
                    .build()
                notificationManager?.notify(notifId + 10_000, completedNotification)
                return
            }

            val clamped = progress.coerceIn(0, 99)
            val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(com.example.R.drawable.ic_stat_music_note)
                .setContentTitle("Downloading $title")
                .setContentText("$clamped%")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, clamped, false)

            notificationManager?.notify(notifId, builder.build())
        } catch (e: Exception) {
            Log.w("DownloadPreferences", "Notification error: ${e.message}")
        }
    }

    private val _downloadState = MutableStateFlow<DownloadTaskState>(DownloadTaskState.Idle)
    val downloadState: StateFlow<DownloadTaskState> = _downloadState.asStateFlow()

    private val _completedDownloads = MutableStateFlow<List<NormalizedTrack>>(loadSavedDownloads())
    val completedDownloads: StateFlow<List<NormalizedTrack>> = _completedDownloads.asStateFlow()

    fun cancelActiveDownload() {
        val current = _downloadState.value
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        if (current is DownloadTaskState.Downloading) {
            notificationManager?.cancel(current.trackTitle.hashCode())
            _downloadState.value = DownloadTaskState.Canceled(current.trackId)
        } else {
            _downloadState.value = DownloadTaskState.Idle
        }
    }

    fun startDownloadInBackground(
        track: NormalizedTrack,
        provider: DownloadProvider,
        format: DownloadFormat,
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(context).state.value.downloadQuality,
        onTrackSavedAsLocalMusic: (NormalizedTrack) -> Unit
    ) {
        activeDownloadJob?.cancel()
        activeDownloadJob = serviceScope.launch {
            downloadTrackToPublicMusicStorage(track, provider, format, preferredQuality, onTrackSavedAsLocalMusic)
        }
    }

    suspend fun downloadTrackToPublicMusicStorage(
        track: NormalizedTrack,
        provider: DownloadProvider,
        format: DownloadFormat,
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(context).state.value.downloadQuality,
        onTrackSavedAsLocalMusic: (NormalizedTrack) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        recordProviderAndFormatSelection(provider, format)
        val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
        _downloadState.value = DownloadTaskState.Downloading(
            trackId = track.sourceId,
            trackTitle = track.title,
            artistName = primaryArtist,
            thumbnailUrl = track.artUrl,
            progressPercent = 1,
            downloadedBytes = 0L,
            totalBytes = 0L,
            provider = provider
        )

        runCatching {
            val streamUrl = resolveDirectDownloadUrl(track, primaryArtist, provider, preferredQuality)
                ?: throw IllegalStateException("Could not resolve downloadable stream for ${track.title}")

            val isYt = streamUrl.contains("googlevideo.com") || streamUrl.contains("youtube.com")
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                if (isYt) {
                    setRequestProperty("User-Agent", com.seitharol.player.OuterTuneResolvingDataSource.VR_USER_AGENT)
                    setRequestProperty("Range", "bytes=0-")
                } else {
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                }
            }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Audio server returned HTTP ${conn.responseCode}")
            }

            // Fallback estimated size if Content-Length is missing (-1)
            val fallbackEstimatedBytes = ((track.durationMs / 1000L).coerceAtLeast(180L)) * 20_000L
            val totalBytes = conn.contentLengthLong.takeIf { it > 1000L } ?: fallbackEstimatedBytes
            val safeFileName = sanitizeFileName("$primaryArtist - ${track.title}.${format.ext}")

            val savedUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
                    put(MediaStore.Audio.Media.TITLE, track.title)
                    put(MediaStore.Audio.Media.ARTIST, track.artists.joinToString(", "))
                    put(MediaStore.Audio.Media.ALBUM, track.album ?: "Single")
                    put(MediaStore.Audio.Media.DURATION, track.durationMs)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Seitharol")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IllegalStateException("Failed to create MediaStore Audio entry")

                try {
                    resolver.openOutputStream(itemUri)?.use { output ->
                        streamWithProgress(conn, output, totalBytes, track, primaryArtist, provider)
                    } ?: throw IllegalStateException("Failed to open output stream for $itemUri")

                    val publishValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publishValues, null, null)
                    itemUri
                } catch (e: Exception) {
                    resolver.delete(itemUri, null, null)
                    throw e
                }
            } else {
                val publicMusicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Seitharol"
                ).apply { if (!exists()) mkdirs() }
                val destFile = File(publicMusicDir, safeFileName)
                try {
                    FileOutputStream(destFile).use { output ->
                        streamWithProgress(conn, output, totalBytes, track, primaryArtist, provider)
                    }
                } catch (e: Exception) {
                    destFile.delete()
                    throw e
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(format.mimeType),
                    null
                )
                Uri.fromFile(destFile)
            }

            val localMusicTrack = track.copy(
                source = TrackSource.LOCAL_FILE,
                isPlayableNatively = true,
                localPath = savedUri.toString()
            )
            persistCompletedDownload(localMusicTrack)

            // Immediately register into CascadeDatabase Room DAOs so search & library see it synchronously
            runCatching {
                val db = com.example.data.local.CascadeDatabase.getInstance(context)
                val trackDao = db.trackDao()
                val downloadDao = db.downloadDao()
                val existing = trackDao.getTrackById(track.sourceId)
                if (existing == null) {
                    trackDao.insertTrack(
                        com.example.data.local.TrackEntity(
                            id = track.sourceId,
                            title = track.title,
                            artist = primaryArtist,
                            album = track.album ?: "Discover Downloads",
                            durationMs = track.durationMs,
                            artUrl = track.artUrl.orEmpty(),
                            isDownloaded = true,
                            localPath = savedUri.toString()
                        )
                    )
                } else {
                    trackDao.setDownloaded(track.sourceId, true, savedUri.toString())
                }
                downloadDao.insertDownload(
                    com.example.data.local.DownloadEntity(
                        trackId = track.sourceId,
                        tier = format.name.replaceFirstChar { it.uppercase() },
                        state = "COMPLETED",
                        localPath = savedUri.toString(),
                        progress = 1.0f,
                        bytesDownloaded = totalBytes,
                        totalBytes = totalBytes
                    )
                )
            }

            withContext(Dispatchers.Main) {
                onTrackSavedAsLocalMusic(localMusicTrack)
            }
            updateDownloadNotification(track.title, 100, isCompleted = true)
            _downloadState.value = DownloadTaskState.Completed(track.sourceId, savedUri)
            savedUri
        }.onFailure { err ->
            if (err is CancellationException) {
                notificationManager?.cancel(track.title.hashCode())
                _downloadState.value = DownloadTaskState.Canceled(track.sourceId)
            } else {
                updateDownloadNotification(track.title, 0, isFailed = true)
                _downloadState.value = DownloadTaskState.Failed(
                    trackId = track.sourceId,
                    message = err.localizedMessage ?: "Download failed for ${track.title}"
                )
            }
        }
    }

    private suspend fun streamWithProgress(
        conn: HttpURLConnection,
        output: OutputStream,
        totalBytes: Long,
        track: NormalizedTrack,
        primaryArtist: String,
        provider: DownloadProvider
    ) {
        var downloadedBytes = 0L
        var lastNotifMs = 0L
        conn.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                coroutineContext.ensureActive() // Enables immediate cancellation
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                val progress = ((downloadedBytes * 100L) / totalBytes.coerceAtLeast(1L)).toInt().coerceIn(1, 99)
                _downloadState.value = DownloadTaskState.Downloading(
                    trackId = track.sourceId,
                    trackTitle = track.title,
                    artistName = primaryArtist,
                    thumbnailUrl = track.artUrl,
                    progressPercent = progress,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    provider = provider
                )
                val now = System.currentTimeMillis()
                if (now - lastNotifMs > 300L) {
                    lastNotifMs = now
                    updateDownloadNotification(track.title, progress)
                }
            }
            output.flush()
        }
    }

    private suspend fun resolveDirectDownloadUrl(
        track: NormalizedTrack,
        primaryArtist: String,
        provider: DownloadProvider,
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(context).state.value.downloadQuality
    ): String? {
        val metadata = TrackMetadata(
            id = track.sourceId,
            title = track.title,
            artist = primaryArtist,
            album = track.album.orEmpty(),
            durationSeconds = (track.durationMs / 1000L).toInt(),
            thumbnailUrl = track.artUrl
        )
        if (track.source == TrackSource.YOUTUBE && track.sourceId.length == 11) {
            InnerTubeStreamExtractor.resolveWorkingStreamUrl(track.sourceId, preferredQuality)
                .getOrNull()?.let { return it }
        }
        if (provider == DownloadProvider.JIOSAAVN) {
            StreamResolver.resolveExactStreamUrl(metadata, preferredQuality)?.let { return it }
        }
        val ytCandidates = InnerTubeStreamExtractor.searchOfficialYtmSongs("${track.title} $primaryArtist", limit = 6)
        val bestYt = CanonicalAudioResolver.selectBestCanonicalMatch(
            queryTitle = track.title,
            queryArtist = primaryArtist,
            targetDurationSeconds = metadata.durationSeconds,
            candidates = ytCandidates
        )
        if (bestYt != null) {
            InnerTubeStreamExtractor.resolveWorkingStreamUrl(bestYt.videoId, preferredQuality)
                .getOrNull()?.let { return it }
        }
        return StreamResolver.resolveExactStreamUrl(metadata, preferredQuality)
    }

    private fun persistCompletedDownload(track: NormalizedTrack) {
        val updated = (_completedDownloads.value.filterNot { it.sourceId == track.sourceId } + track).reversed()
        _completedDownloads.value = updated
        val arr = JSONArray()
        updated.forEach { t ->
            arr.put(JSONObject().apply {
                put("sourceId", t.sourceId)
                put("title", t.title)
                put("artist", t.artists.firstOrNull().orEmpty())
                put("album", t.album.orEmpty())
                put("durationMs", t.durationMs)
                put("artUrl", t.artUrl)
                put("localPath", t.localPath.orEmpty())
            })
        }
        prefs.edit().putString("persisted_downloads_v1", arr.toString()).apply()
    }

    private fun loadSavedDownloads(): List<NormalizedTrack> {
        val raw = prefs.getString("persisted_downloads_v1", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        NormalizedTrack(
                            source = TrackSource.LOCAL_FILE,
                            sourceId = o.optString("sourceId"),
                            title = o.optString("title"),
                            artists = listOf(o.optString("artist", "Unknown Artist")),
                            album = o.optString("album", "Single"),
                            durationMs = o.optLong("durationMs", 210_000L),
                            artUrl = o.optString("artUrl"),
                            isPlayableNatively = true,
                            localPath = o.optString("localPath")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun getSortedProviders(): List<DownloadProvider> =
        DownloadProvider.entries.sortedWith(
            compareByDescending<DownloadProvider> { getUsageCount(it) }
                .thenByDescending { getLastUsedTimestamp(it) }
        )

    fun getPreferredFormat(): DownloadFormat {
        val saved = prefs.getString("preferred_format", DownloadFormat.MP3.name) ?: DownloadFormat.MP3.name
        return runCatching { DownloadFormat.valueOf(saved) }.getOrDefault(DownloadFormat.MP3)
    }

    fun recordProviderAndFormatSelection(provider: DownloadProvider, format: DownloadFormat) {
        val nextCount = getUsageCount(provider) + 1
        prefs.edit()
            .putInt("provider_count_${provider.id}", nextCount)
            .putLong("provider_last_${provider.id}", System.currentTimeMillis())
            .putString("preferred_format", format.name)
            .apply()
    }

    fun getUsageCount(provider: DownloadProvider): Int = prefs.getInt("provider_count_${provider.id}", 0)
    private fun getLastUsedTimestamp(provider: DownloadProvider): Long = prefs.getLong("provider_last_${provider.id}", 0L)
    private fun sanitizeFileName(name: String): String = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim()

    companion object {
        @Volatile
        private var instance: DownloadPreferencesAndService? = null

        fun getInstance(context: Context): DownloadPreferencesAndService {
            return instance ?: synchronized(this) {
                instance ?: DownloadPreferencesAndService(context.applicationContext).also { instance = it }
            }
        }
    }
}
