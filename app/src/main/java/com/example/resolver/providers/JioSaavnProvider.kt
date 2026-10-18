package com.example.resolver.providers

import android.util.Base64
import android.util.Log
import com.example.network.NetworkClients
import com.example.resolver.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnProvider : AudioStreamProvider {

    override val source: SourceType = SourceType.JIOSAAVN

    private val TAG = "JioSaavnProvider"
    private val desKey = "38346591".toByteArray(Charsets.UTF_8)

    private val trackCache = ConcurrentHashMap<String, SaavnTrackInfo>()

    data class SaavnTrackInfo(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String,
        val encryptedMediaUrl: String,
        val previewUrl: String,
        val has320: Boolean,
        val durationMs: Long
    )

    suspend fun getTrackInfo(songId: String): SaavnTrackInfo? = withContext(Dispatchers.IO) {
        trackCache[songId] ?: fetchSongDetails(songId)?.also { trackCache[songId] = it }
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            if (query.trim().isBlank()) return@withContext emptyList()
            try {
                val encQuery = URLEncoder.encode(query.trim(), "UTF-8")
                val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=web6dot0&q=$encQuery&n=$limit"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.jiosaavn.com/")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parseResultsJson(body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    override suspend fun getPlaylistTracks(playlistId: String): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://www.jiosaavn.com/api.php?__call=playlist.getDetails&listid=$playlistId&api_version=4&_format=json&_marker=0&ctx=web6dot0"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val json = JSONObject(body)
                    val songsArray = json.optJSONArray("songs")
                        ?: json.optJSONArray("list")
                        ?: json.optJSONObject("more_info")?.optJSONArray("songs")
                    if (songsArray != null) parseSongsArray(songsArray) else emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    override suspend fun resolveStream(sourceId: String, preferredKbps: Int): ResolvedStream =
        withContext(Dispatchers.IO) {
            val info = getTrackInfo(sourceId)
                ?: throw IllegalStateException("Failed to fetch JioSaavn details for id $sourceId")

            val decryptedUrl = when {
                info.encryptedMediaUrl.isNotBlank() -> {
                    decryptMediaUrl(
                        encryptedUrl = info.encryptedMediaUrl,
                        allow320 = info.has320 && preferredKbps >= 256
                    )
                }
                info.previewUrl.isNotBlank() -> {
                    val targetSuffix = if (info.has320 && preferredKbps >= 256) "_320.mp4" else "_160.mp4"
                    info.previewUrl
                        .replace("preview.saavncdn.com", "aac.saavncdn.com")
                        .replace("_96_p.mp4", targetSuffix)
                        .replace("http://", "https://")
                }
                else -> throw IllegalStateException("No encrypted_media_url or previewUrl for JioSaavn id $sourceId")
            }

            val kbps = if (decryptedUrl.contains("_320.mp4")) 320 else 160

            ResolvedStream(
                videoId = sourceId,
                streamUrl = decryptedUrl,
                itag = 140,
                codec = if (kbps == 320) "AAC 320k" else "AAC 160k",
                bitrate = "$kbps kbps",
                expiresAtMs = System.currentTimeMillis() + (12 * 3600 * 1000L),
                isLocal = false,
                resolvedBy = "On-Device JioSaavn DES Decryptor ($kbps kbps)",
                latencyMs = 10
            )
        }

    fun decryptMediaUrl(encryptedUrl: String, allow320: Boolean = true): String {
        return try {
            val keySpec = SecretKeySpec(desKey, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)

            val decodedBytes = Base64.decode(encryptedUrl.trim(), Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            var url = String(decryptedBytes, Charsets.UTF_8).trim()

            url = if (allow320) {
                url.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
            } else {
                url.replace("_96.mp4", "_160.mp4")
            }

            if (url.startsWith("http://")) {
                url = "https://" + url.substring(7)
            }
            url
        } catch (e: Exception) {
            Log.e(TAG, "DES decryption failed: ${e.message}", e)
            throw IllegalStateException("DES stream decryption failed: ${e.message}")
        }
    }

    private fun fetchSongDetails(songId: String): SaavnTrackInfo? {
        return try {
            val url = "https://www.jiosaavn.com/api.php?__call=song.getDetails&pids=$songId&api_version=4&_format=json&_marker=0&ctx=web6dot0"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://www.jiosaavn.com/")
                .build()

            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val item = json.optJSONObject(songId)
                    ?: json.optJSONArray("songs")?.optJSONObject(0)
                    ?: return null
                parseSingleSong(item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSongDetails failed: ${e.message}")
            null
        }
    }

    private fun parseResultsJson(body: String): List<NormalizedTrack> {
        val json = JSONObject(body)
        val resultsArray = json.optJSONArray("results") ?: return emptyList()
        return parseSongsArray(resultsArray)
    }

    private fun parseSongsArray(array: JSONArray): List<NormalizedTrack> {
        val tracks = mutableListOf<NormalizedTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val info = parseSingleSong(item)
            if (info != null) {
                trackCache[info.id] = info
                tracks.add(
                    NormalizedTrack(
                        source = SourceType.JIOSAAVN,
                        sourceId = info.id,
                        title = info.title,
                        artists = listOf(info.artist),
                        album = info.album,
                        durationMs = info.durationMs,
                        artUrl = info.artUrl,
                        isPlayableNatively = true
                    )
                )
            }
        }
        return tracks
    }

    private fun parseSingleSong(item: JSONObject): SaavnTrackInfo? {
        val moreInfo = item.optJSONObject("more_info")
        val id = item.optString("id").ifBlank { item.optString("song_id") }
        if (id.isBlank()) return null

        val rawTitle = item.optString("song")
            .ifBlank { item.optString("title") }
            .ifBlank { "Unknown Track" }
        val title = cleanHtmlEntities(rawTitle)

        var rawArtist = item.optString("singers")
            .ifBlank { item.optString("primary_artists") }
            .ifBlank { moreInfo?.optString("singers") ?: "" }
            .ifBlank { moreInfo?.optString("music") ?: "" }

        if (rawArtist.isBlank()) {
            val primaryArtistsArr = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
                ?: moreInfo?.optJSONObject("artistMap")?.optJSONArray("artists")
            if (primaryArtistsArr != null && primaryArtistsArr.length() > 0) {
                rawArtist = primaryArtistsArr.optJSONObject(0)?.optString("name") ?: ""
            }
        }
        if (rawArtist.isBlank()) {
            rawArtist = item.optString("subtitle").substringBefore(" - ").trim()
        }
        val artist = cleanHtmlEntities(rawArtist.ifBlank { "Various Artists" })

        val rawAlbum = item.optString("album")
            .ifBlank { moreInfo?.optString("album") ?: "" }
            .ifBlank { "Single" }
        val album = cleanHtmlEntities(rawAlbum)

        val durationSec = item.optLong("duration", 0L).let {
            if (it > 0L) it else (moreInfo?.optLong("duration", 210L) ?: 210L)
        }

        val encryptedUrl = item.optString("encrypted_media_url")
            .ifBlank { moreInfo?.optString("encrypted_media_url") ?: "" }

        val previewUrl = item.optString("media_preview_url")
            .ifBlank { moreInfo?.optString("media_preview_url") ?: "" }
            .ifBlank { moreInfo?.optString("vlink") ?: "" }

        val has320Str = item.optString("320kbps")
            .ifBlank { moreInfo?.optString("320kbps") ?: "false" }
        val has320 = has320Str.equals("true", ignoreCase = true)

        var image = item.optString("image", "")
        if (image.isNotBlank()) {
            image = image.replace("150x150", "500x500").replace("http://", "https://")
        }

        return SaavnTrackInfo(
            id = id,
            title = title,
            artist = artist,
            album = album,
            artUrl = image,
            encryptedMediaUrl = encryptedUrl,
            previewUrl = previewUrl,
            has320 = has320,
            durationMs = durationSec * 1000L
        )
    }

    private fun cleanHtmlEntities(str: String): String {
        return str
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
