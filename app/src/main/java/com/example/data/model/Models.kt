package com.example.data.model

data class Track(
    val id: String, // YouTube videoId or local ID
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artUrl: String = "",
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val isLiked: Boolean = false,
    val lyrics: String = "",
    val genre: String = "Pop",
    val moodX: Float = 0.5f, // Energy: 0.0 (calm) to 1.0 (energetic)
    val moodY: Float = 0.5f, // Valence: 0.0 (sad/melancholic) to 1.0 (happy/positive)
    val loudnessGainDb: Float = 0f,
    val audioUrl: String = "",
    val itag: Int = 251, // Default 251 = Opus 160kbps
    val codec: String = "Opus"
) {
    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val isVideoTrack: Boolean
        get() = genre.equals("YouTube Video", ignoreCase = true) ||
            genre.equals("Local Video", ignoreCase = true) ||
            album.equals("YouTube Video", ignoreCase = true) ||
            codec.equals("MP4", ignoreCase = true) ||
            localPath?.lowercase()?.endsWith(".mp4") == true
}

data class LyricsLine(
    val timestampMs: Long,
    val text: String,
    val translation: String? = null,
    val isInstrumental: Boolean = false
)

enum class DownloadTier(val label: String, val bitrate: String, val codec: String) {
    DATA_SAVER("Data Saver", "64 kbps", "Opus"),
    STANDARD("Standard", "128 kbps", "AAC"),
    HIGH("High", "160 kbps", "Opus"),
    ORIGINAL("Original", "256 kbps", "AAC"),
    VIDEO_MP4("MP4 Video", "720p / 360p", "MP4")
}

enum class DownloadState {
    IDLE,
    QUEUED,
    DOWNLOADING,
    FINALIZING,
    COMPLETED,
    ERROR
}

data class DownloadItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val artUrl: String,
    val tier: DownloadTier = DownloadTier.STANDARD,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val state: DownloadState = DownloadState.IDLE,
    val localFilePath: String? = null,
    val errorMessage: String? = null
)

data class Playlist(
    val id: String,
    val title: String,
    val description: String,
    val isLocal: Boolean = true,
    val trackCount: Int = 0,
    val coverUrl: String? = null,
    val isLiked: Boolean = false
)

enum class SegmentCategory(val displayName: String, val colorHex: Long) {
    SPONSOR("Sponsor Segment", 0xFFFFB300),
    SELF_PROMO("Self Promotion", 0xFFFF5E62),
    INTERACTION("Interaction Reminder", 0xFF2979FF),
    INTRO("Intro / Off-topic", 0xFF00E676),
    OUTRO("Endcard / Outro", 0xFFAB47BC)
}

data class SponsorSegment(
    val id: Long = 0,
    val videoId: String,
    val category: SegmentCategory,
    val startMs: Long,
    val endMs: Long,
    val autoSkip: Boolean = true
)

enum class TrustZone {
    ZONE_1_DEVICE,
    ZONE_2_OWN_RESOLVER,
    ZONE_3_COMMUNITY_RESOLVER
}

data class ResolverNode(
    val id: String,
    val name: String,
    val url: String,
    val trustZone: TrustZone,
    val pingMs: Int,
    val isOnline: Boolean,
    val isSelected: Boolean = false,
    val successRate: Float = 0.99f
)

data class Artist(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val thumbnailUrl: String = avatarUrl,
    val subscriberCount: String = "",
    val bannerUrl: String? = null,
    val bio: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<com.example.domain.Album> = emptyList()
)

data class StreamInfo(
    val videoId: String,
    val streamUrl: String,
    val itag: Int = 251,
    val codec: String = "Opus",
    val bitrate: String = "160 kbps",
    val expiresAtMs: Long = 0L,
    val isLocal: Boolean = false,
    val latencyMs: Long = 0L
)

