package com.example.resolver.providers

import android.util.Log
import com.example.network.NetworkClients
import com.example.resolver.KotlinJsInterp
import com.example.resolver.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * On-Device YouTube Music InnerTube Provider.
 * Completely standalone: performs search and audio stream resolution using direct InnerTube endpoints
 * emulating ANDROID_VR, IOS_MUSIC, and TVHTML5 clients for direct deciphered streams without yt-dlp.
 */
class YouTubeInnerTubeProvider : AudioStreamProvider {

    override val source: SourceType = SourceType.YOUTUBE

    private val TAG = "YouTubeInnerTubeProvider"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // InnerTube Clients Configuration
    private val CLIENT_ANDROID_VR = JSONObject().apply {
        put("clientName", "ANDROID_VR")
        put("clientVersion", "1.60.19")
        put("deviceMake", "Oculus")
        put("deviceModel", "Quest 3")
        put("osName", "Android")
        put("osVersion", "12")
        put("androidSdkVersion", 32)
        put("hl", "en")
        put("gl", "US")
    }

    private val CLIENT_IOS = JSONObject().apply {
        put("clientName", "IOS")
        put("clientVersion", "19.29.1")
        put("deviceMake", "Apple")
        put("deviceModel", "iPhone16,2")
        put("osName", "iOS")
        put("osVersion", "17.5.1.21F90")
        put("hl", "en")
        put("gl", "US")
    }

    private val CLIENT_TV = JSONObject().apply {
        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
        put("clientVersion", "2.0")
        put("hl", "en")
        put("gl", "US")
    }

    private val CLIENT_WEB_REMIX = JSONObject().apply {
        put("clientName", "WEB_REMIX")
        put("clientVersion", "1.20240101.01.00")
        put("hl", "en")
        put("gl", "US")
    }

    // Base64 protobuf for song-only filter
    private const val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ=="

    override suspend fun searchTracks(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            if (query.trim().isBlank()) return@withContext emptyList()
            try {
                val url = "https://music.youtube.com/youtubei/v1/search"
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", CLIENT_WEB_REMIX) })
                    put("query", query.trim())
                    put("params", SEARCH_PARAMS_SONGS)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.youtube.com/")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parseSearchResponse(JSONObject(body), limit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    override suspend fun getPlaylistTracks(playlistId: String): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val cleanId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            try {
                val url = "https://music.youtube.com/youtubei/v1/browse"
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", CLIENT_WEB_REMIX) })
                    put("browseId", cleanId)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.youtube.com/")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parseSearchResponse(JSONObject(body), limit = 50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    override suspend fun resolveStream(sourceId: String, preferredKbps: Int): ResolvedStream =
        withContext(Dispatchers.IO) {
            val clients = listOf(CLIENT_ANDROID_VR, CLIENT_IOS, CLIENT_TV, CLIENT_WEB_REMIX)
            var lastException: Exception? = null

            for (client in clients) {
                try {
                    val stream = resolveWithClient(sourceId, client, preferredKbps)
                    if (stream != null) return@withContext stream
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Client ${client.optString("clientName")} failed for $sourceId: ${e.message}")
                }
            }

            throw lastException ?: IllegalStateException("Failed to resolve stream for YouTube track $sourceId")
        }

    private fun resolveWithClient(videoId: String, clientObj: JSONObject, preferredKbps: Int): ResolvedStream? {
        val clientName = clientObj.optString("clientName")
        val url = "https://music.youtube.com/youtubei/v1/player"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientObj)
                if (clientName == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                    put("thirdParty", JSONObject().apply { put("embedUrl", "https://www.youtube.com") })
                }
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://music.youtube.com/")
            .build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != "OK") {
                Log.w(TAG, "Playability status: $status on client $clientName")
                return null
            }

            val streamingData = json.optJSONObject("streamingData") ?: return null
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

            var bestUrl: String? = null
            var bestItag = 251
            var bestBitrate = 0
            var bestCodec = "Opus"

            for (i in 0 until adaptiveFormats.length()) {
                val f = adaptiveFormats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio/")) continue

                var streamUrl = f.optString("url", "")
                val cipher = f.optString("signatureCipher", "")
                val itag = f.optInt("itag", 251)
                val bitrate = f.optInt("bitrate", 0)

                if (streamUrl.isBlank() && cipher.isNotBlank()) {
                    val params = parseQueryString(cipher)
                    val s = params["s"] ?: ""
                    val sp = params["sp"] ?: "sig"
                    val baseUrl = params["url"] ?: ""
                    if (baseUrl.isNotBlank()) {
                        val decipheredSig = KotlinJsInterp.decipherSignature(s, emptyList())
                        streamUrl = "$baseUrl&$sp=$decipheredSig"
                    }
                }

                if (streamUrl.isNotBlank()) {
                    if (streamUrl.contains("&n=")) {
                        val nMatch = Regex("&n=([^&]+)").find(streamUrl)
                        if (nMatch != null) {
                            val originalN = nMatch.groupValues[1]
                            val transformedN = KotlinJsInterp.transformNParam(originalN)
                            streamUrl = streamUrl.replace("&n=$originalN", "&n=$transformedN")
                        }
                    }

                    val isOpus = mime.contains("opus") || itag == 251
                    val isAac = mime.contains("mp4a") || mime.contains("aac") || itag == 140

                    if (isOpus && bitrate >= 150000 && (bestUrl == null || bitrate > bestBitrate)) {
                        bestUrl = streamUrl
                        bestBitrate = bitrate
                        bestItag = itag
                        bestCodec = "Opus 160k"
                    } else if (bestUrl == null || (isAac && bitrate > bestBitrate && !bestCodec.contains("Opus"))) {
                        bestUrl = streamUrl
                        bestBitrate = bitrate
                        bestItag = itag
                        bestCodec = if (isOpus) "Opus" else "AAC 128k"
                    }
                }
            }

            if (bestUrl != null) {
                return ResolvedStream(
                    videoId = videoId,
                    streamUrl = bestUrl,
                    itag = bestItag,
                    codec = bestCodec,
                    bitrate = "${if (bestBitrate > 0) bestBitrate / 1000 else preferredKbps} kbps",
                    expiresAtMs = System.currentTimeMillis() + (6 * 3600 * 1000L),
                    isLocal = false,
                    resolvedBy = "On-Device InnerTube ($clientName)",
                    latencyMs = 15
                )
            }
        }
        return null
    }

    private fun parseSearchResponse(json: JSONObject, limit: Int): List<NormalizedTrack> {
        val renderers = mutableListOf<JSONObject>()
        findItemRenderers(json, renderers)

        val results = mutableListOf<NormalizedTrack>()
        for (item in renderers) {
            if (results.size >= limit) break

            var videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            if (videoId.isNullOrBlank()) {
                val overlay = item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                videoId = overlay?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
            }

            var title = "Unknown Track"
            val flexCols = item.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() > 0) {
                val col0Runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (col0Runs != null && col0Runs.length() > 0) {
                    title = col0Runs.getJSONObject(0).optString("text", "Unknown Track")
                }
            }

            val artists = mutableListOf<String>()
            var album = "Single"
            var durationMs = 210000L

            if (flexCols != null && flexCols.length() > 1) {
                val col1Runs = flexCols.getJSONObject(1)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")

                if (col1Runs != null && col1Runs.length() > 0) {
                    val texts = mutableListOf<String>()
                    for (i in 0 until col1Runs.length()) {
                        val txt = col1Runs.getJSONObject(i).optString("text", "").trim()
                        if (txt.isNotBlank() && txt != "•") {
                            texts.add(txt)
                        }
                    }
                    if (texts.isNotEmpty()) {
                        artists.add(texts[0])
                    }
                    if (texts.size >= 2) {
                        val last = texts.last()
                        if (last.contains(":")) {
                            durationMs = parseDurationMs(last)
                            if (texts.size >= 3) album = texts[1]
                        } else {
                            album = texts[1]
                        }
                    }
                }
            }

            var artUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80"
            val thumbObj = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1).optString("url")
                if (lastThumb.isNotBlank()) artUrl = lastThumb
            }

            if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                results.add(
                    NormalizedTrack(
                        source = SourceType.YOUTUBE,
                        sourceId = videoId,
                        title = title,
                        artists = if (artists.isEmpty()) listOf("Various Artists") else artists,
                        album = album,
                        durationMs = durationMs,
                        artUrl = artUrl,
                        isPlayableNatively = true
                    )
                )
            }
        }
        return results
    }

    private fun findItemRenderers(obj: Any, outList: MutableList<JSONObject>) {
        when (obj) {
            is JSONObject -> {
                val optItem = obj.optJSONObject("musicResponsiveListItemRenderer")
                if (optItem != null) outList.add(optItem)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val child = obj.opt(keys.next())
                    if (child != null) findItemRenderers(child, outList)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val child = obj.opt(i)
                    if (child != null) findItemRenderers(child, outList)
                }
            }
        }
    }

    private fun parseDurationMs(durStr: String): Long {
        val parts = durStr.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toLongOrNull() ?: 3L
                val s = parts[1].toLongOrNull() ?: 30L
                (m * 60 + s) * 1000L
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: 1L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toLongOrNull() ?: 0L
                (h * 3600 + m * 60 + s) * 1000L
            }
            else -> 210000L
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf("=")
            if (idx > 0) {
                val key = part.substring(0, idx)
                val value = try {
                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                } catch (e: Exception) {
                    part.substring(idx + 1)
                }
                map[key] = value
            }
        }
        return map
    }
}
