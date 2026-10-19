package com.seitharol.data.resolver

import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource

interface InnerTubeProvider {
    suspend fun findBestAtvMatch(
        title: String,
        artist: String,
        isrc: String? = null,
        targetDurationSeconds: Int? = null
    ): CandidateStream?

    suspend fun resolveStreamUrl(videoId: String): Result<String>
}

interface JioSaavnProvider {
    suspend fun findBestMatch(
        query: String,
        targetDurationSeconds: Int? = null
    ): CandidateStream?

    suspend fun resolveStreamUrl(trackId: String): Result<String>
}

class TrackMatcher(
    private val innerTubeProvider: InnerTubeProvider,
    private val jioSaavnProvider: JioSaavnProvider // Gaana removed; JioSaavn handles regional/Indian catalog
) {
    /**
     * Maps non-natively-playable tracks (e.g., NormalizedTrack with source = SPOTIFY,
     * isPlayableNatively = false) to a full-length playable track from YouTube ATV or JioSaavn.
     */
    suspend fun matchToPlayable(track: NormalizedTrack): Result<NormalizedTrack> {
        if (track.isPlayableNatively) return Result.success(track)

        val query = "${track.title} ${track.artists.joinToString(" ")}".trim()
        val targetDurationSec = (track.durationMs / 1000L).toInt()

        // 1. Try canonical YouTube Music Art Track (ATV) match first (ISRC or strict duration/metadata)
        val ytCandidate = innerTubeProvider.findBestAtvMatch(
            title = track.title,
            artist = track.artists.firstOrNull().orEmpty(),
            isrc = track.isrc,
            targetDurationSeconds = targetDurationSec
        )
        if (ytCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.YOUTUBE,
                    sourceId = ytCandidate.videoId,
                    isPlayableNatively = true
                )
            )
        }

        // 2. Fallback to JioSaavn (Gaana removed as duplicate provider)
        val saavnCandidate = jioSaavnProvider.findBestMatch(
            query = query,
            targetDurationSeconds = targetDurationSec
        )
        if (saavnCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.JIOSAAVN,
                    sourceId = saavnCandidate.videoId,
                    isPlayableNatively = true
                )
            )
        }

        return Result.failure(IllegalStateException("No full-length match found for ${track.title}"))
    }
}

class DefaultInnerTubeProvider(
    private val ytProvider: com.example.resolver.providers.YouTubeInnerTubeProvider
) : InnerTubeProvider {
    override suspend fun findBestAtvMatch(
        title: String,
        artist: String,
        isrc: String?,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        val tracks = ytProvider.searchTracks("$title $artist", limit = 10)
        val candidates = tracks.map { t ->
            CandidateStream(
                videoId = t.sourceId,
                title = t.title,
                artist = t.primaryArtist,
                channelName = "${t.primaryArtist} - Topic",
                albumName = t.album,
                durationSeconds = (t.durationMs / 1000).toInt(),
                videoType = if (t.isArtTrack) "MUSIC_VIDEO_TYPE_ATV" else "MUSIC_VIDEO_TYPE_OMV",
                thumbnailUrl = t.artUrl
            )
        }
        return CanonicalAudioResolver.selectBestCanonicalMatch(title, artist, targetDurationSeconds, candidates)
    }

    override suspend fun resolveStreamUrl(videoId: String): Result<String> {
        return runCatching {
            val resolved = ytProvider.resolveStream(videoId)
            resolved.streamUrl
        }
    }
}

class DefaultJioSaavnProvider(
    private val jioProvider: com.example.resolver.providers.JioSaavnProvider
) : JioSaavnProvider {
    override suspend fun findBestMatch(
        query: String,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        val tracks = jioProvider.searchTracks(query, limit = 10)
        val candidates = tracks.map { t ->
            CandidateStream(
                videoId = t.sourceId,
                title = t.title,
                artist = t.primaryArtist,
                channelName = t.primaryArtist,
                albumName = t.album,
                durationSeconds = (t.durationMs / 1000).toInt(),
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                thumbnailUrl = t.artUrl
            )
        }
        return candidates.firstOrNull()
    }

    override suspend fun resolveStreamUrl(trackId: String): Result<String> {
        return runCatching {
            val resolved = jioProvider.resolveStream(trackId)
            resolved.streamUrl
        }
    }
}
