package com.seitharol.domain.model

enum class TrackSource {
    YOUTUBE,
    JIOSAAVN,
    SPOTIFY,
    LOCAL_FILE
}

data class NormalizedTrack(
    val source: TrackSource,
    val sourceId: String,
    val title: String,
    val artists: List<String>,
    val primaryArtistBrowseId: String? = null,
    val artistAvatarUrl: String = "", // Artist profile pic shown at bottom-left of Discover
    val album: String? = null,
    val durationMs: Long,
    val artUrl: String,
    val isrc: String? = null,
    val isPlayableNatively: Boolean,
    val previewUrl: String? = null,
    val localPath: String? = null,
    val isLiked: Boolean = false
) {
    companion object {
        fun fromSpotifyPreview(
            spotifyId: String,
            title: String,
            artists: List<String>,
            artistId: String? = null,
            artistAvatarUrl: String = "",
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
            artistAvatarUrl = artistAvatarUrl.ifBlank { artUrl },
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
