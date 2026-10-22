package com.example.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
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
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000L
        val existing = historyDao.getRecentHistoryForTrack(track.id, fiveMinutesAgo)
        if (existing != null) return@withContext

        // Preserve existing isLiked / isDownloaded flags if already in Room
        val existingTrack = trackDao.getTrackById(track.id)
        val mergedTrack = track.copy(
            isLiked = existingTrack?.isLiked ?: track.isLiked,
            isDownloaded = existingTrack?.isDownloaded ?: track.isDownloaded,
            localPath = existingTrack?.localPath ?: track.localPath
        )
        trackDao.insertTrack(mergedTrack.toEntity())
        historyDao.insertHistory(
            PlayHistoryEntity(
                trackId = mergedTrack.id,
                title = mergedTrack.title,
                artist = mergedTrack.artist,
                album = mergedTrack.album,
                durationMs = mergedTrack.durationMs,
                artUrl = mergedTrack.artUrl,
                genre = mergedTrack.genre,
                isLiked = mergedTrack.isLiked,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteTrackFromHistory(trackId: String) = withContext(Dispatchers.IO) {
        historyDao.deleteHistoryByTrackId(trackId)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
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
            val count = runCatching { playlistDao.getPlaylistTrackCount(it.id) }.getOrDefault(0)
            Playlist(
                id = it.id,
                title = it.title,
                description = it.description,
                isLocal = it.isLocal,
                trackCount = count,
                coverUrl = it.coverUrl,
                isLiked = it.isLiked
            )
        }
    }

    suspend fun togglePlaylistLike(playlist: Playlist) = withContext(Dispatchers.IO) {
        val existing = playlistDao.getPlaylistById(playlist.id)
        if (existing == null) {
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    id = playlist.id,
                    title = playlist.title,
                    description = playlist.description,
                    isLocal = playlist.isLocal,
                    coverUrl = playlist.coverUrl,
                    isLiked = true
                )
            )
        } else {
            playlistDao.setPlaylistLiked(playlist.id, !existing.isLiked)
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

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String, position: Int? = null) =
        withContext(Dispatchers.IO) {
            if (playlistDao.isTrackInPlaylist(playlistId, trackId) == 0) {
                val nextPos = position ?: (playlistDao.getMaxPositionForPlaylist(playlistId) + 1)
                playlistDao.insertPlaylistItems(
                    listOf(PlaylistItemEntity(playlistId = playlistId, position = nextPos, trackId = trackId))
                )
            }
            val track = trackDao.getTrackById(trackId)
            val pl = playlistDao.getPlaylistById(playlistId)
            if (pl != null) {
                val updatedCover = if (pl.coverUrl.isNullOrBlank() && track != null && track.artUrl.isNotBlank()) {
                    track.artUrl
                } else {
                    pl.coverUrl
                }
                // Touch playlist entity so Room's getAllPlaylists() Flow emits immediately with updated trackCount!
                playlistDao.insertPlaylist(pl.copy(coverUrl = updatedCover))
            }
        }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        playlistDao.deleteAllItemsForPlaylist(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
        playlistDao.getPlaylistById(playlistId)?.let { pl ->
            playlistDao.insertPlaylist(pl)
        }
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
        val existing = trackDao.getTrackById(track.id)
        val merged = if (existing != null) {
            track.copy(
                isLiked = existing.isLiked || track.isLiked,
                isDownloaded = existing.isDownloaded || track.isDownloaded,
                localPath = track.localPath ?: existing.localPath
            )
        } else {
            track
        }
        trackDao.insertTrack(merged.toEntity())
    }

    suspend fun insertTracks(tracks: List<Track>) = withContext(Dispatchers.IO) {
        tracks.forEach { insertTrack(it) }
    }
}

fun TrackEntity.toDomain() = Track(
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

fun PlayHistoryEntity.toDomain() = Track(
    id = trackId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artUrl = artUrl,
    genre = genre,
    isLiked = isLiked
)

fun Track.toEntity() = TrackEntity(
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
            val notifId = title.hashCode()
            if (isCompleted || isFailed) {
                notificationManager?.cancel(notifId)

                val completedNotification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_music_note)
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
                .setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle("Downloading $title")
                .setContentText("$clamped%")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, clamped, false)

            notificationManager?.notify(notifId, builder.build())
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
                    "High" -> DownloadTier.HIGH
                    "Original" -> DownloadTier.ORIGINAL
                    else -> DownloadTier.STANDARD
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
            val existing = trackDao.getTrackById(track.id)
            trackDao.insertTrack(
                track.copy(
                    isLiked = existing?.isLiked ?: track.isLiked,
                    isDownloaded = false,
                    localPath = destFile.absolutePath
                ).toEntity()
            )
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

            val resolvedStream = resolverEngine.resolveTrack(track)
            val streamUrl = resolvedStream.streamUrl

            if (streamUrl.isBlank()) {
                throw IllegalStateException("Empty stream URL resolved for ${track.title}")
            }

            // Use client-matched headers so GoogleVideo CDN never rejects download with HTTP 403
            val clientHeaders = com.seitharol.player.OuterTuneResolvingDataSource.resolveClientHeadersForUrl(streamUrl)
            val requestBuilder = Request.Builder().url(streamUrl)
            clientHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
            val request = requestBuilder.build()

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
                    val currentDb = trackDao.getTrackById(track.id)
                    val completedTrack = track.copy(
                        isLiked = currentDb?.isLiked ?: track.isLiked,
                        isDownloaded = true,
                        localPath = finalFile.absolutePath
                    )
                    trackDao.insertTrack(completedTrack.toEntity())
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
        val existingDl = downloadDao.getDownload(trackId)
        val tier = when (existingDl?.tier) {
            "Data Saver" -> DownloadTier.DATA_SAVER
            "High" -> DownloadTier.HIGH
            "Original" -> DownloadTier.ORIGINAL
            else -> DownloadTier.STANDARD
        }
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
                tier
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

    suspend fun rescanLocalDownloads() = withContext(Dispatchers.IO) {
        try {
            val downloadDir = File(context.filesDir, "downloads")
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Seitharol"
            )

            val directoriesToScan = listOfNotNull(
                downloadDir.takeIf { it.exists() },
                publicDir.takeIf { it.exists() }
            )

            for (dir in directoriesToScan) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (file.isFile && file.length() > 10_240L && !file.name.endsWith(".tmp")) {
                        val baseName = file.nameWithoutExtension
                        val trackId = baseName.substringBeforeLast("_", baseName)
                        val tierName = baseName.substringAfterLast("_", "standard")

                        val existingTrack = trackDao.getTrackById(trackId)
                        if (existingTrack == null) {
                            val cleanTitle = trackId.replace("_", " ").replaceFirstChar { it.uppercase() }
                            val newEntity = TrackEntity(
                                id = trackId,
                                title = cleanTitle,
                                artist = "Downloaded Music",
                                album = "Offline Storage",
                                durationMs = 0,
                                artUrl = "",
                                isDownloaded = true,
                                localPath = file.absolutePath
                            )
                            trackDao.insertTrack(newEntity)
                        } else if (!existingTrack.isDownloaded || existingTrack.localPath.isNullOrBlank()) {
                            trackDao.setDownloaded(trackId, true, file.absolutePath)
                        }

                        val existingDl = downloadDao.getDownload(trackId)
                        if (existingDl == null || existingDl.state != "COMPLETED") {
                            downloadDao.insertDownload(
                                DownloadEntity(
                                    trackId = trackId,
                                    tier = tierName.replaceFirstChar { it.uppercase() },
                                    state = "COMPLETED",
                                    localPath = file.absolutePath,
                                    progress = 1.0f,
                                    bytesDownloaded = file.length(),
                                    totalBytes = file.length()
                                )
                            )
                        }
                    }
                }
            }

            val discoverService = runCatching {
                com.seitharol.data.download.DownloadPreferencesAndService.getInstance(context)
            }.getOrNull()
            discoverService?.completedDownloads?.value?.forEach { norm ->
                if (!norm.localPath.isNullOrBlank()) {
                    val existing = trackDao.getTrackById(norm.sourceId)
                    if (existing == null) {
                        trackDao.insertTrack(
                            TrackEntity(
                                id = norm.sourceId,
                                title = norm.title,
                                artist = norm.artists.firstOrNull() ?: "Unknown Artist",
                                album = norm.album ?: "Discover Downloads",
                                durationMs = norm.durationMs,
                                artUrl = norm.artUrl.orEmpty(),
                                isDownloaded = true,
                                localPath = norm.localPath
                            )
                        )
                    } else if (!existing.isDownloaded || existing.localPath.isNullOrBlank()) {
                        trackDao.setDownloaded(norm.sourceId, true, norm.localPath)
                    }

                    val dl = downloadDao.getDownload(norm.sourceId)
                    if (dl == null || dl.state != "COMPLETED") {
                        downloadDao.insertDownload(
                            DownloadEntity(
                                trackId = norm.sourceId,
                                tier = "High",
                                state = "COMPLETED",
                                localPath = norm.localPath,
                                progress = 1.0f,
                                bytesDownloaded = 100_000L,
                                totalBytes = 100_000L
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DownloadRepository", "rescanLocalDownloads error: ${e.message}")
        }
    }
}
