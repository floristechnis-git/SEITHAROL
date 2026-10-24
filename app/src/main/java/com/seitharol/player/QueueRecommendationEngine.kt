package com.seitharol.player

import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object QueueRecommendationEngine {

    private val GENERIC_BOLLYWOOD_LABELS = listOf(
        "t-series", "zee music", "yrf", "tips official", "saregama",
        "sony music india", "desi melodies", "venus", "speed records",
        "shemaroo", "eros now", "gaana", "bollywood", "hindi"
    )

    /**
     * Generates 12–15 high-affinity PlayQueue recommendations that stay 100% relevant
     * to [currentTrack] across the entire suggestion list (not just the first 4):
     * 1. For YouTube Video tracks: queries related YouTube Videos from the same creator/channel and topic.
     * 2. For Audio songs: combines primary artist catalog, contextual song/scene search, queue artist
     *    affinity, and filtered YouTube Music Automix (strictly blocking unrelated Hindi/Bollywood
     *    injections when the seed song is not a Hindi track).
     * 3. Strictly excludes all tracks in [queueTracks], [additionalExcludedIds], and
     *    [additionalExcludedSignatures] so recently played songs NEVER repeat.
     */
    suspend fun fetchRelevantRecommendations(
        currentTrack: TrackMetadata,
        queueTracks: List<TrackMetadata>,
        historyArtists: List<String> = emptyList(),
        limit: Int = 14,
        additionalExcludedIds: Set<String> = emptySet(),
        additionalExcludedSignatures: Set<String> = emptySet()
    ): List<TrackMetadata> = withContext(Dispatchers.IO) {
        val excludedIds = (queueTracks.map { it.id } + currentTrack.id + additionalExcludedIds).toHashSet()
        val excludedTitles = (
            queueTracks.map { normalizeTitle(it.title) } +
                normalizeTitle(currentTrack.title) +
                additionalExcludedSignatures.map { normalizeTitle(it.substringBefore("|")) }
            ).filter { it.isNotBlank() }.toHashSet()
        val excludedSigs = (
            queueTracks.map { signature(it.title, it.artist) } +
                signature(currentTrack.title, currentTrack.artist) +
                additionalExcludedSignatures
            ).toHashSet()

        val primaryArtist = currentTrack.artist
            .removeSuffix(" - Topic")
            .removeSuffix("- Topic")
            .split(",", "&", "feat.", "ft.")
            .firstOrNull()
            ?.trim()
            .orEmpty()

        val cleanTitleWords = normalizeTitle(currentTrack.title)
            .split(" ")
            .filter { it.length >= 3 && it !in setOf("official", "video", "audio", "song", "lyrics", "full", "new", "hd", "4k") }
            .take(4)
            .joinToString(" ")

        val isVideoSeed = currentTrack.album.equals("YouTube Video", ignoreCase = true) ||
            currentTrack.source.equals("YOUTUBE_VIDEO", ignoreCase = true)

        val results = mutableListOf<TrackMetadata>()

        if (isVideoSeed) {
            coroutineScope {
                val q1 = if (primaryArtist.isNotBlank() && cleanTitleWords.isNotBlank()) {
                    "$primaryArtist $cleanTitleWords"
                } else {
                    "${currentTrack.title} ${currentTrack.artist}".trim()
                }
                val q2 = if (primaryArtist.isNotBlank()) primaryArtist else currentTrack.title
                val q3 = if (cleanTitleWords.isNotBlank()) "$cleanTitleWords video" else currentTrack.title

                val job1 = async { InnerTubeStreamExtractor.searchAllYoutubeVideos(q1, limit = 10) }
                val job2 = async { InnerTubeStreamExtractor.searchAllYoutubeVideos(q2, limit = 10) }
                val job3 = async {
                    if (!q3.equals(q1, ignoreCase = true) && !q3.equals(q2, ignoreCase = true)) {
                        InnerTubeStreamExtractor.searchAllYoutubeVideos(q3, limit = 8)
                    } else emptyList()
                }

                val combinedVideos = interleaveBatches(listOf(job1.await(), job2.await(), job3.await()))
                for (cand in combinedVideos) {
                    if (results.size >= limit) break
                    val normTitle = normalizeTitle(cand.title)
                    val sig = signature(cand.title, cand.artist)
                    if (cand.videoId in excludedIds || normTitle in excludedTitles || sig in excludedSigs) continue
                    excludedIds.add(cand.videoId)
                    excludedTitles.add(normTitle)
                    excludedSigs.add(sig)
                    results.add(
                        TrackMetadata(
                            id = cand.videoId,
                            title = cand.title,
                            artist = cand.artist,
                            album = "YouTube Video",
                            durationSeconds = cand.durationSeconds,
                            thumbnailUrl = cand.thumbnailUrl,
                            source = "YOUTUBE_VIDEO"
                        )
                    )
                }
            }
            return@withContext results.take(limit)
        }

        // Audio Song Recommendations
        val seedCombinedLower = "${currentTrack.title} ${currentTrack.artist} ${currentTrack.album}".lowercase()
        val isSeedHindiOrBollywood = GENERIC_BOLLYWOOD_LABELS.any { seedCombinedLower.contains(it) }
        val isSeedManipuri = seedCombinedLower.contains("manipuri") ||
            seedCombinedLower.contains("meitei") ||
            seedCombinedLower.contains("imphal") ||
            queueTracks.any {
                val ql = "${it.title} ${it.artist}".lowercase()
                ql.contains("manipuri") || ql.contains("meitei")
            }

        coroutineScope {
            // 1. Same-Artist Official Tracks (high relevance)
            val primaryArtistDeferred = async {
                if (primaryArtist.isNotBlank() && !primaryArtist.equals("Unknown Artist", true)) {
                    InnerTubeStreamExtractor.searchOfficialYtmSongs("$primaryArtist official songs", limit = 12)
                        .filterNot { it.videoType == "MUSIC_VIDEO_TYPE_OMV" || it.albumName == "YouTube Video" }
                } else emptyList()
            }

            // 2. Contextual Song / Scene Affinity Search (ensures full 12+ relevant tracks for the song)
            val contextualDeferred = async {
                val contextQuery = when {
                    isSeedManipuri && primaryArtist.isNotBlank() -> "$primaryArtist Manipuri song"
                    isSeedManipuri -> "$cleanTitleWords Manipuri song"
                    primaryArtist.isNotBlank() && cleanTitleWords.isNotBlank() -> "$cleanTitleWords $primaryArtist"
                    cleanTitleWords.isNotBlank() -> "$cleanTitleWords official audio"
                    else -> "${currentTrack.title} official audio"
                }
                InnerTubeStreamExtractor.searchOfficialYtmSongs(contextQuery, limit = 12)
                    .filterNot { it.videoType == "MUSIC_VIDEO_TYPE_OMV" || it.albumName == "YouTube Video" }
            }

            // 3. Secondary Scene / Queue Artist Search
            val queueArtists = (queueTracks.map {
                it.artist.removeSuffix(" - Topic").split(",", "&").first().trim()
            } + historyArtists)
                .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) && !it.equals(primaryArtist, true) }
                .distinct()
                .take(2)

            val secondaryDeferred = async {
                val queries = buildList {
                    if (isSeedManipuri) add("Manipuri official hit songs")
                    queueArtists.forEach { add("$it official songs") }
                }
                queries.flatMap { q ->
                    InnerTubeStreamExtractor.searchOfficialYtmSongs(q, limit = 8)
                        .filterNot { it.videoType == "MUSIC_VIDEO_TYPE_OMV" || it.albumName == "YouTube Video" }
                }
            }

            // 4. Filtered YouTube Music Automix (strictly excluding unrelated Bollywood/Hindi injections)
            val automixDeferred = async {
                val rawAutomix = if (currentTrack.id.length == 11) {
                    InnerTubeStreamExtractor.fetchAutomixQueue(currentTrack.id, limit = limit + 10)
                } else {
                    val resolvedSeed = InnerTubeStreamExtractor.searchOfficialYtmSongs(
                        "${currentTrack.title} $primaryArtist".trim(),
                        limit = 2
                    ).firstOrNull()
                    if (resolvedSeed != null) {
                        InnerTubeStreamExtractor.fetchAutomixQueue(resolvedSeed.videoId, limit = limit + 10)
                    } else emptyList()
                }
                rawAutomix.filter { cand ->
                    val candLower = "${cand.title} ${cand.artist} ${cand.channelName}".lowercase()
                    if (!isSeedHindiOrBollywood && GENERIC_BOLLYWOOD_LABELS.any { candLower.contains(it) }) {
                        false
                    } else {
                        true
                    }
                }
            }

            val artistTracks = primaryArtistDeferred.await()
            val contextualTracks = contextualDeferred.await()
            val automixTracks = automixDeferred.await()
            val secondaryTracks = secondaryDeferred.await()

            // Prioritize same-artist & contextual tracks + verified automix tracks so all 12-15 items are relevant
            val orderedCandidates = interleaveBatches(
                listOf(artistTracks, contextualTracks, automixTracks, secondaryTracks)
            )

            for (cand in orderedCandidates) {
                if (results.size >= limit) break
                val normTitle = normalizeTitle(cand.title)
                val sig = signature(cand.title, cand.artist)
                if (cand.videoId in excludedIds || normTitle in excludedTitles || sig in excludedSigs) continue
                if (StreamResolver.containsUnwantedVariant(currentTrack.title, cand.title)) continue

                excludedIds.add(cand.videoId)
                excludedTitles.add(normTitle)
                excludedSigs.add(sig)
                results.add(
                    TrackMetadata(
                        id = cand.videoId,
                        title = cand.title,
                        artist = cand.artist,
                        album = cand.albumName.orEmpty(),
                        durationSeconds = cand.durationSeconds,
                        thumbnailUrl = cand.thumbnailUrl,
                        source = "YOUTUBE"
                    )
                )
            }
        }

        results.take(limit)
    }

    private fun interleaveBatches(batches: List<List<CandidateStream>>): List<CandidateStream> {
        val out = mutableListOf<CandidateStream>()
        val maxLen = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (batch in batches) {
                if (i < batch.size) {
                    out.add(batch[i])
                }
            }
        }
        return out
    }

    private fun normalizeTitle(raw: String): String =
        raw.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()

    private fun signature(title: String, artist: String): String =
        "${normalizeTitle(title)}|${artist.lowercase().trim()}"
}
