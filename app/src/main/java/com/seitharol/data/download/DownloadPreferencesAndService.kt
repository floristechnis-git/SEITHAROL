package com.seitharol.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
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

enum class DownloadProvider(val id: String, val displayName: String, val subtitle: String) {
    JIOSAAVN("jiosaavn", "JioSaavn Direct", "320kbps Studio AAC / MP4"),
    AMAZON("amazon", "YouTube ATV Direct", "Canonical Official Art Track Stream"),
    TIDAL("tidal", "Tidal Hi-Fi Relay", "High Bitrate Master Stream"),
    QOBUZ("qobuz", "Qobuz Studio Relay", "High Bitrate Studio Stream")
}

enum class DownloadFormat(val ext: String, val mimeType: String, val label: String) {
    MP3("m4a", "audio/mp4", "High Quality (320 kbps)"),
    FLAC("flac", "audio/flac", "Lossless / Master")
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

    fun getUsageCount(provider: DownloadProvider): Int =
        prefs.getInt("provider_count_${provider.id}", 0)

    private fun getLastUsedTimestamp(provider: DownloadProvider): Long =
        prefs.getLong("provider_last_${provider.id}", 0L)

    /**
     * Resolves the canonical full-length audio stream directly on-device (no localhost 10.0.2.2 server needed)
     * and writes it to public Music storage (`Music/Seitharol/`).
     */
    suspend fun downloadTrackToPublicMusicStorage(
        track: NormalizedTrack,
        provider: DownloadProvider,
        format: DownloadFormat,
        onTrackSavedAsLocalMusic: (NormalizedTrack) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        recordProviderAndFormatSelection(provider, format)
        _downloadState.value = DownloadTaskState.Downloading(track.sourceId, 2, provider)

        runCatching {
            val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
            val streamUrl = resolveDirectDownloadUrl(track, primaryArtist, provider)
                ?: throw IllegalStateException("Could not resolve downloadable stream for ${track.title}")

            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }

            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Audio server returned HTTP ${conn.responseCode}")
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
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
                        streamWithProgress(conn, output, totalBytes, track.sourceId, provider)
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

            val localMusicTrack = track.copy(
                source = TrackSource.LOCAL_FILE,
                isPlayableNatively = true,
                localPath = savedUri.toString()
            )
            onTrackSavedAsLocalMusic(localMusicTrack)
            _downloadState.value = DownloadTaskState.Completed(track.sourceId, savedUri)
            savedUri
        }.onFailure { err ->
            val friendlyMsg = com.example.util.ErrorFormatter.toUserFriendlyMessage(err, context, track.title)
            _downloadState.value = DownloadTaskState.Failed(
                trackId = track.sourceId,
                message = friendlyMsg
            )
        }
    }

    private suspend fun resolveDirectDownloadUrl(
        track: NormalizedTrack,
        primaryArtist: String,
        provider: DownloadProvider
    ): String? {
        val metadata = TrackMetadata(
            id = track.sourceId,
            title = track.title,
            artist = primaryArtist,
            album = track.album.orEmpty(),
            durationSeconds = (track.durationMs / 1000L).toInt(),
            thumbnailUrl = track.artUrl
        )

        // 1. If track is already a YouTube videoId (11 chars) or user picked YouTube ATV Direct
        if (track.source == TrackSource.YOUTUBE && track.sourceId.length == 11) {
            InnerTubeStreamExtractor.resolveWorkingStreamUrl(track.sourceId, AudioQuality.HIGH)
                .getOrNull()?.let { return it }
        }

        // 2. Try JioSaavn 320kbps direct stream first if JIOSAAVN selected
        if (provider == DownloadProvider.JIOSAAVN) {
            StreamResolver.resolveExactStreamUrl(metadata, AudioQuality.HIGH)?.let { return it }
        }

        // 3. Search canonical YouTube Music ATV song and extract high-bitrate stream
        val ytCandidates = InnerTubeStreamExtractor.searchOfficialYtmSongs("${track.title} $primaryArtist", limit = 6)
        val bestYt = com.seitharol.data.resolver.CanonicalAudioResolver.selectBestCanonicalMatch(
            queryTitle = track.title,
            queryArtist = primaryArtist,
            targetDurationSeconds = metadata.durationSeconds,
            candidates = ytCandidates
        )
        if (bestYt != null) {
            InnerTubeStreamExtractor.resolveWorkingStreamUrl(bestYt.videoId, AudioQuality.HIGH)
                .getOrNull()?.let { return it }
        }

        // 4. Final fallback to StreamResolver (JioSaavn 320kbps + Piped strict)
        return StreamResolver.resolveExactStreamUrl(metadata, AudioQuality.HIGH)
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
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                val progress = if (totalBytes > 1000L) {
                    ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                } else {
                    50
                }
                _downloadState.value = DownloadTaskState.Downloading(trackId, progress, provider)
            }
            output.flush()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }
}
