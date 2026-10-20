package com.praxper.seitharol.data

import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.math.abs

data class SongItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val streamUrl: String? = null,
    val localFilePath: String? = null,
    val category: String = "All"
)

data class PlaylistItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val isLikedPlaylist: Boolean = false,
    val songs: List<SongItem> = emptyList()
)

class MusicResolverRepository(private val client: OkHttpClient = OkHttpClient()) {

    private val unwantedKeywords = listOf(
        "remix", "slowed", "reverb", "8d audio", "karaoke",
        "instrumental", "nightcore", "sped up", "cover", "mashup", "dj "
    )

    /**
     * Searches official YouTube Music Songs
     * and filters out remixes/fake versions unless explicitly requested.
     */
    suspend fun searchOfficialSongs(
        query: String,
        expectedArtist: String? = null,
        expectedDurationMs: Long? = null
    ): List<SongItem> = withContext(Dispatchers.IO) {
        val candidates = runCatching {
            InnerTubeStreamExtractor.searchOfficialYtmSongs(query, limit = 25)
        }.getOrDefault(emptyList())

        val rawSongs = candidates.map { c ->
            SongItem(
                id = c.videoId,
                title = c.title,
                artist = c.artist,
                durationMs = c.durationSeconds * 1000L,
                thumbnailUrl = c.thumbnailUrl
            )
        }

        val queryLower = query.lowercase()
        // Filter out remixes/fakes unless the user query specifically asked for a remix
        val filtered = rawSongs.filter { song ->
            val candidateTitle = "${song.title} ${song.artist}".lowercase()
            unwantedKeywords.none { kw ->
                candidateTitle.contains(kw) && !queryLower.contains(kw)
            }
        }.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }

        // If matching a specific song (e.g. from Spotify or playlist), rank by artist & duration
        if (expectedDurationMs != null && expectedDurationMs > 0) {
            filtered.sortedBy { song ->
                val durationPenalty = abs(song.durationMs - expectedDurationMs)
                val artistPenalty = if (
                    expectedArtist != null &&
                    song.artist.contains(expectedArtist, ignoreCase = true)
                ) 0L else 15_000L
                durationPenalty + artistPenalty
            }
        } else {
            filtered
        }
    }

    /**
     * Resolves a Spotify Track or Playlist URL without requiring a paid/expired OAuth token
     * by reading Spotify's embed __NEXT_DATA__ JSON and matching official YTM audio.
     */
    suspend fun resolveSpotifyUrl(spotifyUrl: String): List<SongItem> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = spotifyUrl.substringBefore("?")
            val embedUrl = cleanUrl.replace("open.spotify.com/", "open.spotify.com/embed/")
            val doc = Jsoup.connect(embedUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()

            val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.html()
                ?: return@withContext emptyList()
            val root = JSONObject(nextDataScript)
            val entity = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity") ?: return@withContext emptyList()

            val results = mutableListOf<SongItem>()
            val type = entity.optString("type")

            if (type == "track") {
                val title = entity.optString("title")
                val artist = entity.optJSONArray("artists")
                    ?.optJSONObject(0)?.optString("name") ?: ""
                val durationMs = entity.optLong("duration", 0L)
                val match = searchOfficialSongs("$title $artist", artist, durationMs).firstOrNull()
                if (match != null) results.add(match)
            } else {
                val trackList = entity.optJSONArray("trackList") ?: JSONArray()
                for (i in 0 until minOf(trackList.length(), 25)) {
                    val tr = trackList.getJSONObject(i)
                    val title = tr.optString("title")
                    val artist = tr.optString("subtitle")
                    val durationMs = tr.optLong("duration", 0L)
                    val match = searchOfficialSongs("$title $artist", artist, durationMs).firstOrNull()
                    if (match != null) results.add(match)
                }
            }
            results.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
