package com.seitharol.data.provider

import android.util.Log
import com.example.network.NetworkClients
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

interface SpotifyMetadataProvider {
    suspend fun searchTracksWithPreviews(query: String, limit: Int = 10): List<NormalizedTrack>
}

class DefaultSpotifyMetadataProvider : SpotifyMetadataProvider {
    private val TAG = "DefaultSpotifyMetadataProvider"
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

    override suspend fun searchTracksWithPreviews(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            val token = getAnonymousToken()

            val results = mutableListOf<NormalizedTrack>()
            if (token != null) {
                try {
                    val enc = URLEncoder.encode(clean, "UTF-8")
                    val url = "https://api.spotify.com/v1/search?type=track&q=$enc&limit=${limit * 2}"
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = JSONObject(body)
                            val items = json.optJSONObject("tracks")?.optJSONArray("items")
                            if (items != null) {
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val id = item.optString("id")
                                    val name = item.optString("name")
                                    if (id.isBlank() || name.isBlank()) continue

                                    var previewUrl = item.optString("preview_url")
                                    if (previewUrl.isBlank() || previewUrl == "null") {
                                        previewUrl = "https://p.scdn.co/mp3-preview/$id"
                                    }

                                    val artists = mutableListOf<String>()
                                    val artistsArray = item.optJSONArray("artists")
                                    var primaryArtistId: String? = null
                                    if (artistsArray != null) {
                                        for (j in 0 until artistsArray.length()) {
                                            val aObj = artistsArray.optJSONObject(j) ?: continue
                                            val aName = aObj.optString("name")
                                            if (aName.isNotBlank()) {
                                                artists.add(aName)
                                                if (primaryArtistId == null) primaryArtistId = aObj.optString("id")
                                            }
                                        }
                                    }

                                    val albumObj = item.optJSONObject("album")
                                    val albumName = albumObj?.optString("name") ?: "Single"
                                    var artUrl = ""
                                    val images = albumObj?.optJSONArray("images")
                                    if (images != null && images.length() > 0) {
                                        artUrl = images.getJSONObject(0).optString("url")
                                    }

                                    val durationMs = item.optLong("duration_ms", 210000L)
                                    val isrc = item.optJSONObject("external_ids")?.optString("isrc")

                                    results.add(
                                        NormalizedTrack(
                                            source = TrackSource.SPOTIFY,
                                            sourceId = id,
                                            title = name,
                                            artists = if (artists.isEmpty()) listOf("Various Artists") else artists,
                                            primaryArtistBrowseId = primaryArtistId,
                                            album = albumName,
                                            durationMs = durationMs,
                                            artUrl = artUrl,
                                            isrc = isrc.takeIf { !it.isNullOrBlank() },
                                            isPlayableNatively = false,
                                            previewUrl = previewUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "searchTracksWithPreviews API call failed: ${e.message}")
                }
            }

            return@withContext results.take(limit)
        }
}
