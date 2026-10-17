package com.example.resolver.providers

import com.example.data.model.Track
import com.example.resolver.ResolvedStream

/**
 * Unified Source Types for Standalone On-Device Engine (§A.3).
 */
enum class SourceType {
    YOUTUBE,
    JIOSAAVN,
    SPOTIFY,
    LOCAL
}

/**
 * Capability-Based Normalized Track Schema.
 * Bridges disparate providers (YouTube, JioSaavn, Spotify) into a single unified format.
 */
data class NormalizedTrack(
    val source: SourceType,
    val sourceId: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val artUrl: String,
    val isrc: String? = null,
    val isPlayableNatively: Boolean = true
) {
    val primaryArtist: String
        get() = artists.firstOrNull()?.trim() ?: "Unknown Artist"

    fun toDomainTrack(): Track {
        val moodX = ((Math.abs(sourceId.hashCode()) % 100) / 100f).coerceIn(0.2f, 0.9f)
        val moodY = (((Math.abs(title.hashCode()) % 100)) / 100f).coerceIn(0.2f, 0.9f)
        return Track(
            id = sourceId,
            title = title,
            artist = primaryArtist,
            album = album,
            durationMs = durationMs,
            artUrl = artUrl,
            isDownloaded = false,
            localPath = null,
            isLiked = false,
            lyrics = "",
            genre = when (source) {
                SourceType.JIOSAAVN -> "Bollywood / Indian"
                SourceType.YOUTUBE -> "YouTube Music"
                SourceType.SPOTIFY -> "Spotify Import"
                SourceType.LOCAL -> "Local Audio"
            },
            moodX = moodX,
            moodY = moodY,
            loudnessGainDb = 0f,
            audioUrl = "",
            itag = if (source == SourceType.JIOSAAVN) 140 else 251,
            codec = if (source == SourceType.JIOSAAVN) "AAC 320k" else "Opus 160k"
        )
    }
}

/**
 * Base read-only Metadata Provider interface for searching tracks and importing playlists.
 */
interface MetadataProvider {
    val source: SourceType
    suspend fun searchTracks(query: String, limit: Int = 20): List<NormalizedTrack>
    suspend fun getPlaylistTracks(playlistId: String): List<NormalizedTrack>
}

/**
 * Audio Stream Provider interface capable of on-device stream URL resolution.
 */
interface AudioStreamProvider : MetadataProvider {
    suspend fun resolveStream(sourceId: String, preferredKbps: Int = 160): ResolvedStream
}
