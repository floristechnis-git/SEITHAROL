package com.seitharol.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class DownloadProvider(val id: String, val displayName: String, val subtitle: String) {
    AMAZON("amazon", "Amazon Music", "Hi-Res / CD Lossless & 320kbps"),
    TIDAL("tidal", "Tidal", "Master / FLAC & High Quality"),
    QOBUZ("qobuz", "Qobuz", "24-Bit Studio Masters & MP3"),
    JIOSAAVN("jiosaavn", "JioSaavn", "320kbps AAC/MP3")
}

enum class DownloadFormat(val ext: String, val mimeType: String, val label: String) {
    MP3("mp3", "audio/mpeg", "MP3 (320 kbps)"),
    FLAC("flac", "audio/flac", "FLAC (Lossless)")
}

sealed class DownloadTaskState {
    data object Idle : DownloadTaskState()
    data class Downloading(val trackId: String, val progressPercent: Int, val provider: DownloadProvider) : DownloadTaskState()
    data class Completed(val trackId: String, val publicUri: Uri) : DownloadTaskState()
    data class Failed(val trackId: String, val message: String) : DownloadTaskState()
}

class DownloadPreferencesAndService(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("discover_download_prefs", Context.MODE_PRIVATE)

    private val _downloadState = MutableStateFlow<DownloadTaskState>(DownloadTaskState.Idle)
    val downloadState: StateFlow<DownloadTaskState> = _downloadState.asStateFlow()

    fun getSortedProviders(): List<DownloadProvider> {
        return DownloadProvider.entries.sortedWith(
            compareByDescending<DownloadProvider> { getUsageCount(it) }
                .thenByDescending { getLastUsedTimestamp(it) }
        )
    }

    fun getPreferredFormat(): DownloadFormat {
        val saved = prefs.getString("preferred_format", DownloadFormat.FLAC.name) ?: DownloadFormat.FLAC.name
        return runCatching { DownloadFormat.valueOf(saved) }.getOrDefault(DownloadFormat.FLAC)
    }

    fun recordProviderAndFormatSelection(provider: DownloadProvider, format: DownloadFormat) {
        val nextCount = getUsageCount(provider) + 1
        prefs.edit()
            .putInt("provider_count_${provider.id}", nextCount)
            .putLong("provider_last_${provider.id}", System.currentTimeMillis())
            .putString("preferred_format", format.name)
            .apply()
    }

    fun getUsageCount(provider: DownloadProvider): Int =
        prefs.getInt("provider_count_${provider.id}", 0)

    private fun getLastUsedTimestamp(provider: DownloadProvider): Long =
        prefs.getLong("provider_last_${provider.id}", 0L)

    /**
     * Downloads the full MP3/FLAC track from the external backend and saves it directly
     * into the device's public Music storage (`Music/Seitharol/`) via MediaStore so it
     * behaves and plays like a standard local music file on the phone (NOT an app cache).
     */
    suspend fun downloadTrackToPublicMusicStorage(
        track: NormalizedTrack,
        provider: DownloadProvider,
        format: DownloadFormat,
        backendBaseUrl: String = "http://10.0.2.2:8000",
        onTrackSavedAsLocalMusic: (NormalizedTrack) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        recordProviderAndFormatSelection(provider, format)
        _downloadState.value = DownloadTaskState.Downloading(track.sourceId, 0, provider)

        runCatching {
            val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
            val safeFileName = sanitizeFileName("$primaryArtist - ${track.title}.${format.ext}")

            val encodedId = URLEncoder.encode(track.sourceId, "UTF-8")
            val encodedTitle = URLEncoder.encode(track.title, "UTF-8")
            val encodedArtist = URLEncoder.encode(primaryArtist, "UTF-8")
            val requestUrl = "$backendBaseUrl/api/v1/download?spotify_id=$encodedId" +
                "&title=$encodedTitle&artist=$encodedArtist" +
                "&provider=${provider.id}&format=${format.ext}"

            val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
            }

            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Download server returned HTTP ${conn.responseCode}")
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)

            val savedUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Insert directly into public MediaStore.Audio.Media (Music/Seitharol)
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
                        streamWithProgress(conn, output, totalBytes, track.sourceId, provider)
                    } ?: throw IllegalStateException("Failed to open output stream for $itemUri")

                    // Publish file so it is immediately visible and playable as local device music
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
                // Android 9 and below: Write directly to public /Music/Seitharol directory & scan
                val publicMusicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "Seitharol"
                ).apply { if (!exists()) mkdirs() }

                val destFile = File(publicMusicDir, safeFileName)
                FileOutputStream(destFile).use { output ->
                    streamWithProgress(conn, output, totalBytes, track.sourceId, provider)
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(format.mimeType),
                    null
                )
                Uri.fromFile(destFile)
            }

            // Emit updated NormalizedTrack marked as native local device audio
            val localMusicTrack = track.copy(
                source = TrackSource.LOCAL_FILE,
                isPlayableNatively = true,
                localPath = savedUri.toString() // content://media/external/audio/media/... or file:///storage/...
            )
            onTrackSavedAsLocalMusic(localMusicTrack)
            _downloadState.value = DownloadTaskState.Completed(track.sourceId, savedUri)
            savedUri
        }.onFailure { err ->
            _downloadState.value = DownloadTaskState.Failed(
                trackId = track.sourceId,
                message = err.message ?: "Download failed"
            )
        }
    }

    private fun streamWithProgress(
        conn: HttpURLConnection,
        output: OutputStream,
        totalBytes: Long,
        trackId: String,
        provider: DownloadProvider
    ) {
        var downloadedBytes = 0L
        conn.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                val progress = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                _downloadState.value = DownloadTaskState.Downloading(trackId, progress, provider)
            }
            output.flush()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
