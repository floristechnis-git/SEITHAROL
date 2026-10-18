package com.example.player

import android.content.Context
import android.net.Uri
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

enum class DownloadStatus {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED
}

class TrackDownloadManager(
    private val context: Context,
    private val httpClient: OkHttpClient = com.example.network.NetworkClients.okHttpClient
) {
    private val downloadsDir: File = File(context.filesDir, "downloads").apply { mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadStatus>> = _downloadStates.asStateFlow()

    fun getDownloadStatus(trackId: String): DownloadStatus {
        return if (isDownloaded(trackId)) DownloadStatus.DOWNLOADED 
        else _downloadStates.value[trackId] ?: DownloadStatus.NOT_DOWNLOADED
    }

    fun isDownloaded(trackId: String): Boolean {
        val file = getFileForTrack(trackId)
        return file.exists() && file.length() > 10_240L
    }

    fun getFileForTrack(trackId: String): File {
        // Sanitize track ID for filename safety
        val safeId = trackId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val candidateAudio = File(downloadsDir, "$safeId.audio")
        if (candidateAudio.exists() && candidateAudio.length() > 10_240L) return candidateAudio
        val candidateOpus = File(downloadsDir, "${trackId}_high.opus")
        if (candidateOpus.exists() && candidateOpus.length() > 10_240L) return candidateOpus
        val candidateM4a = File(downloadsDir, "${trackId}_high.m4a")
        if (candidateM4a.exists() && candidateM4a.length() > 10_240L) return candidateM4a
        return candidateAudio
    }

    fun getPlayableUri(track: Track): Uri {
        return if (isDownloaded(track.id)) {
            // Play directly from local offline file storage
            Uri.fromFile(getFileForTrack(track.id))
        } else if (!track.localPath.isNullOrBlank() && File(track.localPath).exists()) {
            Uri.fromFile(File(track.localPath))
        } else {
            // Live streaming / stream resolver URI
            Uri.parse(track.audioUrl.ifBlank { "resolver://track/${track.id}" })
        }
    }

    suspend fun downloadTrack(track: Track, resolvedStreamUrl: String) = withContext(Dispatchers.IO) {
        val trackId = track.id
        _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.DOWNLOADING)

        try {
            val request = Request.Builder()
                .url(resolvedStreamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed with HTTP ${response.code}")
                
                val body = response.body ?: throw IllegalStateException("Empty response body")
                val targetFile = getFileForTrack(trackId)
                val tempFile = File(downloadsDir, "${targetFile.name}.tmp")

                FileOutputStream(tempFile).use { outputStream ->
                    body.byteStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (tempFile.exists() && tempFile.length() > 10_240L) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                } else {
                    tempFile.delete()
                    throw IllegalStateException("Downloaded file is empty or corrupted")
                }
            }
            _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.DOWNLOADED)
        } catch (e: Exception) {
            _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.FAILED)
        }
    }

    fun deleteDownloadedTrack(trackId: String) {
        val file = getFileForTrack(trackId)
        if (file.exists()) {
            file.delete()
        }
        _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.NOT_DOWNLOADED)
    }
}
