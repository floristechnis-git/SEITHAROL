package com.seitharol.data.provider

import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface SpotifyMetadataProvider {
    suspend fun searchTracksWithPreviews(query: String, limit: Int = 10): List<NormalizedTrack>
    suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack?
}

class DefaultSpotifyMetadataProvider : SpotifyMetadataProvider {

    override suspend fun searchTracksWithPreviews(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()

            coroutineScope {
                // 1. JioSaavn Direct CDN Streams (0ms instant preview playback via decrypted URL)
                val saavnDeferred = async {
                    runCatching { searchJioSaavnTracks(clean, limit) }.getOrDefault(emptyList())
                }

                // 2. Official YouTube Music Songs
                val ytmDeferred = async {
                    runCatching {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = limit).map { yt ->
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
                                previewUrl = "ytm_clip:${yt.videoId}"
                            )
                        }
                    }.getOrDefault(emptyList())
                }

                val merged = mutableListOf<NormalizedTrack>()
                for (track in (saavnDeferred.await() + ytmDeferred.await())) {
                    val exists = merged.any {
                        it.title.equals(track.title, ignoreCase = true) &&
                            it.artists.firstOrNull().equals(track.artists.firstOrNull(), ignoreCase = true)
                    }
                    if (!exists && merged.size < limit) {
                        merged.add(track)
                    }
                }
                merged
            }
        }

    private fun searchJioSaavnTracks(query: String, limit: Int): List<NormalizedTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=android&q=$encoded&n=$limit"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 4500
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<NormalizedTrack>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title", obj.optString("song"))
                .replace("&quot;", "\"").replace("&amp;", "&")
            val moreInfo = obj.optJSONObject("more_info")
            val durationSec = moreInfo?.optString("duration")?.toLongOrNull() ?: 210L
            val artist = moreInfo?.optJSONObject("artistMap")
                ?.optJSONArray("primary_artists")
                ?.optJSONObject(0)
                ?.optString("name")
                ?: obj.optString("subtitle").ifBlank { "Unknown Artist" }
            val image = obj.optString("image").replace("150x150", "500x500")

            // Decrypt encrypted_media_url immediately so Discover preview starts in 0ms without re-querying!
            val encryptedUrl = obj.optString("encrypted_media_url").ifBlank {
                moreInfo?.optString("encrypted_media_url").orEmpty()
            }
            val directCdnUrl = if (encryptedUrl.isNotBlank()) {
                StreamResolver.decryptJioSaavnUrl(encryptedUrl, AudioQuality.LOW)
            } else null

            out.add(
                NormalizedTrack(
                    source = TrackSource.JIOSAAVN,
                    sourceId = id,
                    title = title,
                    artists = listOf(artist),
                    artistAvatarUrl = image,
                    album = moreInfo?.optString("album") ?: "Single",
                    durationMs = durationSec * 1000L,
                    artUrl = image,
                    isPlayableNatively = true,
                    previewUrl = directCdnUrl ?: "saavn_clip:$id"
                )
            )
        }
        return out
    }

    override suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack? = null
}
