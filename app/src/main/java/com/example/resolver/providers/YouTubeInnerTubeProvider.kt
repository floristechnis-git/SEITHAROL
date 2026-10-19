package com.example.resolver.providers

import android.util.Log
import com.example.network.NetworkClients
import com.example.resolver.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

class YouTubeInnerTubeProvider : AudioStreamProvider {

    override val source: SourceType = SourceType.YOUTUBE

    private val TAG = "YouTubeInnerTubeProvider"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class ClientProfile(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val playerEndpoint: String,
        val clientJson: JSONObject,
        val isEmbedded: Boolean = false
    )

    private val CLIENT_PROFILES = listOf(
        ClientProfile(
            name = "IOS",
            version = "19.45.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientJson = JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "19.45.4")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.1.0.22B83")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            name = "ANDROID_VR",
            version = "1.60.19",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientJson = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.60.19")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12L")
                put("androidSdkVersion", 32)
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            version = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            isEmbedded = true,
            clientJson = JSONObject().apply {
                put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                put("clientVersion", "2.0")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            name = "ANDROID_TESTSUITE",
            version = "1.9",
            clientId = "30",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12; US) gzip",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientJson = JSONObject().apply {
                put("clientName", "ANDROID_TESTSUITE")
                put("clientVersion", "1.9")
                put("androidSdkVersion", 32)
                put("osName", "Android")
                put("osVersion", "12")
                put("hl", "en")
                put("gl", "US")
            }
        ),
        ClientProfile(
            name = "WEB_REMIX",
            version = "1.20250101.01.00",
            clientId = "67",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            playerEndpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientJson = JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20250101.01.00")
                put("hl", "en")
                put("gl", "US")
            }
        )
    )

    private val CLIENT_WEB_REMIX = CLIENT_PROFILES.last().clientJson
    private val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    /**
     * Resolves title and artist for a YouTube videoId when Track metadata is blank.
     */
    suspend fun fetchMetadataById(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return@withContext null
        try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$cleanId&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val title = json.optString("title", "").trim()
                val author = json.optString("author_name", "").removeSuffix(" - Topic").trim()
                if (title.isNotBlank()) return@withContext title to author
            }
        } catch (e: Exception) {
            Log.w(TAG, "oEmbed metadata lookup failed for $cleanId: ${e.message}")
        }
        null
    }

    override suspend fun searchTracks(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()
            try {
                // 1. Try filtered song search first
                val songs = executeSearch(cleanQuery, SEARCH_PARAMS_SONGS, limit)
                if (songs.isNotEmpty()) return@withContext songs

                // 2. Fallback to unfiltered YouTube Music search (catches videos/remixes/ISRC)
                executeSearch(cleanQuery, null, limit)
            } catch (e: Exception) {
                Log.e(TAG, "searchTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    private fun executeSearch(query: String, params: String?, limit: Int): List<NormalizedTrack> {
        val url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply { put("client", CLIENT_WEB_REMIX) })
            put("query", query)
            if (params != null) put("params", params)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250101.01.00")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            return parseSearchResponse(JSONObject(body), limit)
        }
    }

    override suspend fun getPlaylistTracks(playlistId: String): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val cleanId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            try {
                val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply { put("client", CLIENT_WEB_REMIX) })
                    put("browseId", cleanId)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("X-YouTube-Client-Name", "67")
                    .header("X-YouTube-Client-Version", "1.20250101.01.00")
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
            val errors = mutableListOf<String>()

            for (profile in CLIENT_PROFILES) {
                try {
                    val stream = resolveWithClient(sourceId, profile, preferredKbps)
                    if (stream != null) return@withContext stream
                } catch (e: Exception) {
                    errors.add("${profile.name}: ${e.message}")
                    Log.w(TAG, "Client ${profile.name} failed for $sourceId: ${e.message}")
                }
            }

            throw IllegalStateException("YouTube ($sourceId) failed [${errors.joinToString("; ")}]")
        }

    private fun resolveWithClient(videoId: String, profile: ClientProfile, preferredKbps: Int): ResolvedStream? {
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", profile.clientJson)
                if (profile.isEmbedded) {
                    put("thirdParty", JSONObject().apply { put("embedUrl", "https://www.youtube.com") })
                }
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val origin = if (profile.playerEndpoint.contains("music.youtube.com")) {
            "https://music.youtube.com"
        } else {
            "https://www.youtube.com"
        }

        val request = Request.Builder()
            .url(profile.playerEndpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientId)
            .header("X-YouTube-Client-Version", profile.version)
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Empty body")
            val json = JSONObject(body)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status") ?: "UNKNOWN"
            if (status != "OK") {
                val reason = playability?.optString("reason") ?: ""
                throw IllegalStateException("$status ($reason)")
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: throw IllegalStateException("Missing streamingData")

            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val legacyFormats = streamingData.optJSONArray("formats")

            var bestUrl: String? = null
            var bestItag = 251
            var bestBitrate = 0
            var bestCodec = "Opus"

            fun inspectFormats(formatsArray: JSONArray?) {
                if (formatsArray == null) return
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType", "")
                    val isAudio = mime.startsWith("audio/") || f.optInt("itag") in listOf(140, 251, 250, 249, 139, 18)
                    if (!isAudio) continue

                    // Only accept direct, unencrypted URLs so we never feed an undeciphered signature to ExoPlayer
                    val streamUrl = f.optString("url", "")
                    if (streamUrl.isBlank()) continue

                    val itag = f.optInt("itag", 251)
                    val bitrate = f.optInt("bitrate", 128000)
                    val isOpus = mime.contains("opus") || itag in listOf(249, 250, 251)
                    val isAac = mime.contains("mp4a") || mime.contains("aac") || itag in listOf(140, 139, 18)

                    if (isOpus && bitrate >= 120000 && (bestUrl == null || bitrate > bestBitrate)) {
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

            inspectFormats(adaptiveFormats)
            if (bestUrl == null) {
                inspectFormats(legacyFormats)
            }
            if (bestUrl == null) {
                val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                if (hlsUrl.isNotBlank()) {
                    bestUrl = hlsUrl
                    bestItag = 140
                    bestCodec = "HLS AAC"
                }
            }

            val resolvedUrl = bestUrl ?: throw IllegalStateException("No direct audio URL in formats")
            return ResolvedStream(
                videoId = videoId,
                streamUrl = resolvedUrl,
                itag = bestItag,
                codec = bestCodec,
                bitrate = "${if (bestBitrate > 0) bestBitrate / 1000 else preferredKbps} kbps",
                expiresAtMs = System.currentTimeMillis() + (6 * 3600 * 1000L),
                isLocal = false,
                resolvedBy = "On-Device InnerTube (${profile.name})",
                latencyMs = 15
            )
        }
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

            var title = ""
            val flexCols = item.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() > 0) {
                val col0Runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (col0Runs != null && col0Runs.length() > 0) {
                    val runObj = col0Runs.getJSONObject(0)
                    title = runObj.optString("text", "").trim()
                    if (videoId.isNullOrBlank()) {
                        videoId = runObj.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                    }
                }
            }
            if (title.isBlank()) title = "Unknown Track"

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
                        if (txt.isNotBlank() && txt != "•" && !txt.equals("Song", ignoreCase = true) && !txt.equals("Video", ignoreCase = true)) {
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

            var artUrl = if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
            val thumbObj = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1).optString("url")
                if (lastThumb.isNotBlank()) artUrl = lastThumb
            }

            val overlay = item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            val playBtn = overlay?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            val watchEndpoint = playBtn?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            val musicVideoType = watchEndpoint?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType", "") ?: ""

            var viewsCount = 0L
            if (flexCols != null) {
                for (c in 0 until flexCols.length()) {
                    val runs = flexCols.getJSONObject(c).optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")
                    if (runs != null) {
                        for (r in 0 until runs.length()) {
                            val txt = runs.getJSONObject(r).optString("text", "")
                            val v = parseViewsCount(txt)
                            if (v > viewsCount) viewsCount = v
                        }
                    }
                }
            }

            val isTopicArtist = artists.any { it.contains("Topic", ignoreCase = true) }
            val isOfficialArtTrack = musicVideoType == "MUSIC_VIDEO_TYPE_ATV" || isTopicArtist

            if (!videoId.isNullOrBlank()) {
                results.add(
                    NormalizedTrack(
                        source = SourceType.YOUTUBE,
                        sourceId = videoId,
                        title = title,
                        artists = if (artists.isEmpty()) listOf("Various Artists") else artists,
                        album = album,
                        durationMs = durationMs,
                        artUrl = artUrl,
                        isPlayableNatively = true,
                        isArtTrack = isOfficialArtTrack,
                        viewsCount = viewsCount
                    )
                )
            }
        }
        return results.sortedWith(
            compareByDescending<NormalizedTrack> { it.isArtTrack }
                .thenByDescending { it.viewsCount }
        )
    }

    suspend fun getRadioTracks(videoId: String): List<NormalizedTrack> = withContext(Dispatchers.IO) {
        val radioPlaylistId = if (videoId.startsWith("RDAMVM")) videoId else "RDAMVM$videoId"
        getPlaylistTracks(radioPlaylistId)
    }

    private fun parseViewsCount(txt: String): Long {
        val clean = txt.trim().lowercase()
        if (!clean.contains("view") && !clean.contains("play")) return 0L
        val numStr = clean.replace(Regex("""[^0-9.kmbt]"""), "")
        return try {
            when {
                numStr.endsWith("b") -> (numStr.dropLast(1).toDouble() * 1_000_000_000).toLong()
                numStr.endsWith("m") -> (numStr.dropLast(1).toDouble() * 1_000_000).toLong()
                numStr.endsWith("k") -> (numStr.dropLast(1).toDouble() * 1_000).toLong()
                else -> numStr.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) { 0L }
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
}
