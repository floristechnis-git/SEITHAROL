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
import java.util.Locale

class YouTubeInnerTubeProvider : AudioStreamProvider {

    override val source: SourceType = SourceType.YOUTUBE

    private val TAG = "YouTubeInnerTubeProvider"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun resolveDeviceGl(): String {
        val country = runCatching { Locale.getDefault().country }.getOrNull().orEmpty()
        return if (country.length == 2) country.uppercase(Locale.US) else "IN"
    }

    private data class ClientProfile(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val playerEndpoint: String,
        val clientJson: JSONObject,
        val params: String? = null,
        val isEmbedded: Boolean = false,
        val sendOriginHeaders: Boolean = false
    )

    private val CLIENT_PROFILES: List<ClientProfile>
        get() {
            val deviceGl = resolveDeviceGl()
            return listOf(
                ClientProfile(
                    name = "VISIONOS",
                    version = "0.1",
                    clientId = "101",
                    userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                    playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    sendOriginHeaders = true,
                    clientJson = JSONObject().apply {
                        put("clientName", "VISIONOS")
                        put("clientVersion", "0.1")
                        put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15")
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice14,1")
                        put("osName", "visionOS")
                        put("osVersion", "1.3.21O771")
                        put("hl", "en")
                        put("gl", deviceGl)
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                    }
                ),

                ClientProfile(
                    name = "IOS",
                    version = "20.10.4",
                    clientId = "5",
                    userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)",
                    playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    clientJson = JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "20.10.4")
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iOS")
                        put("osVersion", "18.3.1.22D72")
                        put("hl", "en")
                        put("gl", deviceGl)
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                    }
                ),
                ClientProfile(
                    name = "ANDROID_VR_143",
                    version = "1.43.32",
                    clientId = "28",
                    userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                    playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    clientJson = JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.43.32")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("androidSdkVersion", 32)
                        put("osName", "Android")
                        put("osVersion", "12L")
                        put("hl", "en")
                        put("gl", "US")
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                    }
                ),
                ClientProfile(
                    name = "ANDROID_TESTSUITE",
                    version = "1.9",
                    clientId = "30",
                    userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12L; US) gzip",
                    playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    params = "CgIIAQ==",
                    clientJson = JSONObject().apply {
                        put("clientName", "ANDROID_TESTSUITE")
                        put("clientVersion", "1.9")
                        put("androidSdkVersion", 32)
                        put("osName", "Android")
                        put("osVersion", "12L")
                        put("hl", "en")
                        put("gl", deviceGl)
                    }
                ),
                ClientProfile(
                    name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                    version = "2.0",
                    clientId = "85",
                    userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
                    playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    isEmbedded = true,
                    sendOriginHeaders = true,
                    clientJson = JSONObject().apply {
                        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        put("clientVersion", "2.0")
                        put("hl", "en")
                        put("gl", deviceGl)
                    }
                )
            )
        }

    private fun webRemixClientJson(): JSONObject = JSONObject().apply {
        put("clientName", "WEB_REMIX")
        put("clientVersion", "1.20250310.01.00")
        put("hl", "en")
        put("gl", resolveDeviceGl())
    }

    private val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

    suspend fun fetchMetadataById(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId) ?: videoId.trim()
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
                val songs = executeSearch(cleanQuery, SEARCH_PARAMS_SONGS, limit)
                if (songs.isNotEmpty()) return@withContext songs
                executeSearch(cleanQuery, null, limit)
            } catch (e: Exception) {
                Log.e(TAG, "searchTracks failed: ${e.message}", e)
                emptyList()
            }
        }

    private fun executeSearch(query: String, params: String?, limit: Int): List<NormalizedTrack> {
        val url = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply { put("client", webRemixClientJson()) })
            put("query", query)
            if (params != null) put("params", params)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20250310.01.00")
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
                    put("context", JSONObject().apply { put("client", webRemixClientJson()) })
                    put("browseId", cleanId)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("X-YouTube-Client-Name", "67")
                    .header("X-YouTube-Client-Version", "1.20250310.01.00")
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

    companion object {
        fun extractVideoId(id: String?): String? =
            com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(id)

        fun isValidYoutubeVideoId(id: String?): Boolean =
            extractVideoId(id) != null
    }

    suspend fun resolveExactYoutubeStreamById(
        videoId: String,
        preferredQuality: com.seitharol.data.resolver.AudioQuality = com.seitharol.data.resolver.AudioQuality.NORMAL,
        forceRefresh: Boolean = false
    ): Result<String> {
        val cleanId = extractVideoId(videoId) ?: videoId
        return com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
            cleanId,
            preferredQuality,
            forceRefresh
        )
    }

    override suspend fun resolveStream(sourceId: String, preferredKbps: Int): ResolvedStream =
        withContext(Dispatchers.IO) {
            val quality = when {
                preferredKbps <= 96 -> com.seitharol.data.resolver.AudioQuality.LOW
                preferredKbps in 97..192 -> com.seitharol.data.resolver.AudioQuality.NORMAL
                else -> com.seitharol.data.resolver.AudioQuality.HIGH
            }
            val cleanId = extractVideoId(sourceId)
            if (cleanId != null) {
                val directResult = resolveExactYoutubeStreamById(cleanId, quality)
                if (directResult.isSuccess) {
                    val url = directResult.getOrThrow()
                    val now = System.currentTimeMillis()
                    val bitrateLabel = when (quality) {
                        com.seitharol.data.resolver.AudioQuality.LOW -> "96 kbps (Data Saver)"
                        com.seitharol.data.resolver.AudioQuality.NORMAL -> "160 kbps"
                        com.seitharol.data.resolver.AudioQuality.HIGH -> "320 kbps (High)"
                    }
                    return@withContext ResolvedStream(
                        videoId = cleanId,
                        streamUrl = url,
                        itag = if (url.contains("opus") || url.contains("webm")) 251 else 140,
                        codec = if (url.contains("opus") || url.contains("webm")) "Opus" else "AAC",
                        bitrate = bitrateLabel,
                        expiresAtMs = now + (6 * 3600 * 1000L),
                        isLocal = false,
                        resolvedBy = "YouTube InnerTube Verified",
                        latencyMs = 12
                    )
                }
            }

            val errors = mutableListOf<String>()
            val targetId = cleanId ?: sourceId.trim()

            for (profile in CLIENT_PROFILES) {
                try {
                    val stream = resolveWithClient(targetId, profile, preferredKbps)
                    if (stream != null) return@withContext stream
                } catch (e: Exception) {
                    errors.add("${profile.name}: ${e.message}")
                    Log.w(TAG, "Client ${profile.name} failed for $targetId: ${e.message}")
                }
            }

            throw IllegalStateException("YouTube ($targetId) failed [${errors.joinToString("; ")}]")
        }

    private fun resolveWithClient(videoId: String, profile: ClientProfile, preferredKbps: Int): ResolvedStream? {
        val cpn = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".random() }.joinToString("")
        val visitorData = com.seitharol.data.resolver.InnerTubeStreamExtractor.ensureVisitorData()

        val clientObj = JSONObject(profile.clientJson.toString()).apply {
            put("userAgent", profile.userAgent)
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
        }

        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientObj)
                if (profile.isEmbedded) {
                    put("thirdParty", JSONObject().apply { put("embedUrl", "https://www.youtube.com/") })
                }
            })
            if (profile.isEmbedded || profile.sendOriginHeaders) {
                KotlinJsInterp.ensurePlayerJsLoaded(forceRefresh = false)
                val sts = KotlinJsInterp.getSignatureTimestamp()
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", sts)
                    })
                })
                val poToken = KotlinJsInterp.generatePoToken(visitorData)
                if (!poToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", JSONObject().apply {
                        put("poToken", poToken)
                    })
                }
            }
            profile.params?.let { put("params", it) }
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val origin = if (profile.playerEndpoint.contains("music.youtube.com")) {
            "https://music.youtube.com"
        } else {
            "https://www.youtube.com"
        }

        val requestBuilder = Request.Builder()
            .url(profile.playerEndpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientId)
            .header("X-YouTube-Client-Version", profile.version)
            .header("X-Goog-Api-Format-Version", "2")
        if (profile.sendOriginHeaders) {
            requestBuilder.header("Origin", origin)
            requestBuilder.header("Referer", "$origin/")
        }
        if (!visitorData.isNullOrBlank()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        val request = requestBuilder.build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IllegalStateException("Empty body")
            val json = JSONObject(body)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status") ?: "UNKNOWN"
            if (status == "LOGIN_REQUIRED") {
                com.seitharol.data.resolver.InnerTubeStreamExtractor.ensureVisitorData(forceFresh = true)
                val reason = playability?.optString("reason") ?: "Sign in required"
                throw IllegalStateException("$status ($reason)")
            }
            if (status != "OK") {
                val reason = playability?.optString("reason") ?: ""
                throw IllegalStateException("$status ($reason)")
            }

            val streamingData = json.optJSONObject("streamingData")
                ?: throw IllegalStateException("Missing streamingData")

            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val legacyFormats = streamingData.optJSONArray("formats")

            data class CandidateFmt(
                val url: String,
                val itag: Int,
                val bitrate: Int,
                val codec: String,
                val isAac: Boolean
            )
            val candidates = mutableListOf<CandidateFmt>()

            fun inspectFormats(formatsArray: JSONArray?) {
                if (formatsArray == null) return
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType", "")
                    val isAudio = mime.startsWith("audio/") || f.optInt("itag") in listOf(140, 141, 139, 251, 250, 249, 18)
                    if (!isAudio) continue

                    var streamUrl = f.optString("url", "")
                    val wasCiphered = streamUrl.isBlank()
                    if (wasCiphered) {
                        val cipherStr = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
                        if (cipherStr.isNotBlank()) {
                            streamUrl = extractAndDecipherSignatureCipher(cipherStr) ?: ""
                        }
                    }
                    if (streamUrl.isBlank()) continue

                    if (wasCiphered && streamUrl.contains("&n=")) {
                        val nMatch = Regex("&n=([^&]+)").find(streamUrl)
                        if (nMatch != null) {
                            val origN = nMatch.groupValues[1]
                            val transN = KotlinJsInterp.transformNParam(origN)
                            streamUrl = streamUrl.replace("&n=$origN", "&n=$transN")
                        }
                    }
                    if (!streamUrl.contains("cpn=")) {
                        val sep = if (streamUrl.contains("?")) "&" else "?"
                        streamUrl = "$streamUrl${sep}cpn=$cpn"
                    }

                    val jsonClen = f.optString("contentLength", "").toLongOrNull() ?: -1L
                    if (jsonClen > 0L) {
                        com.seitharol.player.OuterTuneResolvingDataSource.recordKnownStreamLength(streamUrl, jsonClen)
                    }
                    val itag = f.optInt("itag", 140)
                    val bitrate = f.optInt("bitrate", 128000)
                    val isAac = mime.contains("mp4a") || mime.contains("aac") || itag in listOf(140, 141, 139, 18)
                    val codecLabel = if (isAac) "AAC 128k" else "Opus 160k"
                    candidates.add(CandidateFmt(streamUrl, itag, bitrate, codecLabel, isAac))
                }
            }

            inspectFormats(legacyFormats)
            inspectFormats(adaptiveFormats)

            val sorted = candidates.sortedWith(
                compareByDescending<CandidateFmt> { it.itag == 140 || (it.isAac && it.itag != 18) }
                    .thenByDescending { it.itag != 18 }
                    .thenByDescending { it.bitrate }
            )

            var verifiedCandidate: CandidateFmt? = null
            for (cand in sorted.distinctBy { it.itag }.take(3)) {
                val verifiedUrl = com.seitharol.data.resolver.InnerTubeStreamExtractor.verifyStreamPlayable(cand.url)
                if (!verifiedUrl.isNullOrBlank()) {
                    verifiedCandidate = cand.copy(url = verifiedUrl)
                    break
                }
            }

            val finalCand = verifiedCandidate ?: throw IllegalStateException("No verified playable audio URL in formats")
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(
                videoId,
                com.seitharol.data.resolver.AudioQuality.NORMAL,
                finalCand.url
            )
            return ResolvedStream(
                videoId = videoId,
                streamUrl = finalCand.url,
                itag = finalCand.itag,
                codec = finalCand.codec,
                bitrate = "${if (finalCand.bitrate > 0) finalCand.bitrate / 1000 else preferredKbps} kbps",
                expiresAtMs = System.currentTimeMillis() + (6 * 3600 * 1000L),
                isLocal = false,
                resolvedBy = "On-Device InnerTube (${profile.name})",
                latencyMs = 15
            )
        }
    }

    private fun extractAndDecipherSignatureCipher(cipherStr: String): String? {
        return try {
            val params = mutableMapOf<String, String>()
            for (param in cipherStr.split("&")) {
                val idx = param.indexOf('=')
                if (idx != -1) {
                    val key = URLDecoder.decode(param.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(param.substring(idx + 1), "UTF-8")
                    params[key] = value
                }
            }
            val rawUrl = params["url"] ?: return null
            val sig = params["s"]
            val sigParam = params["sp"] ?: "sig"
            if (sig.isNullOrBlank()) return rawUrl

            val activeOps = KotlinJsInterp.getActiveCipherOperations()
            val decipheredSig = KotlinJsInterp.decipherSignature(sig, activeOps)
            if (decipheredSig.isBlank() || decipheredSig == sig) return null
            val separator = if (rawUrl.contains("?")) "&" else "?"
            "$rawUrl$separator$sigParam=$decipheredSig"
        } catch (e: Exception) {
            Log.w(TAG, "extractAndDecipherSignatureCipher failed: ${e.message}")
            null
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

            if (durationMs in 1..45_000L) {
                continue
            }

            val isTopicArtist = artists.any { it.contains("Topic", ignoreCase = true) }
            val hasOfficialAudio = title.contains("Official Audio", ignoreCase = true) ||
                title.contains("Official Music Video", ignoreCase = true) ||
                title.contains("Official Track", ignoreCase = true)
            val isOfficialArtTrack = musicVideoType == "MUSIC_VIDEO_TYPE_ATV" || isTopicArtist || hasOfficialAudio

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
