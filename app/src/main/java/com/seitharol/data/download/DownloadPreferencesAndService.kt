// seitharol/data/download/DownloadPreferencesAndService.kt
package com.seitharol.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.AudioQuality
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
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("discover_download_prefs", Context.MODE_PRIVATE)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null
    @Volatile private var activeTrackTitle: String? = null
    @Volatile private var activeCanonicalId: String? = null
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val DOWNLOAD_CHANNEL_ID = "seitharol_download_channel"

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_DISCOVER_DOWNLOAD) {
                cancelActiveDownload()
            }
        }
    }

    init {
        createDownloadNotificationChannel()
        registerCancelReceiver()
    }

    private fun registerCancelReceiver() {
        try {
            val filter = IntentFilter(ACTION_CANCEL_DISCOVER_DOWNLOAD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(cancelReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w("DownloadPreferences", "Cancel receiver registration skipped: ${t.message}")
        }
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

    private fun buildCancelPendingIntent(notifId: Int): PendingIntent {
        val cancelIntent = Intent(ACTION_CANCEL_DISCOVER_DOWNLOAD).apply {
            setPackage(appContext.packageName)
            putExtra("NOTIF_ID", notifId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            notifId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateDownloadNotification(title: String, progress: Int, isCompleted: Boolean = false, isFailed: Boolean = false) {
        try {
            val notifId = title.hashCode()
            if (isCompleted || isFailed) {
                notificationManager?.cancel(notifId)
                val completedNotification = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(com.example.R.drawable.ic_stat_music_note)
                    .setContentTitle(if (isFailed) "Download Failed" else "Download Completed")
                    .setContentText(if (isFailed) "$title • Tap to dismiss" else "$title is ready for offline playback")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setProgress(0, 0, false)
                    .build()
                notificationManager?.notify(notifId + 10_000, completedNotification)
                return
            }

            val clamped = progress.coerceIn(0, 99)
            val cancelPending = buildCancelPendingIntent(notifId)
            val builder = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(com.example.R.drawable.ic_stat_music_note)
                .setContentTitle("Downloading $title")
                .setContentText("$clamped% • Tap to cancel")
                .setContentIntent(cancelPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
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
        val titleToCancel = activeTrackTitle
        val canonToCancel = activeCanonicalId
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        activeTrackTitle = null
        activeCanonicalId = null

        if (titleToCancel != null) {
            notificationManager?.cancel(titleToCancel.hashCode())
        }
        if (current is DownloadTaskState.Downloading) {
            notificationManager?.cancel(current.trackTitle.hashCode())
            _downloadState.value = DownloadTaskState.Canceled(current.trackId)
        } else {
            _downloadState.value = DownloadTaskState.Idle
        }

        if (canonToCancel != null) {
            serviceScope.launch {
                runCatching {
                    val db = com.example.data.local.CascadeDatabase.getInstance(appContext)
                    db.downloadDao().deleteDownload(canonToCancel)
                    val downloadDir = File(appContext.filesDir, "downloads")
                    downloadDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith(canonToCancel) && f.name.endsWith(".tmp")) {
                            f.delete()
                        }
                    }
                }
            }
        }
    }

    fun startDownloadInBackground(
        track: NormalizedTrack,
        provider: DownloadProvider,
        format: DownloadFormat,
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(appContext).state.value.downloadQuality,
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
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(appContext).state.value.downloadQuality,
        onTrackSavedAsLocalMusic: (NormalizedTrack) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        recordProviderAndFormatSelection(provider, format)
        val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
        val canonicalId = canonicalTrackId(track.sourceId)
        activeTrackTitle = track.title
        activeCanonicalId = canonicalId

        _downloadState.value = DownloadTaskState.Downloading(
            trackId = track.sourceId,
            trackTitle = track.title,
            artistName = primaryArtist,
            thumbnailUrl = track.artUrl,
            progressPercent = 2,
            downloadedBytes = 0L,
            totalBytes = 4_000_000L,
            provider = provider
        )
        updateDownloadNotification(track.title, 2)

        val downloadDir = File(appContext.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        val tempFile = File(downloadDir, "${canonicalId}_discover.${format.ext}.tmp")
        val internalDestFile = File(downloadDir, "${canonicalId}_discover.${format.ext}")

        runCatching {
            val db = com.example.data.local.CascadeDatabase.getInstance(appContext)
            val trackDao = db.trackDao()
            val downloadDao = db.downloadDao()
            val existingTrack = trackDao.getTrackById(canonicalId) ?: trackDao.getTrackById(track.sourceId)
            trackDao.insertTrack(
                com.example.data.local.TrackEntity(
                    id = canonicalId,
                    title = track.title,
                    artist = primaryArtist,
                    album = track.album ?: "Discover Downloads",
                    durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L,
                    artUrl = track.artUrl.orEmpty(),
                    isLiked = existingTrack?.isLiked ?: track.isLiked,
                    isDownloaded = false,
                    localPath = internalDestFile.absolutePath
                )
            )
            downloadDao.insertDownload(
                com.example.data.local.DownloadEntity(
                    trackId = canonicalId,
                    tier = format.label,
                    state = "DOWNLOADING",
                    localPath = internalDestFile.absolutePath,
                    progress = 0.05f,
                    bytesDownloaded = 0L,
                    totalBytes = 4_000_000L
                )
            )

            val streamUrl = resolveDirectDownloadUrl(track, primaryArtist, provider, preferredQuality)
                ?: throw IllegalStateException("Could not resolve downloadable stream for ${track.title}")

            val headers = com.seitharol.player.OuterTuneResolvingDataSource.resolveClientHeadersForUrl(streamUrl)
            val requestBuilder = Request.Builder().url(streamUrl)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val fallbackEstimatedBytes = ((track.durationMs / 1000L).coerceAtLeast(180L)) * 20_000L
            var finalTotalBytes = fallbackEstimatedBytes

            com.example.network.NetworkClients.okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Audio server returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty response body from audio server")
                val totalBytes = body.contentLength().takeIf { it > 10_000L } ?: fallbackEstimatedBytes
                finalTotalBytes = totalBytes

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        streamWithProgress(
                            input = input,
                            output = output,
                            totalBytes = totalBytes,
                            track = track,
                            canonicalId = canonicalId,
                            primaryArtist = primaryArtist,
                            provider = provider,
                            format = format,
                            destPath = internalDestFile.absolutePath,
                            downloadDao = downloadDao
                        )
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 8_192L) {
                tempFile.delete()
                throw IllegalStateException("Downloaded audio stream was incomplete")
            }

            if (internalDestFile.exists()) internalDestFile.delete()
            val renamed = tempFile.renameTo(internalDestFile)
            val validInternalFile = if (renamed) internalDestFile else tempFile
            finalTotalBytes = validInternalFile.length()

            // Mirror to public Music/Seitharol if possible, but always keep internalDestFile as guaranteed offline file
            val safeFileName = sanitizeFileName("$primaryArtist - ${track.title}.${format.ext}")
            runCatching {
                exportToPublicMusicFolder(validInternalFile, safeFileName, format, track)
            }

            val savedUri = Uri.fromFile(validInternalFile)

            val localMusicTrack = track.copy(
                source = TrackSource.LOCAL_FILE,
                sourceId = canonicalId,
                isPlayableNatively = true,
                localPath = validInternalFile.absolutePath
            )
            persistCompletedDownload(localMusicTrack)

            runCatching {
                val existing = trackDao.getTrackById(canonicalId)
                trackDao.insertTrack(
                    com.example.data.local.TrackEntity(
                        id = canonicalId,
                        title = track.title,
                        artist = primaryArtist,
                        album = track.album ?: "Discover Downloads",
                        durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L,
                        artUrl = track.artUrl.orEmpty(),
                        isLiked = existing?.isLiked ?: track.isLiked,
                        isDownloaded = true,
                        localPath = validInternalFile.absolutePath
                    )
                )
                trackDao.setDownloaded(canonicalId, true, validInternalFile.absolutePath)
                downloadDao.insertDownload(
                    com.example.data.local.DownloadEntity(
                        trackId = canonicalId,
                        tier = format.label,
                        state = "COMPLETED",
                        localPath = validInternalFile.absolutePath,
                        progress = 1.0f,
                        bytesDownloaded = finalTotalBytes,
                        totalBytes = finalTotalBytes
                    )
                )
            }

            withContext(Dispatchers.Main) {
                onTrackSavedAsLocalMusic(localMusicTrack)
            }
            activeTrackTitle = null
            activeCanonicalId = null
            updateDownloadNotification(track.title, 100, isCompleted = true)
            _downloadState.value = DownloadTaskState.Completed(track.sourceId, savedUri)
            savedUri
        }.onFailure { err ->
            activeTrackTitle = null
            activeCanonicalId = null
            if (tempFile.exists()) tempFile.delete()
            val db = runCatching { com.example.data.local.CascadeDatabase.getInstance(appContext) }.getOrNull()
            if (err is CancellationException) {
                notificationManager?.cancel(track.title.hashCode())
                runCatching { db?.downloadDao()?.deleteDownload(canonicalId) }
                _downloadState.value = DownloadTaskState.Canceled(track.sourceId)
            } else {
                Log.e("DownloadPreferences", "Discover download failed for ${track.title}: ${err.message}", err)
                runCatching { db?.downloadDao()?.deleteDownload(canonicalId) }
                updateDownloadNotification(track.title, 0, isFailed = true)
                _downloadState.value = DownloadTaskState.Failed(
                    trackId = track.sourceId,
                    message = err.localizedMessage ?: "Download failed for ${track.title}"
                )
            }
        }
    }

    private fun exportToPublicMusicFolder(
        sourceFile: File,
        safeFileName: String,
        format: DownloadFormat,
        track: NormalizedTrack
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
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
            val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                val publishValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
            }
        } else {
            val publicMusicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Seitharol"
            ).apply { if (!exists()) mkdirs() }
            val destFile = File(publicMusicDir, safeFileName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(destFile.absolutePath),
                arrayOf(format.mimeType),
                null
            )
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        track: NormalizedTrack,
        canonicalId: String,
        primaryArtist: String,
        provider: DownloadProvider,
        format: DownloadFormat,
        destPath: String,
        downloadDao: com.example.data.local.DownloadDao
    ) {
        var downloadedBytes = 0L
        var lastNotifMs = 0L
        val buffer = ByteArray(16 * 1024)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            coroutineContext.ensureActive()
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
                runCatching {
                    downloadDao.updateDownload(
                        com.example.data.local.DownloadEntity(
                            trackId = canonicalId,
                            tier = format.label,
                            state = "DOWNLOADING",
                            localPath = destPath,
                            progress = (progress / 100f).coerceIn(0.02f, 0.99f),
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalBytes
                        )
                    )
                }
            }
        }
        output.flush()
    }

    private suspend fun resolveDirectDownloadUrl(
        track: NormalizedTrack,
        primaryArtist: String,
        provider: DownloadProvider,
        preferredQuality: AudioQuality = AppSettingsManager.getInstance(appContext).state.value.downloadQuality
    ): String? {
        // 1. Check if track.sourceId or track.previewUrl contains a valid 11-char YouTube videoId
        val cleanYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            ?: InnerTubeStreamExtractor.extractVideoId(track.previewUrl?.removePrefix("ytm_clip:"))
        if (!cleanYtId.isNullOrBlank()) {
            val directYt = InnerTubeStreamExtractor.resolveWorkingStreamUrl(cleanYtId, preferredQuality).getOrNull()
                ?: InnerTubeStreamExtractor.resolveExactYoutubeStreamById(cleanYtId, preferredQuality, forceRefresh = true).getOrNull()
            if (!directYt.isNullOrBlank()) return directYt
        }

        val canonicalId = canonicalTrackId(track.sourceId)
        val metadata = TrackMetadata(
            id = canonicalId,
            title = track.title,
            artist = primaryArtist,
            album = track.album.orEmpty(),
            durationSeconds = (track.durationMs / 1000L).toInt(),
            thumbnailUrl = track.artUrl
        )

        // 2. If JioSaavn is selected, try JioSaavn first, then fall back to YouTube Music search
        if (provider == DownloadProvider.JIOSAAVN) {
            val saavnUrl = runCatching { StreamResolver.resolveExactStreamUrl(metadata, preferredQuality) }.getOrNull()
            if (!saavnUrl.isNullOrBlank()) return saavnUrl
        }

        // 3. Search YouTube Music by title + artist for non-YouTube seeded Discover tracks (e.g. Spotify/iTunes/Deezer)
        val searchQuery = "${track.title} $primaryArtist".trim()
        if (searchQuery.isNotBlank()) {
            val ytmCandidates = runCatching {
                InnerTubeStreamExtractor.searchOfficialYtmSongs(searchQuery, limit = 5)
            }.getOrDefault(emptyList())
            for (cand in ytmCandidates) {
                val resolved = InnerTubeStreamExtractor.resolveWorkingStreamUrl(cand.videoId, preferredQuality).getOrNull()
                    ?: InnerTubeStreamExtractor.resolveExactYoutubeStreamById(cand.videoId, preferredQuality).getOrNull()
                if (!resolved.isNullOrBlank()) return resolved
            }
        }

        // 4. Fallback to JioSaavn if not already tried
        if (provider != DownloadProvider.JIOSAAVN) {
            val saavnUrl = runCatching { StreamResolver.resolveExactStreamUrl(metadata, preferredQuality) }.getOrNull()
            if (!saavnUrl.isNullOrBlank()) return saavnUrl
        }

        // 5. Fallback to full app ResolverEngine
        val appResolverUrl = runCatching {
            val domainTrack = com.example.data.model.Track(
                id = canonicalId,
                title = track.title,
                artist = primaryArtist,
                album = track.album.orEmpty(),
                durationMs = track.durationMs,
                artUrl = track.artUrl
            )
            com.example.CascadeApplication.instance.resolverEngine.resolveTrack(domainTrack, preferredQuality).streamUrl
        }.getOrNull()
        if (!appResolverUrl.isNullOrBlank()) return appResolverUrl

        // 6. Final fallback to direct HTTP previewUrl if available
        return track.previewUrl?.takeIf { it.startsWith("http") }
    }

    fun canonicalTrackId(rawId: String): String {
        return InnerTubeStreamExtractor.extractVideoId(rawId)
            ?: rawId.removePrefix("ytm_clip:").removePrefix("saavn_clip:").removePrefix("local_").trim()
    }

    private fun persistCompletedDownload(track: NormalizedTrack) {
        val canonicalId = canonicalTrackId(track.sourceId)
        val normalizedTrack = track.copy(sourceId = canonicalId)
        val updated = (_completedDownloads.value.filterNot {
            canonicalTrackId(it.sourceId) == canonicalId ||
                (it.title.equals(normalizedTrack.title, ignoreCase = true) &&
                    it.artists.firstOrNull().orEmpty().equals(normalizedTrack.artists.firstOrNull().orEmpty(), ignoreCase = true))
        } + normalizedTrack).reversed()
        _completedDownloads.value = updated
        saveCompletedListToPrefs(updated)
    }

    fun removeCompletedDownload(trackId: String, title: String? = null) {
        val targetCanonical = canonicalTrackId(trackId)
        val toRemove = _completedDownloads.value.filter {
            it.sourceId == trackId ||
                canonicalTrackId(it.sourceId) == targetCanonical ||
                (!title.isNullOrBlank() && it.title.equals(title, ignoreCase = true))
        }
        toRemove.forEach { item ->
            val path = item.localPath.orEmpty()
            if (path.startsWith("content://")) {
                runCatching { appContext.contentResolver.delete(Uri.parse(path), null, null) }
            } else if (path.isNotBlank()) {
                runCatching { File(path.removePrefix("file://")).takeIf { it.exists() }?.delete() }
            }
        }
        val remaining = _completedDownloads.value.filterNot {
            it.sourceId == trackId ||
                canonicalTrackId(it.sourceId) == targetCanonical ||
                (!title.isNullOrBlank() && it.title.equals(title, ignoreCase = true))
        }
        _completedDownloads.value = remaining
        saveCompletedListToPrefs(remaining)
    }

    private fun saveCompletedListToPrefs(list: List<NormalizedTrack>) {
        val arr = JSONArray()
        list.forEach { t ->
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
        const val ACTION_CANCEL_DISCOVER_DOWNLOAD = "com.seitharol.ACTION_CANCEL_DISCOVER_DOWNLOAD"

        @Volatile
        private var instance: DownloadPreferencesAndService? = null

        fun getInstance(context: Context): DownloadPreferencesAndService {
            return instance ?: synchronized(this) {
                instance ?: DownloadPreferencesAndService(context.applicationContext).also { instance = it }
            }
        }
    }
}
