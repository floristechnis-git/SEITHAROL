package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val isLiked: Boolean = false,
    val lyrics: String = "",
    val genre: String = "Pop",
    val moodX: Float = 0.5f,
    val moodY: Float = 0.5f,
    val loudnessGainDb: Float = 0f,
    val audioUrl: String = "",
    val itag: Int = 251,
    val codec: String = "Opus",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val isLocal: Boolean = true,
    val coverUrl: String? = null,
    val isLiked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "position"]
)
data class PlaylistItemEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val tier: String,
    val state: String,
    val localPath: String?,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playback_checkpoint")
data class PlaybackCheckpointEntity(
    @PrimaryKey val id: Int = 1,
    val currentTrackId: String?,
    val currentIndex: Int,
    val positionMs: Long,
    val queueIdsJson: String,
    val shuffleMode: Boolean,
    val repeatMode: Int,
    val crossfadeMs: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sponsor_segments")
data class SponsorSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    val autoSkip: Boolean = true
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String,
    val genre: String = "Pop",
    val isLiked: Boolean = false,
    val playedAt: Long = System.currentTimeMillis()
)
