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

            // 2. Query "${track.primaryArtist} - ${track.title}" on YouTube InnerTube
            val cleanTitle = cleanTitle(track.title)
            val cleanArtist = track.primaryArtist.replace(Regex("""\(.*?\)"""), "").trim()
            val query = "$cleanArtist $cleanTitle".trim()

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

            // 3. Fallback: Query JioSaavn Provider
            try {
                val saavnCandidates = jioSaavnProvider.searchTracks(query, limit = 5)
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

            // 4. Last resort: If candidates list was non-empty, resolve fallback candidates
            val fallbackCandidates = youtubeProvider.searchTracks(track.title, limit = 5)
            val rankedFallback = rankCandidates(track, fallbackCandidates)
            for (cand in rankedFallback) {
                try {
                    return@withContext youtubeProvider.resolveStream(cand.sourceId, preferredKbps)
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback candidate ${cand.sourceId} failed: ${e.message}")
                }
            }

            throw IllegalStateException("Could not match stream for track '${track.title}' by '${track.primaryArtist}'")
        }

    private val BANNED_MODIFIERS = listOf(
        "remix", "re-mix", "loop", "extended", "1 hour", "10 hours", "hour version",
        "slowed", "reverb", "cover", "live", "tribute", "karaoke",
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
        val normQueryTitle = normalizeText(target.title)
        val normQueryArtist = normalizeText(target.primaryArtist)
        val targetDurationSec = (target.durationMs / 1000L).toInt()

        return candidates
            .filter { it.durationMs > 45_000L || targetDurationSec <= 45 }
            .map { candidate ->
                candidate to scoreCandidate(candidate, normQueryTitle, normQueryArtist, targetQueryLower, targetDurationSec)
            }
            .filter { it.second > -400 } // Filter out heavily penalized fake remixes
            .sortedWith(
                compareByDescending<Pair<NormalizedTrack, Int>> { it.first.isArtTrack || it.first.artists.any { a -> a.contains("Topic", ignoreCase = true) } }
                    .thenByDescending { it.second }
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
        val normCandTitle = normalizeText(c.title)
        val normCandArtist = normalizeText(c.primaryArtist)
        val isTopicChannel = c.artists.any { it.contains("Topic", ignoreCase = true) }

        // 1. Reject shorts / previews (<= 45 seconds)
        if (c.durationMs in 1..45_000L && targetDurationSec > 45) {
            return -1000
        }

        // 2. Prioritize Official Art Tracks (ATV) & "- Topic" Channels (+150, UGC: -150)
        when {
            c.isArtTrack || isTopicChannel -> score += 150
            candTitleLower.contains("official audio") -> score += 100
            else -> score -= 100
        }

        // 3. Official Album Metadata Presence
        if (c.album.isNotBlank() && !c.album.equals("Single", ignoreCase = true)) {
            score += 25
        }

        // 4. Strict Unrequested Remix / Loop / Edit Filtering
        for (keyword in BANNED_MODIFIERS) {
            val requestedInQuery = rawQueryLower.contains(keyword)
            val presentInCandidate = candTitleLower.contains(keyword) || c.artists.any { it.contains(keyword, ignoreCase = true) }
            if (presentInCandidate && !requestedInQuery) {
                score -= 400 // Hard penalty for unrequested remix/loop/edit
            }
        }

        // 5. Exact Title & Artist Matching
        if (normCandTitle == normQueryTitle) {
            score += 60
        } else if (normCandTitle.contains(normQueryTitle) || normQueryTitle.contains(normCandTitle)) {
            score += 30
        } else {
            score -= 40
        }

        val cleanChannel = normalizeText(c.primaryArtist.removeSuffix("- Topic").trim())
        if (normCandArtist == normQueryArtist || cleanChannel == normQueryArtist) {
            score += 70
        } else if (normCandArtist.contains(normQueryArtist) || normQueryArtist.contains(normCandArtist)) {
            score += 35
        } else {
            score -= 50
        }

        // 6. Strict Duration Proximity Scoring (against canonical track)
        if (targetDurationSec > 45 && c.durationMs > 0) {
            val candDurationSec = (c.durationMs / 1000L).toInt()
            val diff = abs(candDurationSec - targetDurationSec)
            score += when {
                diff <= 3 -> 60
                diff <= 7 -> 40
                diff <= 15 -> 15
                else -> -350 // Rejects out-of-range streams (> 15s difference)
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
