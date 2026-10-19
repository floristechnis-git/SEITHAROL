package com.seitharol.data.resolver

import kotlin.math.abs

data class CandidateStream(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistBrowseId: String? = null,
    val channelName: String,
    val albumName: String?,
    val durationSeconds: Int,
    val videoType: String?, // e.g., "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"
    val viewCount: Long = 0L,
    val thumbnailUrl: String,
    val isVerifiedArtist: Boolean = false
)

object CanonicalAudioResolver {

    private val BANNED_MODIFIERS = listOf(
        "remix", "re-mix", "loop", "extended", "1 hour", "10 hours", "slowed", "reverb",
        "sped up", "nightcore", "cover", "live", "8d", "mashup", "instrumental", "karaoke"
    )

    /**
     * Ranks candidate streams to ensure canonical studio audio (ATV / Topic) is chosen
     * over user-uploaded remixes, loops, or altered edits.
     */
    fun selectBestCanonicalMatch(
        queryTitle: String,
        queryArtist: String,
        targetDurationSeconds: Int?,
        candidates: List<CandidateStream>
    ): CandidateStream? {
        val normalizedQuery = "$queryTitle $queryArtist".lowercase()
        val normTitle = normalizeText(queryTitle)
        val normArtist = normalizeText(queryArtist)

        return candidates
            .map { candidate -> candidate to scoreCandidate(candidate, normTitle, normArtist, normalizedQuery, targetDurationSeconds) }
            .filter { (_, score) -> score > -50 } // Reject hard-penalized fake remixes
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun scoreCandidate(
        c: CandidateStream,
        normQueryTitle: String,
        normQueryArtist: String,
        rawQueryLower: String,
        targetDurationSeconds: Int?
    ): Int {
        var score = 0
        val candTitleLower = c.title.lowercase()
        val candChannelLower = c.channelName.lowercase()
        val normCandTitle = normalizeText(c.title)
        val normCandArtist = normalizeText(c.artist)

        // 1. Prioritize Official Art Tracks (ATV) & "- Topic" Channels (+100, UGC: -150)
        val isAtv = c.videoType == "MUSIC_VIDEO_TYPE_ATV"
        val isTopicChannel = candChannelLower.endsWith("- topic") || candChannelLower.contains(" - topic")
        when {
            isAtv || isTopicChannel -> score += 100
            c.videoType == "MUSIC_VIDEO_TYPE_OMV" -> score += 20
            c.videoType == "MUSIC_VIDEO_TYPE_UGC" -> score -= 150
            else -> score -= 30
        }

        // 2. Official Album Metadata Presence
        if (!c.albumName.isNullOrBlank()) {
            score += 25
        }

        // 3. Strict Unrequested Remix / Loop / Edit Filtering
        for (keyword in BANNED_MODIFIERS) {
            val requestedInQuery = rawQueryLower.contains(keyword)
            val presentInCandidate = candTitleLower.contains(keyword) || candChannelLower.contains(keyword)
            if (presentInCandidate && !requestedInQuery) {
                score -= 300 // Hard-filter penalty for unrequested remix/loop/edit
            }
        }

        // 4. Exact Title & Artist Matching
        if (normCandTitle == normQueryTitle) {
            score += 50
        } else if (normCandTitle.contains(normQueryTitle) || normQueryTitle.contains(normCandTitle)) {
            score += 25
        } else {
            score -= 40
        }

        val cleanChannel = normalizeText(c.channelName.removeSuffix("- Topic").trim())
        if (normCandArtist == normQueryArtist || cleanChannel == normQueryArtist) {
            score += 60
        } else if (normCandArtist.contains(normQueryArtist) || normQueryArtist.contains(normCandArtist)) {
            score += 30
        } else {
            score -= 50
        }

        // 5. Strict Duration Proximity Scoring (within +-5 seconds of canonical track)
        if (targetDurationSeconds != null && targetDurationSeconds > 0 && c.durationSeconds > 0) {
            val diff = abs(c.durationSeconds - targetDurationSeconds)
            score += when {
                diff <= 2 -> 50
                diff <= 5 -> 35
                diff <= 10 -> -80
                else -> -150 // Rejects out-of-range streams (> 5s difference)
            }
        }

        return score
    }

    /**
     * Sorts tracks strictly by popularity (most listened / highest view count).
     */
    fun sortByPopularity(tracks: List<CandidateStream>): List<CandidateStream> {
        return tracks.sortedByDescending { it.viewCount }
    }

    /**
     * Filters out unverified song/remix uploaders so only real, verified artists
     * appear in Search and Home artist suggestions.
     */
    fun filterVerifiedArtistsOnly(tracks: List<CandidateStream>): List<Pair<String, String?>> {
        return tracks
            .filter { track ->
                val isOfficialSource = track.videoType == "MUSIC_VIDEO_TYPE_ATV" ||
                    track.channelName.endsWith("- Topic", ignoreCase = true) ||
                    track.isVerifiedArtist ||
                    (track.artistBrowseId?.startsWith("UC") == true || track.artistBrowseId?.startsWith("MPLA") == true)
                val hasRemixUploaderName = BANNED_MODIFIERS.any { mod ->
                    track.artist.contains(mod, ignoreCase = true) || track.channelName.contains(mod, ignoreCase = true)
                }
                isOfficialSource && !hasRemixUploaderName
            }
            .map { it.artist.removeSuffix(" - Topic").trim() to it.artistBrowseId }
            .distinctBy { it.first.lowercase() }
    }

    /**
     * Deduplicates tracks for the Home Screen shelf below the category filter.
     */
    fun deduplicateTracks(tracks: List<CandidateStream>): List<CandidateStream> {
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()
        val result = ArrayList<CandidateStream>(tracks.size)
        for (track in tracks) {
            val sig = "${normalizeText(track.title)}|${normalizeText(track.artist)}"
            if (seenIds.add(track.videoId) && seenSignatures.add(sig)) {
                result.add(track)
            }
        }
        return result
    }

    fun normalizeText(input: String): String {
        return input.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
