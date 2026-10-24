package com.seitharol.player

import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QueueRecommendationEngine {

    /**
     * Generates high-affinity PlayQueue recommendations using:
     * 1. Real YouTube Music RDAMVM{videoId} Automix Watch-Next radio for 11-char YouTube IDs.
     * 2. Dominant artist + seed song contextual YouTube Music ATV search.
     * 3. JioSaavn station similarity fallback for numeric/Saavn IDs.
     *
     * Strictly excludes all tracks in [queueTracks], [additionalExcludedIds], and
     * [additionalExcludedSignatures] so recently played songs NEVER repeat in Autoplay.
     */
    suspend fun fetchRelevantRecommendations(
        currentTrack: TrackMetadata,
        queueTracks: List<TrackMetadata>,
        historyArtists: List<String> = emptyList(),
        limit: Int = 10,
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
        val results = mutableListOf<TrackMetadata>()

        coroutineScope {
            // 1. Primary: Real YouTube Music RDAMVM Automix Queue
            val automixDeferred = async {
                if (currentTrack.id.length == 11) {
                    InnerTubeStreamExtractor.fetchAutomixQueue(currentTrack.id, limit = limit + 10)
                } else {
                    val resolvedSeed = InnerTubeStreamExtractor.searchOfficialYtmSongs(
                        "${currentTrack.title} ${currentTrack.artist}",
                        limit = 2
                    ).firstOrNull()
                    if (resolvedSeed != null) {
                        InnerTubeStreamExtractor.fetchAutomixQueue(resolvedSeed.videoId, limit = limit + 10)
                    } else emptyList()
                }
            }

            // 2. Secondary: Artist + Queue taste affinity search
            val primaryArtist = currentTrack.artist.split(",", "&", "feat.").first().trim()
            val queueArtists = (queueTracks.map { it.artist.split(",", "&").first().trim() } + historyArtists)
                .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) && !it.equals(primaryArtist, true) }
                .distinct()
                .take(2)

            val artistSearchDeferred = async {
                val queries = buildList {
                    add("$primaryArtist official songs")
                    queueArtists.forEach { add("$it official songs") }
                }
                queries.flatMap { q ->
                    InnerTubeStreamExtractor.searchOfficialYtmSongs(q, limit = 6)
                }
            }

            val automixTracks = automixDeferred.await()
            val artistTracks = artistSearchDeferred.await()

            for (cand in (automixTracks + artistTracks)) {
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

        // 3. Tertiary fallback: JioSaavn reco if still under limit
        if (results.size < limit) {
            fetchSaavnStationRecommendations(currentTrack.id, excludedIds, excludedTitles, excludedSigs, results)
        }

        results.take(limit)
    }

    private fun normalizeTitle(raw: String): String =
        raw.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()

    private fun signature(title: String, artist: String): String =
        "${normalizeTitle(title)}|${artist.lowercase().trim()}"

    private fun fetchSaavnStationRecommendations(
        songId: String,
        excludedIds: MutableSet<String>,
        excludedTitles: MutableSet<String>,
        excludedSigs: MutableSet<String>,
        out: MutableList<TrackMetadata>
    ) {
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=reco.getreco&_format=json&_marker=0&cc=in&pid=${URLEncoder.encode(songId, "UTF-8")}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            }
            if (conn.responseCode !in 200..299) return
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("title", obj.optString("song"))
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .takeIf { it.isNotBlank() } ?: continue
                val norm = normalizeTitle(title)
                val moreInfo = obj.optJSONObject("more_info")
                val artist = moreInfo?.optJSONObject("artistMap")
                    ?.optJSONArray("primary_artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    ?: obj.optString("primary_artists").ifBlank { "Unknown Artist" }
                val sig = signature(title, artist)
                if (id in excludedIds || norm in excludedTitles || sig in excludedSigs) continue

                val image = obj.optString("image").replace("150x150", "500x500")
                val duration = moreInfo?.optString("duration")?.toIntOrNull() ?: 210
                val encryptedUrl = moreInfo?.optString("encrypted_media_url")

                excludedIds.add(id)
                excludedTitles.add(norm)
                excludedSigs.add(sig)
                out.add(
                    TrackMetadata(
                        id = id,
                        title = title,
                        artist = artist,
                        durationSeconds = duration,
                        thumbnailUrl = image,
                        encryptedMediaUrl = encryptedUrl
                    )
                )
            }
        } catch (_: Exception) {
        }
    }
}
