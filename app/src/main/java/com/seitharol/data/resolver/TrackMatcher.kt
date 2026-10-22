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

    suspend fun resolveStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String>
}

interface JioSaavnProvider {
    suspend fun findBestMatch(
        title: String,
        artist: String,
        targetDurationSeconds: Int? = null
    ): CandidateStream?

    suspend fun resolveStreamUrl(
        trackId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String>
}

class TrackMatcher(
    private val innerTubeProvider: InnerTubeProvider,
    private val jioSaavnProvider: JioSaavnProvider
) {
    suspend fun matchToPlayable(track: NormalizedTrack): Result<NormalizedTrack> {
        // Fast-path: If sourceId is already a valid 11-char YouTube ID, NEVER re-search!
        val extractedYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
        if (!extractedYtId.isNullOrBlank()) {
            return Result.success(
                track.copy(
                    source = TrackSource.YOUTUBE,
                    sourceId = extractedYtId,
                    isPlayableNatively = true
                )
            )
        }

        if (track.isPlayableNatively &&
            (track.source == TrackSource.YOUTUBE || track.source == TrackSource.JIOSAAVN || track.source == TrackSource.LOCAL_FILE)
        ) {
            return Result.success(track)
        }

        val primaryArtist = track.artists.firstOrNull().orEmpty()
        val targetDurationSec = (track.durationMs / 1000L).toInt().takeIf { it > 0 }

        val ytCandidate = innerTubeProvider.findBestAtvMatch(
            title = track.title,
            artist = primaryArtist,
            isrc = track.isrc,
            targetDurationSeconds = targetDurationSec
        )
        if (ytCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.YOUTUBE,
                    sourceId = ytCandidate.videoId,
                    durationMs = if (ytCandidate.durationSeconds > 0) ytCandidate.durationSeconds * 1000L else track.durationMs,
                    isPlayableNatively = true
                )
            )
        }

        val saavnCandidate = jioSaavnProvider.findBestMatch(
            title = track.title,
            artist = primaryArtist,
            targetDurationSeconds = targetDurationSec
        )
        if (saavnCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.JIOSAAVN,
                    sourceId = saavnCandidate.videoId,
                    durationMs = if (saavnCandidate.durationSeconds > 0) saavnCandidate.durationSeconds * 1000L else track.durationMs,
                    isPlayableNatively = true
                )
            )
        }

        return Result.failure(IllegalStateException("No verified canonical match found for ${track.title}"))
    }
}

class DefaultInnerTubeProvider(
    @Suppress("UNUSED_PARAMETER") ytProvider: Any? = null
) : InnerTubeProvider {

    override suspend fun findBestAtvMatch(
        title: String,
        artist: String,
        isrc: String?,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        val query = if (!isrc.isNullOrBlank()) "$isrc $title $artist" else "$title $artist"
        val candidates = InnerTubeStreamExtractor.searchOfficialYtmSongs(query.trim(), limit = 12)
        return CanonicalAudioResolver.selectBestCanonicalMatch(title, artist, targetDurationSeconds, candidates)
    }

    override suspend fun resolveStreamUrl(videoId: String, preferredQuality: AudioQuality): Result<String> {
        return InnerTubeStreamExtractor.resolveWorkingStreamUrl(videoId, preferredQuality)
    }
}

class DefaultJioSaavnProvider(
    @Suppress("UNUSED_PARAMETER") jioProvider: Any? = null
) : JioSaavnProvider {

    override suspend fun findBestMatch(
        title: String,
        artist: String,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        return null
    }

    override suspend fun resolveStreamUrl(trackId: String, preferredQuality: AudioQuality): Result<String> {
        val url = StreamResolver.resolveExactStreamUrl(
            TrackMetadata(id = trackId, title = "", artist = ""),
            preferredQuality
        )
        return if (!url.isNullOrBlank()) {
            Result.success(url)
        } else {
            Result.failure(IllegalStateException("Unable to resolve JioSaavn stream for $trackId"))
        }
    }
}
