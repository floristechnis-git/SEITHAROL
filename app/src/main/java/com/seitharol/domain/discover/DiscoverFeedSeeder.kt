package com.seitharol.domain.discover

import com.seitharol.data.provider.SpotifyMetadataProvider
import com.seitharol.domain.model.NormalizedTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DiscoverFeedSeeder(
    private val spotifyMetadataProvider: SpotifyMetadataProvider
) {
    /**
     * Seeding Algorithm:
     * 1. Rank top 4 most-played artists from listening history.
     * 2. Extract top 3 most-recently liked tracks (artist + title).
     * 3. Extract 2 recent history tracks not in the above sets.
     * 4. If user has cold history, use curated starter seed queries.
     * 5. Run parallel queries against SpotifyMetadataProvider (embed/scraper),
     *    keep only tracks with non-null 30s previewUrl, deduplicate by sourceId
     *    and normalized title+artist, and shuffle with round-robin artist diversity.
     */
    suspend fun generatePersonalizedFeed(
        listeningHistory: List<NormalizedTrack>,
        likedTracks: List<NormalizedTrack>,
        alreadySeenIds: Set<String> = emptySet()
    ): List<NormalizedTrack> = coroutineScope {
        val topArtists = listeningHistory
            .flatMap { it.artists }
            .groupingBy { it.trim() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)

        val likedSeeds = likedTracks
            .take(3)
            .map { "${it.artists.firstOrNull().orEmpty()} ${it.title}".trim() }

        val recentSeeds = listeningHistory
            .take(3)
            .mapNotNull { it.artists.firstOrNull() }

        val seedQueries = (topArtists + likedSeeds + recentSeeds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .ifEmpty {
                listOf("Trending Hits", "Indie Pop Chill", "Electronic Vibes", "Acoustic Sessions")
            }

        val likedIds = likedTracks.map { it.sourceId }.toSet()

        val rawBatches = seedQueries.map { query ->
            async {
                runCatching {
                    spotifyMetadataProvider.searchTracksWithPreviews(query, limit = 10)
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        // Interleave results across seed queries so one artist doesn't dominate consecutive pages
        val interleaved = mutableListOf<NormalizedTrack>()
        val maxBatchSize = rawBatches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxBatchSize) {
            for (batch in rawBatches) {
                if (i < batch.size) {
                    interleaved.add(batch[i])
                }
            }
        }

        val seenSourceIds = HashSet<String>(alreadySeenIds)
        val seenSignatures = HashSet<String>()

        interleaved
            .filter { !it.previewUrl.isNullOrBlank() }
            .filter { track ->
                val sig = "${track.title.lowercase().trim()}|${track.artists.firstOrNull()?.lowercase()?.trim()}"
                seenSourceIds.add(track.sourceId) && seenSignatures.add(sig)
            }
            .map { track ->
                track.copy(
                    source = com.seitharol.domain.model.TrackSource.SPOTIFY,
                    isPlayableNatively = false,
                    isLiked = track.sourceId in likedIds
                )
            }
            .shuffled()
    }
}
