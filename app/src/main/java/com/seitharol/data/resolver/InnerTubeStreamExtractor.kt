package com.seitharol.data.resolver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class AudioQuality(val label: String, val maxBitrateKbps: Int) {
    LOW("Data Saver (96 kbps)", 96),
    NORMAL("Normal (160 kbps)", 160),
    HIGH("High (320 kbps)", 320)
}

data class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val clientId: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val params: String? = null,
    val glOverride: String? = null,
    val origin: String = "https://www.youtube.com",
    val playerEndpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    val isEmbedded: Boolean = false,
    val sendOriginHeaders: Boolean = false
)

data class YtmCollectionSummary(
    val browseId: String,
    val playlistId: String? = null,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val type: String // "ALBUM", "SINGLE", "PLAYLIST", "MIX"
)

data class YtmArtistPreview(
    val browseId: String,
    val name: String,
    val subscribers: String,
    val avatarUrl: String
)

data class YtmArtistPageData(
    val browseId: String,
    val name: String,
    val description: String,
    val subscriberCountText: String,
    val avatarUrl: String,
    val bannerUrl: String,
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
    val topSongs: List<CandidateStream>,
    val albums: List<YtmCollectionSummary>,
    val singles: List<YtmCollectionSummary>,
    val playlists: List<YtmCollectionSummary>,
    val similarArtists: List<YtmArtistPreview>,
    val isChannel: Boolean = false
)

data class YtmCollectionPageData(
    val id: String,
    val title: String,
    val author: String,
    val subtitle: String,
    val description: String,
    val thumbnailUrl: String,
    val tracks: List<CandidateStream>
)

data class VideoDescriptionMetadata(
    val videoId: String,
    val title: String,
    val author: String,
    val viewCountText: String = "",
    val description: String = ""
)

object InnerTubeStreamExtractor {

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientRotationOffset = AtomicInteger(0)

    private fun resolveDeviceGl(): String {
        val country = runCatching { Locale.getDefault().country }.getOrNull().orEmpty()
        return if (country.length == 2) country.uppercase(Locale.US) else "IN"
    }

    /**
     * Verified multi-client InnerTube pipeline:
     * 1. ANDROID_VR (1.61.48, Quest 3, regional gl="IN"/device) — Native VR client, unciphered direct URLs, supports Indian/Manipuri regional catalog.
     * 2. ANDROID_TESTSUITE (1.9, params="CgIIAQ==") — Unciphered direct audio streams.
     * 3. ANDROID_VR (1.43.32, Quest 3, gl="US") — Global US fallback VR profile.
     * 4. IOS (20.10.4, iPhone16,2, regional gl="IN"/device) — Native iOS client with M4A (140), Opus, and HLS manifest support.
     * 5. TVHTML5_SIMPLY_EMBEDDED_PLAYER (2.0) — Embedded player fallback.
     */
    val VERIFIED_CLIENTS = listOf(
        InnerTubeClientConfig(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            clientId = "101",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            glOverride = null,
            sendOriginHeaders = true
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            glOverride = "US",
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip",
            clientId = "3",
            deviceMake = "Google",
            deviceModel = "Pixel 8",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            params = "CgIIAQ==",
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "IOS",
            clientVersion = "21.26.4",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            clientId = "5",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            clientId = "101",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            glOverride = null,
            sendOriginHeaders = true
        ),
        InnerTubeClientConfig(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
            clientId = "85",
            glOverride = null,
            isEmbedded = true,
            sendOriginHeaders = true
        ),
        InnerTubeClientConfig(
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "2.20260708.00.00",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            clientId = "56",
            glOverride = null,
            isEmbedded = true,
            sendOriginHeaders = true
        )
    )

    @Volatile
    private var cachedVisitorData: String? = null
    private val videoStreamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun invalidateVideoStreamCache(videoId: String) {
        val clean = extractVideoId(videoId) ?: videoId.trim()
        val failedSet = failedVideoStreamsByVideoId.getOrPut(clean) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        videoStreamCache.remove(clean)?.first?.let { failedUrl ->
            failedSet.add(failedUrl)
        }
        videoStreamCache.remove(videoId.trim())
    }

    fun ensureVisitorData(forceFresh: Boolean = false): String? {
        if (!forceFresh && !cachedVisitorData.isNullOrBlank()) {
            return cachedVisitorData
        }
        return fetchVisitorDataSync()
    }

    private fun fetchVisitorDataSync(): String? {
        return runCatching {
            val conn = (URL("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", VERIFIED_CLIENTS[0].userAgent)
                setRequestProperty("X-YouTube-Client-Name", VERIFIED_CLIENTS[0].clientId)
                setRequestProperty("X-YouTube-Client-Version", VERIFIED_CLIENTS[0].clientVersion)
            }
            val reqJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", VERIFIED_CLIENTS[0].clientName)
                        put("clientVersion", VERIFIED_CLIENTS[0].clientVersion)
                        put("hl", "en")
                        put("gl", resolveDeviceGl())
                    })
                })
            }
            conn.outputStream.use { it.write(reqJson.toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val vis = json.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                if (vis != null) {
                    cachedVisitorData = vis
                    return@runCatching vis
                }
            }
            val browsePayload = webRemixPayload().apply { put("browseId", "FEmusic_home") }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", browsePayload)
            root?.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.also {
                cachedVisitorData = it
            }
        }.getOrNull()
    }

    val VALID_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    fun extractVideoId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
            .removePrefix("ytm_clip:")
            .removePrefix("ytm://")
            .removePrefix("youtube://")
            .removePrefix("resolver://track/")
            .substringBefore("?")
            .trim()
        if (VALID_VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val paramMatch = Regex("[?&](?:v|id)=([a-zA-Z0-9_-]{11})").find(raw)
        if (paramMatch != null) return paramMatch.groupValues[1]

        val watchMatch = Regex("(?:^|/|\\?)watch\\?(?:.*&)?(?:v|id)=([a-zA-Z0-9_-]{11})").find(raw)
        if (watchMatch != null) return watchMatch.groupValues[1]

        val pathMatch = Regex("(?:youtu\\.be/|/(?:embed|v|shorts|live)/)([a-zA-Z0-9_-]{11})").find(raw)
        if (pathMatch != null) return pathMatch.groupValues[1]

        return null
    }

    fun isValidYoutubeVideoId(id: String?): Boolean =
        extractVideoId(id) != null

    private fun generateCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { alphabet.random() }.joinToString("")
    }

    val isHlsSupported: Boolean by lazy {
        runCatching {
            Class.forName("androidx.media3.exoplayer.hls.HlsMediaSource\$Factory")
            true
        }.getOrDefault(false)
    }

    val VIDEO_CLIENTS = listOf(
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            params = null,
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            params = null,
            glOverride = "US",
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            clientId = "101",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            osName = "visionOS",
            osVersion = "1.3.21O771",
            params = null,
            glOverride = null,
            sendOriginHeaders = true
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip",
            clientId = "3",
            deviceMake = "Google",
            deviceModel = "Pixel 8",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            params = "CgIIAQ==",
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "IOS",
            clientVersion = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)",
            clientId = "5",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.3.1.22D72",
            params = null,
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
            clientId = "85",
            params = null,
            glOverride = null,
            isEmbedded = true,
            sendOriginHeaders = true
        ),
        InnerTubeClientConfig(
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20250310.01.00",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            clientId = "56",
            params = null,
            glOverride = null,
            isEmbedded = true,
            sendOriginHeaders = true
        )
    )

    private val probeHttpClient by lazy {
        com.example.network.NetworkClients.okHttpClient.newBuilder()
            .connectTimeout(1400, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1400, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    fun verifyVideoStreamPlayable(streamUrl: String): Boolean {
        val trimmed = streamUrl.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("merged_av::")) {
            val parts = trimmed.removePrefix("merged_av::").split("|||")
            if (parts.size < 2) return false
            return verifySingleStreamUrl(parts[0]) && verifySingleStreamUrl(parts[1])
        }
        if (trimmed.contains(".m3u8", ignoreCase = true) || trimmed.contains("manifest.googlevideo.com", ignoreCase = true)) {
            if (!isHlsSupported) return false
            return verifySingleStreamUrl(trimmed, isHls = true)
        }
        return verifySingleStreamUrl(trimmed, isHls = false)
    }

    private fun verifySingleStreamUrl(url: String, isHls: Boolean = false): Boolean {
        return runCatching {
            val headers = com.seitharol.player.OuterTuneResolvingDataSource.resolveClientHeadersForUrl(url)
            val clen = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val isAdaptiveGir = url.contains("gir=yes", ignoreCase = true) &&
                !url.contains("itag=18&", ignoreCase = true) &&
                !url.endsWith("itag=18", ignoreCase = true) &&
                !url.contains("itag=22&", ignoreCase = true) &&
                !url.endsWith("itag=22", ignoreCase = true)

            // For adaptive gir=yes streams, probe past the ~1 MiB (1:04) GVS cap (at 1,150,000 bytes).
            // For progressive ratebypass=yes MP4 streams (itag=18/22 where clen is often absent from sparams),
            // probe bytes 0..255 so we never send an out-of-range offset on short tracks.
            val probeStart = when {
                !isAdaptiveGir -> 0L
                clen > 1_200_000L -> 1_150_000L
                clen <= 0L -> 1_150_000L
                clen > 100_000L -> 65_536L
                else -> 0L
            }
            val probeEnd = probeStart + 255L

            val targetUrl = if (!isHls && isAdaptiveGir && !url.contains("&range=")) {
                val sep = if (url.contains("?")) "&" else "?"
                "${url}${sep}range=${probeStart}-${probeEnd}"
            } else {
                url
            }

            val reqBuilder = okhttp3.Request.Builder().url(targetUrl).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            if (!isHls && !isAdaptiveGir) {
                reqBuilder.header("Range", "bytes=${probeStart}-${probeEnd}")
            }

            val primaryOk = probeHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code in 200..206) {
                    val crHeader = resp.header("Content-Range").orEmpty()
                    val slashIdx = crHeader.lastIndexOf('/')
                    if (slashIdx > 0 && slashIdx < crHeader.length - 1) {
                        val discoveredTotal = crHeader.substring(slashIdx + 1).trim().toLongOrNull() ?: -1L
                        if (discoveredTotal > 0L) {
                            com.seitharol.player.OuterTuneResolvingDataSource.recordKnownStreamLength(url, discoveredTotal)
                        }
                    }
                    val cLenHeader = resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    if (cLenHeader == 0L) {
                        false
                    } else {
                        val firstByte = resp.body?.byteStream()?.read() ?: -1
                        firstByte != -1
                    }
                } else if (resp.code == 416 && clen <= 0L && probeStart > 0L) {
                    // Short clip (<1.15 MB): verify byte 0..255 with bounded range
                    val shortUrl = if (!isHls && isAdaptiveGir && !url.contains("&range=")) {
                        val sep = if (url.contains("?")) "&" else "?"
                        "${url}${sep}range=0-255"
                    } else url
                    val shortReq = okhttp3.Request.Builder().url(shortUrl).get().apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        if (!isHls && !isAdaptiveGir) header("Range", "bytes=0-255")
                    }.build()
                    probeHttpClient.newCall(shortReq).execute().use { r2 ->
                        val cLen2 = r2.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                        r2.code in 200..206 && cLen2 != 0L && ((r2.body?.byteStream()?.read() ?: -1) != -1)
                    }
                } else {
                    false
                }
            }
            if (primaryOk) {
                true
            } else if (!isHls && isAdaptiveGir) {
                // Fallback: if &range= returned 0 bytes, verify with standard HTTP Range header
                val rangeReq = okhttp3.Request.Builder().url(url).get().apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=${probeStart}-${probeEnd}")
                }.build()
                probeHttpClient.newCall(rangeReq).execute().use { rFallback ->
                    val cLenFb = rFallback.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    rFallback.code in 200..206 && cLenFb != 0L && ((rFallback.body?.byteStream()?.read() ?: -1) != -1)
                }
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private val failedAudioStreamsByVideoId = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    fun markAudioStreamFailed(videoId: String, failedUrl: String?) {
        if (videoId.isBlank() || failedUrl.isNullOrBlank()) return
        val clean = extractVideoId(videoId) ?: videoId.trim()
        val set = failedAudioStreamsByVideoId.getOrPut(clean) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        set.add(failedUrl.substringBefore("&range=").substringBefore("?range="))
        set.add(failedUrl)
    }

    fun verifyStreamPlayable(streamUrl: String): String? {
        val trimmed = streamUrl.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("http")) return null
        val isHls = trimmed.contains(".m3u8", ignoreCase = true) ||
            trimmed.contains("manifest.googlevideo.com", ignoreCase = true)
        if (isHls && !isHlsSupported) return null
        return if (verifySingleStreamUrl(trimmed, isHls = isHls)) trimmed else null
    }

    private fun extractStreamWithConfig(
        videoId: String,
        client: InnerTubeClientConfig,
        preferredQuality: AudioQuality,
        cpn: String,
        allowHls: Boolean = true,
        includeVisitorData: Boolean = false,
        requiredItag: Int? = null,
        preferProgressiveMp4: Boolean = true
    ): String? {
        return runCatching {
            val visData = if (includeVisitorData || client.isEmbedded || client.clientName.equals("VISIONOS", ignoreCase = true)) {
                cachedVisitorData?.takeIf { it.isNotBlank() } ?: ensureVisitorData(forceFresh = false)
            } else {
                cachedVisitorData?.takeIf { it.isNotBlank() }
            }
            val effectiveGl = client.glOverride ?: resolveDeviceGl()
            val endpoint = URL(client.playerEndpoint)
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2800
                readTimeout = 2800
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", client.userAgent)
                setRequestProperty("X-YouTube-Client-Name", client.clientId)
                setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
                setRequestProperty("X-Goog-Api-Format-Version", "2")
                if (client.sendOriginHeaders) {
                    setRequestProperty("Origin", client.origin)
                    setRequestProperty("Referer", "${client.origin}/")
                }
                if (visData != null) {
                    setRequestProperty("X-Goog-Visitor-Id", visData)
                }
            }

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (!client.params.isNullOrBlank()) {
                    put("params", client.params)
                }
                if (client.isEmbedded) {
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("signatureTimestamp", com.example.resolver.KotlinJsInterp.getSignatureTimestamp())
                        })
                    })
                }
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("userAgent", client.userAgent)
                        put("hl", "en")
                        put("gl", effectiveGl)
                        client.deviceMake?.let { put("deviceMake", it) }
                        client.deviceModel?.let { put("deviceModel", it) }
                        client.osName?.let { put("osName", it) }
                        client.osVersion?.let { put("osVersion", it) }
                        client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        if (visData != null) {
                            put("visitorData", visData)
                        }
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                    })
                    if (client.isEmbedded) {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com/")
                        })
                    }
                })
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@runCatching null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                cachedVisitorData = it
            }

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status == "LOGIN_REQUIRED" || status == "UNPLAYABLE") {
                if (client.clientName.equals("VISIONOS", ignoreCase = true)) {
                    cachedVisitorData = null
                }
            }
            if (status != "OK") return@runCatching null

            val streamingData = json.optJSONObject("streamingData") ?: return@runCatching null
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val legacyFormats = streamingData.optJSONArray("formats")

            data class AudioStreamCandidate(
                val itag: Int,
                val kbps: Int,
                val isAacMp4: Boolean,
                val isProgressiveMp4: Boolean,
                val isOpus: Boolean,
                val url: String
            )
            val audioStreams = mutableListOf<AudioStreamCandidate>()

            fun collectStreams(arr: JSONArray?, isLegacy: Boolean, allowCipher: Boolean) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val fmt = arr.optJSONObject(i) ?: continue
                    val mime = fmt.optString("mimeType", "")
                    val itag = fmt.optInt("itag", 0)
                    // Collect pure audio streams (itag 140/141/139 M4A AAC, 251/250/249 WebM Opus)
                    // AND progressive muxed MP4 (itag 18/22 with ratebypass=yes) as universal fallback
                    val isAudio = mime.startsWith("audio/") || itag in listOf(140, 141, 139, 251, 250, 249, 18, 22)
                    if (!isAudio) continue

                    var rawUrl = fmt.optString("url", "")
                    if (rawUrl.isBlank() && allowCipher) {
                        val cipherStr = fmt.optString("signatureCipher").ifBlank { fmt.optString("cipher") }
                        if (cipherStr.isNotBlank()) {
                            val params = mutableMapOf<String, String>()
                            for (part in cipherStr.split("&")) {
                                val idx = part.indexOf('=')
                                if (idx > 0) {
                                    params[URLDecoder.decode(part.substring(0, idx), "UTF-8")] =
                                        URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                                }
                            }
                            val baseUrl = params["url"].orEmpty()
                            val sig = params["s"].orEmpty()
                            val sp = params["sp"] ?: "sig"
                            if (baseUrl.isNotBlank() && sig.isNotBlank()) {
                                val ops = com.example.resolver.KotlinJsInterp.getActiveCipherOperations()
                                if (ops.isNotEmpty()) {
                                    val deciphered = com.example.resolver.KotlinJsInterp.decipherSignature(sig, ops)
                                    if (deciphered.isNotBlank() && deciphered != sig) {
                                        val sep = if (baseUrl.contains("?")) "&" else "?"
                                        rawUrl = "$baseUrl$sep$sp=$deciphered"
                                    }
                                }
                            }
                        }
                    }
                    if (rawUrl.isBlank()) continue

                    val finalUrl = if (!rawUrl.contains("cpn=")) {
                        val sep = if (rawUrl.contains("?")) "&" else "?"
                        "$rawUrl${sep}cpn=$cpn"
                    } else {
                        rawUrl
                    }
                    val jsonClen = fmt.optString("contentLength", "").toLongOrNull() ?: -1L
                    if (jsonClen > 0L) {
                        com.seitharol.player.OuterTuneResolvingDataSource.recordKnownStreamLength(finalUrl, jsonClen)
                    }

                    val kbps = fmt.optInt("bitrate", 128000) / 1000
                    val isAacMp4 = mime.contains("mp4a", ignoreCase = true) || itag in listOf(140, 141, 139)
                    val isProgressiveMp4 = isLegacy || itag == 18 || itag == 22 || !finalUrl.contains("gir=yes", ignoreCase = true)
                    val isOpus = mime.contains("opus", ignoreCase = true) || itag in listOf(249, 250, 251)
                    audioStreams.add(
                        AudioStreamCandidate(
                            itag = itag,
                            kbps = kbps,
                            isAacMp4 = isAacMp4,
                            isProgressiveMp4 = isProgressiveMp4,
                            isOpus = isOpus,
                            url = finalUrl
                        )
                    )
                }
            }

            // Pass 1: Collect direct unciphered URLs from both adaptiveFormats and legacyFormats (itag=18 ratebypass=yes)
            collectStreams(adaptiveFormats, isLegacy = false, allowCipher = false)
            collectStreams(legacyFormats, isLegacy = true, allowCipher = false)

            // Pass 2: Inspect ciphered streams on embedded clients if Pass 1 returned zero direct URLs
            if (audioStreams.isEmpty() && client.isEmbedded) {
                collectStreams(adaptiveFormats, isLegacy = false, allowCipher = true)
                collectStreams(legacyFormats, isLegacy = true, allowCipher = true)
            }

            val failedForVideo = failedAudioStreamsByVideoId[videoId].orEmpty()
            // VISIONOS is exempt from YouTube's 1 MB (1:04) GVS proof-of-origin cap, so pure audio (itag=140 M4A / itag=251 Opus)
            // plays and seeks 100% of the track uninterrupted! For non-VISIONOS fallback clients, prefer progressive MP4 (itag=18)
            // or verify gir=yes streams past byte 1,150,000 so a 1:04-capped stream is never returned.
            val isVisionOs = client.clientName.equals("VISIONOS", ignoreCase = true)
            val effectivePreferProgressive = if (isVisionOs) preferProgressiveMp4 else true
            val sortedCandidates = audioStreams
                .filter { cand ->
                    requiredItag == null || cand.itag == requiredItag
                }
                .filterNot { cand ->
                    val base = cand.url.substringBefore("&range=").substringBefore("?range=")
                    failedForVideo.contains(cand.url) || failedForVideo.contains(base)
                }
                .sortedWith(
                    compareByDescending<AudioStreamCandidate> { requiredItag != null && it.itag == requiredItag }
                        .thenByDescending { if (effectivePreferProgressive) it.isProgressiveMp4 else !it.isProgressiveMp4 }
                        .thenByDescending { it.itag == 140 || it.isAacMp4 }
                        .thenBy { kotlin.math.abs(it.kbps - preferredQuality.maxBitrateKbps) }
                )

            // Return the top candidate immediately without blocking on 1.4s OkHttp pre-probes!
            // ExoPlayer OuterTuneResolvingDataSource opens it directly and marks any failed stream in failedAudioStreamsByVideoId.
            val bestCandidate = sortedCandidates.firstOrNull()
            if (bestCandidate != null) {
                return@runCatching bestCandidate.url
            }

            if (allowHls) {
                val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                if (hlsUrl.isNotBlank()) {
                    val verifiedHls = verifyStreamPlayable(hlsUrl)
                    if (!verifiedHls.isNullOrBlank()) {
                        return@runCatching verifiedHls
                    }
                }
            }

            null
        }.getOrNull()
    }

    suspend fun resolveExactYoutubeStreamById(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL,
        forceRefresh: Boolean = false,
        requiredItag: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId) ?: videoId.trim()
        if (!VALID_VIDEO_ID_REGEX.matches(cleanVideoId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube videoId: $videoId"))
        }

        if (forceRefresh) {
            com.seitharol.player.OuterTuneResolvingDataSource.invalidate(cleanVideoId)
        } else if (requiredItag == null) {
            com.seitharol.player.OuterTuneResolvingDataSource.getCachedUrl(cleanVideoId, preferredQuality)?.let { cached ->
                if (!cached.contains(".m3u8", ignoreCase = true) && !cached.contains("manifest.googlevideo.com", ignoreCase = true)) {
                    return@withContext Result.success(cached)
                }
            }
        }

        val cpn = generateCpn()
        val startOffset = if (forceRefresh) {
            clientRotationOffset.incrementAndGet() % VERIFIED_CLIENTS.size
        } else {
            0
        }

        // Fast Path: Try primary client first (VISIONOS 1.02 for uncapped M4A/Opus, or ANDROID_VR 1.61.48 for progressive MP4 itag=18).
        val primaryClient = VERIFIED_CLIENTS[startOffset % VERIFIED_CLIENTS.size]
        val fastUrl = extractStreamWithConfig(
            videoId = cleanVideoId,
            client = primaryClient,
            preferredQuality = preferredQuality,
            cpn = cpn,
            allowHls = false,
            includeVisitorData = primaryClient.isEmbedded || primaryClient.clientName.equals("VISIONOS", ignoreCase = true),
            requiredItag = requiredItag,
            preferProgressiveMp4 = !primaryClient.clientName.equals("VISIONOS", ignoreCase = true)
        )
        if (!fastUrl.isNullOrBlank()) {
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, fastUrl)
            return@withContext Result.success(fastUrl)
        }

        // Sequential Client Failover across all verified clients
        val remainingClients = VERIFIED_CLIENTS.indices
            .filter { it != (startOffset % VERIFIED_CLIENTS.size) }
            .map { VERIFIED_CLIENTS[it] }
        for (client in remainingClients) {
            val nextUrl = extractStreamWithConfig(
                videoId = cleanVideoId,
                client = client,
                preferredQuality = preferredQuality,
                cpn = generateCpn(),
                allowHls = false,
                includeVisitorData = client.isEmbedded || client.clientName.equals("VISIONOS", ignoreCase = true),
                requiredItag = requiredItag,
                preferProgressiveMp4 = !client.clientName.equals("VISIONOS", ignoreCase = true)
            )
            if (!nextUrl.isNullOrBlank()) {
                com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, nextUrl)
                return@withContext Result.success(nextUrl)
            }
        }

        // Fallback to progressive video stream extractor (itag=18 ratebypass=yes / federated nodes)
        if (requiredItag == null) {
            val rawVideoFallback = resolveRawVideoStreamById(
                videoId = cleanVideoId,
                forceRefresh = forceRefresh,
                allowHlsFallback = false
            ).getOrNull()
            val singleFallback = when {
                rawVideoFallback.isNullOrBlank() -> null
                rawVideoFallback.startsWith("merged_av::") -> {
                    val parts = rawVideoFallback.removePrefix("merged_av::").split("|||")
                    parts.getOrNull(1) ?: parts.firstOrNull()
                }
                else -> rawVideoFallback
            }
            if (!singleFallback.isNullOrBlank()) {
                com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, singleFallback)
                return@withContext Result.success(singleFallback)
            }
        }

        Result.failure(IllegalStateException("Failed to resolve verified YouTube stream for videoId=$cleanVideoId"))
    }

    suspend fun resolveWorkingStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL,
        forceRefresh: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId)
        if (cleanVideoId != null) {
            return@withContext resolveExactYoutubeStreamById(cleanVideoId, preferredQuality, forceRefresh)
        }
        Result.failure(IllegalStateException("Unable to resolve stream for $videoId"))
    }

    suspend fun fetchAutomixQueue(
        seedVideoId: String,
        playlistIdOverride: String? = null,
        limit: Int = 24
    ): List<CandidateStream> = withContext(Dispatchers.IO) {
        if (seedVideoId.isBlank() && playlistIdOverride.isNullOrBlank()) return@withContext emptyList()
        val playlistId = playlistIdOverride ?: "RDAMVM$seedVideoId"
        val payload = webRemixPayload().apply {
            if (seedVideoId.isNotBlank()) put("videoId", seedVideoId)
            put("playlistId", playlistId)
            put("isAudioOnly", true)
        }
        val root = postWebRemix("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", payload)
            ?: return@withContext emptyList()
        parseAutomixPlaylistPanel(root, seedVideoId).take(limit)
    }

    /**
     * Fetches live dynamic music shelves directly from YouTube Music's `FEmusic_home` and `FEmusic_explore`
     * browse endpoints so cold starts and refreshes always reflect real-time dynamic YTM charts & recommendations.
     */
    suspend fun fetchLiveYtmHomeAndExploreTracks(limit: Int = 28): List<CandidateStream> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<CandidateStream>()
        val browseIds = listOf("FEmusic_home", "FEmusic_explore")
        for (bId in browseIds) {
            runCatching {
                val payload = webRemixPayload().apply { put("browseId", bId) }
                val root = postWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", payload)
                    ?: return@runCatching
                val responsiveItems = mutableListOf<JSONObject>()
                val twoRowItems = mutableListOf<JSONObject>()
                fun collectBrowseNodes(node: Any?) {
                    if (responsiveItems.size + twoRowItems.size >= 80) return
                    when (node) {
                        is JSONObject -> {
                            node.optJSONObject("musicResponsiveListItemRenderer")?.let {
                                responsiveItems.add(it)
                                return
                            }
                            node.optJSONObject("musicTwoRowItemRenderer")?.let {
                                twoRowItems.add(it)
                                return
                            }
                            val keys = node.keys()
                            while (keys.hasNext()) {
                                collectBrowseNodes(node.opt(keys.next()))
                            }
                        }
                        is JSONArray -> {
                            for (i in 0 until node.length()) {
                                collectBrowseNodes(node.opt(i))
                            }
                        }
                    }
                }
                collectBrowseNodes(root)
                for (item in responsiveItems) {
                    parseSingleResponsiveSongItem(item)?.let { discovered.add(it) }
                }
                for (twoRow in twoRowItems) {
                    val vid = extractVideoId(
                        twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                            ?: twoRow.optJSONObject("thumbnailOverlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId")
                    ) ?: continue
                    val title = extractSimpleOrRunsText(twoRow.optJSONObject("title")).takeIf { it.isNotBlank() } ?: continue
                    val subRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                    var artist = "Artist"
                    if (subRuns != null) {
                        for (r in 0 until subRuns.length()) {
                            val t = subRuns.optJSONObject(r)?.optString("text")?.trim().orEmpty()
                            if (t.isNotBlank() && t != "•" && !t.equals("Song", true) && !t.equals("Video", true) && !t.equals("Single", true)) {
                                artist = t
                                break
                            }
                        }
                    }
                    val thumb = extractBestThumbnail(
                        twoRow.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                    ).ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }
                    discovered.add(
                        CandidateStream(
                            videoId = vid,
                            title = title,
                            artist = artist,
                            channelName = "$artist - Topic",
                            albumName = null,
                            durationSeconds = 210,
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            thumbnailUrl = thumb,
                            isVerifiedArtist = true
                        )
                    )
                }
            }
            if (discovered.size >= limit) break
        }
        CanonicalAudioResolver.deduplicateTracks(discovered).shuffled().take(limit)
    }

    suspend fun buildPersonalizedHomeFeed(
        seedVideoIds: List<String>,
        favoriteArtists: List<String>,
        moodCategory: String,
        recentSearchQueries: List<String> = emptyList()
    ): List<CandidateStream> = coroutineScope {
        val validSeeds = seedVideoIds.filter { it.length == 11 }.distinct().take(5)
        val cleanArtists = favoriteArtists
            .map { it.removeSuffix("- Topic").trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
            .distinct()
        val cleanSearches = recentSearchQueries
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(3)

        val isGeneralFeed = moodCategory.equals("All", true) || moodCategory.equals("For You", true)
        val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        // 1. Automix radio continuations from the user's most recent played/liked tracks
        val automixSeeds: List<String> = if (isGeneralFeed) validSeeds else validSeeds.take(2)
        val automixJobs = automixSeeds.map { seedId ->
            async {
                fetchAutomixQueue(seedId, limit = 12).shuffled()
            }
        }

        // 2. Manipuri Playlist / Collection shelf for default general feed OR Mood Category Playlist
        val liveBrowseOrCategoryJob = async {
            if (!isGeneralFeed) {
                fetchMoodCategoryPlaylistTracks(moodCategory).shuffled()
            } else {
                fetchMoodCategoryPlaylistTracks("Manipuri Song").shuffled()
            }
        }

        // 3. Dynamic Time-of-Day + Manipuri Song Focused Discovery Pool
        val timeOfDayQueries = when (hourOfDay) {
            in 5..10 -> listOf(
                "Manipuri morning melody official songs",
                "Fresh morning Manipuri melodies official",
                "Imphal Manipuri acoustic & folk songs",
                "Manipuri classic & modern feel good songs"
            )
            in 11..16 -> listOf(
                "Manipuri modern hit songs official",
                "Imphal Manipuri trending hit songs",
                "Manipuri upbeat pop & indie official songs",
                "Manipuri latest official music releases"
            )
            in 17..21 -> listOf(
                "Manipuri evening romantic & soulful songs",
                "Sunset acoustic & Manipuri soulful songs",
                "Manipuri love songs & duets official",
                "Imphal Manipuri indie & melodic hits"
            )
            else -> listOf(
                "Manipuri late night love & emotional songs",
                "Midnight acoustic & Manipuri love songs",
                "Manipuri romantic ballad hit songs",
                "Imphal Manipuri slow melodic songs"
            )
        }

        val dynamicDiscoveryPool = listOf(
            "Manipuri new official music releases",
            "Imphal trending Manipuri songs official",
            "Manipuri modern folk & indie pop songs",
            "Manipuri romantic melodies official audio",
            "Manipuri latest official hit songs",
            "Manipuri love songs & acoustic duets",
            "Manipuri top viral songs Imphal",
            "Manipuri film & ost hit songs",
            "Manipuri classic & evergreen hit songs",
            "Manipuri indie & band official songs"
        )

        val artistModifiers = listOf("official audio", "latest songs", "top hit songs", "best tracks")

        val searchQueries = when {
            moodCategory.equals("Manipuri Song", true) -> listOf(
                "Manipuri latest official hit songs",
                "Imphal Manipuri trending songs",
                "Manipuri romantic & modern folk songs",
                "Manipuri classic & new official audio",
                "Manipuri indie & acoustic hit songs"
            ).shuffled().take(4)
            moodCategory.equals("Trending", true) -> listOf(
                "Manipuri trending hit songs official",
                "Imphal Manipuri top viral songs",
                "Manipuri latest chart hit songs",
                "Manipuri popular music official"
            ).shuffled().take(3)
            moodCategory.equals("Energize", true) -> listOf(
                "Energize upbeat pop dance hit songs",
                "High energy feel good official songs",
                "Upbeat party & energetic hits",
                "Fast tempo electro pop & dance anthems"
            ).shuffled().take(3)
            moodCategory.equals("Sad", true) -> listOf(
                "Sad emotional heartbreak songs official",
                "Acoustic sad songs & melancholic ballads",
                "Broken heart emotional hit songs",
                "Soulful melancholic indie & pop songs"
            ).shuffled().take(3)
            moodCategory.equals("Chill", true) -> listOf(
                "Chill vibes relaxing indie & R&B songs",
                "Late night chill acoustic official songs",
                "Smooth chillout pop & soul tracks",
                "Laidback indie & acoustic chill hits"
            ).shuffled().take(3)
            moodCategory.equals("Romantic", true) -> listOf(
                "Romantic love songs official audio",
                "Best acoustic love ballads & duets",
                "Manipuri & Indian romantic hit songs",
                "Heartfelt love songs & acoustic duets"
            ).shuffled().take(3)
            moodCategory.equals("Workout", true) -> listOf(
                "Workout gym motivation phonk & hip hop songs",
                "High tempo running & training hits",
                "Power workout official audio tracks",
                "Cardio & lifting hype anthems"
            ).shuffled().take(3)
            moodCategory.equals("Lo-Fi", true) -> listOf(
                "Lofi chill beats study & relax",
                "Late night lo-fi hip hop & aesthetic beats",
                "Chill lofi instrumental & vocal mixes",
                "Cozy rain lofi & mellow beats"
            ).shuffled().take(3)
            isGeneralFeed -> {
                // Regional-first (Manipuri & Northeast/India) + Time-of-Day discovery queries.
                // Artist personalization is handled via verified belongsToArtist filtering and Automix
                // seed continuations so artist words (like "Limbo" in "Life in Limbo") never pollute
                // the feed with unrelated songs whose title happens to contain that word!
                val userPersonalizedQueries = mutableListOf<String>()
                cleanSearches.take(2).forEach { q ->
                    userPersonalizedQueries.add("$q official audio")
                }
                val regionalCore = listOf(
                    "Manipuri new official music releases",
                    "Imphal trending Manipuri songs official",
                    "Manipuri modern folk & indie pop songs",
                    "Manipuri romantic melodies official audio"
                ).shuffled().take(2)
                val sampledDiscovery = (regionalCore + timeOfDayQueries.shuffled().take(1) + dynamicDiscoveryPool.shuffled().take(1)).distinct()
                (sampledDiscovery + userPersonalizedQueries.take(2)).distinct()
            }
            else -> listOf(
                "$moodCategory official hit songs",
                "$moodCategory trending music",
                "$moodCategory top tracks"
            )
        }

        val searchJobs = searchQueries.map { q ->
            async {
                searchOfficialYtmSongs(q, limit = 12).shuffled()
            }
        }
        // Fetch verified songs actually BY the user's favorite artists (strict belongsToArtist check
        // on candidate.artist so "Life in Limbo" never matches unrelated songs titled "Limbo"!)
        val verifiedArtistJobs = if (isGeneralFeed) {
            cleanArtists.take(3).map { favArtist ->
                async {
                    searchOfficialYtmSongs(favArtist, limit = 10)
                        .filter { cand -> belongsToArtist(targetArtist = favArtist, itemTitle = "", itemSubtitle = "", itemArtist = cand.artist) }
                        .shuffled()
                        .take(3)
                }
            }
        } else {
            emptyList()
        }
        val liveOrPlaylistBatch: List<CandidateStream> = liveBrowseOrCategoryJob.await()
        val automixBatches = automixJobs.awaitAll().filter { it.isNotEmpty() }
        val verifiedArtistBatch = verifiedArtistJobs.awaitAll().flatten()
        val searchBatches = searchJobs.awaitAll().filter { it.isNotEmpty() }

        val batches = mutableListOf<List<CandidateStream>>()
        // Interleave regional-first discovery, verified favorite-artist tracks, and automix continuations
        batches.addAll(searchBatches)
        if (verifiedArtistBatch.isNotEmpty()) {
            batches.add(verifiedArtistBatch)
        }
        batches.addAll(automixBatches)
        if (liveOrPlaylistBatch.isNotEmpty()) {
            batches.add(liveOrPlaylistBatch)
        }

        val interleaved = mutableListOf<CandidateStream>()
        val maxLen = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (batch in batches) {
                if (i < batch.size) {
                    val track = batch[i]
                    if (!CanonicalAudioResolver.containsBannedModifier(moodCategory, track.title)) {
                        interleaved.add(track)
                    }
                }
            }
        }
        CanonicalAudioResolver.deduplicateTracks(interleaved)
    }


    /**
     * Regional-first + personalization-aware generator for the "Fresh New Music" shelf.
     * - Always anchors on regional Manipuri & Indian fresh releases + live YouTube Music `FEmusic_new_releases` / `FEmusic_explore`.
     * - Incorporates the user's personalization via Automix radio continuations from recent played/liked `seedVideoIds`
     *   and strictly verified artist matches (`belongsToArtist` on `cand.artist` only, capped to 2 tracks per artist)
     *   so an artist name containing a common word (e.g. "Life in Limbo") NEVER floods "Fresh New Music" with
     *   unrelated songs that merely have "Limbo" in their song title!
     */
    suspend fun buildFreshNewMusicFeed(
        seedVideoIds: List<String>,
        favoriteArtists: List<String>,
        moodCategory: String = "All"
    ): List<CandidateStream> = coroutineScope {
        val validSeeds = seedVideoIds.filter { it.length == 11 }.distinct().take(3)
        val cleanArtists = favoriteArtists
            .map { it.removeSuffix("- Topic").trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
            .distinct()
            .take(3)

        // 1. Regional-first Fresh Releases (Manipuri & Northeast / India new official songs)
        val regionalFreshQueries = when {
            moodCategory.equals("Manipuri Song", true) -> listOf(
                "Manipuri new official song releases",
                "Latest Imphal Manipuri official audio",
                "Manipuri modern folk & romantic new songs"
            )
            !moodCategory.equals("All", true) && !moodCategory.equals("For You", true) -> listOf(
                "Manipuri new official song releases",
                "$moodCategory latest official audio releases"
            )
            else -> listOf(
                "Manipuri latest official new songs",
                "Imphal new Manipuri music official audio",
                "Manipuri new official song releases",
                "Manipuri modern folk & romantic new songs"
            ).shuffled().take(3)
        }

        val regionalJobs = regionalFreshQueries.map { q ->
            async { searchOfficialYtmSongs(q, limit = 10).shuffled() }
        }

        // 2. Fresh Manipuri New Releases shelf
        val liveNewReleasesJob = async {
            searchOfficialYtmSongs("Manipuri new song release official", limit = 14).shuffled()
        }

        // 3. Personalization via Automix seed continuations (true musical similarity, never keyword title matching!)
        val automixPersonalizedJob = async {
            validSeeds.take(2).flatMap { seed ->
                runCatching { fetchAutomixQueue(seed, limit = 8) }.getOrDefault(emptyList())
            }.shuffled().take(6)
        }

        // 4. Strictly verified releases actually BY the user's top artists (checking candidate.artist ONLY,
        // never matching song title words, and capped at 2 songs per artist for healthy diversity)
        val verifiedArtistReleasesJob = async {
            cleanArtists.take(2).flatMap { artistName ->
                runCatching {
                    searchOfficialYtmSongs(artistName, limit = 8)
                        .filter { cand ->
                            belongsToArtist(
                                targetArtist = artistName,
                                itemTitle = "",
                                itemSubtitle = "",
                                itemArtist = cand.artist
                            )
                        }
                        .take(2)
                }.getOrDefault(emptyList())
            }
        }

        val regionalBatches = regionalJobs.awaitAll().filter { it.isNotEmpty() }
        val liveBatch = liveNewReleasesJob.await()
        val automixBatch = automixPersonalizedJob.await()
        val verifiedArtistBatch = verifiedArtistReleasesJob.await()

        val allBatches = mutableListOf<List<CandidateStream>>()
        // Lead with regional-first batches, then interleave personalized automix, live explore, and verified artist tracks
        allBatches.addAll(regionalBatches)
        if (automixBatch.isNotEmpty()) allBatches.add(automixBatch)
        if (liveBatch.isNotEmpty()) allBatches.add(liveBatch)
        if (verifiedArtistBatch.isNotEmpty()) allBatches.add(verifiedArtistBatch)

        val interleaved = mutableListOf<CandidateStream>()
        val artistCounts = mutableMapOf<String, Int>()
        val maxLen = allBatches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (batch in allBatches) {
                if (i < batch.size) {
                    val track = batch[i]
                    val artistKey = track.artist.lowercase().trim()
                    val count = artistCounts[artistKey] ?: 0
                    if (count < 2 && !CanonicalAudioResolver.containsBannedModifier(moodCategory, track.title)) {
                        artistCounts[artistKey] = count + 1
                        interleaved.add(track)
                    }
                }
            }
        }

        CanonicalAudioResolver.deduplicateTracks(interleaved)
    }

    private suspend fun fetchMoodCategoryPlaylistTracks(moodCategory: String): List<CandidateStream> {
        return try {
            val moodQuery = when {
                moodCategory.equals("Manipuri Song", true) -> "Manipuri Hit Songs"
                moodCategory.equals("Trending", true) -> "Manipuri Trending Hit Songs"
                moodCategory.equals("Energize", true) -> "Energize Pop & Dance Hits"
                moodCategory.equals("Sad", true) -> "Sad Heartbreak Songs Official"
                moodCategory.equals("Chill", true) -> "Chill Vibes Official Songs"
                moodCategory.equals("Romantic", true) -> "Romantic Love Songs Official"
                moodCategory.equals("Workout", true) -> "Workout Gym Hype Songs"
                moodCategory.equals("Lo-Fi", true) -> "Lo-Fi Chill Beats & Late Night Vibes"
                else -> "$moodCategory Official Hit Songs"
            }
            val playlists = searchYtmCollections(
                query = moodQuery,
                filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==",
                type = "PLAYLIST",
                limit = 3
            )
            val firstPl = playlists.firstOrNull()
            if (firstPl != null) {
                fetchPlaylistAlbumOrMixDetails(firstPl.browseId, firstPl.title, firstPl.thumbnailUrl)
                    .tracks
                    .take(20)
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun fetchPersonalizedCollections(
        favoriteArtists: List<String>,
        seedVideoIds: List<String>,
        moodCategory: String = "All"
    ): List<YtmCollectionSummary> = coroutineScope {
        val collections = mutableListOf<YtmCollectionSummary>()

        val query = when {
            moodCategory.equals("Manipuri Song", true) -> "Manipuri Hits"
            !moodCategory.equals("All", true) && !moodCategory.equals("For You", true) -> "$moodCategory Music"
            favoriteArtists.isNotEmpty() -> "${favoriteArtists.first()} best"
            else -> "Trending Hits India"
        }

        val playlistDeferred = async { searchYtmCollections(query, filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==", type = "PLAYLIST", limit = 6) }
        val albumDeferred = async { searchYtmCollections(query, filterParams = "EgWKAQIYAWoKEAkQChADEAUQBA==", type = "ALBUM", limit = 6) }

        collections.addAll(playlistDeferred.await())
        collections.addAll(albumDeferred.await())
        collections.distinctBy { it.browseId }
    }

    suspend fun searchYtmCollections(
        query: String,
        filterParams: String = "EgWKAQIoAWoKEAkQChADEAUQBA==",
        type: String = "PLAYLIST",
        limit: Int = 8
    ): List<YtmCollectionSummary> = withContext(Dispatchers.IO) {
        val payload = webRemixPayload().apply {
            put("query", query)
            put("params", filterParams)
        }
        val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
            ?: return@withContext emptyList()

        val out = mutableListOf<YtmCollectionSummary>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs") ?: return@withContext out

        for (t in 0 until tabs.length()) {
            val sections = tabs.getJSONObject(t)
                .optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue

            for (s in 0 until sections.length()) {
                val shelfContents = sections.getJSONObject(s)
                    .optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (i in 0 until shelfContents.length()) {
                    val item = shelfContents.getJSONObject(i)
                        .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val browseId = item.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                        ?.takeIf { it.isNotBlank() } ?: continue

                    val flexColumns = item.optJSONArray("flexColumns") ?: continue
                    val title = flexColumns.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text") ?: continue

                    val subRuns = flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    val subtitle = buildString {
                        if (subRuns != null) {
                            for (r in 0 until subRuns.length()) {
                                append(subRuns.getJSONObject(r).optString("text"))
                            }
                        }
                    }.trim().ifBlank { type.lowercase().replaceFirstChar { it.uppercase() } }

                    val thumbUrl = extractBestThumbnail(
                        item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                    )

                    val isSingle = subtitle.contains("Single", ignoreCase = true) ||
                        subtitle.contains("1 song", ignoreCase = true) ||
                        subtitle.contains("1 track", ignoreCase = true)
                    val effectiveType = if (isSingle && type == "ALBUM") "SINGLE" else type

                    out.add(
                        YtmCollectionSummary(
                            browseId = browseId,
                            playlistId = browseId.removePrefix("VL"),
                            title = title,
                            subtitle = subtitle,
                            thumbnailUrl = thumbUrl,
                            type = effectiveType
                        )
                    )
                    if (out.size >= limit) return@withContext out
                }
            }
        }
        out
    }

    fun belongsToArtist(
        targetArtist: String,
        itemTitle: String,
        itemSubtitle: String = "",
        itemArtist: String = ""
    ): Boolean {
        val normTarget = CanonicalAudioResolver.normalizeText(
            targetArtist.removeSuffix(" - Topic").removeSuffix("- Topic").trim()
        )
        if (normTarget.isBlank()) return true

        val normTitle = CanonicalAudioResolver.normalizeText(itemTitle)
        val normSub = CanonicalAudioResolver.normalizeText(itemSubtitle)
        val normArt = CanonicalAudioResolver.normalizeText(
            itemArtist.removeSuffix(" - Topic").removeSuffix("- Topic").trim()
        )

        if (normArt.contains(normTarget) || normTarget.contains(normArt).takeIf { normArt.length >= 3 } == true) {
            return true
        }
        if (normSub.contains(normTarget) || normTitle.contains(normTarget)) {
            return true
        }

        val targetTokens = normTarget.split(" ").filter { it.length >= 3 }
        if (targetTokens.isEmpty()) return true
        val combinedCandidate = "$normArt $normSub $normTitle"
        val matchedCount = targetTokens.count { token -> combinedCandidate.contains(token) }
        return if (targetTokens.size == 1) {
            matchedCount == 1
        } else {
            matchedCount >= targetTokens.size
        }
    }

    data class YouTubeChannelMeta(
        val channelId: String,
        val title: String,
        val avatarUrl: String,
        val bannerUrl: String,
        val subscriberCountText: String,
        val description: String,
        val videos: List<CandidateStream>,
        val playlists: List<YtmCollectionSummary> = emptyList()
    )

    private fun upgradeAvatarUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val withHttps = if (raw.startsWith("//")) "https:$raw" else raw
        return withHttps
            .replace(Regex("=s\\d+[^&\\s]*"), "=s512-c-k-c0x00ffffff-no-rj")
            .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w512-h512")
    }

    private fun parseViewCountLong(raw: String): Long {
        if (raw.isBlank()) return 0L
        val clean = raw.lowercase(Locale.US)
            .replace("views", "")
            .replace("view", "")
            .replace(",", "")
            .trim()
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([kmb]?)").find(clean) ?: return 0L
        val num = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val mult = when (match.groupValues[2]) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    private fun parsePublishedAgeSeconds(raw: String): Long {
        if (raw.isBlank()) return Long.MAX_VALUE
        val lower = raw.lowercase(Locale.US)
        if (lower.contains("just now") || lower.contains("moment")) return 10L
        val match = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)").find(lower)
            ?: return Long.MAX_VALUE
        val count = match.groupValues[1].toLongOrNull() ?: return Long.MAX_VALUE
        val unitSec = when (match.groupValues[2]) {
            "second" -> 1L
            "minute" -> 60L
            "hour" -> 3_600L
            "day" -> 86_400L
            "week" -> 604_800L
            "month" -> 2_592_000L
            "year" -> 31_536_000L
            else -> 86_400L
        }
        return count * unitSec
    }

    private fun extractSimpleOrRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()
    }

    /**
     * Queries YouTube WEB search (`EgIQAg==` channel filter), channel Videos tab (`EgZ2aWRlb3PyBgQKAjoA`),
     * and channel Playlists tab (`EglwbGF5bGlzdHPyBgQKAkIA`) to retrieve real channel avatar, banner,
     * subscriber count, playlists, and uploaded videos with view count and publish date metadata.
     */
    suspend fun fetchYouTubeChannelMeta(
        channelName: String,
        knownBrowseId: String? = null,
        treatUploadsAsVideo: Boolean = false
    ): YouTubeChannelMeta? = withContext(Dispatchers.IO) {
        val cleanName = channelName.removeSuffix(" - Topic").removeSuffix("- Topic").trim()
        if (cleanName.isBlank() && knownBrowseId.isNullOrBlank()) return@withContext null

        var resolvedId = knownBrowseId?.takeIf { it.startsWith("UC") }.orEmpty()
        var resolvedTitle = cleanName
        var resolvedAvatar = ""
        var resolvedSubs = ""
        var resolvedDesc = ""

        // Step 1: Search YouTube Channels (`params = "EgIQAg=="`) to get real channelId, avatar & subscriber count
        runCatching {
            val conn = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3500
                readTimeout = 3500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                setRequestProperty("X-YouTube-Client-Name", "1")
                setRequestProperty("X-YouTube-Client-Version", "2.20250310.01.00")
            }
            val payload = JSONObject().apply {
                put("query", cleanName)
                put("params", "EgIQAg==") // Channels filter
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250310.01.00")
                        put("hl", "en")
                        put("gl", resolveDeviceGl())
                    })
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val channelRenderers = mutableListOf<JSONObject>()
                fun collectChannels(node: Any?) {
                    if (channelRenderers.size >= 8) return
                    when (node) {
                        is JSONObject -> {
                            node.optJSONObject("channelRenderer")?.let { channelRenderers.add(it); return }
                            val keys = node.keys()
                            while (keys.hasNext()) collectChannels(node.opt(keys.next()))
                        }
                        is JSONArray -> {
                            for (i in 0 until node.length()) collectChannels(node.opt(i))
                        }
                    }
                }
                collectChannels(root)

                val normTarget = CanonicalAudioResolver.normalizeText(cleanName)
                val bestChannel = channelRenderers.firstOrNull { cr ->
                    val cid = cr.optString("channelId")
                    val cTitle = extractSimpleOrRunsText(cr.optJSONObject("title"))
                    val normCand = CanonicalAudioResolver.normalizeText(cTitle)
                    (resolvedId.isNotBlank() && cid == resolvedId) ||
                        normCand == normTarget ||
                        normCand.contains(normTarget) ||
                        normTarget.contains(normCand)
                } ?: channelRenderers.firstOrNull()

                if (bestChannel != null) {
                    val cid = bestChannel.optString("channelId")
                    if (resolvedId.isBlank() && cid.startsWith("UC")) resolvedId = cid
                    val cTitle = extractSimpleOrRunsText(bestChannel.optJSONObject("title"))
                    if (cTitle.isNotBlank()) resolvedTitle = cTitle
                    val rawThumb = extractBestThumbnail(bestChannel.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                    if (rawThumb.isNotBlank()) resolvedAvatar = upgradeAvatarUrl(rawThumb)
                    val subSimple = extractSimpleOrRunsText(bestChannel.optJSONObject("subscriberCountText"))
                        .ifBlank { extractSimpleOrRunsText(bestChannel.optJSONObject("videoCountText")) }
                    if (subSimple.isNotBlank()) resolvedSubs = subSimple
                    val descRuns = bestChannel.optJSONObject("descriptionSnippet")?.optJSONArray("runs")
                    if (descRuns != null) {
                        resolvedDesc = buildString {
                            for (r in 0 until descRuns.length()) append(descRuns.getJSONObject(r).optString("text"))
                        }.trim()
                    }
                }
            }
        }

        var resolvedBanner = resolvedAvatar
        val channelUploads = mutableListOf<CandidateStream>()
        val channelPlaylists = mutableListOf<YtmCollectionSummary>()

        // Step 2: If we have a real UC... channelId, browse the channel's Videos tab AND Playlists tab on YouTube
        if (resolvedId.startsWith("UC")) {
            runCatching {
                val conn = (URL("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    setRequestProperty("X-YouTube-Client-Name", "1")
                    setRequestProperty("X-YouTube-Client-Version", "2.20250310.01.00")
                }
                val payload = JSONObject().apply {
                    put("browseId", resolvedId)
                    put("params", "EgZ2aWRlb3PyBgQKAjoA") // Videos tab
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20250310.01.00")
                            put("hl", "en")
                            put("gl", resolveDeviceGl())
                        })
                    })
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val c4Header = root.optJSONObject("header")?.optJSONObject("c4TabbedHeaderRenderer")
                    if (c4Header != null) {
                        c4Header.optString("title").takeIf { it.isNotBlank() }?.let { resolvedTitle = it }
                        val headerAvatar = extractBestThumbnail(c4Header.optJSONObject("avatar")?.optJSONArray("thumbnails"))
                        if (headerAvatar.isNotBlank()) resolvedAvatar = upgradeAvatarUrl(headerAvatar)
                        val headerBanner = extractBestThumbnail(c4Header.optJSONObject("banner")?.optJSONArray("thumbnails"))
                        if (headerBanner.isNotBlank()) resolvedBanner = upgradeAvatarUrl(headerBanner)
                        val subTxt = extractSimpleOrRunsText(c4Header.optJSONObject("subscriberCountText"))
                        if (subTxt.isNotBlank()) resolvedSubs = subTxt
                    }
                    val metadataHeader = root.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
                    if (metadataHeader != null) {
                        metadataHeader.optString("title").takeIf { it.isNotBlank() }?.let { resolvedTitle = it }
                        metadataHeader.optString("description").takeIf { it.isNotBlank() && resolvedDesc.isBlank() }?.let { resolvedDesc = it }
                        val metaAvatar = extractBestThumbnail(metadataHeader.optJSONObject("avatar")?.optJSONArray("thumbnails"))
                        if (resolvedAvatar.isBlank() && metaAvatar.isNotBlank()) resolvedAvatar = upgradeAvatarUrl(metaAvatar)
                    }

                    val vRenderers = mutableListOf<JSONObject>()
                    fun collectChannelVideos(node: Any?) {
                        if (vRenderers.size >= 100) return
                        when (node) {
                            is JSONObject -> {
                                node.optJSONObject("videoRenderer")?.let { vRenderers.add(it); return }
                                node.optJSONObject("gridVideoRenderer")?.let { vRenderers.add(it); return }
                                val keys = node.keys()
                                while (keys.hasNext()) collectChannelVideos(node.opt(keys.next()))
                            }
                            is JSONArray -> {
                                for (i in 0 until node.length()) collectChannelVideos(node.opt(i))
                            }
                        }
                    }
                    collectChannelVideos(root)

                    for ((idx, vr) in vRenderers.withIndex()) {
                        val vid = extractVideoId(vr.optString("videoId")) ?: continue
                        val vTitle = extractSimpleOrRunsText(vr.optJSONObject("title")).takeIf { it.isNotBlank() } ?: continue
                        val lenText = extractSimpleOrRunsText(vr.optJSONObject("lengthText"))
                        if (lenText.isBlank()) continue
                        val durSec = parseDurationSeconds(lenText)
                        val viewsTxt = extractSimpleOrRunsText(vr.optJSONObject("viewCountText"))
                            .ifBlank { extractSimpleOrRunsText(vr.optJSONObject("shortViewCountText")) }
                        val pubTxt = extractSimpleOrRunsText(vr.optJSONObject("publishedTimeText"))
                        val parsedViews = parseViewCountLong(viewsTxt)
                        val parsedAgeSec = parsePublishedAgeSeconds(pubTxt).let { age ->
                            // Channel Videos tab is ordered newest-first by default; preserve relative recency if age text is missing
                            if (age == Long.MAX_VALUE) idx.toLong() * 60L else age
                        }
                        val thumb = extractBestThumbnail(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                            .ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }
                        channelUploads.add(
                            CandidateStream(
                                videoId = vid,
                                title = vTitle,
                                artist = resolvedTitle,
                                artistBrowseId = resolvedId,
                                channelName = resolvedTitle,
                                albumName = null,
                                durationSeconds = durSec,
                                videoType = if (treatUploadsAsVideo) "MUSIC_VIDEO_TYPE_OMV" else "MUSIC_VIDEO_TYPE_ATV",
                                viewCount = parsedViews,
                                thumbnailUrl = thumb,
                                isVerifiedArtist = true,
                                viewCountText = viewsTxt,
                                publishedTimeText = pubTxt,
                                publishedAgeSeconds = parsedAgeSec
                            )
                        )
                    }
                }
            }

            // Step 2B: Browse the channel's Playlists tab (`params = "EglwbGF5bGlzdHPyBgQKAkIA"`)
            runCatching {
                val conn = (URL("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3500
                    readTimeout = 3500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    setRequestProperty("X-YouTube-Client-Name", "1")
                    setRequestProperty("X-YouTube-Client-Version", "2.20250310.01.00")
                }
                val payload = JSONObject().apply {
                    put("browseId", resolvedId)
                    put("params", "EglwbGF5bGlzdHPyBgQKAkIA") // Playlists tab
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20250310.01.00")
                            put("hl", "en")
                            put("gl", resolveDeviceGl())
                        })
                    })
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val plRenderers = mutableListOf<JSONObject>()
                    fun collectChannelPlaylists(node: Any?) {
                        if (plRenderers.size >= 30) return
                        when (node) {
                            is JSONObject -> {
                                node.optJSONObject("gridPlaylistRenderer")?.let { plRenderers.add(it); return }
                                node.optJSONObject("playlistRenderer")?.let { plRenderers.add(it); return }
                                node.optJSONObject("compactStationRenderer")?.let { plRenderers.add(it); return }
                                val keys = node.keys()
                                while (keys.hasNext()) collectChannelPlaylists(node.opt(keys.next()))
                            }
                            is JSONArray -> {
                                for (i in 0 until node.length()) collectChannelPlaylists(node.opt(i))
                            }
                        }
                    }
                    collectChannelPlaylists(root)

                    for (pr in plRenderers) {
                        val plId = pr.optString("playlistId").ifBlank {
                            pr.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.removePrefix("VL").orEmpty()
                        }
                        if (plId.isBlank()) continue
                        val plTitle = extractSimpleOrRunsText(pr.optJSONObject("title")).ifBlank { continue }
                        val countTxt = extractSimpleOrRunsText(pr.optJSONObject("videoCountText"))
                            .ifBlank { extractSimpleOrRunsText(pr.optJSONObject("videoCountShortText")) }
                        val thumb = extractBestThumbnail(pr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                            .ifBlank {
                                extractBestThumbnail(
                                    pr.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONArray("thumbnails")
                                )
                            }
                            .ifBlank { resolvedAvatar }
                        val browsePlId = if (plId.startsWith("VL")) plId else "VL$plId"
                        channelPlaylists.add(
                            YtmCollectionSummary(
                                browseId = browsePlId,
                                playlistId = plId.removePrefix("VL"),
                                title = plTitle,
                                subtitle = if (countTxt.isNotBlank()) "$countTxt • $resolvedTitle" else "Playlist • $resolvedTitle",
                                thumbnailUrl = thumb,
                                type = "PLAYLIST"
                            )
                        )
                    }
                }
            }
        }

        // Supplement channel playlists via YouTube playlist search (`params = "EgIQAw=="`) if needed
        if (channelPlaylists.size < 4 && resolvedTitle.isNotBlank()) {
            runCatching {
                val ytmPlaylists = searchYtmCollections(
                    query = resolvedTitle,
                    filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==",
                    type = "PLAYLIST",
                    limit = 10
                ).filter { belongsToArtist(resolvedTitle, it.title, it.subtitle) }
                channelPlaylists.addAll(ytmPlaylists)
            }
        }

        if (resolvedBanner.isBlank()) resolvedBanner = resolvedAvatar

        if (resolvedId.isBlank() && resolvedAvatar.isBlank() && channelUploads.isEmpty() && channelPlaylists.isEmpty()) {
            return@withContext null
        }

        YouTubeChannelMeta(
            channelId = resolvedId,
            title = resolvedTitle,
            avatarUrl = resolvedAvatar,
            bannerUrl = resolvedBanner,
            subscriberCountText = resolvedSubs,
            description = resolvedDesc,
            videos = channelUploads.distinctBy { it.videoId },
            playlists = channelPlaylists.distinctBy { it.browseId }
        )
    }

    /**
     * Searches YouTube Music & YouTube for real verified artist/channel profiles matching [query].
     * Never fabricates fake artists from song titles or unverified strings.
     */
    suspend fun searchVerifiedArtists(query: String, limit: Int = 5): List<YtmArtistPreview> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.length < 2) return@withContext emptyList()
            val out = mutableListOf<YtmArtistPreview>()

            val payload = webRemixPayload().apply {
                put("query", clean)
                put("params", "EgWKAQIgAWoKEAkQChADEAUQBA==")
            }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
            if (root != null) {
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")
                if (tabs != null) {
                    for (t in 0 until tabs.length()) {
                        val sections = tabs.getJSONObject(t)
                            .optJSONObject("tabRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents") ?: continue
                        for (s in 0 until sections.length()) {
                            val shelfContents = sections.getJSONObject(s)
                                .optJSONObject("musicShelfRenderer")
                                ?.optJSONArray("contents") ?: continue
                            for (i in 0 until shelfContents.length()) {
                                val item = shelfContents.getJSONObject(i)
                                    .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                val browseId = item.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")
                                    ?.optString("browseId").orEmpty()
                                if (!browseId.startsWith("UC") && !browseId.startsWith("MPLA")) continue

                                val flexColumns = item.optJSONArray("flexColumns")
                                val itemTitle = flexColumns?.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                    ?.optJSONObject(0)
                                    ?.optString("text").orEmpty().trim()
                                if (itemTitle.isBlank()) continue

                                val subRuns = flexColumns?.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                val subText = buildString {
                                    if (subRuns != null) {
                                        for (r in 0 until subRuns.length()) {
                                            append(subRuns.getJSONObject(r).optString("text"))
                                        }
                                    }
                                }.trim().ifBlank { "Official Artist" }

                                val rawAvatar = extractBestThumbnail(
                                    item.optJSONObject("thumbnail")
                                        ?.optJSONObject("musicThumbnailRenderer")
                                        ?.optJSONObject("thumbnail")
                                        ?.optJSONArray("thumbnails")
                                )
                                val avatar = upgradeAvatarUrl(rawAvatar)
                                if (avatar.isNotBlank() && isAuthenticArtistAvatar(avatar)) {
                                    com.example.ui.components.ArtistAvatarCache.put(itemTitle, avatar)
                                    out.add(
                                        YtmArtistPreview(
                                            browseId = browseId,
                                            name = itemTitle,
                                            subscribers = subText,
                                            avatarUrl = avatar
                                        )
                                    )
                                }
                                if (out.size >= limit) return@withContext out
                            }
                        }
                    }
                }
            }
            out
        }

    suspend fun fetchArtistPage(
        artistName: String,
        artistBrowseId: String? = null,
        isChannel: Boolean = false
    ): YtmArtistPageData = withContext(Dispatchers.IO) {
        val cleanTargetName = artistName.removeSuffix(" - Topic").removeSuffix("- Topic").trim()

        // 1. If explicitly visiting a YouTube Content Creator's Channel (from a YouTube Video),
        // fetch their authentic YouTube Channel profile, Playlists, and ALL uploaded videos (with view counts & recency)!
        if (isChannel) {
            val ytChannel = fetchYouTubeChannelMeta(
                channelName = cleanTargetName,
                knownBrowseId = artistBrowseId,
                treatUploadsAsVideo = true
            )
            val resolvedChannelTitle = ytChannel?.title?.ifBlank { cleanTargetName } ?: cleanTargetName
            val searchedSupplement = runCatching {
                searchAllYoutubeVideos(resolvedChannelTitle, limit = 60).filter { cand ->
                    val normCh = CanonicalAudioResolver.normalizeText(cand.artist)
                    val normT = CanonicalAudioResolver.normalizeText(resolvedChannelTitle)
                    val normOrig = CanonicalAudioResolver.normalizeText(cleanTargetName)
                    normCh == normT || normCh.contains(normT) || normT.contains(normCh) ||
                        normCh == normOrig || normCh.contains(normOrig) || normOrig.contains(normCh)
                }
            }.getOrDefault(emptyList())

            val mergedVideos = LinkedHashMap<String, CandidateStream>()
            ytChannel?.videos?.forEach { v -> mergedVideos[v.videoId] = v }
            searchedSupplement.forEach { v ->
                val existing = mergedVideos[v.videoId]
                if (existing == null) {
                    mergedVideos[v.videoId] = v.copy(artist = resolvedChannelTitle, channelName = resolvedChannelTitle)
                } else if (existing.viewCount == 0L && v.viewCount > 0L) {
                    mergedVideos[v.videoId] = existing.copy(
                        viewCount = v.viewCount,
                        viewCountText = v.viewCountText.ifBlank { existing.viewCountText },
                        publishedTimeText = existing.publishedTimeText.ifBlank { v.publishedTimeText }
                    )
                }
            }

            val channelVideos = mergedVideos.values.toList()
            val finalAvatar = ytChannel?.avatarUrl.orEmpty()
            val finalBanner = ytChannel?.bannerUrl?.ifBlank { finalAvatar }.orEmpty()
            if (finalAvatar.isNotBlank()) {
                com.example.ui.components.ArtistAvatarCache.put(cleanTargetName, finalAvatar)
            }
            return@withContext YtmArtistPageData(
                browseId = ytChannel?.channelId?.ifBlank { artistBrowseId.orEmpty() }.orEmpty().ifBlank { cleanTargetName },
                name = resolvedChannelTitle,
                description = ytChannel?.description.orEmpty(),
                subscriberCountText = ytChannel?.subscriberCountText.orEmpty().ifBlank { "YouTube Channel" },
                avatarUrl = finalAvatar,
                bannerUrl = finalBanner,
                radioPlaylistId = channelVideos.firstOrNull()?.videoId?.let { "RDAMVM$it" },
                shufflePlaylistId = channelVideos.firstOrNull()?.videoId?.let { "RDAMVM$it" },
                topSongs = channelVideos,
                albums = emptyList(),
                singles = emptyList(),
                playlists = ytChannel?.playlists.orEmpty(),
                similarArtists = emptyList(),
                isChannel = true
            )
        }

        // 2. Music Artist Profile Resolution
        val resolvedBrowse = if (!artistBrowseId.isNullOrBlank() &&
            (artistBrowseId.startsWith("UC") || artistBrowseId.startsWith("MPLA"))
        ) {
            artistBrowseId to ""
        } else {
            resolveOfficialArtistBrowseId(cleanTargetName)
        }

        val browseId = resolvedBrowse.first
        val fallbackAvatar = resolvedBrowse.second

        if (browseId.isNotBlank()) {
            val payload = webRemixPayload().apply { put("browseId", browseId) }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", payload)
            if (root != null) {
                val parsed = parseArtistBrowseResponse(root, browseId, cleanTargetName, fallbackAvatar)
                if (parsed.topSongs.isNotEmpty() || parsed.albums.isNotEmpty() || parsed.singles.isNotEmpty()) {
                    if (parsed.avatarUrl.isBlank()) {
                        val ytMeta = fetchYouTubeChannelMeta(cleanTargetName, browseId, treatUploadsAsVideo = false)
                        if (ytMeta != null && ytMeta.avatarUrl.isNotBlank()) {
                            return@withContext parsed.copy(
                                avatarUrl = ytMeta.avatarUrl,
                                bannerUrl = parsed.bannerUrl.ifBlank { ytMeta.bannerUrl },
                                subscriberCountText = parsed.subscriberCountText.ifBlank { ytMeta.subscriberCountText },
                                playlists = parsed.playlists.ifEmpty { ytMeta.playlists }
                            )
                        }
                    }
                    return@withContext parsed
                }
            }
        }

        // 3. Real YouTube Channel Profile fallback for regional/indie artists whose profile lives on YouTube
        val ytChannelMeta = fetchYouTubeChannelMeta(
            channelName = cleanTargetName,
            knownBrowseId = browseId.takeIf { it.startsWith("UC") },
            treatUploadsAsVideo = false
        )

        val rawSongs = searchOfficialYtmSongs(cleanTargetName, limit = 24)
        val verifiedSongs = rawSongs.filter { song ->
            val normArt = CanonicalAudioResolver.normalizeText(song.artist.removeSuffix(" - Topic").removeSuffix("- Topic"))
            val normTarget = CanonicalAudioResolver.normalizeText(cleanTargetName)
            normArt == normTarget || (normTarget.length >= 3 && (normArt.contains(normTarget) || normTarget.contains(normArt)))
        }
        val topSongs = if (verifiedSongs.isNotEmpty()) {
            verifiedSongs
        } else {
            ytChannelMeta?.videos.orEmpty()
        }

        val albums = emptyList<YtmCollectionSummary>()
        val singles = emptyList<YtmCollectionSummary>()

        val verifiedAvatar = when {
            !ytChannelMeta?.avatarUrl.isNullOrBlank() -> ytChannelMeta!!.avatarUrl
            isAuthenticArtistAvatar(fallbackAvatar) -> fallbackAvatar
            else -> resolveJioSaavnArtistAvatar(cleanTargetName)
        }
        val verifiedBanner = when {
            !ytChannelMeta?.bannerUrl.isNullOrBlank() -> ytChannelMeta!!.bannerUrl
            else -> verifiedAvatar
        }
        if (verifiedAvatar.isNotBlank()) {
            com.example.ui.components.ArtistAvatarCache.put(cleanTargetName, verifiedAvatar)
        }

        val effectiveBrowseId = browseId.ifBlank { ytChannelMeta?.channelId.orEmpty() }
        val isOfficial = effectiveBrowseId.startsWith("UC") || effectiveBrowseId.startsWith("MPLA")
        YtmArtistPageData(
            browseId = effectiveBrowseId.ifBlank { cleanTargetName },
            name = ytChannelMeta?.title?.ifBlank { cleanTargetName } ?: cleanTargetName,
            description = ytChannelMeta?.description.orEmpty(),
            subscriberCountText = ytChannelMeta?.subscriberCountText.orEmpty().ifBlank { if (isOfficial) "Official Artist" else "" },
            avatarUrl = verifiedAvatar,
            bannerUrl = verifiedBanner,
            radioPlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            shufflePlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            playlists = ytChannelMeta?.playlists.orEmpty(),
            similarArtists = emptyList(),
            isChannel = verifiedSongs.isEmpty() && ytChannelMeta != null
        )
    }

    fun isAuthenticArtistAvatar(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("i.ytimg.com/vi/") || lower.contains("img.youtube.com/vi/") ||
            lower.contains("hqdefault") || lower.contains("mqdefault") || lower.contains("sddefault")
        ) {
            return false
        }
        return lower.contains("ggpht.com") || lower.contains("googleusercontent.com") ||
            lower.contains("/artists/") || lower.contains("saavncdn.com/artists/")
    }

    fun resolveJioSaavnArtistAvatar(artistName: String): String {
        return runCatching {
            val encQuery = java.net.URLEncoder.encode(artistName.trim(), "UTF-8")
            val urlStr = "https://www.jiosaavn.com/api.php?__call=search.getArtistResults&api_version=4&_format=json&_marker=0&ctx=web6dot0&q=$encQuery&n=1"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            if (conn.responseCode != 200) return ""
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val results = json.optJSONArray("results") ?: return ""
            if (results.length() == 0) return ""
            val first = results.getJSONObject(0)
            val img = first.optString("image", "")
            if (img.isNotBlank() && !img.contains("default")) {
                img.replace("50x50", "500x500").replace("150x150", "500x500")
            } else ""
        }.getOrDefault("")
    }

    suspend fun resolveOfficialArtistBrowseId(artistName: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (artistName.isBlank()) return@withContext "" to ""
            val payload = webRemixPayload().apply {
                put("query", artistName)
                put("params", "EgWKAQIgAWoKEAkQChADEAUQBA==")
            }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)

            if (root != null) {
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")

                if (tabs != null) {
                    for (t in 0 until tabs.length()) {
                        val sections = tabs.getJSONObject(t)
                            .optJSONObject("tabRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("sectionListRenderer")
                            ?.optJSONArray("contents") ?: continue

                        for (s in 0 until sections.length()) {
                            val shelfContents = sections.getJSONObject(s)
                                .optJSONObject("musicShelfRenderer")
                                ?.optJSONArray("contents") ?: continue

                            for (i in 0 until shelfContents.length()) {
                                val item = shelfContents.getJSONObject(i)
                                    .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                                val flexColumns = item.optJSONArray("flexColumns")
                                val itemTitle = flexColumns?.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                    ?.optJSONObject(0)
                                    ?.optString("text").orEmpty()

                                val normItemTitle = CanonicalAudioResolver.normalizeText(itemTitle)
                                val normArtist = CanonicalAudioResolver.normalizeText(artistName)
                                val sharesTokens = normArtist.split(" ").filter { it.length >= 2 }.any { normItemTitle.contains(it) } ||
                                    normItemTitle.contains(normArtist) || normArtist.contains(normItemTitle)
                                if (!sharesTokens && normItemTitle.isNotBlank()) continue

                                val browseId = item.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")
                                    ?.optString("browseId").orEmpty()

                                if (browseId.startsWith("UC") || browseId.startsWith("MPLA")) {
                                    val rawAvatar = extractBestThumbnail(
                                        item.optJSONObject("thumbnail")
                                            ?.optJSONObject("musicThumbnailRenderer")
                                            ?.optJSONObject("thumbnail")
                                            ?.optJSONArray("thumbnails")
                                    )
                                    val avatar = if (isAuthenticArtistAvatar(rawAvatar)) {
                                        upgradeAvatarUrl(rawAvatar)
                                    } else {
                                        resolveJioSaavnArtistAvatar(artistName)
                                    }
                                    return@withContext browseId to avatar
                                }
                            }
                        }
                    }
                }
            }

            val ytChannel = fetchYouTubeChannelMeta(artistName, treatUploadsAsVideo = false)
            if (ytChannel != null && ytChannel.channelId.isNotBlank()) {
                return@withContext ytChannel.channelId to ytChannel.avatarUrl
            }

            "" to resolveJioSaavnArtistAvatar(artistName)
        }

    private suspend fun parseArtistBrowseResponse(
        root: JSONObject,
        browseId: String,
        fallbackName: String,
        fallbackAvatar: String
    ): YtmArtistPageData {
        val header = root.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
            ?: root.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")
            ?: root.optJSONObject("header")?.optJSONObject("musicHeaderRenderer")

        val name = header?.optJSONObject("title")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text") ?: fallbackName

        val description = header?.optJSONObject("description")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text").orEmpty()

        val subText = header?.optJSONObject("subscriptionButton")
            ?.optJSONObject("subscribeButtonRenderer")
            ?.optJSONObject("subscriberCountText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: "Official Artist"

        val bannerThumbs = header?.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: header?.optJSONObject("foregroundThumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        val rawBanner = upgradeAvatarUrl(extractBestThumbnail(bannerThumbs))
        val bannerUrl = if (isAuthenticArtistAvatar(rawBanner)) rawBanner else fallbackAvatar
        val avatarUrl = if (isAuthenticArtistAvatar(fallbackAvatar)) fallbackAvatar
        else if (isAuthenticArtistAvatar(rawBanner)) rawBanner
        else resolveJioSaavnArtistAvatar(name)

        val radioId = header?.optJSONObject("startRadioButton")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId")

        val shuffleId = header?.optJSONObject("playButton")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId")

        val topSongs = mutableListOf<CandidateStream>()
        val albums = mutableListOf<YtmCollectionSummary>()
        val singles = mutableListOf<YtmCollectionSummary>()
        val playlists = mutableListOf<YtmCollectionSummary>()
        val similarArtists = mutableListOf<YtmArtistPreview>()

        val sections = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: JSONArray()

        for (s in 0 until sections.length()) {
            val secObj = sections.getJSONObject(s)

            secObj.optJSONObject("musicShelfRenderer")?.let { shelf ->
                val contents = shelf.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until contents.length()) {
                    val item = contents.getJSONObject(i)
                        .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseSingleResponsiveSongItem(item, defaultArtist = name, defaultArt = avatarUrl)?.let { song ->
                        if (belongsToArtist(name, song.title, itemArtist = song.artist)) {
                            topSongs.add(song)
                        }
                    }
                }
            }

            secObj.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                val shelfTitle = carousel.optJSONObject("header")
                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optString("text").orEmpty().lowercase()

                val items = carousel.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val twoRow = items.getJSONObject(i)
                        .optJSONObject("musicTwoRowItemRenderer") ?: continue

                    val itemTitle = twoRow.optJSONObject("title")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: continue

                    val itemSubRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                    val itemSubtitle = buildString {
                        if (itemSubRuns != null) {
                            for (r in 0 until itemSubRuns.length()) {
                                append(itemSubRuns.getJSONObject(r).optString("text"))
                            }
                        }
                    }.trim()

                    val itemThumb = extractBestThumbnail(
                        twoRow.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                    )

                    val navBrowseId = twoRow.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId").orEmpty()

                    val playNavEndpoint = twoRow.optJSONObject("thumbnailOverlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")

                    val directWatchVideoId = playNavEndpoint
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId")
                        ?.takeIf { it.isNotBlank() }
                        ?: twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                            ?.takeIf { it.isNotBlank() }

                    val watchPlaylistId = directWatchVideoId
                        ?: playNavEndpoint
                            ?.optJSONObject("watchPlaylistEndpoint")
                            ?.optString("playlistId")

                    val isFakeVideoTitle = itemTitle.contains("YouTube Video", ignoreCase = true) ||
                        itemSubtitle.contains("YouTube Video", ignoreCase = true)
                    val isOfficialAlbumBrowseId = navBrowseId.startsWith("MPREb_") || navBrowseId.startsWith("OLAK5uy_")

                    when {
                        shelfTitle.contains("video") || shelfTitle.contains("live performance") || isFakeVideoTitle -> Unit

                        shelfTitle.contains("fan") || shelfTitle.contains("similar") || shelfTitle.contains("like") ||
                            navBrowseId.startsWith("UC") || navBrowseId.startsWith("MPLA") -> {
                            if (navBrowseId.isNotBlank()) {
                                similarArtists.add(
                                    YtmArtistPreview(
                                        browseId = navBrowseId,
                                        name = itemTitle,
                                        subscribers = itemSubtitle.ifBlank { "Artist" },
                                        avatarUrl = upgradeAvatarUrl(itemThumb)
                                    )
                                )
                            }
                        }

                        (shelfTitle.contains("single") || shelfTitle.contains("ep")) &&
                            (isOfficialAlbumBrowseId || !watchPlaylistId.isNullOrBlank()) -> {
                            albumsOrSinglesAdd(
                                singles,
                                navBrowseId,
                                watchPlaylistId,
                                itemTitle,
                                itemSubtitle.ifBlank { "Single • $name" },
                                itemThumb,
                                "SINGLE"
                            )
                        }

                        (shelfTitle.contains("album") || shelfTitle.contains("release")) &&
                            !shelfTitle.contains("featured") &&
                            isOfficialAlbumBrowseId -> {
                            val isSingleRelease = itemSubtitle.contains("Single", ignoreCase = true) ||
                                itemSubtitle.contains("EP", ignoreCase = true) ||
                                itemSubtitle.contains("1 song", ignoreCase = true) ||
                                itemSubtitle.contains("1 track", ignoreCase = true)
                            if (isSingleRelease) {
                                albumsOrSinglesAdd(
                                    singles,
                                    navBrowseId,
                                    watchPlaylistId,
                                    itemTitle,
                                    itemSubtitle.ifBlank { "Single • $name" },
                                    itemThumb,
                                    "SINGLE"
                                )
                            } else {
                                albumsOrSinglesAdd(
                                    albums,
                                    navBrowseId,
                                    watchPlaylistId,
                                    itemTitle,
                                    itemSubtitle.ifBlank { "Album • $name" },
                                    itemThumb,
                                    "ALBUM"
                                )
                            }
                        }

                        (shelfTitle.contains("playlist") || shelfTitle.contains("mix") || shelfTitle.contains("artist")) &&
                            (navBrowseId.startsWith("VL") || navBrowseId.startsWith("PL") || navBrowseId.startsWith("RD")) -> {
                            if (belongsToArtist(name, itemTitle, itemSubtitle)) {
                                val effectiveType = if (itemTitle.contains("Mix", true) || itemTitle.contains("Radio", true) || navBrowseId.startsWith("RD")) "MIX" else "PLAYLIST"
                                albumsOrSinglesAdd(playlists, navBrowseId, watchPlaylistId, itemTitle, itemSubtitle, itemThumb, effectiveType)
                            }
                        }
                    }
                }
            }
        }

        if (topSongs.isEmpty()) {
            val searchedSongs = searchOfficialYtmSongs(name, limit = 18)
            val verified = searchedSongs.filter { song ->
                val normArt = CanonicalAudioResolver.normalizeText(song.artist.removeSuffix(" - Topic").removeSuffix("- Topic"))
                val normTarget = CanonicalAudioResolver.normalizeText(name)
                normArt == normTarget || (normTarget.length >= 3 && (normArt.contains(normTarget) || normTarget.contains(normArt)))
            }
            topSongs.addAll(verified)
        }

        val cleanedAlbums = albums.filterNot {
            it.type == "SINGLE" ||
                it.subtitle.contains("Single", true) ||
                it.subtitle.contains("1 song", true) ||
                it.subtitle.contains("1 track", true) ||
                it.title.contains("YouTube Video", true) ||
                it.subtitle.contains("YouTube Video", true) ||
                (!it.browseId.startsWith("MPREb_") && !it.browseId.startsWith("OLAK5uy_"))
        }.distinctBy { it.browseId }

        val cleanedSingles = (singles + albums.filter {
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true)
        }).map { it.copy(type = "SINGLE") }.distinctBy { it.browseId }

        return YtmArtistPageData(
            browseId = browseId,
            name = name,
            description = description,
            subscriberCountText = subText,
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            radioPlaylistId = radioId ?: topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            shufflePlaylistId = shuffleId,
            topSongs = CanonicalAudioResolver.deduplicateTracks(topSongs),
            albums = cleanedAlbums,
            singles = cleanedSingles,
            playlists = playlists.distinctBy { it.browseId },
            similarArtists = similarArtists.distinctBy { it.browseId },
            isChannel = false
        )
    }

    private fun albumsOrSinglesAdd(
        target: MutableList<YtmCollectionSummary>,
        browseId: String,
        playlistId: String?,
        title: String,
        subtitle: String,
        thumbUrl: String,
        type: String
    ) {
        val id = browseId.ifBlank { playlistId.orEmpty() }
        if (id.isBlank()) return
        target.add(
            YtmCollectionSummary(
                browseId = id,
                playlistId = playlistId ?: id.removePrefix("VL"),
                title = title,
                subtitle = subtitle,
                thumbnailUrl = thumbUrl,
                type = type
            )
        )
    }

    suspend fun fetchPlaylistAlbumOrMixDetails(
        collectionId: String,
        fallbackTitle: String = "Music Collection",
        fallbackThumb: String = ""
    ): YtmCollectionPageData = withContext(Dispatchers.IO) {
        val cleanId = collectionId.trim()

        if (cleanId.startsWith("RD") && !cleanId.startsWith("RDCLAK5uy_")) {
            val seedVid = cleanId.removePrefix("RDAMVM").takeIf { it.length == 11 }.orEmpty()
            val tracks = fetchAutomixQueue(seedVideoId = seedVid, playlistIdOverride = cleanId, limit = 35)
            val heroThumb = fallbackThumb.ifBlank { tracks.firstOrNull()?.thumbnailUrl.orEmpty() }
            return@withContext YtmCollectionPageData(
                id = cleanId,
                title = if (fallbackTitle != "Music Collection") fallbackTitle else "YouTube Personalized Mix",
                author = "YouTube Music Automix",
                subtitle = "${tracks.size} songs • Continuous personalized radio",
                description = "Updated dynamically based on your listening taste.",
                thumbnailUrl = heroThumb,
                tracks = tracks
            )
        }

        val browseId = when {
            cleanId.startsWith("MPREb_") || cleanId.startsWith("VL") || cleanId.startsWith("FEmusic_") -> cleanId
            cleanId.startsWith("PL") || cleanId.startsWith("RDCLAK5uy_") || cleanId.startsWith("OLAK5uy_") -> "VL$cleanId"
            else -> cleanId
        }

        val payload = webRemixPayload().apply { put("browseId", browseId) }
        val root = postWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", payload)
        if (root != null) {
            val parsed = parseCollectionBrowseResponse(root, cleanId, fallbackTitle, fallbackThumb)
            if (parsed.tracks.isNotEmpty()) {
                return@withContext parsed
            }
        }

        val rawPlaylistId = cleanId.removePrefix("VL")
        val nextTracks = fetchAutomixQueue(seedVideoId = "", playlistIdOverride = rawPlaylistId, limit = 35)
        YtmCollectionPageData(
            id = cleanId,
            title = fallbackTitle,
            author = "YouTube Music",
            subtitle = "${nextTracks.size} songs",
            description = "",
            thumbnailUrl = fallbackThumb.ifBlank { nextTracks.firstOrNull()?.thumbnailUrl.orEmpty() },
            tracks = nextTracks
        )
    }

    private fun parseCollectionBrowseResponse(
        root: JSONObject,
        collectionId: String,
        fallbackTitle: String,
        fallbackThumb: String
    ): YtmCollectionPageData {
        val twoColPrimary = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?.optJSONObject(0)

        val responsiveHeader = twoColPrimary?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: twoColPrimary?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: root.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
            ?: root.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")

        val title = responsiveHeader?.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: fallbackTitle

        val author = responsiveHeader?.optJSONObject("straplineTextOne")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: responsiveHeader?.optJSONObject("subtitle")
                ?.optJSONArray("runs")?.optJSONObject(2)?.optString("text")
            ?: "YouTube Music"

        val headerThumb = extractBestThumbnail(
            responsiveHeader?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        ).ifBlank { fallbackThumb }

        val subtitleRuns = responsiveHeader?.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitle = buildString {
            if (subtitleRuns != null) {
                for (i in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.getJSONObject(i).optString("text"))
                }
            }
        }.trim()

        val tracks = mutableListOf<CandidateStream>()

        val secondarySections = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: JSONArray()

        for (s in 0 until secondarySections.length()) {
            val sec = secondarySections.getJSONObject(s)
            val items = sec.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                ?: sec.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                ?: continue
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i).optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseSingleResponsiveSongItem(item, defaultArtist = author, defaultArt = headerThumb, defaultAlbum = title)?.let {
                    tracks.add(it)
                }
            }
        }

        if (tracks.isEmpty()) {
            val singleSections = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (s in 0 until singleSections.length()) {
                val sec = singleSections.getJSONObject(s)
                val items = sec.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                    ?: sec.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                    ?: continue
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i).optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseSingleResponsiveSongItem(item, defaultArtist = author, defaultArt = headerThumb, defaultAlbum = title)?.let {
                        tracks.add(it)
                    }
                }
            }
        }

        return YtmCollectionPageData(
            id = collectionId,
            title = title,
            author = author,
            subtitle = subtitle.ifBlank { "${tracks.size} tracks" },
            description = "",
            thumbnailUrl = headerThumb.ifBlank { tracks.firstOrNull()?.thumbnailUrl.orEmpty() },
            tracks = CanonicalAudioResolver.deduplicateTracks(tracks)
        )
    }

    suspend fun searchOfficialYtmSongs(query: String, limit: Int = 15): List<CandidateStream> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val payload = webRemixPayload().apply {
                put("query", query)
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
            }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
                ?: return@withContext emptyList()
            parseMusicResponsiveListItems(root).take(limit)
        }

    private fun parseMusicResponsiveListItems(root: JSONObject): List<CandidateStream> {
        val out = mutableListOf<CandidateStream>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs") ?: return out

        for (t in 0 until tabs.length()) {
            val sections = tabs.getJSONObject(t)
                .optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue

            for (s in 0 until sections.length()) {
                val shelfContents = sections.getJSONObject(s)
                    .optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (i in 0 until shelfContents.length()) {
                    val item = shelfContents.getJSONObject(i)
                        .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseSingleResponsiveSongItem(item)?.let { out.add(it) }
                }
            }
        }
        return CanonicalAudioResolver.deduplicateTracks(out)
    }

    private fun parseSingleResponsiveSongItem(
        item: JSONObject,
        defaultArtist: String = "Unknown Artist",
        defaultArt: String = "",
        defaultAlbum: String? = null
    ): CandidateStream? {
        val rawVideoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("onTap")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: item.optJSONArray("flexColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }

        val videoId = extractVideoId(rawVideoId) ?: return null

        val flexColumns = item.optJSONArray("flexColumns") ?: return null
        val titleRuns = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val title = titleRuns?.optJSONObject(0)?.optString("text") ?: return null

        var artist = defaultArtist
        var artistBrowseId: String? = null
        var albumName: String? = defaultAlbum
        var durationSeconds = 0

        for (colIdx in 1 until flexColumns.length()) {
            val subRuns = flexColumns.optJSONObject(colIdx)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: continue

            for (r in 0 until subRuns.length()) {
                val runObj = subRuns.getJSONObject(r)
                val text = runObj.optString("text").trim()
                if (text.isEmpty() || text == "•" || text == "&") continue

                val browseId = runObj.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                if ((browseId?.startsWith("UC") == true || browseId?.startsWith("MPLA") == true) ||
                    (artist == defaultArtist && colIdx == 1 && !text.matches(Regex("\\d+:\\d+")))
                ) {
                    if (artist == defaultArtist || artist == "Unknown Artist") {
                        artist = text
                        if (!browseId.isNullOrBlank()) artistBrowseId = browseId
                    }
                } else if (browseId?.startsWith("MPREb_") == true) {
                    albumName = text
                } else if (text.matches(Regex("\\d+:\\d+(?::\\d+)?"))) {
                    durationSeconds = parseDurationSeconds(text)
                }
            }
        }

        if (durationSeconds == 0) {
            val fixedCols = item.optJSONArray("fixedColumns")
            val fixedText = fixedCols?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text").orEmpty()
            if (fixedText.matches(Regex("\\d+:\\d+(?::\\d+)?"))) {
                durationSeconds = parseDurationSeconds(fixedText)
            }
        }

        val thumbArr = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = extractBestThumbnail(thumbArr)
            .ifBlank { defaultArt }
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }

        val resolvedVideoType = "MUSIC_VIDEO_TYPE_ATV"
        val resolvedAlbumName = albumName?.takeUnless {
            it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
        }

        return CandidateStream(
            videoId = videoId,
            title = title,
            artist = artist,
            artistBrowseId = artistBrowseId,
            channelName = "$artist - Topic",
            albumName = resolvedAlbumName,
            durationSeconds = if (durationSeconds > 0) durationSeconds else 210,
            videoType = resolvedVideoType,
            thumbnailUrl = thumbUrl,
            isVerifiedArtist = true
        )
    }

    private fun parseAutomixPlaylistPanel(root: JSONObject, seedVideoId: String): List<CandidateStream> {
        val results = mutableListOf<CandidateStream>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: JSONArray()

        val directPanel = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("playlist")
            ?.optJSONObject("playlistPanelRenderer")
            ?.optJSONArray("contents")

        val panelArrays = mutableListOf<JSONArray>()
        if (directPanel != null) panelArrays.add(directPanel)

        for (t in 0 until tabs.length()) {
            val contents = tabs.getJSONObject(t)
                .optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents")
            if (contents != null) panelArrays.add(contents)
        }

        for (contents in panelArrays) {
            for (i in 0 until contents.length()) {
                val renderer = contents.getJSONObject(i)
                    .optJSONObject("playlistPanelVideoRenderer") ?: continue

                val vid = extractVideoId(renderer.optString("videoId")) ?: renderer.optString("videoId")
                if (vid.isBlank() || (seedVideoId.isNotBlank() && vid == seedVideoId && i == 0)) continue

                val title = renderer.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: continue

                val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
                    ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")
                val artist = bylineRuns?.optJSONObject(0)?.optString("text") ?: "Unknown Artist"
                val artistBrowseId = bylineRuns?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                val durationText = renderer.optJSONObject("lengthText")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "3:30"

                val thumb = extractBestThumbnail(renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                    .ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }

                results.add(
                    CandidateStream(
                        videoId = vid,
                        title = title,
                        artist = artist,
                        artistBrowseId = artistBrowseId,
                        channelName = "$artist - Topic",
                        albumName = null,
                        durationSeconds = parseDurationSeconds(durationText),
                        videoType = "MUSIC_VIDEO_TYPE_ATV",
                        thumbnailUrl = thumb,
                        isVerifiedArtist = true
                    )
                )
            }
        }
        return CanonicalAudioResolver.deduplicateTracks(results)
    }

    private suspend fun resolvePipedOrInvidiousMuxedVideo(cleanVideoId: String): String? = coroutineScope {
        val invidiousNodes = listOf(
            "https://inv.nadeko.net",
            "https://invidious.f5.si",
            "https://yewtu.be",
            "https://invidious.nerdvpn.de"
        )
        val pipedNodes = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.yt",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.smnz.de"
        )

        val jobs = invidiousNodes.map { node ->
            async(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("$node/api/v1/videos/$cleanVideoId?fields=formatStreams,adaptiveFormats").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val fmtArr = json.optJSONArray("formatStreams")
                        if (fmtArr != null) {
                            for (i in 0 until fmtArr.length()) {
                                val f = fmtArr.optJSONObject(i) ?: continue
                                val u = f.optString("url", "")
                                val type = f.optString("type", "")
                                if (u.startsWith("http") && (type.contains("mp4", true) || u.contains("itag=18") || u.contains("itag=22"))) {
                                    if (verifyVideoStreamPlayable(u)) return@runCatching u
                                }
                            }
                        }
                    }
                    null
                }.getOrNull()
            }
        } + pipedNodes.map { node ->
            async(Dispatchers.IO) {
                runCatching {
                    val conn = (URL("$node/streams/$cleanVideoId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val vidArr = json.optJSONArray("videoStreams")
                        if (vidArr != null) {
                            for (i in 0 until vidArr.length()) {
                                val v = vidArr.optJSONObject(i) ?: continue
                                val videoOnly = v.optBoolean("videoOnly", true)
                                val u = v.optString("url", "")
                                if (!videoOnly && u.startsWith("http") && !u.contains(".m3u8", true)) {
                                    if (verifyVideoStreamPlayable(u)) return@runCatching u
                                }
                            }
                        }
                    }
                    null
                }.getOrNull()
            }
        }

        jobs.awaitAll().firstOrNull { !it.isNullOrBlank() }
    }

    private val failedVideoStreamsByVideoId = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val videoClientRotation = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun resolveRawVideoStreamById(
        videoId: String,
        forceRefresh: Boolean = false,
        allowHlsFallback: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId) ?: videoId.trim()
        if (!VALID_VIDEO_ID_REGEX.matches(cleanVideoId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube videoId: $videoId"))
        }

        val failedSet = failedVideoStreamsByVideoId.getOrPut(cleanVideoId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }

        val now = System.currentTimeMillis()
        if (forceRefresh) {
            videoStreamCache.remove(cleanVideoId)?.first?.let { failedUrl ->
                failedSet.add(failedUrl)
            }
        } else {
            videoStreamCache[cleanVideoId]?.let { (cachedUrl, ts) ->
                if (now - ts < 45 * 60 * 1000L &&
                    !failedSet.contains(cachedUrl) &&
                    (!cachedUrl.contains(".m3u8", true) || isHlsSupported)
                ) {
                    return@withContext Result.success(cachedUrl)
                }
            }
        }

        val allowAdaptive = allowHlsFallback
        val startIdx = if (forceRefresh) {
            videoClientRotation.incrementAndGet() % VIDEO_CLIENTS.size
        } else {
            0
        }

        var hlsFallbackCandidate: String? = null
        var verifiedAdaptiveMergedCandidate: String? = null

        val orderedClients = VIDEO_CLIENTS.indices.map { offset ->
            VIDEO_CLIENTS[(startIdx + offset) % VIDEO_CLIENTS.size]
        }

        for (client in orderedClients) {
            val candidate = extractProgressiveVideoWithClient(
                cleanVideoId = cleanVideoId,
                client = client,
                cpn = generateCpn(),
                includeVisitorData = client.isEmbedded,
                allowAdaptiveMerge = allowAdaptive,
                failedUrls = failedSet
            ) { hls ->
                if (isHlsSupported && hlsFallbackCandidate.isNullOrBlank() && hls.isNotBlank()) {
                    hlsFallbackCandidate = hls
                }
            }

            if (!candidate.isNullOrBlank() && !failedSet.contains(candidate)) {
                if (!candidate.startsWith("merged_av::")) {
                    val isDirectNativeClient = !client.isEmbedded
                    if (isDirectNativeClient || verifyVideoStreamPlayable(candidate)) {
                        com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, AudioQuality.HIGH, candidate)
                        videoStreamCache[cleanVideoId] = candidate to now
                        return@withContext Result.success(candidate)
                    } else {
                        failedSet.add(candidate)
                    }
                } else if (allowAdaptive) {
                    if (verifyVideoStreamPlayable(candidate)) {
                        val parts = candidate.removePrefix("merged_av::").split("|||")
                        if (parts.size >= 2) {
                            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, AudioQuality.HIGH, parts[0])
                            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, AudioQuality.NORMAL, parts[1])
                        }
                        videoStreamCache[cleanVideoId] = candidate to now
                        return@withContext Result.success(candidate)
                    } else {
                        if (verifiedAdaptiveMergedCandidate == null) {
                            verifiedAdaptiveMergedCandidate = candidate
                        }
                        failedSet.add(candidate)
                    }
                }
            }
        }

        if (allowHlsFallback && isHlsSupported && !hlsFallbackCandidate.isNullOrBlank()) {
            val hls = hlsFallbackCandidate!!
            if (!failedSet.contains(hls)) {
                videoStreamCache[cleanVideoId] = hls to now
                return@withContext Result.success(hls)
            }
        }

        val federatedMp4 = resolvePipedOrInvidiousMuxedVideo(cleanVideoId)
        if (!federatedMp4.isNullOrBlank() && !failedSet.contains(federatedMp4)) {
            videoStreamCache[cleanVideoId] = federatedMp4 to now
            return@withContext Result.success(federatedMp4)
        }

        if (verifiedAdaptiveMergedCandidate != null) {
            videoStreamCache[cleanVideoId] = verifiedAdaptiveMergedCandidate!! to now
            return@withContext Result.success(verifiedAdaptiveMergedCandidate!!)
        }

        Result.failure(IllegalStateException("No verified video stream found for videoId=$cleanVideoId"))
    }

    private fun extractProgressiveVideoWithClient(
        cleanVideoId: String,
        client: InnerTubeClientConfig,
        cpn: String,
        includeVisitorData: Boolean = false,
        allowAdaptiveMerge: Boolean = true,
        failedUrls: Set<String> = emptySet(),
        onHlsDiscovered: (String) -> Unit = {}
    ): String? {
        return runCatching {
            val visData = if (client.isEmbedded && includeVisitorData) cachedVisitorData?.takeIf { it.isNotBlank() } else null
            val effectiveGl = client.glOverride ?: resolveDeviceGl()
            val conn = (URL(client.playerEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2200
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", client.userAgent)
                setRequestProperty("X-YouTube-Client-Name", client.clientId)
                setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
                setRequestProperty("X-Goog-Api-Format-Version", "2")
                if (client.sendOriginHeaders) {
                    setRequestProperty("Origin", client.origin)
                    setRequestProperty("Referer", "${client.origin}/")
                }
                if (visData != null) setRequestProperty("X-Goog-Visitor-Id", visData)
            }
            val payload = JSONObject().apply {
                put("videoId", cleanVideoId)
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (client.params != null && client.params != "CgIIAQ==") {
                    put("params", client.params)
                }
                if (client.isEmbedded) {
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("signatureTimestamp", com.example.resolver.KotlinJsInterp.getSignatureTimestamp())
                        })
                    })
                }
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("userAgent", client.userAgent)
                        put("hl", "en")
                        put("gl", effectiveGl)
                        put("timeZone", "UTC")
                        put("utcOffsetMinutes", 0)
                        client.deviceMake?.let { put("deviceMake", it) }
                        client.deviceModel?.let { put("deviceModel", it) }
                        client.osName?.let { put("osName", it) }
                        client.osVersion?.let { put("osVersion", it) }
                        client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        if (visData != null) put("visitorData", visData)
                    })
                    if (client.isEmbedded) {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com/")
                        })
                    }
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val videoDetails = json.optJSONObject("videoDetails")
            if (videoDetails != null) {
                val vdTitle = videoDetails.optString("title", "")
                val vdAuthor = videoDetails.optString("author", "")
                val vdViews = videoDetails.optString("viewCount", "")
                val vdDesc = videoDetails.optString("shortDescription", "")
                if (vdTitle.isNotBlank() || vdDesc.isNotBlank()) {
                    videoDescriptionCache[cleanVideoId] = VideoDescriptionMetadata(
                        videoId = cleanVideoId,
                        title = vdTitle,
                        author = vdAuthor,
                        viewCountText = vdViews,
                        description = vdDesc
                    )
                }
            }
            val status = json.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") return@runCatching null
            val streamingData = json.optJSONObject("streamingData") ?: return@runCatching null

            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsUrl.isNotBlank() && isHlsSupported) onHlsDiscovered(hlsUrl)

            fun resolveFormatUrl(fmt: JSONObject, allowCipher: Boolean, customCpn: String = cpn): String {
                var rawUrl = fmt.optString("url", "")
                if (rawUrl.isBlank() && allowCipher) {
                    val cipherStr = fmt.optString("signatureCipher").ifBlank { fmt.optString("cipher") }
                    if (cipherStr.isNotBlank()) {
                        val params = mutableMapOf<String, String>()
                        for (part in cipherStr.split("&")) {
                            val idx = part.indexOf('=')
                            if (idx > 0) {
                                params[URLDecoder.decode(part.substring(0, idx), "UTF-8")] =
                                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                            }
                        }
                        val baseUrl = params["url"].orEmpty()
                        val sig = params["s"].orEmpty()
                        val sp = params["sp"] ?: "sig"
                        if (baseUrl.isNotBlank() && sig.isNotBlank()) {
                            val ops = com.example.resolver.KotlinJsInterp.getActiveCipherOperations()
                            if (ops.isNotEmpty()) {
                                val deciphered = com.example.resolver.KotlinJsInterp.decipherSignature(sig, ops)
                                if (deciphered.isNotBlank() && deciphered != sig) {
                                    val sep = if (baseUrl.contains("?")) "&" else "?"
                                    rawUrl = "$baseUrl$sep$sp=$deciphered"
                                }
                            }
                        }
                    }
                }
                if (rawUrl.isNotBlank() && !rawUrl.contains("cpn=")) {
                    val sep = if (rawUrl.contains("?")) "&" else "?"
                    rawUrl = "$rawUrl${sep}cpn=$customCpn"
                }
                val jsonClen = fmt.optString("contentLength", "").toLongOrNull() ?: -1L
                if (rawUrl.isNotBlank() && jsonClen > 0L) {
                    com.seitharol.player.OuterTuneResolvingDataSource.recordKnownStreamLength(rawUrl, jsonClen)
                }
                return rawUrl
            }

            val cipherPasses = if (client.isEmbedded) listOf(false, true) else listOf(false)

            fun findMuxedMp4(): String? {
                val formats = streamingData.optJSONArray("formats")
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                val candidates = mutableListOf<Pair<Int, String>>()
                for (allowCipher in cipherPasses) {
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val fmt = formats.optJSONObject(i) ?: continue
                            val mime = fmt.optString("mimeType", "")
                            val itag = fmt.optInt("itag", 0)
                            if (!mime.startsWith("video/") && itag !in listOf(18, 22, 37, 59, 78)) continue
                            val rawUrl = resolveFormatUrl(fmt, allowCipher = allowCipher)
                            val height = fmt.optInt("height", 360)
                            if (rawUrl.isNotBlank() && !rawUrl.contains(".m3u8", true)) {
                                candidates.add(height to rawUrl)
                            }
                        }
                    }
                    if (candidates.isEmpty() && adaptive != null) {
                        for (i in 0 until adaptive.length()) {
                            val fmt = adaptive.optJSONObject(i) ?: continue
                            val itag = fmt.optInt("itag", 0)
                            if (itag !in listOf(18, 22)) continue
                            val rawUrl = resolveFormatUrl(fmt, allowCipher = allowCipher)
                            val height = fmt.optInt("height", 360)
                            if (rawUrl.isNotBlank() && !rawUrl.contains(".m3u8", true)) {
                                candidates.add(height to rawUrl)
                            }
                        }
                    }
                    if (candidates.isNotEmpty()) break
                }
                return candidates.sortedByDescending { it.first }.firstOrNull()?.second
            }

            fun findAdaptiveMergedAv(): String? {
                if (!allowAdaptiveMerge) return null
                if (client.clientName == "IOS") return null
                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: return null
                val videoCandidates = mutableListOf<Pair<Int, String>>()
                val audioCandidates = mutableListOf<Pair<Int, String>>()
                val audioCpn = generateCpn()
                for (allowCipher in cipherPasses) {
                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val mime = fmt.optString("mimeType", "")
                        val itag = fmt.optInt("itag", 0)
                        val isMp4Avc = mime.startsWith("video/mp4", ignoreCase = true) &&
                            (mime.contains("avc1", ignoreCase = true) || itag in listOf(134, 135, 136, 160, 298))
                        val isWebmVp9 = mime.startsWith("video/webm", ignoreCase = true) &&
                            (mime.contains("vp9", ignoreCase = true) || itag in listOf(242, 243, 244, 247))
                        if (isMp4Avc || isWebmVp9) {
                            val url = resolveFormatUrl(fmt, allowCipher = allowCipher, customCpn = cpn)
                            if (url.isBlank()) continue
                            val height = fmt.optInt("height", 360)
                            if (height in 144..720) {
                                val codecBonus = if (isMp4Avc) 5000 else 2000
                                val resScore = when (height) {
                                    480 -> 600
                                    360 -> 550
                                    720 -> 500
                                    240 -> 300
                                    else -> height
                                }
                                videoCandidates.add((codecBonus + resScore) to url)
                            }
                        } else if (mime.startsWith("audio/", ignoreCase = true) || itag in listOf(140, 251, 250, 249, 139)) {
                            val url = resolveFormatUrl(fmt, allowCipher = allowCipher, customCpn = audioCpn)
                            if (url.isBlank()) continue
                            val isM4a = itag == 140 || mime.contains("mp4a", ignoreCase = true)
                            val score = (if (isM4a) 10_000 else 5_000) + (fmt.optInt("bitrate", 128000) / 1000)
                            audioCandidates.add(score to url)
                        }
                    }
                    if (videoCandidates.isNotEmpty() && audioCandidates.isNotEmpty()) break
                }
                val bestVideo = videoCandidates.maxByOrNull { it.first }?.second
                val bestAudio = audioCandidates.maxByOrNull { it.first }?.second
                return if (!bestVideo.isNullOrBlank() && !bestAudio.isNullOrBlank()) {
                    "merged_av::$bestVideo|||$bestAudio"
                } else null
            }

            findMuxedMp4()?.takeIf { !failedUrls.contains(it) }?.let { return@runCatching it }
            if (allowAdaptiveMerge) {
                findAdaptiveMergedAv()?.takeIf { !failedUrls.contains(it) }?.let { return@runCatching it }
            }
            null
        }.getOrNull()
    }

    suspend fun resolveDownloadableVideoCandidates(
        videoId: String?,
        title: String,
        artist: String
    ): List<String> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<String>()
        fun addVideoCandidate(url: String?) {
            if (!url.isNullOrBlank() &&
                url.startsWith("http") &&
                !url.startsWith("merged_av::") &&
                !url.contains(".m3u8", ignoreCase = true) &&
                !url.contains("manifest.googlevideo.com", ignoreCase = true) &&
                !candidates.contains(url)
            ) {
                candidates.add(url)
            }
        }

        var targetYtId = extractVideoId(videoId)
        if (targetYtId.isNullOrBlank() && title.isNotBlank()) {
            val cleanArtist = artist.takeIf { !it.equals("Unknown Artist", true) && !it.equals("YouTube", true) }.orEmpty()
            val q = "$title $cleanArtist".trim()
            val searched = runCatching { searchAllYoutubeVideos(q, limit = 3) }.getOrDefault(emptyList())
            targetYtId = searched.firstOrNull()?.videoId
        }

        if (!targetYtId.isNullOrBlank()) {
            videoStreamCache[targetYtId]?.first?.let { addVideoCandidate(it) }

            for (client in VIDEO_CLIENTS) {
                val url = extractProgressiveVideoWithClient(targetYtId, client, generateCpn(), allowAdaptiveMerge = false)
                addVideoCandidate(url)
                if (candidates.size >= 3) break
            }

            if (candidates.size < 3) {
                for (client in VERIFIED_CLIENTS) {
                    val url = extractStreamWithConfig(
                        videoId = targetYtId,
                        client = client,
                        preferredQuality = AudioQuality.HIGH,
                        cpn = generateCpn(),
                        allowHls = false,
                        requiredItag = 18,
                        preferProgressiveMp4 = true
                    )
                    addVideoCandidate(url)
                    if (candidates.size >= 4) break
                }
            }

            if (candidates.isEmpty()) {
                val resolved = resolveRawVideoStreamById(targetYtId, forceRefresh = true, allowHlsFallback = false).getOrNull()
                addVideoCandidate(resolved)
            }
        }

        candidates
    }

    suspend fun resolveDownloadableStreamCandidates(
        videoId: String?,
        title: String,
        artist: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): List<String> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<String>()
        val fallbackProgressiveCandidates = mutableListOf<String>()

        fun isMuxedVideoItag(url: String): Boolean {
            return url.contains("itag=18&", ignoreCase = true) ||
                url.endsWith("itag=18", ignoreCase = true) ||
                url.contains("itag=22&", ignoreCase = true) ||
                url.endsWith("itag=22", ignoreCase = true)
        }

        fun addCandidate(url: String?) {
            if (!url.isNullOrBlank() &&
                url.startsWith("http") &&
                !url.startsWith("merged_av::") &&
                !url.contains(".m3u8", ignoreCase = true) &&
                !url.contains("manifest.googlevideo.com", ignoreCase = true)
            ) {
                val cleanUrl = com.seitharol.player.OuterTuneResolvingDataSource.stripRangeQueryParam(url)
                if (isMuxedVideoItag(cleanUrl)) {
                    if (!fallbackProgressiveCandidates.contains(cleanUrl)) {
                        fallbackProgressiveCandidates.add(cleanUrl)
                    }
                } else if (!candidates.contains(cleanUrl)) {
                    candidates.add(cleanUrl)
                }
            }
        }

        val isExplicitSaavn = videoId?.startsWith("saavn_") == true || videoId?.startsWith("saavn_clip:") == true
        var targetYtId = if (isExplicitSaavn) null else extractVideoId(videoId)

        // 1. If it's a YouTube track, resolve pure audio (itag=140 M4A / itag=251 Opus) directly & fast!
        if (!targetYtId.isNullOrBlank()) {
            addCandidate(com.seitharol.player.OuterTuneResolvingDataSource.getCachedUrl(targetYtId, preferredQuality))

            for (client in VERIFIED_CLIENTS) {
                val isVision = client.clientName.equals("VISIONOS", ignoreCase = true)
                val streamUrl = extractStreamWithConfig(
                    videoId = targetYtId,
                    client = client,
                    preferredQuality = preferredQuality,
                    cpn = generateCpn(),
                    allowHls = false,
                    includeVisitorData = client.isEmbedded || isVision,
                    preferProgressiveMp4 = !isVision
                )
                if (!streamUrl.isNullOrBlank()) {
                    com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(targetYtId, preferredQuality, streamUrl)
                    addCandidate(streamUrl)
                }
                val progUrl = extractStreamWithConfig(
                    videoId = targetYtId,
                    client = client,
                    preferredQuality = preferredQuality,
                    cpn = generateCpn(),
                    allowHls = false,
                    includeVisitorData = client.isEmbedded || isVision,
                    requiredItag = 18,
                    preferProgressiveMp4 = true
                )
                addCandidate(progUrl)
                if (candidates.size >= 2 && fallbackProgressiveCandidates.isNotEmpty()) break
            }
        }

        // 2. For JioSaavn tracks or as a fallback when YouTube direct extraction is restricted
        if (candidates.isEmpty() && title.isNotBlank()) {
            val saavnMeta = TrackMetadata(
                id = if (targetYtId != null) "" else videoId.orEmpty(),
                title = title,
                artist = artist,
                durationSeconds = 0
            )
            val saavnUrl = runCatching { StreamResolver.resolveExactStreamUrl(saavnMeta, preferredQuality) }.getOrNull()
            addCandidate(saavnUrl)
        }

        // 3. If non-YouTube track wasn't on JioSaavn, search YouTube Music by title + artist
        if (candidates.isEmpty() && targetYtId.isNullOrBlank() && title.isNotBlank()) {
            val cleanArtist = artist.takeIf { !it.equals("Unknown Artist", true) }.orEmpty()
            val q = "$title $cleanArtist".trim()
            val searched = runCatching { searchOfficialYtmSongs(q, limit = 3) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { searchAllYoutubeVideos(q, limit = 3) }.getOrDefault(emptyList()) }
            targetYtId = searched.firstOrNull()?.videoId
            if (!targetYtId.isNullOrBlank()) {
                for (client in VERIFIED_CLIENTS.take(3)) {
                    val pureAudioUrl = extractStreamWithConfig(
                        videoId = targetYtId,
                        client = client,
                        preferredQuality = preferredQuality,
                        cpn = generateCpn(),
                        allowHls = false,
                        includeVisitorData = client.isEmbedded,
                        preferProgressiveMp4 = false
                    )
                    addCandidate(pureAudioUrl)
                    if (candidates.isNotEmpty()) break
                }
            }
        }

        fallbackProgressiveCandidates.forEach { prog ->
            if (!candidates.contains(prog)) candidates.add(prog)
        }

        candidates
    }

    suspend fun downloadUrlToFileReliable(
        streamUrl: String,
        tempFile: java.io.File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanBaseUrl = com.seitharol.player.OuterTuneResolvingDataSource.stripRangeQueryParam(streamUrl)
        val headers = com.seitharol.player.OuterTuneResolvingDataSource.resolveClientHeadersForUrl(cleanBaseUrl)
        val isGoogleVideo = cleanBaseUrl.contains("googlevideo.com", ignoreCase = true)
        var knownClen = com.seitharol.player.OuterTuneResolvingDataSource.getKnownStreamLength(cleanBaseUrl)

        val isProgressiveItag = cleanBaseUrl.contains("itag=18&", ignoreCase = true) ||
            cleanBaseUrl.endsWith("itag=18", ignoreCase = true) ||
            cleanBaseUrl.contains("itag=22&", ignoreCase = true) ||
            cleanBaseUrl.endsWith("itag=22", ignoreCase = true) ||
            !cleanBaseUrl.contains("gir=yes", ignoreCase = true)

        // Adaptive googlevideo.com streams (gir=yes, VISIONOS itag=140 M4A / itag=251 Opus)
        // Download in 512 KB chunks using standard HTTP Range header first (VISIONOS 206 Partial Content).
        if (isGoogleVideo && !isProgressiveItag) {
            return@withContext runCatching {
                if (tempFile.exists()) tempFile.delete()
                java.io.FileOutputStream(tempFile).use { output ->
                    var downloaded = 0L
                    val chunkSize = 512L * 1024L // 512 KB OuterTune standard range chunk
                    val buffer = ByteArray(32 * 1024)
                    var useRangeHeader = false
                    var lastProgressEmitMs = 0L

                    while (knownClen <= 0L || downloaded < knownClen) {
                        kotlinx.coroutines.currentCoroutineContext().let { ctx ->
                            if (ctx[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
                        }
                        val endByte = if (knownClen > 10_240L) {
                            (downloaded + chunkSize - 1L).coerceAtMost(knownClen - 1L)
                        } else {
                            downloaded + chunkSize - 1L
                        }
                        val requestedChunkLen = (endByte - downloaded + 1L).coerceAtLeast(1L)
                        if (knownClen > 10_240L && downloaded >= knownClen) break

                        suspend fun fetchChunk(headerMode: Boolean): Long {
                            val reqBuilder = if (headerMode) {
                                okhttp3.Request.Builder()
                                    .url(cleanBaseUrl)
                                    .header("Range", "bytes=$downloaded-$endByte")
                            } else {
                                val sep = if (cleanBaseUrl.contains("?")) "&" else "?"
                                okhttp3.Request.Builder()
                                    .url("${cleanBaseUrl}${sep}range=$downloaded-$endByte")
                            }
                            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                            val call = com.example.network.NetworkClients.okHttpClient.newCall(reqBuilder.build())
                            call.execute().use { resp ->
                                if (resp.code == 416 && downloaded > 64_000L) {
                                    return 0L
                                }
                                if (!resp.isSuccessful && resp.code != 206) {
                                    return -1L
                                }
                                resp.header("Content-Range")?.let { cr ->
                                    val totalFromHeader = cr.substringAfterLast("/", "").trim().toLongOrNull() ?: -1L
                                    if (totalFromHeader > 10_240L) {
                                        knownClen = totalFromHeader
                                        com.seitharol.player.OuterTuneResolvingDataSource.recordKnownStreamLength(cleanBaseUrl, totalFromHeader)
                                    }
                                }
                                val body = resp.body ?: return -1L
                                val contentLen = body.contentLength()
                                if (downloaded == 0L && knownClen <= 0L && !headerMode && contentLen > chunkSize * 2) {
                                    knownClen = contentLen
                                } else if (downloaded > 0L && !headerMode && contentLen > chunkSize * 2) {
                                    return -1L
                                }
                                var chunkRead = 0L
                                body.byteStream().use { input ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        kotlinx.coroutines.currentCoroutineContext().let { ctx ->
                                            if (ctx[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
                                        }
                                        output.write(buffer, 0, read)
                                        downloaded += read
                                        chunkRead += read
                                        val now = System.currentTimeMillis()
                                        if (now - lastProgressEmitMs >= 200L) {
                                            lastProgressEmitMs = now
                                            val estTotal = if (knownClen > 10_240L) knownClen else (downloaded + chunkSize)
                                            onProgress(downloaded, estTotal)
                                        }
                                    }
                                }
                                return chunkRead
                            }
                        }

                        var bytesInChunk = fetchChunk(headerMode = useRangeHeader)
                        if (bytesInChunk < 0L) {
                            useRangeHeader = !useRangeHeader
                            bytesInChunk = fetchChunk(headerMode = useRangeHeader)
                        }
                        if (bytesInChunk <= 0L) {
                            if (downloaded > 64_000L && (knownClen <= 0L || downloaded >= (knownClen * 95L / 100L))) {
                                break
                            }
                            throw IllegalStateException("Zero bytes read on chunk at offset $downloaded")
                        }
                        val estTotal = if (knownClen > 10_240L) knownClen else (downloaded + chunkSize)
                        onProgress(downloaded, estTotal)

                        if (bytesInChunk < requestedChunkLen) {
                            knownClen = downloaded
                            break
                        }
                    }
                    output.flush()
                }
                tempFile.exists() && tempFile.length() > 10_240L
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (tempFile.exists()) tempFile.delete()
                false
            }
        }

        // Single-GET download path for JioSaavn CDN and progressive MP4 (itag=18 ratebypass=yes)
        for (sendRangeZero in listOf(false, true)) {
            val singleSuccess = runCatching {
                if (tempFile.exists()) tempFile.delete()
                val reqBuilder = okhttp3.Request.Builder().url(cleanBaseUrl)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                if (sendRangeZero) {
                    reqBuilder.header("Range", "bytes=0-")
                }

                com.example.network.NetworkClients.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) return@runCatching false
                    val body = resp.body ?: return@runCatching false
                    val totalBytes = body.contentLength().takeIf { it > 10_240L }
                        ?: knownClen.takeIf { it > 10_240L }
                        ?: 4_000_000L

                    var lastProgressEmitMs = 0L
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            var totalRead = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                kotlinx.coroutines.currentCoroutineContext().let { ctx ->
                                    if (ctx[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
                                }
                                output.write(buffer, 0, read)
                                totalRead += read
                                val now = System.currentTimeMillis()
                                if (now - lastProgressEmitMs >= 220L) {
                                    lastProgressEmitMs = now
                                    onProgress(totalRead, totalBytes)
                                }
                            }
                            output.flush()
                            onProgress(totalRead, totalRead.coerceAtLeast(totalBytes))
                        }
                    }
                }
                tempFile.exists() && tempFile.length() > 10_240L
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (singleSuccess) return@withContext true
        }
        false
    }

    /**
     * Searches the main YouTube catalog (all videos, music videos, live performances, podcasts, covers)
     * with view counts and publish recency metadata.
     */
    suspend fun searchAllYoutubeVideos(query: String, limit: Int = 25): List<CandidateStream> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val results = mutableListOf<CandidateStream>()

            runCatching {
                val conn = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4500
                    readTimeout = 4500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    setRequestProperty("X-YouTube-Client-Name", "1")
                    setRequestProperty("X-YouTube-Client-Version", "2.20250310.01.00")
                    setRequestProperty("Origin", "https://www.youtube.com")
                    setRequestProperty("Referer", "https://www.youtube.com/")
                }
                val payload = JSONObject().apply {
                    put("query", query.trim())
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20250310.01.00")
                            put("hl", "en")
                            put("gl", resolveDeviceGl())
                        })
                    })
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val videoRenderers = mutableListOf<JSONObject>()
                    collectVideoRenderers(root, videoRenderers, maxCount = limit.coerceAtLeast(50))
                    for ((idx, vr) in videoRenderers.withIndex()) {
                        val vid = extractVideoId(vr.optString("videoId")) ?: continue
                        val title = extractSimpleOrRunsText(vr.optJSONObject("title")).takeIf { it.isNotBlank() } ?: continue
                        val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: vr.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: vr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: "YouTube"
                        val browseId = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
                            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                        val lenText = extractSimpleOrRunsText(vr.optJSONObject("lengthText"))
                        if (lenText.isBlank()) continue
                        val durSec = parseDurationSeconds(lenText)
                        val viewsTxt = extractSimpleOrRunsText(vr.optJSONObject("viewCountText"))
                            .ifBlank { extractSimpleOrRunsText(vr.optJSONObject("shortViewCountText")) }
                        val pubTxt = extractSimpleOrRunsText(vr.optJSONObject("publishedTimeText"))
                        val parsedViews = parseViewCountLong(viewsTxt)
                        val parsedAgeSec = parsePublishedAgeSeconds(pubTxt).let { age ->
                            if (age == Long.MAX_VALUE) (idx + 100L) * 60L else age
                        }
                        val thumb = extractBestThumbnail(vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
                            .ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" }
                        results.add(
                            CandidateStream(
                                videoId = vid,
                                title = title,
                                artist = channel,
                                artistBrowseId = browseId,
                                channelName = channel,
                                albumName = null,
                                durationSeconds = durSec,
                                videoType = "MUSIC_VIDEO_TYPE_OMV",
                                viewCount = parsedViews,
                                thumbnailUrl = thumb,
                                isVerifiedArtist = true,
                                viewCountText = viewsTxt,
                                publishedTimeText = pubTxt,
                                publishedAgeSeconds = parsedAgeSec
                            )
                        )
                        if (results.size >= limit) break
                    }
                }
            }

            if (results.size < 8) {
                runCatching {
                    val payload = webRemixPayload().apply {
                        put("query", query.trim())
                        put("params", "EgWKAQIQAWoKEAkQChADEAUQBA==")
                    }
                    val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
                    if (root != null) {
                        val ytmVideos = parseMusicResponsiveListItems(root).map {
                            it.copy(videoType = "MUSIC_VIDEO_TYPE_OMV", albumName = null)
                        }
                        results.addAll(ytmVideos)
                    }
                }
            }

            results.distinctBy { it.videoId }.take(limit)
        }

    private fun collectVideoRenderers(node: Any?, out: MutableList<JSONObject>, maxCount: Int = 60) {
        if (out.size >= maxCount) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { out.add(it); return }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideoRenderers(node.opt(keys.next()), out, maxCount)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoRenderers(node.opt(i), out, maxCount)
                }
            }
        }
    }

    suspend fun fetchRealTrendingSearches(): List<String> = withContext(Dispatchers.IO) {
        val trending = mutableListOf<String>()

        val seeds = listOf("manipuri new ", "latest song ", "trending ", "arijit ", "top hits ")
        for (seed in seeds) {
            runCatching {
                val sugs = com.example.network.NetworkClients.getSearchSuggestions(seed)
                sugs.forEach { s ->
                    val clean = s.trim()
                    if (clean.length in 3..36 && trending.none { it.equals(clean, ignoreCase = true) }) {
                        trending.add(clean)
                    }
                }
            }
            if (trending.size >= 10) break
        }

        if (trending.size < 6) {
            runCatching {
                val topTracks = searchOfficialYtmSongs("Manipuri & Indian Trending Songs", limit = 8)
                topTracks.forEach { t ->
                    val cleanTitle = t.title.substringBefore("(").substringBefore("|").substringBefore("-").trim()
                    if (cleanTitle.length in 3..32 && trending.none { it.equals(cleanTitle, ignoreCase = true) }) {
                        trending.add(cleanTitle)
                    }
                }
            }
        }

        trending.take(10)
    }

    private fun webRemixPayload(): JSONObject = JSONObject().apply {
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20250310.01.00")
                put("hl", "en")
                put("gl", resolveDeviceGl())
            })
        })
    }

    private fun postWebRemix(urlStr: String, payload: JSONObject): JSONObject? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4500
                readTimeout = 4500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                setRequestProperty("X-YouTube-Client-Name", "67")
                setRequestProperty("X-YouTube-Client-Version", "1.20250310.01.00")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
                cachedVisitorData = it
            }
            json
        }.getOrNull()
    }

    private fun extractBestThumbnail(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
    }

    private val videoDescriptionCache = ConcurrentHashMap<String, VideoDescriptionMetadata>()

    suspend fun fetchVideoDetailsDescription(
        videoId: String?,
        fallbackTitle: String = "",
        fallbackArtist: String = ""
    ): VideoDescriptionMetadata = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId) ?: videoId.orEmpty().trim()
        videoDescriptionCache[cleanId]?.takeIf { it.description.isNotBlank() }?.let { return@withContext it }

        if (cleanId.length == 11) {
            for (client in VIDEO_CLIENTS.take(2)) {
                runCatching {
                    val conn = (URL(client.playerEndpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 2500
                        readTimeout = 2500
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", client.userAgent)
                        setRequestProperty("X-YouTube-Client-Name", client.clientId)
                        setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
                    }
                    val payload = JSONObject().apply {
                        put("videoId", cleanId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", client.clientName)
                                put("clientVersion", client.clientVersion)
                                put("hl", "en")
                                put("gl", client.glOverride ?: resolveDeviceGl())
                            })
                        })
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val vd = json.optJSONObject("videoDetails")
                        if (vd != null) {
                            val meta = VideoDescriptionMetadata(
                                videoId = cleanId,
                                title = vd.optString("title", fallbackTitle).ifBlank { fallbackTitle },
                                author = vd.optString("author", fallbackArtist).ifBlank { fallbackArtist },
                                viewCountText = vd.optString("viewCount", ""),
                                description = vd.optString("shortDescription", "")
                            )
                            videoDescriptionCache[cleanId] = meta
                            if (meta.description.isNotBlank()) return@withContext meta
                        }
                    }
                }
            }
        }

        videoDescriptionCache[cleanId] ?: VideoDescriptionMetadata(
            videoId = cleanId,
            title = fallbackTitle,
            author = fallbackArtist,
            viewCountText = "",
            description = ""
        )
    }

    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 210
        }
    }
}
