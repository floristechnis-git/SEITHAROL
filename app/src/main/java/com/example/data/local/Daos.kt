package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY addedAt DESC")
    fun getLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 ORDER BY addedAt DESC")
    fun getDownloadedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("""
        SELECT * FROM tracks 
        WHERE title LIKE '%' || :query || '%' 
           OR artist LIKE '%' || :query || '%' 
           OR album LIKE '%' || :query || '%' 
           OR genre LIKE '%' || :query || '%'
        ORDER BY addedAt DESC
    """)
    fun searchTracks(query: String): Flow<List<TrackEntity>>

    @Query("""
        SELECT * FROM tracks 
        WHERE (moodX BETWEEN :minEnergy AND :maxEnergy) 
          AND (moodY BETWEEN :minValence AND :maxValence)
    """)
    suspend fun getTracksByMood(
        minEnergy: Float,
        maxEnergy: Float,
        minValence: Float,
        maxValence: Float
    ): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE id = :id")
    suspend fun setLiked(id: String, isLiked: Boolean)

    @Query("UPDATE tracks SET isDownloaded = :isDownloaded, localPath = :localPath WHERE id = :id")
    suspend fun setDownloaded(id: String, isDownloaded: Boolean, localPath: String?)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getCount(): Int
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET isLiked = :isLiked WHERE id = :id")
    suspend fun setPlaylistLiked(id: String, isLiked: Boolean)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_items pi ON t.id = pi.trackId
        WHERE pi.playlistId = :playlistId
        ORDER BY pi.position ASC
    """)
    fun getTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    suspend fun getDownload(trackId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Update
    suspend fun updateDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)

    @Query("SELECT SUM(bytesDownloaded) FROM downloads WHERE state = 'COMPLETED'")
    fun getTotalDownloadedBytes(): Flow<Long?>
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM playback_checkpoint WHERE id = 1 LIMIT 1")
    suspend fun getCheckpoint(): PlaybackCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: PlaybackCheckpointEntity)
}

@Dao
interface SponsorDao {
    @Query("SELECT * FROM sponsor_segments WHERE videoId = :videoId ORDER BY startMs ASC")
    suspend fun getSegmentsForVideo(videoId: String): List<SponsorSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SponsorSegmentEntity>)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM play_history WHERE playedAt >= :sinceTimestamp ORDER BY playedAt DESC")
    fun getActiveHistory(sinceTimestamp: Long): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history WHERE playedAt < :sinceTimestamp ORDER BY playedAt DESC")
    fun getArchivedHistory(sinceTimestamp: Long): Flow<List<PlayHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: PlayHistoryEntity)

    /** Returns the most-recent history row for [trackId] played after [sinceTimestamp], or null. */
    @Query("SELECT * FROM play_history WHERE trackId = :trackId AND playedAt >= :sinceTimestamp ORDER BY playedAt DESC LIMIT 1")
    suspend fun getRecentHistoryForTrack(trackId: String, sinceTimestamp: Long): PlayHistoryEntity?

    @Query("DELETE FROM play_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM play_history WHERE trackId = :trackId")
    suspend fun deleteHistoryByTrackId(trackId: String)

    @Query("DELETE FROM play_history")
    suspend fun clearHistory()
}
