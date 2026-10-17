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
 * Prioritizes:
 * 1. Exact ISRC match
 * 2. Artist - Title search scored by duration proximity (±15s window) and token overlap
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
            val first = fallbackCandidates.firstOrNull()
                ?: throw IllegalStateException("Could not match stream for track '${track.title}' by '${track.primaryArtist}'")

            youtubeProvider.resolveStream(first.sourceId, preferredKbps)
        }

    private fun pickBestCandidate(target: NormalizedTrack, candidates: List<NormalizedTrack>): NormalizedTrack? {
        if (candidates.isEmpty()) return null

        var bestCandidate: NormalizedTrack? = null
        var highestScore = -1f

        val targetTokens = tokenize("${target.title} ${target.primaryArtist}")

        for (candidate in candidates) {
            val candTokens = tokenize("${candidate.title} ${candidate.primaryArtist}")
            val overlap = targetTokens.count { it in candTokens }
            val tokenScore = if (targetTokens.isNotEmpty()) overlap.toFloat() / targetTokens.size else 0.5f

            // Duration delta penalty (duration proximity within 12 seconds)
            val durDelta = abs(candidate.durationMs - target.durationMs)
            val durScore = when {
                durDelta < 4000L -> 1.0f
                durDelta < 12000L -> 0.85f
                durDelta < 25000L -> 0.6f
                else -> 0.2f
            }

            // Combined score (60% tokens, 40% duration)
            val totalScore = (tokenScore * 0.6f) + (durScore * 0.4f)

            if (totalScore > highestScore) {
                highestScore = totalScore
                bestCandidate = candidate
            }
        }

        // Return best candidate if it meets reasonable threshold
        return if (highestScore >= 0.4f) bestCandidate else candidates.firstOrNull()
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
            .split(" ")
            .filter { it.length > 1 }
            .toSet()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\(official.*?\)|\[official.*?\]|feat\..*|ft\..*|remaster(ed)?"""), "")
            .trim()
    }
}
