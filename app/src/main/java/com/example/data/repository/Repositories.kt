package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.*
import com.example.data.model.*
import com.example.network.NetworkClients
import com.example.resolver.ResolverEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import java.io.FileOutputStream

class MusicRepository(
    private val db: CascadeDatabase
) {
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val checkpointDao = db.checkpointDao()
    private val sponsorDao = db.sponsorDao()
    private val historyDao = db.historyDao()

    private val fifteenDaysMillis = 15L * 24 * 60 * 60 * 1000L

    fun getAllTracks(): Flow<List<Track>> = trackDao.getAllTracks().map { list ->
        list.map { it.toDomain() }
    }

    fun getLikedTracks(): Flow<List<Track>> = trackDao.getLikedTracks().map { list ->
        list.map { it.toDomain() }
    }

    fun getDownloadedTracks(): Flow<List<Track>> = trackDao.getDownloadedTracks().map { list ->
        list.map { it.toDomain() }
    }

    fun searchTracks(query: String): Flow<List<Track>> = trackDao.searchTracks(query).map { list ->
        list.map { it.toDomain() }
    }

    fun getActiveHistory(): Flow<List<Track>> {
        val cutoff = System.currentTimeMillis() - fifteenDaysMillis
        return historyDao.getActiveHistory(cutoff).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun getArchivedHistory(): Flow<List<Track>> {
        val cutoff = System.currentTimeMillis() - fifteenDaysMillis
        return historyDao.getArchivedHistory(cutoff).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun recordPlayHistory(track: Track) = withContext(Dispatchers.IO) {
        // Cache track metadata in Room for offline access
        trackDao.insertTrack(track.toEntity())
        historyDao.insertHistory(
            PlayHistoryEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artUrl = track.artUrl,
                genre = track.genre,
                isLiked = track.isLiked,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getTrackById(id: String): Track? = withContext(Dispatchers.IO) {
        trackDao.getTrackById(id)?.toDomain()
    }

    suspend fun getTracksByMood(centerEnergy: Float, centerValence: Float, delta: Float = 0.35f): List<Track> =
        withContext(Dispatchers.IO) {
            val minE = (centerEnergy - delta).coerceAtLeast(0f)
            val maxE = (centerEnergy + delta).coerceAtMost(1f)
            val minV = (centerValence - delta).coerceAtLeast(0f)
            val maxV = (centerValence + delta).coerceAtMost(1f)
            trackDao.getTracksByMood(minE, maxE, minV, maxV).map { it.toDomain() }
        }

    suspend fun toggleLike(track: Track) = withContext(Dispatchers.IO) {
        val existing = trackDao.getTrackById(track.id)
        if (existing == null) {
            val newTrack = track.copy(isLiked = true)
            trackDao.insertTrack(newTrack.toEntity())
        } else {
            trackDao.setLiked(track.id, !existing.isLiked)
        }
    }

    suspend fun toggleLike(id: String, current: Boolean) = withContext(Dispatchers.IO) {
        trackDao.setLiked(id, !current)
    }

    fun getPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists().map { list ->
        list.map {
            Playlist(
                id = it.id,
                title = it.title,
                description = it.description,
                isLocal = it.isLocal,
                coverUrl = it.coverUrl,
                isLiked = it.isLiked
            )
        }
    }

    suspend fun togglePlaylistLike(playlistId: String, current: Boolean) = withContext(Dispatchers.IO) {
        playlistDao.setPlaylistLiked(playlistId, !current)
    }

    fun getTracksForPlaylist(playlistId: String): Flow<List<Track>> =
        playlistDao.getTracksForPlaylist(playlistId).map { list ->
            list.map { it.toDomain() }
        }

    suspend fun createPlaylist(title: String, description: String, coverUrl: String? = null): String =
        withContext(Dispatchers.IO) {
            val id = "pl_" + System.currentTimeMillis()
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    id = id,
                    title = title,
                    description = description,
                    coverUrl = coverUrl
                )
            )
            id
        }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String, position: Int = 0) =
        withContext(Dispatchers.IO) {
            playlistDao.insertPlaylistItems(
                listOf(PlaylistItemEntity(playlistId = playlistId, position = position, trackId = trackId))
            )
        }

    suspend fun getSponsorSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        sponsorDao.getSegmentsForVideo(videoId).map {
            val cat = when (it.category) {
                "SPONSOR" -> SegmentCategory.SPONSOR
                "INTRO" -> SegmentCategory.INTRO
                "OUTRO" -> SegmentCategory.OUTRO
                "INTERACTION" -> SegmentCategory.INTERACTION
                else -> SegmentCategory.SELF_PROMO
            }
            SponsorSegment(
                id = it.id,
                videoId = it.videoId,
                category = cat,
                startMs = it.startMs,
                endMs = it.endMs,
                autoSkip = it.autoSkip
            )
        }
    }

    suspend fun saveCheckpoint(
        trackId: String?,
        index: Int,
        posMs: Long,
        queueIds: List<String>,
        shuffle: Boolean,
        repeat: Int,
        crossfadeMs: Int = 0
    ) = withContext(Dispatchers.IO) {
        checkpointDao.saveCheckpoint(
            PlaybackCheckpointEntity(
                id = 1,
                currentTrackId = trackId,
                currentIndex = index,
                positionMs = posMs,
                queueIdsJson = queueIds.joinToString(","),
                shuffleMode = shuffle,
                repeatMode = repeat,
                crossfadeMs = crossfadeMs,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getCheckpoint(): PlaybackCheckpointEntity? = withContext(Dispatchers.IO) {
        checkpointDao.getCheckpoint()
    }

    suspend fun insertTrack(track: Track) = withContext(Dispatchers.IO) {
        trackDao.insertTrack(track.toEntity())
    }

    private fun TrackEntity.toDomain() = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artUrl = artUrl,
        isDownloaded = isDownloaded,
        localPath = localPath,
        isLiked = isLiked,
        lyrics = lyrics,
        genre = genre,
        moodX = moodX,
        moodY = moodY,
        loudnessGainDb = loudnessGainDb,
        audioUrl = audioUrl,
        itag = itag,
        codec = codec
    )

    private fun PlayHistoryEntity.toDomain() = Track(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artUrl = artUrl,
        genre = genre,
        isLiked = isLiked
    )

    private fun Track.toEntity() = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artUrl = artUrl,
        isDownloaded = isDownloaded,
        localPath = localPath,
        isLiked = isLiked,
        lyrics = lyrics,
        genre = genre,
        moodX = moodX,
        moodY = moodY,
        loudnessGainDb = loudnessGainDb,
        audioUrl = audioUrl,
        itag = itag,
        codec = codec
    )
}

class DownloadRepository(
    private val context: Context,
    private val db: CascadeDatabase,
    private val resolverEngine: ResolverEngine
) {
    private val downloadDao = db.downloadDao()
    private val trackDao = db.trackDao()
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
            val notifTitle = when {
                isFailed -> "Download Failed"
                isCompleted -> "Download Completed"
                else -> "Downloading $title"
            }
            val notifText = when {
                isFailed -> "$title • Tap to retry"
                isCompleted -> "$title is ready for offline playback"
                else -> "$progress%"
            }
            val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_splash_logo)
                .setContentTitle(notifTitle)
                .setContentText(notifText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!isCompleted && !isFailed)

            if (!isCompleted && !isFailed) {
                builder.setProgress(100, progress, false)
            } else {
                builder.setProgress(0, 0, false)
            }

            notificationManager?.notify(title.hashCode(), builder.build())
        } catch (e: Exception) {
            Log.w("DownloadRepository", "Notification error: ${e.message}")
        }
    }

    fun getAllDownloads(): Flow<List<DownloadItem>> = downloadDao.getAllDownloads().map { list ->
        list.map { entity ->
            val track = trackDao.getTrackById(entity.trackId)
            DownloadItem(
                trackId = entity.trackId,
                title = track?.title ?: "Track ${entity.trackId}",
                artist = track?.artist ?: "Unknown Artist",
                artUrl = track?.artUrl ?: "",
                tier = when (entity.tier) {
                    "Data Saver" -> DownloadTier.DATA_SAVER
                    "Standard" -> DownloadTier.STANDARD
                    "Original" -> DownloadTier.ORIGINAL
                    else -> DownloadTier.HIGH
                },
                progress = entity.progress,
                downloadedBytes = entity.bytesDownloaded,
                totalBytes = entity.totalBytes,
                state = when (entity.state) {
                    "DOWNLOADING" -> DownloadState.DOWNLOADING
                    "FINALIZING" -> DownloadState.FINALIZING
                    "COMPLETED" -> DownloadState.COMPLETED
                    "ERROR" -> DownloadState.ERROR
                    else -> DownloadState.QUEUED
                },
                localFilePath = entity.localPath
            )
        }
    }

    fun getTotalDownloadedBytes(): Flow<Long> = downloadDao.getTotalDownloadedBytes().map { it ?: 0L }

    suspend fun startDownload(track: Track, tier: DownloadTier) = withContext(Dispatchers.IO) {
        val downloadDir = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        val ext = if (tier.codec.contains("Opus", ignoreCase = true)) "opus" else "m4a"
        val destFile = File(downloadDir, "${track.id}_${tier.name.lowercase()}.$ext")
        val tempFile = File(downloadDir, "${track.id}_${tier.name.lowercase()}.tmp")

        try {
            // 1. Initial State in DB
            downloadDao.insertDownload(
                DownloadEntity(
                    trackId = track.id,
                    tier = tier.label,
                    state = "DOWNLOADING",
                    localPath = destFile.absolutePath,
                    progress = 0.05f,
                    bytesDownloaded = 0L,
                    totalBytes = 4_000_000L
                )
            )

            // 2. Resolve stream URL
            val resolvedStream = resolverEngine.resolveTrack(track)
            val streamUrl = resolvedStream.streamUrl

            if (streamUrl.isBlank()) {
                throw IllegalStateException("Empty stream URL resolved for ${track.title}")
            }

            // 3. Real HTTP byte-streaming using OkHttpClient
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} downloading track stream")
                }
                val body = response.body ?: throw IllegalStateException("Empty response body")
                val contentLength = body.contentLength().let { if (it > 0) it else 4_000_000L }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastProgressUpdate = System.currentTimeMillis()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 300L) {
                                lastProgressUpdate = now
                                val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0.05f, 0.95f)
                                downloadDao.updateDownload(
                                    DownloadEntity(
                                        trackId = track.id,
                                        tier = tier.label,
                                        state = "DOWNLOADING",
                                        localPath = destFile.absolutePath,
                                        progress = progress,
                                        bytesDownloaded = totalRead,
                                        totalBytes = contentLength
                                    )
                                )
                                updateDownloadNotification(track.title, (progress * 100).toInt())
                            }
                        }
                        output.flush()
                    }
                }

                // Verify binary audio stream (> 10KB to avoid broken file stubs)
                if (tempFile.exists() && tempFile.length() > 10_240L) {
                    if (destFile.exists()) destFile.delete()
                    val renamed = tempFile.renameTo(destFile)
                    val finalFile = if (renamed) destFile else tempFile

                    downloadDao.updateDownload(
                        DownloadEntity(
                            trackId = track.id,
                            tier = tier.label,
                            state = "COMPLETED",
                            localPath = finalFile.absolutePath,
                            progress = 1.0f,
                            bytesDownloaded = finalFile.length(),
                            totalBytes = finalFile.length()
                        )
                    )
                    trackDao.setDownloaded(track.id, true, finalFile.absolutePath)
                    updateDownloadNotification(track.title, 100, isCompleted = true)
                } else {
                    if (tempFile.exists()) tempFile.delete()
                    throw IllegalStateException("Downloaded file was corrupt or smaller than 10KB (${tempFile.length()} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadRepository", "Download failed for ${track.id}: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            if (destFile.exists() && destFile.length() <= 10_240L) destFile.delete()
            downloadDao.updateDownload(
                DownloadEntity(
                    trackId = track.id,
                    tier = tier.label,
                    state = "ERROR",
                    localPath = null,
                    progress = 0f,
                    bytesDownloaded = 0L,
                    totalBytes = 0L
                )
            )
            updateDownloadNotification(track.title, 0, isFailed = true)
        }
    }

    suspend fun retryDownload(trackId: String) = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId)
        if (track != null) {
            startDownload(
                Track(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isDownloaded = false,
                    localPath = null,
                    isLiked = track.isLiked,
                    genre = track.genre,
                    audioUrl = track.audioUrl,
                    itag = track.itag,
                    codec = track.codec
                ),
                DownloadTier.HIGH
            )
        }
    }

    suspend fun deleteDownload(trackId: String) = withContext(Dispatchers.IO) {
        val dl = downloadDao.getDownload(trackId)
        if (dl?.localPath != null) {
            val file = File(dl.localPath)
            if (file.exists()) file.delete()
        }
        downloadDao.deleteDownload(trackId)
        trackDao.setDownloaded(trackId, false, null)
    }
}
