package com.example.resolver.providers

import android.util.Log
import com.example.network.NetworkClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * On-Device Read-Only Spotify Metadata Provider.
 * Allows searching and importing Spotify tracks and playlists.
 * All tracks are marked with isPlayableNatively = false so they are routed through TrackMatcher
 * to be played/downloaded via on-device YouTubeInnerTubeProvider or JioSaavnProvider.
 */
class SpotifyMetadataProvider : MetadataProvider {

    override val source: SourceType = SourceType.SPOTIFY

    private val TAG = "SpotifyMetadataProvider"
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    private suspend fun getAnonymousToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!cachedToken.isNullOrBlank() && now < tokenExpiresAtMs - 60000L) {
            return@withContext cachedToken
        }

        try {
            val url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://open.spotify.com/")
                .build()

            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val token = json.optString("accessToken")
                    val exp = json.optLong("accessTokenExpirationTimestampMs", now + 3600000L)
                    if (token.isNotBlank()) {
                        cachedToken = token
                        tokenExpiresAtMs = exp
                        return@withContext token
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Spotify anonymous web token: ${e.message}")
        }
        return@withContext null
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            if (query.trim().isBlank()) return@withContext emptyList()
            val token = getAnonymousToken()

            if (token != null) {
                try {
                    val enc = URLEncoder.encode(query.trim(), "UTF-8")
                    val url = "https://api.spotify.com/v1/search?type=track&q=$enc&limit=$limit"
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            return@withContext parseSpotifySearchJson(body)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Spotify search via API failed: ${e.message}")
                }
            }

            // Fallback for search: If user pasted a Spotify track or playlist URL
            if (query.contains("spotify.com/playlist/") || query.startsWith("spotify:playlist:")) {
                return@withContext getPlaylistTracks(query)
            }
            emptyList()
        }

    override suspend fun getPlaylistTracks(playlistId: String): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val cleanId = playlistId
                .removePrefix("spotify:playlist:")
                .removePrefix("https://open.spotify.com/playlist/")
                .substringBefore("?")
                .trim()

            val token = getAnonymousToken()
            if (token != null) {
                try {
                    val url = "https://api.spotify.com/v1/playlists/$cleanId/tracks?limit=50"
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val tracks = parseSpotifyPlaylistJson(body)
                            if (tracks.isNotEmpty()) return@withContext tracks
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getPlaylistTracks failed: ${e.message}", e)
                }
            }

            // Fallback: Embed scraper without needing token
            fetchPlaylistViaEmbed(cleanId)
        }

    private fun fetchPlaylistViaEmbed(playlistId: String): List<NormalizedTrack> {
        try {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://open.spotify.com/")
                .build()

            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                val start = body.indexOf(marker)
                if (start == -1) return emptyList()
                val jsonStart = start + marker.length
                val jsonEnd = body.indexOf("</script>", jsonStart)
                if (jsonEnd == -1) return emptyList()

                val jsonStr = body.substring(jsonStart, jsonEnd).trim()
                val nextData = JSONObject(jsonStr)
                val entity = nextData.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                val trackList = entity?.optJSONArray("trackList") ?: return emptyList()

                val results = mutableListOf<NormalizedTrack>()
                for (i in 0 until trackList.length()) {
                    val item = trackList.optJSONObject(i) ?: continue
                    val title = item.optString("title", item.optString("name", ""))
                    val rawUri = item.optString("uri", "")
                    val id = if (rawUri.isNotBlank()) rawUri.substringAfterLast(":") else "spot_$i"
                    val subtitle = item.optString("subtitle", "")
                    val durationMs = item.optLong("duration", 210000L)
                    val artUrl = item.optString("coverArt", item.optString("thumbnailUrl", ""))

                    val artists = if (subtitle.isNotBlank()) {
                        subtitle.split(",").map { it.trim() }
                    } else listOf("Various Artists")

                    if (title.isNotBlank()) {
                        results.add(
                            NormalizedTrack(
                                source = SourceType.SPOTIFY,
                                sourceId = id,
                                title = title,
                                artists = artists,
                                album = "Spotify Playlist",
                                durationMs = durationMs,
                                artUrl = artUrl,
                                isPlayableNatively = false
                            )
                        )
                    }
                }
                return results
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlaylistViaEmbed failed: ${e.message}")
            return emptyList()
        }
    }

    private fun parseSpotifySearchJson(body: String): List<NormalizedTrack> {
        val json = JSONObject(body)
        val tracksObj = json.optJSONObject("tracks") ?: return emptyList()
        val items = tracksObj.optJSONArray("items") ?: return emptyList()
        return parseTrackItems(items)
    }

    private fun parseSpotifyPlaylistJson(body: String): List<NormalizedTrack> {
        val json = JSONObject(body)
        val items = json.optJSONArray("items") ?: return emptyList()
        val trackItems = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            val trk = item?.optJSONObject("track")
            if (trk != null) trackItems.put(trk)
        }
        return parseTrackItems(trackItems)
    }

    private fun parseTrackItems(items: JSONArray): List<NormalizedTrack> {
        val list = mutableListOf<NormalizedTrack>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = item.optString("name")
            if (id.isBlank() || name.isBlank()) continue

            val artists = mutableListOf<String>()
            val artistsArray = item.optJSONArray("artists")
            if (artistsArray != null) {
                for (j in 0 until artistsArray.length()) {
                    val aName = artistsArray.optJSONObject(j)?.optString("name")
                    if (!aName.isNullOrBlank()) artists.add(aName)
                }
            }

            val albumObj = item.optJSONObject("album")
            val albumName = albumObj?.optString("name") ?: "Single"

            var artUrl = ""
            val images = albumObj?.optJSONArray("images")
            if (images != null && images.length() > 0) {
                val img = images.getJSONObject(0).optString("url")
                if (img.isNotBlank()) artUrl = img
            }

            val durationMs = item.optLong("duration_ms", 210000L)
            val isrc = item.optJSONObject("external_ids")?.optString("isrc")

            list.add(
                NormalizedTrack(
                    source = SourceType.SPOTIFY,
                    sourceId = id,
                    title = name,
                    artists = if (artists.isEmpty()) listOf("Various Artists") else artists,
                    album = albumName,
                    durationMs = durationMs,
                    artUrl = artUrl,
                    isrc = if (!isrc.isNullOrBlank()) isrc else null,
                    isPlayableNatively = false
                )
            )
        }
        return list
    }
}
