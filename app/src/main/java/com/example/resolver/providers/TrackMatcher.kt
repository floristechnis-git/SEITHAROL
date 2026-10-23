package com.example.resolver.providers

import android.util.Log
import com.example.resolver.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Cross-Source Audio Matcher.
 * Matches metadata-only or fallback tracks to verified on-device playable audio streams
 * from YouTubeInnerTubeProvider or JioSaavnProvider with strict Title + Artist + Duration + No-Remix gates.
 */
class TrackMatcher(
    private val youtubeProvider: YouTubeInnerTubeProvider,
    private val jioSaavnProvider: JioSaavnProvider
) {
    private val TAG = "TrackMatcher"

    suspend fun matchAndResolve(track: NormalizedTrack, preferredKbps: Int = 160): ResolvedStream =
        withContext(Dispatchers.IO) {
            // 1. Try ISRC search if available
            if (!track.isrc.isNullOrBlank()) {
                val isrcQuery = track.isrc
                try {
                    val ytCandidates = youtubeProvider.searchTracks(isrcQuery, limit = 5)
                    val ranked = rankCandidates(track, ytCandidates)
                    for (cand in ranked) {
                        try {
                            return@withContext youtubeProvider.resolveStream(cand.sourceId, preferredKbps)
                        } catch (e: Exception) {
                            Log.w(TAG, "ISRC candidate ${cand.sourceId} failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ISRC search on YouTube failed: ${e.message}")
                }
            }

            val cleanTitle = cleanTitle(track.title)
            val cleanArtist = track.primaryArtist
                .removeSuffix(" - Topic")
                .removeSuffix("- Topic")
                .replace(Regex("""\(.*?\)"""), "")
                .trim()
            val query = "$cleanArtist $cleanTitle".trim()

            // 2. Query YouTube InnerTube
            try {
                val ytCandidates = youtubeProvider.searchTracks(query, limit = 10)
                val ranked = rankCandidates(track, ytCandidates)
                for (cand in ranked) {
                    try {
                        return@withContext youtubeProvider.resolveStream(cand.sourceId, preferredKbps)
                    } catch (e: Exception) {
                        Log.w(TAG, "YouTube candidate ${cand.sourceId} failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube text search match failed: ${e.message}")
            }

            // 3. Fallback: Query JioSaavn Provider using artist + title (never title-only when artist is known!)
            val saavnQueries = if (cleanArtist.isNotBlank() &&
                !cleanArtist.equals("Unknown Artist", ignoreCase = true) &&
                !cleanArtist.equals("Various Artists", ignoreCase = true)
            ) {
                listOfNotNull(query.takeIf { it.isNotBlank() })
            } else {
                listOfNotNull(cleanTitle.takeIf { it.isNotBlank() })
            }

            for (sq in saavnQueries) {
                try {
                    val saavnCandidates = jioSaavnProvider.searchTracks(sq, limit = 6)
                    val rankedSaavn = rankCandidates(track, saavnCandidates)
                    for (cand in rankedSaavn) {
                        try {
                            return@withContext jioSaavnProvider.resolveStream(cand.sourceId, preferredKbps)
                        } catch (e: Exception) {
                            Log.w(TAG, "JioSaavn candidate ${cand.sourceId} failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "JioSaavn search match failed: ${e.message}")
                }
            }

            throw IllegalStateException("Could not match verified stream for track '${track.title}' by '${track.primaryArtist}'")
        }

    private val BANNED_MODIFIERS = listOf(
        "remix", "loop", "extended", "1 hour", "10 hours", "hour version",
        "slowed", "reverb", "cover", "live", "unplugged", "tribute", "karaoke",
        "instrumental", "parody", "sped up", "nightcore", "8d", "mashup",
        "dj", "status", "ringtone", "lofi", "lo-fi",
        "bass boosted", "fan made", "unofficial"
    )

    fun pickBestCandidate(target: NormalizedTrack, candidates: List<NormalizedTrack>): NormalizedTrack? {
        return rankCandidates(target, candidates).firstOrNull()
    }

    fun rankCandidates(target: NormalizedTrack, candidates: List<NormalizedTrack>): List<NormalizedTrack> {
        if (candidates.isEmpty()) return emptyList()

        val targetQueryLower = "${target.title} ${target.primaryArtist}".lowercase()
        val normQueryTitle = normalizeText(cleanTitle(target.title))
        val normQueryArtist = normalizeText(
            target.primaryArtist
                .removeSuffix(" - Topic")
                .removeSuffix("- Topic")
                .split(",", "&", "feat.", "ft.")
                .firstOrNull()
                .orEmpty()
        )
        val targetDurationSec = (target.durationMs / 1000L).toInt()

        return candidates
            .filter { it.durationMs > 45_000L || targetDurationSec <= 45 }
            .map { candidate ->
                candidate to scoreCandidate(candidate, normQueryTitle, normQueryArtist, targetQueryLower, targetDurationSec)
            }
            .filter { it.second >= 120 }
            .sortedWith(
                compareByDescending<Pair<NormalizedTrack, Int>> {
                    it.first.source == SourceType.JIOSAAVN || it.first.isArtTrack || it.first.artists.any { a -> a.contains("Topic", ignoreCase = true) }
                }.thenByDescending { it.second }
            )
            .map { it.first }
    }

    private fun scoreCandidate(
        c: NormalizedTrack,
        normQueryTitle: String,
        normQueryArtist: String,
        rawQueryLower: String,
        targetDurationSec: Int
    ): Int {
        var score = 0
        val candTitleLower = c.title.lowercase()
        val normCandTitle = normalizeText(cleanTitle(c.title))
        val normCandArtist = normalizeText(c.artists.joinToString(" "))
        val isTopicChannel = c.artists.any { it.contains("Topic", ignoreCase = true) }
        val isOfficialSource = c.source == SourceType.JIOSAAVN || c.isArtTrack || isTopicChannel

        // 1. Reject shorts / previews (<= 45 seconds)
        if (c.durationMs in 1..45_000L && targetDurationSec > 45) {
            return -1000
        }

        // HARD GATE 1: Reject candidate if title does not share core words with query title
        if (normQueryTitle.isNotBlank()) {
            val titleTokens = normQueryTitle.split(" ").filter { it.length >= 2 }
            val matchedTitleTokens = titleTokens.count { normCandTitle.contains(it) }
            val titleMatchRatio = if (titleTokens.isNotEmpty()) matchedTitleTokens.toFloat() / titleTokens.size else 1f
            if (normCandTitle != normQueryTitle && !normCandTitle.contains(normQueryTitle) && titleMatchRatio < 0.70f) {
                return -1000
            }
        }

        // HARD GATE 2: Strict Unrequested Remix / Loop / Edit Filtering
        for (keyword in BANNED_MODIFIERS) {
            val requestedInQuery = rawQueryLower.contains(keyword)
            val presentInCandidate = candTitleLower.contains(keyword) || c.artists.any { it.contains(keyword, ignoreCase = true) }
            if (presentInCandidate && !requestedInQuery) {
                return -1000
            }
        }

        // HARD GATE 3: Strict Artist Ownership Gate
        // If target artist is known, candidate artist or title MUST share the artist name or core artist token!
        val cleanChannel = normalizeText(c.primaryArtist.removeSuffix("- Topic").trim())
        if (normQueryArtist.isNotBlank() &&
            !normQueryArtist.equals("unknown artist", ignoreCase = true) &&
            !normQueryArtist.equals("various artists", ignoreCase = true)
        ) {
            val artistTokens = normQueryArtist.split(" ").filter { it.length >= 3 }
                .ifEmpty { normQueryArtist.split(" ").filter { it.length >= 2 } }
            val artistMatches = normCandArtist == normQueryArtist ||
                cleanChannel == normQueryArtist ||
                normCandArtist.contains(normQueryArtist) ||
                normQueryArtist.contains(normCandArtist).takeIf { normCandArtist.length >= 3 } == true ||
                normCandTitle.contains(normQueryArtist) ||
                (artistTokens.isNotEmpty() && artistTokens.any { normCandArtist.contains(it) || normCandTitle.contains(it) })

            if (!artistMatches) {
                return -1000
            }
            score += if (normCandArtist == normQueryArtist || cleanChannel == normQueryArtist) 70 else 45
        }

        // HARD GATE 4: Duration Proximity Gate (rejects candidates >15s off from targetDurationSec)
        if (targetDurationSec > 45 && c.durationMs > 0 && targetDurationSec != 210) {
            val candDurationSec = (c.durationMs / 1000L).toInt()
            val diff = abs(candDurationSec - targetDurationSec)
            if (diff > 15) {
                return -1000
            }
            score += when {
                diff <= 5 -> 50
                diff <= 15 -> 30
                else -> -1000
            }
        }

        // Prioritize Official Art Tracks / Studio Releases (JioSaavn & YouTube ATV)
        when {
            isOfficialSource -> score += 100
            candTitleLower.contains("official audio") || candTitleLower.contains("official video") -> score += 70
            else -> score += 20
        }

        if (c.album.isNotBlank() && !c.album.equals("Single", ignoreCase = true)) {
            score += 20
        }

        if (normCandTitle == normQueryTitle) {
            score += 80
        } else if (normCandTitle.contains(normQueryTitle) || normQueryTitle.contains(normCandTitle)) {
            score += 45
        }

        return score
    }

    private fun normalizeText(input: String): String {
        return input.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(official.*?\)|\[official.*?\]|feat\..*|ft\..*|remaster(ed)?"""), "")
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .trim()
    }
}
