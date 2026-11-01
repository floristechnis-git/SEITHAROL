package com.seitharol.domain.discover

import com.seitharol.data.provider.SpotifyMetadataProvider
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DiscoverFeedSeeder(
    private val spotifyMetadataProvider: SpotifyMetadataProvider
) {
    private val bannedArtistTokens = setOf(
        "unknown artist", "unknown", "local artist", "local audio", "local video",
        "device storage", "downloaded in seitharol", "<unknown>", "various artists",
        "various", "download", "seitharol"
    )

    private fun cleanPrimaryArtist(raw: String): String {
        return raw
            .removeSuffix(" - Topic")
            .removeSuffix("- Topic")
            .split(",", "&", "feat.", "ft.", "/")
            .firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun isValidArtistName(raw: String): Boolean {
        val clean = cleanPrimaryArtist(raw)
        if (clean.length < 2) return false
        val lower = clean.lowercase()
        return bannedArtistTokens.none { lower == it || lower.contains(it) }
    }

    /**
     * Whole-History Personalization Seeding Algorithm for Discover & Download:
     * 1. Ranks the user's top artists across their ENTIRE listening history and liked tracks.
     * 2. Selects diverse seed tracks across the user's listening history and liked songs and fetches
     *    authentic YouTube Music RDAMVM Automix radio recommendations for those history seeds.
     * 3. Queries SpotifyMetadataProvider (JioSaavn direct CDN + YTM official songs) for the user's
     *    top history artists, liked track combinations, and recent search queries.
     * 4. Interleaves Automix history continuations, top-artist catalogs, and search matches so
     *    Discover & Download is deeply personalized to the user's actual listening taste.
     */
    suspend fun generatePersonalizedFeed(
        listeningHistory: List<NormalizedTrack>,
        likedTracks: List<NormalizedTrack>,
        recentSearchQueries: List<String> = emptyList(),
        alreadySeenIds: Set<String> = emptySet()
    ): List<NormalizedTrack> = coroutineScope {
        val artistWeights = LinkedHashMap<String, Int>()
        fun addWeight(rawArtist: String, weight: Int) {
            val clean = cleanPrimaryArtist(rawArtist)
            if (!isValidArtistName(clean)) return
            val key = artistWeights.keys.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
            artistWeights[key] = (artistWeights[key] ?: 0) + weight
        }

        listeningHistory.forEach { track ->
            track.artists.forEach { addWeight(it, 10) }
        }
        likedTracks.forEach { track ->
            track.artists.forEach { addWeight(it, 12) }
        }

        val topArtists = artistWeights.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(6)

        // Pick diverse history + liked tracks (1 per distinct artist first) for RDAMVM Automix radio seeding
        val combinedHistoryPool = (likedTracks + listeningHistory).distinctBy { it.sourceId }
        val diverseSeedTracks = mutableListOf<NormalizedTrack>()
        val seenSeedArtists = HashSet<String>()
        for (tr in combinedHistoryPool) {
            val artKey = cleanPrimaryArtist(tr.artists.firstOrNull().orEmpty()).lowercase()
            if (artKey.isNotBlank() && seenSeedArtists.add(artKey)) {
                diverseSeedTracks.add(tr)
                if (diverseSeedTracks.size >= 6) break
            }
        }
        if (diverseSeedTracks.size < 6) {
            for (tr in combinedHistoryPool) {
                if (diverseSeedTracks.none { it.sourceId == tr.sourceId }) {
                    diverseSeedTracks.add(tr)
                    if (diverseSeedTracks.size >= 6) break
                }
            }
        }

        val validYtSeedIds = diverseSeedTracks
            .mapNotNull { InnerTubeStreamExtractor.extractVideoId(it.sourceId) }
            .distinct()
            .take(5)

        // 1. YouTube Music RDAMVM Automix queues seeded directly from the user's listening history
        val automixDeferred = validYtSeedIds.map { seedVid ->
            async {
                runCatching {
                    InnerTubeStreamExtractor.fetchAutomixQueue(seedVid, limit = 12)
                        .filter { it.videoId != seedVid }
                        .map { yt ->
                            NormalizedTrack(
                                source = TrackSource.YOUTUBE,
                                sourceId = yt.videoId,
                                title = yt.title,
                                artists = listOf(yt.artist.removeSuffix(" - Topic").removeSuffix("- Topic").trim()),
                                primaryArtistBrowseId = yt.artistBrowseId,
                                artistAvatarUrl = yt.thumbnailUrl,
                                album = yt.albumName ?: "Single",
                                durationMs = yt.durationSeconds.coerceAtLeast(180) * 1000L,
                                artUrl = yt.thumbnailUrl,
                                isPlayableNatively = true,
                                previewUrl = "ytm_clip:${yt.videoId}"
                            )
                        }
                }.getOrDefault(emptyList())
            }
        }

        // 2. Personalized search queries from history artists, history tracks, liked songs, and recent searches
        val artistQueries = topArtists.flatMap { artist ->
            listOf("$artist top songs", "$artist latest songs")
        }.take(6)

        val trackQueries = diverseSeedTracks.take(4).mapNotNull { tr ->
            val cleanArt = cleanPrimaryArtist(tr.artists.firstOrNull().orEmpty())
            val q = "${tr.title} $cleanArt".trim()
            q.takeIf { it.length >= 3 }
        }

        val cleanSearchSeeds = recentSearchQueries
            .map { it.trim() }
            .filter { it.length >= 2 && isValidArtistName(it) }
            .distinctBy { it.lowercase() }
            .take(4)

        val seedQueries = (artistQueries + trackQueries + cleanSearchSeeds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(10)
            .ifEmpty {
                listOf(
                    "Manipuri latest official hit songs",
                    "Imphal Manipuri trending songs",
                    "Trending Hits",
                    "Indie Pop Chill"
                )
            }

        val likedIds = likedTracks.map { it.sourceId }.toSet()
        val recentHistoryIds = listeningHistory.take(8).map { it.sourceId }.toSet()

        val searchBatchesDeferred = seedQueries.map { query ->
            async {
                runCatching {
                    spotifyMetadataProvider.searchTracksWithPreviews(query, limit = 10)
                }.getOrDefault(emptyList())
            }
        }

        val automixBatches = automixDeferred.awaitAll().filter { it.isNotEmpty() }
        val searchBatches = searchBatchesDeferred.awaitAll().filter { it.isNotEmpty() }
        val allBatches = automixBatches + searchBatches

        // Interleave results across Automix and search batches so one artist doesn't dominate consecutive pages
        val interleaved = mutableListOf<NormalizedTrack>()
        val maxBatchSize = allBatches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxBatchSize) {
            for (batch in allBatches) {
                if (i < batch.size) {
                    interleaved.add(batch[i])
                }
            }
        }

        val seenSourceIds = HashSet<String>(alreadySeenIds)
        val seenSignatures = HashSet<String>()

        val deduplicated = interleaved
            .filter { !it.previewUrl.isNullOrBlank() }
            .filter { track ->
                val cleanArt = cleanPrimaryArtist(track.artists.firstOrNull().orEmpty())
                val sig = "${track.title.lowercase().trim()}|${cleanArt.lowercase()}"
                isValidArtistName(cleanArt) && seenSourceIds.add(track.sourceId) && seenSignatures.add(sig)
            }
            .map { track ->
                track.copy(
                    isPlayableNatively = true,
                    isLiked = track.sourceId in likedIds
                )
            }

        // Prioritize fresh personalized discoveries ahead of songs just played in the immediate session
        val freshDiscoveries = deduplicated.filterNot { it.sourceId in recentHistoryIds }
        val familiarFavorites = deduplicated.filter { it.sourceId in recentHistoryIds }

        // Apply round-robin artist diversity so the user gets a smooth mix across their favorite history styles
        val byArtist = LinkedHashMap<String, MutableList<NormalizedTrack>>()
        for (t in (freshDiscoveries + familiarFavorites)) {
            val key = cleanPrimaryArtist(t.artists.firstOrNull().orEmpty()).lowercase().ifBlank { t.sourceId }
            byArtist.getOrPut(key) { mutableListOf() }.add(t)
        }
        val diverseOut = mutableListOf<NormalizedTrack>()
        var addedAny = true
        while (addedAny) {
            addedAny = false
            for ((_, queue) in byArtist) {
                if (queue.isNotEmpty()) {
                    diverseOut.add(queue.removeAt(0))
                    addedAny = true
                }
            }
        }
        diverseOut
    }
}
