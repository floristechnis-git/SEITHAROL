// java/com/seitharol/player/QueueRecommendationEngine.kt
package com.seitharol.player

import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QueueRecommendationEngine {

    /**
     * Generates recommendations strictly relevant to [currentTrack] and [queueTracks].
     */
    suspend fun fetchRelevantRecommendations(
        currentTrack: TrackMetadata,
        queueTracks: List<TrackMetadata>,
        limit: Int = 8
    ): List<TrackMetadata> = withContext(Dispatchers.IO) {
        val excludedIds = (queueTracks.map { it.id } + currentTrack.id).toSet()
        val excludedTitles = (queueTracks.map { it.title.lowercase().trim() } + currentTrack.title.lowercase().trim()).toSet()
        val results = mutableListOf<TrackMetadata>()

        // 1. Try JioSaavn's native song similarity API (reco.getreco) if ID is numeric/Saavn
        fetchSaavnStationRecommendations(currentTrack.id, excludedIds, excludedTitles, results)

        // 2. Query by Primary Artist + dominant queue artists to keep the exact vibe/language
        if (results.size < limit) {
            val primaryArtist = currentTrack.artist.split(",", "&", "feat.").first().trim()
            val queueArtists = queueTracks
                .map { it.artist.split(",", "&").first().trim() }
                .distinct()
                .take(2)

            val queries = buildList {
                add("$primaryArtist ${currentTrack.title}")
                add(primaryArtist)
                queueArtists.forEach { if (!it.equals(primaryArtist, true)) add(it) }
            }

            for (query in queries) {
                if (results.size >= limit) break
                searchContextualTracks(query, currentTrack.title, excludedIds, excludedTitles, results, limit)
            }
        }

        results.take(limit)
    }

    private fun fetchSaavnStationRecommendations(
        songId: String,
        excludedIds: Set<String>,
        excludedTitles: Set<String>,
        out: MutableList<TrackMetadata>
    ) {
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=reco.getreco&_format=json&_marker=0&cc=in&pid=${URLEncoder.encode(songId, "UTF-8")}"
            val raw = httpGet(url) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val track = parseSaavnSongObject(obj) ?: continue
                if (track.id !in excludedIds && track.title.lowercase().trim() !in excludedTitles) {
                    out.add(track)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun searchContextualTracks(
        query: String,
        seedTitle: String,
        excludedIds: Set<String>,
        excludedTitles: Set<String>,
        out: MutableList<TrackMetadata>,
        limit: Int
    ) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&q=$encoded&n=10"
            val raw = httpGet(url) ?: return
            val resultsArr = JSONObject(raw).optJSONArray("results") ?: return

            for (i in 0 until resultsArr.length()) {
                if (out.size >= limit) break
                val obj = resultsArr.getJSONObject(i)
                val track = parseSaavnSongObject(obj) ?: continue
                val normTitle = track.title.lowercase().trim()
                if (track.id in excludedIds || normTitle in excludedTitles) continue
                if (out.any { it.id == track.id || it.title.equals(track.title, true) }) continue
                if (StreamResolver.containsUnwantedVariant(seedTitle, track.title)) continue
                out.add(track)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseSaavnSongObject(obj: JSONObject): TrackMetadata? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title", obj.optString("song"))
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() } ?: return null
        val moreInfo = obj.optJSONObject("more_info")
        val artist = moreInfo?.optJSONObject("artistMap")
            ?.optJSONArray("primary_artists")
            ?.optJSONObject(0)
            ?.optString("name")
            ?: obj.optString("primary_artists")
            .ifBlank { obj.optString("subtitle") }
        val image = obj.optString("image").replace("150x150", "500x500")
        val duration = moreInfo?.optString("duration")?.toIntOrNull() ?: 0
        val encryptedUrl = moreInfo?.optString("encrypted_media_url") ?: obj.optString("encrypted_media_url")

        return TrackMetadata(
            id = id,
            title = title,
            artist = artist,
            durationSeconds = duration,
            thumbnailUrl = image,
            encryptedMediaUrl = encryptedUrl.ifBlank { null }
        )
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}
