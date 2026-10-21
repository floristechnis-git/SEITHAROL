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
    val videoType: String?, // "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC"
    val viewCount: Long = 0L,
    val thumbnailUrl: String,
    val isVerifiedArtist: Boolean = false
)

object CanonicalAudioResolver {

    private val BANNED_MODIFIERS = listOf(
        "loop", "extended", "1 hour", "10 hours", "slowed", "reverb",
        "sped up", "nightcore", "cover", "live", "8d", "mashup", "instrumental", "karaoke",
        "ringtone", "status", "dj "
    )

    fun containsBannedModifier(userQuery: String, candidateTitle: String): Boolean {
        val qLower = userQuery.lowercase()
        val cLower = candidateTitle.lowercase()
        return BANNED_MODIFIERS.any { mod -> cLower.contains(mod) && !qLower.contains(mod) }
    }

    fun selectBestCanonicalMatch(
        queryTitle: String,
        queryArtist: String,
        targetDurationSeconds: Int?,
        candidates: List<CandidateStream>
    ): CandidateStream? {
        if (candidates.isEmpty()) return null
        val rawQueryLower = "$queryTitle $queryArtist".lowercase()
        val normTitle = normalizeText(queryTitle)
        val normArtist = normalizeText(queryArtist.split(",", "&", "feat.", "ft.").first())

        return candidates
            .mapNotNull { candidate ->
                val candTitle = normalizeText(candidate.title)
                val candArtist = normalizeText(candidate.artist)
                val candChannel = normalizeText(candidate.channelName.removeSuffix("- Topic"))

                // HARD GATE 1: Reject if title does not share core words with queryTitle
                val titleTokens = normTitle.split(" ").filter { it.length >= 2 }
                val matchedTitleTokens = titleTokens.count { candTitle.contains(it) }
                val titleMatchRatio = if (titleTokens.isNotEmpty()) matchedTitleTokens.toFloat() / titleTokens.size else 1f
                if (candTitle != normTitle && !candTitle.contains(normTitle) && titleMatchRatio < 0.65f) {
                    return@mapNotNull null
                }

                // HARD GATE 2: Reject unrequested covers/slowed/live/loops
                if (containsBannedModifier(rawQueryLower, "${candidate.title} ${candidate.channelName}")) {
                    return@mapNotNull null
                }

                // HARD GATE 3: Artist must match or be contained in candidate artist/channel/title
                val artistMatches = normArtist.isBlank() ||
                    candArtist.contains(normArtist) ||
                    normArtist.contains(candArtist) ||
                    candChannel.contains(normArtist) ||
                    candTitle.contains(normArtist)
                if (!artistMatches) return@mapNotNull null

                val score = scoreCandidate(candidate, normTitle, normArtist, rawQueryLower, targetDurationSeconds)
                if (score >= 120) candidate to score else null
            }
            .maxByOrNull { it.second }
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

        // 1. Prioritize Official Art Tracks (ATV) & verified Topic channels
        val isAtv = c.videoType == "MUSIC_VIDEO_TYPE_ATV"
        val isTopicChannel = candChannelLower.endsWith("- topic") || candChannelLower.contains(" - topic")
        when {
            isAtv -> score += 110
            isTopicChannel -> score += 90
            c.videoType == "MUSIC_VIDEO_TYPE_OMV" -> score += 35
            c.videoType == "MUSIC_VIDEO_TYPE_UGC" -> score -= 140
            else -> score -= 15
        }

        if (!c.albumName.isNullOrBlank()) score += 20

        // 2. Hard-penalize unrequested slowed/reverb, covers, loops
        for (keyword in BANNED_MODIFIERS) {
            val requestedInQuery = rawQueryLower.contains(keyword)
            val presentInCandidate = candTitleLower.contains(keyword) || candChannelLower.contains(keyword)
            if (presentInCandidate && !requestedInQuery) {
                return -1000
            }
        }

        // 3. Title accuracy
        if (normCandTitle == normQueryTitle) {
            score += 75
        } else if (normCandTitle.startsWith(normQueryTitle) || normQueryTitle.startsWith(normCandTitle)) {
            score += 45
        } else if (normCandTitle.contains(normQueryTitle) || normQueryTitle.contains(normCandTitle)) {
            score += 25
        } else {
            val queryTokens = normQueryTitle.split(" ").filter { it.length > 2 }
            val matched = queryTokens.count { normCandTitle.contains(it) }
            score += if (queryTokens.isNotEmpty() && matched > 0) (matched * 30) / queryTokens.size else -60
        }

        // 4. Artist accuracy
        val cleanChannel = normalizeText(c.channelName.removeSuffix("- Topic").trim())
        if (normQueryArtist.isNotEmpty()) {
            if (normCandArtist == normQueryArtist || cleanChannel == normQueryArtist) {
                score += 70
            } else if (
                normCandArtist.contains(normQueryArtist) ||
                normQueryArtist.contains(normCandArtist) ||
                cleanChannel.contains(normQueryArtist)
            ) {
                score += 40
            } else {
                score -= 55
            }
        }

        // 5. Duration proximity
        if (targetDurationSeconds != null && targetDurationSeconds > 0 && c.durationSeconds > 0) {
            val diff = abs(c.durationSeconds - targetDurationSeconds)
            score += when {
                diff <= 2 -> 55
                diff <= 5 -> 35
                diff <= 12 -> 5
                diff <= 25 -> -60
                else -> -160
            }
        }

        return score
    }

    /**
     * Ranks search results by query relevance first, using official status and popularity only as tie-breakers.
     */
    fun rankSearchResultsByRelevance(
        query: String,
        tracks: List<CandidateStream>
    ): List<CandidateStream> {
        val cleanQuery = normalizeText(query)
        if (cleanQuery.isBlank()) return deduplicateTracks(tracks)

        val rawQueryLower = query.lowercase().trim()
        val queryTokens = cleanQuery.split(" ").filter { it.isNotBlank() }

        return deduplicateTracks(tracks)
            .mapIndexed { originalIndex, track ->
                var relevance = 0
                val normTitle = normalizeText(track.title)
                val normArtist = normalizeText(track.artist)
                val combined = "$normTitle $normArtist".trim()

                // Keep natural YouTube Music top-rank boost
                relevance += (30 - originalIndex * 2).coerceAtLeast(0)

                if (normTitle == cleanQuery) relevance += 120
                else if (normTitle.startsWith(cleanQuery)) relevance += 85
                else if (normTitle.contains(cleanQuery)) relevance += 55

                if (normArtist == cleanQuery) relevance += 90
                else if (normArtist.startsWith(cleanQuery)) relevance += 60

                if (combined == cleanQuery) relevance += 140

                val matchedTokens = queryTokens.count { token -> combined.contains(token) }
                if (queryTokens.isNotEmpty()) {
                    relevance += (matchedTokens * 60) / queryTokens.size
                    if (matchedTokens == 0) relevance -= 120
                }

                if (track.videoType == "MUSIC_VIDEO_TYPE_ATV" || track.isVerifiedArtist) {
                    relevance += 35
                }

                for (mod in BANNED_MODIFIERS) {
                    if (track.title.lowercase().contains(mod) && !rawQueryLower.contains(mod)) {
                        relevance -= 150
                    }
                }

                track to relevance
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun filterVerifiedArtistsOnly(
        tracks: List<CandidateStream>,
        searchQuery: String = ""
    ): List<Pair<String, String?>> {
        val normQuery = normalizeText(searchQuery)
        return tracks
            .asSequence()
            .filter { track ->
                val isOfficialSource = track.videoType == "MUSIC_VIDEO_TYPE_ATV" ||
                    track.channelName.endsWith("- Topic", ignoreCase = true) ||
                    track.isVerifiedArtist ||
                    (track.artistBrowseId?.startsWith("UC") == true || track.artistBrowseId?.startsWith("MPLA") == true)
                val hasRemixUploaderName = BANNED_MODIFIERS.any { mod ->
                    track.artist.contains(mod, ignoreCase = true) || track.channelName.contains(mod, ignoreCase = true)
                }
                isOfficialSource && !hasRemixUploaderName &&
                    !track.artist.equals("Unknown Artist", ignoreCase = true) &&
                    !track.artist.equals("Unknown", ignoreCase = true)
            }
            .flatMap { track ->
                val parts = track.artist
                    .removeSuffix(" - Topic")
                    .split(",", "&", "feat.", "ft.", "/")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
                parts.mapIndexedNotNull { index, singleArtist ->
                    val normArtist = normalizeText(singleArtist)
                    if (normQuery.isNotBlank() && !normArtist.contains(normQuery) && !normQuery.contains(normArtist)) {
                        null
                    } else {
                        // Only attach track.artistBrowseId if this artist was the sole/primary artist
                        val validBrowseId = if (index == 0 && parts.size == 1) track.artistBrowseId else null
                        singleArtist to validBrowseId
                    }
                }.asSequence()
            }
            .distinctBy { normalizeText(it.first) }
            .toList()
    }

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
