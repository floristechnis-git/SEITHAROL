package com.example.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.*
import com.example.data.model.*
import com.example.network.NetworkClients
import com.example.resolver.ResolverEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

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
        list.map { it.toDomain() }.filterNot { it.isLocalOnlyTrack }
    }

    fun getLikedTracks(): Flow<List<Track>> = trackDao.getLikedTracks().map { list ->
        list.map { it.toDomain() }
    }

    fun getDownloadedTracks(): Flow<List<Track>> = trackDao.getDownloadedTracks().map { list ->
        list.map { it.toDomain() }
    }

    fun searchTracks(query: String): Flow<List<Track>> = trackDao.searchTracks(query).map { list ->
        list.map { it.toDomain() }.filterNot { it.isLocalOnlyTrack }
    }

    fun getActiveHistory(): Flow<List<Track>> {
        val cutoff = System.currentTimeMillis() - fifteenDaysMillis
        return historyDao.getActiveHistory(cutoff).map { list ->
            list.map { it.toDomain() }.filterNot { it.isLocalOnlyTrack }
        }
    }

    fun getArchivedHistory(): Flow<List<Track>> {
        val cutoff = System.currentTimeMillis() - fifteenDaysMillis
        return historyDao.getArchivedHistory(cutoff).map { list ->
            list.map { it.toDomain() }.filterNot { it.isLocalOnlyTrack }
        }
    }

    suspend fun recordPlayHistory(track: Track) = withContext(Dispatchers.IO) {
        // Never record local device songs/videos into history or Home feed seeds!
        if (track.isLocalOnlyTrack) return@withContext
        // Deduplicate previous entry for this track so most recently played song always moves to index 0 immediately
        runCatching { historyDao.deleteHistoryByTrackId(track.id) }

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
    }.flowOn(Dispatchers.IO)

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
        if (track.isLocalOnlyTrack) return@withContext
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
    private val appContext = context.applicationContext
    private val downloadDao = db.downloadDao()
    private val trackDao = db.trackDao()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadSemaphore = Semaphore(2)
    private val activeTrackTitles = ConcurrentHashMap<String, String>()
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val DOWNLOAD_CHANNEL_ID = "seitharol_download_channel"

    companion object {
        const val ACTION_CANCEL_TRACK_DOWNLOAD = "com.example.ACTION_CANCEL_TRACK_DOWNLOAD"
        const val EXTRA_TRACK_ID = "EXTRA_TRACK_ID"
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_TRACK_DOWNLOAD) {
                val trackId = intent.getStringExtra(EXTRA_TRACK_ID).orEmpty()
                if (trackId.isNotBlank()) {
                    cancelDownload(trackId)
                }
            }
        }
    }

    init {
        createDownloadNotificationChannel()
        registerCancelReceiver()
    }

    private fun registerCancelReceiver() {
        try {
            val filter = IntentFilter(ACTION_CANCEL_TRACK_DOWNLOAD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(cancelReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w("DownloadRepository", "Cancel receiver registration skipped: ${t.message}")
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

    private fun buildCancelPendingIntent(trackId: String, notifId: Int): PendingIntent {
        val cancelIntent = Intent(ACTION_CANCEL_TRACK_DOWNLOAD).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_TRACK_ID, trackId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            notifId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateDownloadNotification(
        trackId: String,
        title: String,
        progress: Int,
        isCompleted: Boolean = false,
        isFailed: Boolean = false
    ) {
        try {
            val notifId = title.hashCode()
            if (isCompleted || isFailed) {
                notificationManager?.cancel(notifId)

                val completedNotification = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_music_note)
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
            val cancelPending = buildCancelPendingIntent(trackId, notifId)
            val builder = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music_note)
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
                    "High", "Lossless / Master" -> DownloadTier.HIGH
                    "Original" -> DownloadTier.ORIGINAL
                    "MP4 Video" -> DownloadTier.VIDEO_MP4
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

    fun cancelDownload(trackId: String) {
        val discoverService = runCatching {
            com.seitharol.data.download.DownloadPreferencesAndService.getInstance(appContext)
        }.getOrNull()
        val canonicalId = discoverService?.canonicalTrackId(trackId) ?: trackId

        activeDownloadJobs.remove(trackId)?.cancel()
        activeDownloadJobs.remove(canonicalId)?.cancel()

        val savedTitle = activeTrackTitles.remove(trackId) ?: activeTrackTitles.remove(canonicalId)
        if (savedTitle != null) {
            notificationManager?.cancel(savedTitle.hashCode())
        }

        // Also cancel if this download was started by Discover's DownloadPreferencesAndService
        val currentDiscoverState = discoverService?.downloadState?.value
        if (currentDiscoverState is com.seitharol.data.download.DownloadTaskState.Downloading) {
            if (currentDiscoverState.trackId == trackId ||
                discoverService.canonicalTrackId(currentDiscoverState.trackId) == canonicalId
            ) {
                discoverService.cancelActiveDownload()
            }
        }

        repoScope.launch {
            runCatching {
                val trackObj = trackDao.getTrackById(trackId) ?: trackDao.getTrackById(canonicalId)
                trackObj?.title?.let { notificationManager?.cancel(it.hashCode()) }

                val downloadDir = File(appContext.filesDir, "downloads")
                if (downloadDir.exists()) {
                    downloadDir.listFiles()?.forEach { f ->
                        if ((f.name.startsWith("${trackId}_") || f.name.startsWith("${canonicalId}_")) && f.name.endsWith(".tmp")) {
                            f.delete()
                        }
                    }
                }
                downloadDao.deleteDownload(trackId)
                downloadDao.deleteDownload(canonicalId)
            }
        }
    }

    suspend fun startDownload(track: Track, tier: DownloadTier) = withContext(Dispatchers.IO) {
        enqueueDownload(track, tier).join()
    }

    fun enqueueDownload(track: Track, tier: DownloadTier = DownloadTier.STANDARD): Job {
        activeDownloadJobs[track.id]?.cancel()
        activeTrackTitles[track.id] = track.title
        val effectiveTier = tier
        val isVideoDownload = effectiveTier == DownloadTier.VIDEO_MP4
        val job = repoScope.launch {
            val downloadDir = File(appContext.filesDir, "downloads").apply { if (!exists()) mkdirs() }
            val ext = if (isVideoDownload) "mp4" else "m4a"
            val safeFileId = track.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "track_${track.title.hashCode()}" }
            val destFile = File(downloadDir, "${safeFileId}_${effectiveTier.name.lowercase()}.$ext")
            runCatching {
                val existing = trackDao.getTrackById(track.id)
                trackDao.insertTrack(
                    track.copy(
                        genre = if (isVideoDownload) "YouTube Video" else track.genre,
                        codec = if (isVideoDownload) "MP4" else track.codec,
                        isLiked = existing?.isLiked ?: track.isLiked,
                        isDownloaded = existing?.isDownloaded ?: false,
                        localPath = existing?.localPath ?: destFile.absolutePath
                    ).toEntity()
                )
                downloadDao.insertDownload(
                    DownloadEntity(
                        trackId = track.id,
                        tier = effectiveTier.label,
                        state = "QUEUED",
                        localPath = destFile.absolutePath,
                        progress = 0.01f,
                        bytesDownloaded = 0L,
                        totalBytes = if (isVideoDownload) 18_000_000L else 4_000_000L
                    )
                )
            }
            downloadSemaphore.withPermit {
                coroutineContext.ensureActive()
                executeDownload(track, effectiveTier)
            }
        }
        activeDownloadJobs[track.id] = job
        return job
    }

    fun enqueueDownloadAll(tracks: List<Track>, tier: DownloadTier = DownloadTier.STANDARD) {
        tracks.filter { !it.isDownloaded }.forEach { track ->
            enqueueDownload(track, tier)
        }
    }

    private suspend fun executeDownload(track: Track, tier: DownloadTier) = withContext(Dispatchers.IO) {
        val isVideo = tier == DownloadTier.VIDEO_MP4
        val downloadDir = File(appContext.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        val ext = if (isVideo) "mp4" else "m4a"
        val safeFileId = track.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "track_${track.title.hashCode()}" }
        val destFile = File(downloadDir, "${safeFileId}_${tier.name.lowercase()}.$ext")
        val tempFile = File(downloadDir, "${safeFileId}_${tier.name.lowercase()}.tmp")

        try {
            val existing = trackDao.getTrackById(track.id)
            trackDao.insertTrack(
                track.copy(
                    genre = if (isVideo) "YouTube Video" else track.genre,
                    codec = if (isVideo) "MP4" else track.codec,
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
                    totalBytes = if (isVideo) 18_000_000L else 4_000_000L
                )
            )
            updateDownloadNotification(track.id, track.title, 5)

            val candidateUrls = if (isVideo) {
                com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveDownloadableVideoCandidates(
                    videoId = track.id,
                    title = track.title,
                    artist = track.artist
                )
            } else {
                val quality = when (tier) {
                    DownloadTier.DATA_SAVER -> com.seitharol.data.resolver.AudioQuality.LOW
                    DownloadTier.HIGH, DownloadTier.ORIGINAL -> com.seitharol.data.resolver.AudioQuality.HIGH
                    else -> com.seitharol.data.resolver.AudioQuality.NORMAL
                }
                val fastCandidates = com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveDownloadableStreamCandidates(
                    videoId = track.id,
                    title = track.title,
                    artist = track.artist,
                    preferredQuality = quality
                )
                if (fastCandidates.isNotEmpty()) {
                    fastCandidates
                } else {
                    buildList {
                        runCatching {
                            val resolved = resolverEngine.resolveTrack(track, quality, forceRefresh = true).streamUrl
                            if (resolved.isNotBlank() && !resolved.contains(".m3u8", true)) {
                                add(resolved)
                            }
                        }
                    }
                }
            }

            if (candidateUrls.isEmpty()) {
                throw IllegalStateException("Could not resolve downloadable ${if (isVideo) "MP4 video" else "audio"} stream for ${track.title}")
            }

            var downloadedOk = false
            var lastProgressUpdate = System.currentTimeMillis()
            for (candidateUrl in candidateUrls) {
                coroutineContext.ensureActive()
                val ok = com.seitharol.data.resolver.InnerTubeStreamExtractor.downloadUrlToFileReliable(
                    streamUrl = candidateUrl,
                    tempFile = tempFile
                ) { totalRead, contentLength ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 300L) {
                        lastProgressUpdate = now
                        val progress = (totalRead.toFloat() / contentLength.coerceAtLeast(1L).toFloat()).coerceIn(0.05f, 0.95f)
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
                        updateDownloadNotification(track.id, track.title, (progress * 100).toInt())
                    }
                }
                if (ok && tempFile.exists() && tempFile.length() > 10_240L) {
                    downloadedOk = true
                    break
                }
            }

            if (downloadedOk && tempFile.exists() && tempFile.length() > 10_240L) {
                if (destFile.exists()) destFile.delete()
                val renamed = tempFile.renameTo(destFile)
                val finalFile = if (renamed) destFile else tempFile

                // Export directly to public device storage so user can open/play from File Explorer or Device Scanner
                runCatching {
                    exportMediaFileToPublicDeviceStorage(finalFile, track, isVideo)
                }

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
                    genre = if (isVideo) "YouTube Video" else track.genre,
                    album = track.album.takeUnless { it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true) }.orEmpty(),
                    codec = if (isVideo) "MP4" else track.codec,
                    isLiked = currentDb?.isLiked ?: track.isLiked,
                    isDownloaded = true,
                    localPath = finalFile.absolutePath
                )
                trackDao.insertTrack(completedTrack.toEntity())
                trackDao.setDownloaded(track.id, true, finalFile.absolutePath)
                activeDownloadJobs.remove(track.id)
                activeTrackTitles.remove(track.id)
                updateDownloadNotification(track.id, track.title, 100, isCompleted = true)
            } else {
                if (tempFile.exists()) tempFile.delete()
                throw IllegalStateException("Unable to download ${if (isVideo) "MP4 video" else "audio"} stream for ${track.title}")
            }
        } catch (e: CancellationException) {
            activeDownloadJobs.remove(track.id)
            activeTrackTitles.remove(track.id)
            if (tempFile.exists()) tempFile.delete()
            notificationManager?.cancel(track.title.hashCode())
            downloadDao.deleteDownload(track.id)
            throw e
        } catch (e: Exception) {
            activeDownloadJobs.remove(track.id)
            activeTrackTitles.remove(track.id)
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
            updateDownloadNotification(track.id, track.title, 0, isFailed = true)
        }
    }

    private fun exportMediaFileToPublicDeviceStorage(sourceFile: File, track: Track, isVideo: Boolean) {
        val cleanTitle = track.title.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { track.id }
        val cleanArtist = track.artist.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "Seitharol" }
        val ext = if (isVideo) "mp4" else sourceFile.extension.ifBlank { "m4a" }
        val fileName = "$cleanArtist - $cleanTitle.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            if (isVideo) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.TITLE, track.title)
                    put(android.provider.MediaStore.Video.Media.ARTIST, track.artist)
                    put(android.provider.MediaStore.Video.Media.ALBUM, track.album.takeUnless { it.isBlank() || it.equals("YouTube Video", ignoreCase = true) } ?: "Seitharol")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Seitharol")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (_: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            } else {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(android.provider.MediaStore.Audio.Media.TITLE, track.title)
                    put(android.provider.MediaStore.Audio.Media.ARTIST, track.artist)
                    put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Seitharol")
                    put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (_: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        } else {
            val baseDir = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            )
            val targetDir = File(baseDir, "Seitharol").apply { if (!exists()) mkdirs() }
            val dest = File(targetDir, fileName)
            sourceFile.inputStream().use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            android.media.MediaScannerConnection.scanFile(
                appContext,
                arrayOf(dest.absolutePath),
                arrayOf(if (isVideo) "video/mp4" else "audio/mp4"),
                null
            )
        }
    }

    suspend fun retryDownload(trackId: String) = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId)
        val existingDl = downloadDao.getDownload(trackId)
        val tier = when (existingDl?.tier) {
            "Data Saver" -> DownloadTier.DATA_SAVER
            "High" -> DownloadTier.HIGH
            "Original" -> DownloadTier.ORIGINAL
            "MP4 Video" -> DownloadTier.VIDEO_MP4
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
        cancelDownload(trackId)
        val discoverService = runCatching {
            com.seitharol.data.download.DownloadPreferencesAndService.getInstance(appContext)
        }.getOrNull()
        val canonicalId = discoverService?.canonicalTrackId(trackId) ?: trackId
        val trackObj = trackDao.getTrackById(trackId) ?: trackDao.getTrackById(canonicalId)
        val dl = downloadDao.getDownload(trackId) ?: downloadDao.getDownload(canonicalId)

        listOfNotNull(dl?.localPath, trackObj?.localPath).distinct().forEach { path ->
            if (path.startsWith("content://")) {
                runCatching { appContext.contentResolver.delete(android.net.Uri.parse(path), null, null) }
            } else {
                val file = File(path.removePrefix("file://"))
                if (file.exists()) file.delete()
            }
        }

        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Seitharol"
        )
        if (publicDir.exists() && trackObj != null && trackObj.title.isNotBlank()) {
            publicDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.contains(trackObj.title, ignoreCase = true) || f.name.contains(canonicalId, ignoreCase = true))) {
                    runCatching { f.delete() }
                }
            }
        }

        downloadDao.deleteDownload(trackId)
        downloadDao.deleteDownload(canonicalId)
        trackDao.setDownloaded(trackId, false, null)
        trackDao.setDownloaded(canonicalId, false, null)

        discoverService?.removeCompletedDownload(trackId, trackObj?.title)
        if (canonicalId != trackId) {
            discoverService?.removeCompletedDownload(canonicalId, trackObj?.title)
        }
    }

    suspend fun rescanLocalDownloads() = withContext(Dispatchers.IO) {
        try {
            val downloadDir = File(appContext.filesDir, "downloads")

            val directoriesToScan = listOfNotNull(
                downloadDir.takeIf { it.exists() }
            )

            val knownSuffixes = listOf(
                "_video_mp4" to "MP4 Video",
                "_data_saver" to "Data Saver",
                "_standard" to "Standard",
                "_original" to "Original",
                "_high" to "High",
                "_discover" to "High"
            )

            for (dir in directoriesToScan) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (file.name.endsWith(".tmp")) {
                        // Clean up stale partial .tmp raw files older than 30 minutes
                        if (System.currentTimeMillis() - file.lastModified() > 30 * 60 * 1000L) {
                            runCatching { file.delete() }
                        }
                        continue
                    }
                    if (file.isFile && file.length() > 10_240L) {
                        val baseName = file.nameWithoutExtension
                        val matchedSuffix = knownSuffixes.firstOrNull { (sfx, _) ->
                            baseName.endsWith(sfx, ignoreCase = true)
                        }
                        val trackId = if (matchedSuffix != null) {
                            baseName.dropLast(matchedSuffix.first.length)
                        } else {
                            baseName.substringBeforeLast("_", baseName)
                        }
                        val tierLabel = matchedSuffix?.second
                            ?: if (file.extension.equals("mp4", ignoreCase = true)) "MP4 Video" else "Standard"

                        // Clean up any legacy corrupted ghost entries ("<id>_video" / "<id>_data")
                        if (matchedSuffix != null) {
                            val ghostId = baseName.substringBeforeLast("_", "")
                            if (ghostId.isNotBlank() && ghostId != trackId) {
                                runCatching { downloadDao.deleteDownload(ghostId) }
                                runCatching { trackDao.setDownloaded(ghostId, false, null) }
                            }
                        }

                        val existingTrack = trackDao.getTrackById(trackId)
                        if (existingTrack != null) {
                            if (!existingTrack.isDownloaded || existingTrack.localPath.isNullOrBlank()) {
                                trackDao.setDownloaded(trackId, true, file.absolutePath)
                            }
                            val existingDl = downloadDao.getDownload(trackId)
                            if (existingDl == null || existingDl.state != "COMPLETED") {
                                downloadDao.insertDownload(
                                    DownloadEntity(
                                        trackId = trackId,
                                        tier = tierLabel,
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
            }

            val discoverService = runCatching {
                com.seitharol.data.download.DownloadPreferencesAndService.getInstance(appContext)
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
                                album = norm.album?.takeUnless { it.equals("Discover Downloads", ignoreCase = true) || it.equals("YouTube Video", ignoreCase = true) }.orEmpty(),
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
