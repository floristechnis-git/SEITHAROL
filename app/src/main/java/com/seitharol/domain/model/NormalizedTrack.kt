package com.seitharol.domain.model

enum class TrackSource {
    YOUTUBE,
    JIOSAAVN, // Consolidated provider (Gaana removed)
    SPOTIFY,  // Metadata + 30s preview_url only; isPlayableNatively = false
    LOCAL_FILE
}

data class NormalizedTrack(
    val source: TrackSource,
    val sourceId: String,
    val title: String,
    val artists: List<String>,
    val primaryArtistBrowseId: String? = null,
    val album: String? = null,
    val durationMs: Long,
    val artUrl: String,
    val isrc: String? = null,
    val isPlayableNatively: Boolean,
    val previewUrl: String? = null, // 30s mp3-preview from SpotifyMetadataProvider / spotifyscraper
    val localPath: String? = null,  // Points to context.filesDir/downloads/... for in-app downloads
    val isLiked: Boolean = false
) {
    companion object {
        /**
         * Factory for Discover feed & In-Discover search items.
         * Enforces source = SPOTIFY and isPlayableNatively = false so full playback
         * always routes through TrackMatcher -> ResolverEngine.
         */
        fun fromSpotifyPreview(
            spotifyId: String,
            title: String,
            artists: List<String>,
            artistId: String? = null,
            album: String? = null,
            durationMs: Long,
            artUrl: String,
            previewUrl: String,
            isrc: String? = null,
            isLiked: Boolean = false
        ): NormalizedTrack = NormalizedTrack(
            source = TrackSource.SPOTIFY,
            sourceId = spotifyId,
            title = title,
            artists = artists,
            primaryArtistBrowseId = artistId,
            album = album,
            durationMs = durationMs,
            artUrl = artUrl,
            isrc = isrc,
            isPlayableNatively = false,
            previewUrl = previewUrl,
            localPath = null,
            isLiked = isLiked
        )
    }
}
