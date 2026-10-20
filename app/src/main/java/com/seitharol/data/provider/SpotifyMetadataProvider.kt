package com.seitharol.data.provider

import android.util.Log
import com.example.network.NetworkClients
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

interface SpotifyMetadataProvider {
    suspend fun searchTracksWithPreviews(query: String, limit: Int = 10): List<NormalizedTrack>
    suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack?
}

class DefaultSpotifyMetadataProvider : SpotifyMetadataProvider {
    private val TAG = "SpotifyMetadataProvider"

    override suspend fun searchTracksWithPreviews(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()

            if (clean.contains("open.spotify.com/track/")) {
                val trackId = clean.substringAfter("track/").substringBefore("?").substringBefore("/")
                resolveSpotifyEmbedTrack(trackId)?.let { return@withContext listOf(it) }
            }

            val results = mutableListOf<NormalizedTrack>()

            // 1. Primary 30s Preview Provider: Deezer API (Provides 30s MP3 preview + real Artist Profile Picture!)
            try {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val deezerUrl = "https://api.deezer.com/search?q=$encoded&limit=${limit * 2}"
                val request = Request.Builder()
                    .url(deezerUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val dataArr = JSONObject(body).optJSONArray("data")
                        if (dataArr != null) {
                            for (i in 0 until dataArr.length()) {
                                val item = dataArr.optJSONObject(i) ?: continue
                                val preview = item.optString("preview")
                                if (preview.isBlank() || !preview.startsWith("http")) continue

                                val id = item.optLong("id").toString()
                                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                                val durationSec = item.optLong("duration", 210L)
                                val artistObj = item.optJSONObject("artist")
                                val artistName = artistObj?.optString("name").orEmpty().ifBlank { "Unknown Artist" }
                                val artistAvatar = artistObj?.optString("picture_medium")
                                    ?.ifBlank { artistObj.optString("picture_big") }
                                    .orEmpty()
                                val albumObj = item.optJSONObject("album")
                                val albumTitle = albumObj?.optString("title") ?: "Single"
                                val artUrl = albumObj?.optString("cover_xl")
                                    ?.ifBlank { albumObj.optString("cover_big") }
                                    .orEmpty()

                                results.add(
                                    NormalizedTrack.fromSpotifyPreview(
                                        spotifyId = "dz_$id",
                                        title = title,
                                        artists = listOf(artistName),
                                        artistId = null,
                                        artistAvatarUrl = artistAvatar.ifBlank { artUrl },
                                        album = albumTitle,
                                        durationMs = durationSec * 1000L,
                                        artUrl = artUrl,
                                        previewUrl = preview
                                    )
                                )
                                if (results.size >= limit) break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deezer preview fetch fallback: ${e.message}")
            }

            // 2. Secondary Fallback: Apple iTunes 30s M4A Previews
            if (results.size < limit) {
                try {
                    val encoded = URLEncoder.encode(clean, "UTF-8")
                    val itunesUrl = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&country=IN&limit=${limit * 2}"
                    val request = Request.Builder()
                        .url(itunesUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val arr = JSONObject(body).optJSONArray("results")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    if (results.size >= limit) break
                                    val item = arr.optJSONObject(i) ?: continue
                                    val previewUrl = item.optString("previewUrl")
                                    if (previewUrl.isBlank() || !previewUrl.startsWith("http")) continue

                                    val trackId = item.optLong("trackId").toString()
                                    val title = item.optString("trackName").takeIf { it.isNotBlank() } ?: continue
                                    val artist = item.optString("artistName").ifBlank { "Unknown Artist" }
                                    val album = item.optString("collectionName").ifBlank { "Single" }
                                    val durationMs = item.optLong("trackTimeMillis", 210_000L)
                                    val artUrl = item.optString("artworkUrl100").replace("100x100bb", "600x600bb")

                                    val duplicate = results.any {
                                        it.title.equals(title, ignoreCase = true) &&
                                            it.artists.firstOrNull().equals(artist, ignoreCase = true)
                                    }
                                    if (!duplicate) {
                                        results.add(
                                            NormalizedTrack.fromSpotifyPreview(
                                                spotifyId = "it_$trackId",
                                                title = title,
                                                artists = listOf(artist),
                                                artistAvatarUrl = artUrl,
                                                album = album,
                                                durationMs = durationMs,
                                                artUrl = artUrl,
                                                previewUrl = previewUrl
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "iTunes preview fetch failed: ${e.message}")
                }
            }

            // 3. Regional / Personalized YouTube Music 30s Chorus Clipping Fallback
            // Ensures Manipuri songs & niche personalized tracks ALWAYS load with a real 30s preview!
            if (results.size < limit) {
                runCatching {
                    val ytmTracks = InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = limit)
                    for (yt in ytmTracks) {
                        if (results.size >= limit) break
                        val duplicate = results.any {
                            it.title.equals(yt.title, ignoreCase = true) &&
                                it.artists.firstOrNull().equals(yt.artist, ignoreCase = true)
                        }
                        if (!duplicate) {
                            results.add(
                                NormalizedTrack(
                                    source = TrackSource.YOUTUBE,
                                    sourceId = yt.videoId,
                                    title = yt.title,
                                    artists = listOf(yt.artist),
                                    primaryArtistBrowseId = yt.artistBrowseId,
                                    artistAvatarUrl = yt.thumbnailUrl,
                                    album = yt.albumName ?: "Single",
                                    durationMs = yt.durationSeconds * 1000L,
                                    artUrl = yt.thumbnailUrl,
                                    isPlayableNatively = true,
                                    previewUrl = "ytm_clip:${yt.videoId}" // 30s chorus clip resolved by AudioPlayerManager
                                )
                            )
                        }
                    }
                }
            }

            results.take(limit)
        }

    override suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack? =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = Jsoup.connect("https://open.spotify.com/embed/track/$spotifyTrackId")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(8000)
                    .get()
                val scriptHtml = doc.selectFirst("script#__NEXT_DATA__")?.html() ?: return@withContext null
                val entity = JSONObject(scriptHtml)
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity") ?: return@withContext null

                val id = entity.optString("id").ifBlank { spotifyTrackId }
                val title = entity.optString("title").takeIf { it.isNotBlank() } ?: return@withContext null
                val previewUrl = entity.optJSONObject("audioPreview")?.optString("url")
                    ?.takeIf { it.startsWith("http") } ?: return@withContext null

                val artists = mutableListOf<String>()
                val artistsArr = entity.optJSONArray("artists")
                if (artistsArr != null) {
                    for (i in 0 until artistsArr.length()) {
                        val name = artistsArr.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) artists.add(name)
                    }
                }

                val coverSources = entity.optJSONObject("visualIdentity")?.optJSONObject("image")
                val artUrl = coverSources?.optJSONArray("sources")
                    ?.let { arr -> arr.optJSONObject(arr.length() - 1)?.optString("url") }
                    .orEmpty()

                val durationMs = entity.optLong("duration", 210_000L)

                NormalizedTrack(
                    source = TrackSource.SPOTIFY,
                    sourceId = id,
                    title = title,
                    artists = artists.ifEmpty { listOf("Unknown Artist") },
                    artistAvatarUrl = artUrl,
                    durationMs = durationMs,
                    artUrl = artUrl,
                    isPlayableNatively = false,
                    previewUrl = previewUrl
                )
            }.getOrNull()
        }
}
