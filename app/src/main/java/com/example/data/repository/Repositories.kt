package com.example.data.repository

import android.content.Context
import com.example.data.local.*
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(
    private val db: CascadeDatabase
) {
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val checkpointDao = db.checkpointDao()
    private val sponsorDao = db.sponsorDao()

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
                coverUrl = it.coverUrl
            )
        }
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
    private val db: CascadeDatabase
) {
    private val downloadDao = db.downloadDao()
    private val trackDao = db.trackDao()

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
        val ext = if (tier.codec.contains("Opus")) "opus" else "m4a"
        val destFile = File(downloadDir, "${track.id}_${tier.name.lowercase()}.$ext")

        // Insert queued state
        downloadDao.insertDownload(
            DownloadEntity(
                trackId = track.id,
                tier = tier.label,
                state = "DOWNLOADING",
                localPath = destFile.absolutePath,
                progress = 0.2f,
                bytesDownloaded = 800_000L,
                totalBytes = 4_100_000L
            )
        )

        // Simulate fast progressive download write (or actual local file creation)
        if (!destFile.exists()) {
            destFile.writeText("CASCADE_ENCODED_AUDIO_CONTAINER_${track.id}_${tier.bitrate}")
        }

        kotlinx.coroutines.delay(400)
        downloadDao.updateDownload(
            DownloadEntity(
                trackId = track.id,
                tier = tier.label,
                state = "FINALIZING",
                localPath = destFile.absolutePath,
                progress = 0.85f,
                bytesDownloaded = 3_500_000L,
                totalBytes = 4_100_000L
            )
        )

        kotlinx.coroutines.delay(300)
        downloadDao.updateDownload(
            DownloadEntity(
                trackId = track.id,
                tier = tier.label,
                state = "COMPLETED",
                localPath = destFile.absolutePath,
                progress = 1.0f,
                bytesDownloaded = 4_100_000L,
                totalBytes = 4_100_000L
            )
        )

        // Update track table
        trackDao.setDownloaded(track.id, true, destFile.absolutePath)
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
