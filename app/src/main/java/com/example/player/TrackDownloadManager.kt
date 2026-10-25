package com.example.player

import android.content.Context
import android.net.Uri
import com.example.data.model.Track
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

enum class DownloadStatus {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED
}

class TrackDownloadManager(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val httpClient: OkHttpClient = com.example.network.NetworkClients.okHttpClient
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
        val safeId = trackId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val candidateM4a = File(downloadsDir, "${safeId}_standard.m4a")
        if (candidateM4a.exists() && candidateM4a.length() > 10_240L) return candidateM4a
        val candidateHighM4a = File(downloadsDir, "${safeId}_high.m4a")
        if (candidateHighM4a.exists() && candidateHighM4a.length() > 10_240L) return candidateHighM4a
        val candidateAudio = File(downloadsDir, "$safeId.audio")
        if (candidateAudio.exists() && candidateAudio.length() > 10_240L) return candidateAudio
        val candidateOpus = File(downloadsDir, "${trackId}_high.opus")
        if (candidateOpus.exists() && candidateOpus.length() > 10_240L) return candidateOpus
        return candidateM4a
    }

    fun getPlayableUri(track: Track): Uri {
        return if (isDownloaded(track.id)) {
            Uri.fromFile(getFileForTrack(track.id))
        } else if (!track.localPath.isNullOrBlank() && File(track.localPath).exists()) {
            Uri.fromFile(File(track.localPath))
        } else {
            Uri.parse(track.audioUrl.ifBlank { "resolver://track/${track.id}" })
        }
    }

    suspend fun downloadTrack(track: Track, resolvedStreamUrl: String) = withContext(Dispatchers.IO) {
        val trackId = track.id
        _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.DOWNLOADING)

        try {
            val targetFile = getFileForTrack(trackId)
            val tempFile = File(downloadsDir, "${targetFile.name}.tmp")
            val ok = InnerTubeStreamExtractor.downloadUrlToFileReliable(
                streamUrl = resolvedStreamUrl,
                tempFile = tempFile
            )
            if (ok && tempFile.exists() && tempFile.length() > 10_240L) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.DOWNLOADED)
            } else {
                tempFile.delete()
                throw IllegalStateException("Downloaded file is empty or corrupted")
            }
        } catch (e: Exception) {
            _downloadStates.value = _downloadStates.value + (trackId to DownloadStatus.FAILED)
        }
    }
}
