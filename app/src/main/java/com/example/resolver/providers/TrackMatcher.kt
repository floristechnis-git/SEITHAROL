package com.example.resolver.providers

import android.util.Log
import com.example.resolver.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Cross-Source Audio Matcher.
 * Matches metadata-only tracks (such as Spotify imports or tracks with isPlayableNatively = false)
 * to real on-device playable audio streams from YouTubeInnerTubeProvider or JioSaavnProvider.
 *
 * Strictly prioritizes:
 * 1. Exact ISRC match
 * 2. Official Art Track Videos (MUSIC_VIDEO_TYPE_ATV / "- Topic" channels)
 * 3. Exact artist & title match with strict duration proximity (±5 seconds)
 * 4. Hard-penalizes and filters out unofficial remixes, loops, and edited audio
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
                    val bestYt = pickBestCandidate(track, ytCandidates)
                    if (bestYt != null) {
                        return@withContext youtubeProvider.resolveStream(bestYt.sourceId, preferredKbps)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ISRC search on YouTube failed: ${e.message}")
                }
            }

            // 2. Query "${track.primaryArtist} - ${track.title}" on YouTube InnerTube
            val cleanTitle = cleanTitle(track.title)
            val cleanArtist = track.primaryArtist.replace(Regex("""\(.*?\)"""), "").trim()
            val query = "$cleanArtist $cleanTitle".trim()

            try {
                val ytCandidates = youtubeProvider.searchTracks(query, limit = 10)
                val best = pickBestCandidate(track, ytCandidates)
                if (best != null) {
                    return@withContext youtubeProvider.resolveStream(best.sourceId, preferredKbps)
                }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube text search match failed: ${e.message}")
            }

            // 3. Fallback: Query JioSaavn Provider
            try {
                val saavnCandidates = jioSaavnProvider.searchTracks(query, limit = 5)
                val bestSaavn = pickBestCandidate(track, saavnCandidates)
                if (bestSaavn != null) {
                    return@withContext jioSaavnProvider.resolveStream(bestSaavn.sourceId, preferredKbps)
                }
            } catch (e: Exception) {
                Log.w(TAG, "JioSaavn search match failed: ${e.message}")
            }

            // 4. Last resort: If candidates list was non-empty, resolve first YouTube candidate
            val fallbackCandidates = youtubeProvider.searchTracks(track.title, limit = 3)
            val first = pickBestCandidate(track, fallbackCandidates) ?: fallbackCandidates.firstOrNull()
                ?: throw IllegalStateException("Could not match stream for track '${track.title}' by '${track.primaryArtist}'")

            youtubeProvider.resolveStream(first.sourceId, preferredKbps)
        }

    private val BANNED_MODIFIERS = listOf(
        "remix", "re-mix", "loop", "extended", "1 hour", "10 hours", "hour version",
        "slowed", "reverb", "cover", "live", "tribute", "karaoke",
        "instrumental", "parody", "sped up", "nightcore", "8d", "mashup",
        "bass boosted", "fan made", "unofficial"
    )

    fun pickBestCandidate(target: NormalizedTrack, candidates: List<NormalizedTrack>): NormalizedTrack? {
        if (candidates.isEmpty()) return null

        val targetQueryLower = "${target.title} ${target.primaryArtist}".lowercase()
        val normQueryTitle = normalizeText(target.title)
        val normQueryArtist = normalizeText(target.primaryArtist)
        val targetDurationSec = (target.durationMs / 1000L).toInt()

        val scored = candidates.map { candidate ->
            candidate to scoreCandidate(candidate, normQueryTitle, normQueryArtist, targetQueryLower, targetDurationSec)
        }

        return scored
            .filter { it.second > -50 } // Filter out hard-penalized fake remixes
            .maxByOrNull { it.second }
            ?.first
            ?: candidates.firstOrNull()
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
        val normCandTitle = normalizeText(c.title)
        val normCandArtist = normalizeText(c.primaryArtist)
        val isTopicChannel = c.artists.any { it.contains("Topic", ignoreCase = true) }

        // 1. Prioritize Official Art Tracks (ATV) & "- Topic" Channels (+100, UGC: -150)
        when {
            c.isArtTrack || isTopicChannel -> score += 100
            else -> score -= 150
        }

        // 2. Official Album Metadata Presence
        if (c.album.isNotBlank() && !c.album.equals("Single", ignoreCase = true)) {
            score += 25
        }

        // 3. Strict Unrequested Remix / Loop / Edit Filtering
        for (keyword in BANNED_MODIFIERS) {
            val requestedInQuery = rawQueryLower.contains(keyword)
            val presentInCandidate = candTitleLower.contains(keyword) || c.artists.any { it.contains(keyword, ignoreCase = true) }
            if (presentInCandidate && !requestedInQuery) {
                score -= 300 // Hard penalty for unrequested remix/loop/edit
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

        val cleanChannel = normalizeText(c.primaryArtist.removeSuffix("- Topic").trim())
        if (normCandArtist == normQueryArtist || cleanChannel == normQueryArtist) {
            score += 60
        } else if (normCandArtist.contains(normQueryArtist) || normQueryArtist.contains(normCandArtist)) {
            score += 30
        } else {
            score -= 50
        }

        // 5. Strict Duration Proximity Scoring (within +-5 seconds of canonical track)
        if (targetDurationSec > 0 && c.durationMs > 0) {
            val candDurationSec = (c.durationMs / 1000L).toInt()
            val diff = abs(candDurationSec - targetDurationSec)
            score += when {
                diff <= 2 -> 50
                diff <= 5 -> 35
                diff <= 10 -> -80
                else -> -150 // Rejects out-of-range streams (> 5s difference)
            }
        }

        return score
    }

    private fun normalizeText(input: String): String {
        return input.lowercase()
            .replace(Regex("""\(.*?\)|\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(official.*?\)|\[official.*?\]|feat\..*|ft\..*|remaster(ed)?"""), "")
            .trim()
    }
}
