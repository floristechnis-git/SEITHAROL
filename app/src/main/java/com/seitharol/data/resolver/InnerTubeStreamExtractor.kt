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
    val similarArtists: List<YtmArtistPreview>
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

object InnerTubeStreamExtractor {

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientRotationOffset = AtomicInteger(0)

    private fun resolveDeviceGl(): String {
        val country = runCatching { Locale.getDefault().country }.getOrNull().orEmpty()
        return if (country.length == 2) country.uppercase(Locale.US) else "IN"
    }

    /**
     * Verified multi-client InnerTube pipeline:
     * 1. ANDROID_VR (1.60.19, Quest 3, regional gl="IN"/device) — Native VR client, unciphered direct URLs, supports Indian/Manipuri regional catalog.
     * 2. IOS (20.10.4, iPhone16,2, regional gl="IN"/device) — Native iOS client with M4A (140), Opus, and HLS manifest support.
     * 3. ANDROID_VR (1.43.32, Quest 3, gl="US") — Global US fallback VR profile.
     * 4. ANDROID_TESTSUITE (1.9, params="CgIIAQ==") — Unciphered direct audio streams.
     * 5. TVHTML5_SIMPLY_EMBEDDED_PLAYER (2.0) — Embedded player fallback.
     */
    val VERIFIED_CLIENTS = listOf(
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            glOverride = null, // Uses device/regional GL ("IN")
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
            glOverride = null, // Uses device/regional GL ("IN")
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
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 12L; US) gzip",
            clientId = "30",
            deviceMake = "Google",
            deviceModel = "Pixel 7",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            params = "CgIIAQ==",
            glOverride = null,
            sendOriginHeaders = false
        ),
        InnerTubeClientConfig(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
            clientId = "85",
            glOverride = null,
            isEmbedded = true,
            sendOriginHeaders = true
        )
    )

    @Volatile
    private var cachedVisitorData: String? = null

    init {
        bgScope.launch { ensureVisitorData(forceFresh = true) }
    }

    fun ensureVisitorData(forceFresh: Boolean = false): String? {
        if (!forceFresh && !cachedVisitorData.isNullOrBlank()) {
            return cachedVisitorData
        }
        return fetchVisitorDataSync()
    }

    private fun fetchVisitorDataSync(): String? {
        runCatching {
            val conn = (URL("https://www.youtube.com/sw.js_data").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val match = Regex("\"([A-Za-z0-9_-]{16,}%3D%3D)\"").find(body)
                val token = match?.groupValues?.get(1)
                if (!token.isNullOrBlank()) {
                    cachedVisitorData = token
                    return token
                }
            }
        }

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
                }
                vis
            } else null
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

    /**
     * Performs a fast HTTP byte-range probe (`Range: bytes=0-1023`) with the exact
     * client-matched headers to verify that `googlevideo.com` actually serves audio bytes (HTTP 200/206)
     * and does NOT reject the stream with HTTP 403 Forbidden.
     *
     * CRITICAL: Returns the ORIGINAL `streamUrl` (not the one-time redirected `rr*---sn-*.googlevideo.com` URL
     * bound to `Range: bytes=0-1023`), so ExoPlayer's `DefaultHttpDataSource` can follow the redirect cleanly
     * with its own `Range: bytes=0-` request!
     */
    fun verifyStreamPlayable(streamUrl: String): String? {
        if (streamUrl.isBlank()) return null
        if (streamUrl.contains(".m3u8")) return streamUrl
        return runCatching {
            val headers = com.seitharol.player.OuterTuneResolvingDataSource.resolveClientHeadersForUrl(streamUrl)
            var currentUrl = streamUrl
            for (redirect in 0..3) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1800
                    readTimeout = 1800
                    instanceFollowRedirects = false
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=0-1023")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return@runCatching null
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    continue
                }
                val isOk = code in 200..299
                if (isOk) {
                    conn.inputStream.use { it.read() }
                }
                conn.disconnect()
                // Return the original primary streamUrl so ExoPlayer's full-file Range request is never rejected by edge redirect binding
                return@runCatching if (isOk) streamUrl else null
            }
            null
        }.getOrNull()
    }

    private fun extractStreamWithConfig(
        videoId: String,
        client: InnerTubeClientConfig,
        preferredQuality: AudioQuality,
        cpn: String
    ): String? {
        return runCatching {
            val visData = cachedVisitorData?.takeIf { it.isNotBlank() }
            val effectiveGl = client.glOverride ?: resolveDeviceGl()
            val endpoint = URL(client.playerEndpoint)
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 3000
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
                client.params?.let { put("params", it) }
                if (client.isEmbedded) {
                    com.example.resolver.KotlinJsInterp.ensurePlayerJsLoaded(forceRefresh = false)
                    val sts = com.example.resolver.KotlinJsInterp.getSignatureTimestamp()
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            put("signatureTimestamp", sts)
                        })
                    })
                    val poToken = com.example.resolver.KotlinJsInterp.generatePoToken(visData)
                    if (!poToken.isNullOrBlank()) {
                        put("serviceIntegrityDimensions", JSONObject().apply {
                            put("poToken", poToken)
                        })
                    }
                }
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
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

            json.optJSONObject("responseContext")?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { cachedVisitorData = it }

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status == "LOGIN_REQUIRED") {
                bgScope.launch { ensureVisitorData(forceFresh = true) }
                return@runCatching null
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

            fun collectStreams(arr: JSONArray?, isLegacy: Boolean) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val fmt = arr.optJSONObject(i) ?: continue
                    val mime = fmt.optString("mimeType", "")
                    val itag = fmt.optInt("itag", 0)
                    val isAudio = mime.startsWith("audio/") || itag in listOf(140, 141, 139, 251, 250, 249, 18)
                    if (!isAudio) continue

                    var rawUrl = fmt.optString("url", "")
                    if (rawUrl.isBlank()) {
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
                                val deciphered = com.example.resolver.KotlinJsInterp.decipherSignature(sig, ops)
                                if (deciphered.isNotBlank() && deciphered != sig) {
                                    val sep = if (baseUrl.contains("?")) "&" else "?"
                                    rawUrl = "$baseUrl$sep$sp=$deciphered"
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

                    val kbps = fmt.optInt("bitrate", 128000) / 1000
                    val isAacMp4 = mime.contains("mp4a", ignoreCase = true) || itag in listOf(140, 141, 139)
                    val isProgressiveMp4 = isLegacy || itag == 18
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

            collectStreams(adaptiveFormats, isLegacy = false)
            collectStreams(legacyFormats, isLegacy = true)

            // Prioritize M4A AAC (itag 140) first for universal Android MediaCodec hardware compatibility
            // (eliminating "wrong config length" Opus decoder failures), then Opus (251/250), then progressive MP4 (18).
            val sortedCandidates = audioStreams.sortedWith(
                compareByDescending<AudioStreamCandidate> { it.itag == 140 || it.isAacMp4 }
                    .thenBy { kotlin.math.abs(it.kbps - preferredQuality.maxBitrateKbps) }
            )

            // Probe at most 2 distinct candidates per client (best AAC and best Opus/Progressive) for fast <1.5s turnaround
            val candidatesToProbe = buildList {
                sortedCandidates.firstOrNull { it.isAacMp4 }?.let { add(it) }
                sortedCandidates.firstOrNull { !it.isAacMp4 }?.let { add(it) }
            }.distinctBy { it.itag }.take(2)

            for (candidate in candidatesToProbe) {
                val verifiedUrl = verifyStreamPlayable(candidate.url)
                if (!verifiedUrl.isNullOrBlank()) {
                    return@runCatching verifiedUrl
                }
            }

            // Fallback to HLS manifest if present (e.g. on iOS client)
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsUrl.isNotBlank()) {
                return@runCatching hlsUrl
            }

            null
        }.getOrNull()
    }

    suspend fun resolveExactYoutubeStreamById(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL,
        forceRefresh: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId) ?: videoId.trim()
        if (!VALID_VIDEO_ID_REGEX.matches(cleanVideoId)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube videoId: $videoId"))
        }

        if (forceRefresh) {
            com.seitharol.player.OuterTuneResolvingDataSource.invalidate(cleanVideoId)
        } else {
            com.seitharol.player.OuterTuneResolvingDataSource.getCachedUrl(cleanVideoId, preferredQuality)?.let { cached ->
                return@withContext Result.success(cached)
            }
        }

        if (cachedVisitorData.isNullOrBlank()) {
            ensureVisitorData(forceFresh = false)
        }

        val cpn = generateCpn()
        val startOffset = if (forceRefresh) {
            clientRotationOffset.incrementAndGet() % VERIFIED_CLIENTS.size
        } else {
            0
        }

        for (i in VERIFIED_CLIENTS.indices) {
            val clientIdx = (startOffset + i) % VERIFIED_CLIENTS.size
            val client = VERIFIED_CLIENTS[clientIdx]
            val extractedUrl = extractStreamWithConfig(cleanVideoId, client, preferredQuality, cpn)
            if (!extractedUrl.isNullOrBlank()) {
                com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, extractedUrl)
                return@withContext Result.success(extractedUrl)
            }
        }

        // Refresh visitorData once and retry primary ANDROID_VR & IOS clients if initial pass failed
        ensureVisitorData(forceFresh = true)
        for (client in VERIFIED_CLIENTS.take(2)) {
            val retryUrl = extractStreamWithConfig(cleanVideoId, client, preferredQuality, generateCpn())
            if (!retryUrl.isNullOrBlank()) {
                com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, retryUrl)
                return@withContext Result.success(retryUrl)
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

    suspend fun buildPersonalizedHomeFeed(
        seedVideoIds: List<String>,
        favoriteArtists: List<String>,
        moodCategory: String
    ): List<CandidateStream> = coroutineScope {
        val validSeeds = seedVideoIds.filter { it.length == 11 }.distinct().take(4)
        val cleanArtists = favoriteArtists
            .map { it.removeSuffix("- Topic").trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
            .distinct()

        val isGeneralFeed = moodCategory.equals("All", true) || moodCategory.equals("For You", true)

        val automixJobs = if (isGeneralFeed) {
            validSeeds.map { seedId -> async { fetchAutomixQueue(seedId, limit = 12) } }
        } else {
            validSeeds.take(2).map { seedId -> async { fetchAutomixQueue(seedId, limit = 6) } }
        }

        val searchQueries = when {
            moodCategory.equals("Manipuri Song", true) -> buildList {
                cleanArtists.take(2).forEach { add("$it Manipuri song") }
                add("Manipuri official song new")
                add("Imphal Manipuri hit songs")
                add("Manipuri modern folk acoustic")
            }
            isGeneralFeed -> {
                if (cleanArtists.isNotEmpty()) {
                    cleanArtists.take(4).map { "$it official audio" } + listOf("Manipuri hit songs")
                } else {
                    listOf("Manipuri hit songs", "Top Indian Official Hits", "Global Viral Songs Official")
                }
            }
            else -> buildList {
                cleanArtists.take(2).forEach { artist ->
                    add("$artist $moodCategory songs")
                }
                add("$moodCategory official songs")
                add("$moodCategory Manipuri & Indian songs")
            }
        }

        val searchJobs = searchQueries.map { q -> async { searchOfficialYtmSongs(q, limit = 10) } }
        val batches = automixJobs.awaitAll() + searchJobs.awaitAll()

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

    /**
     * Verifies whether a candidate song, album, single, or playlist genuinely belongs to [targetArtist].
     * Prevents unrelated compilations, other artists' albums, or random user playlists from appearing on the Artist Profile page.
     */
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

        // Check direct containment of the full normalized artist name
        if (normArt.contains(normTarget) || normTarget.contains(normArt).takeIf { normArt.length >= 3 } == true) {
            return true
        }
        if (normSub.contains(normTarget) || normTitle.contains(normTarget)) {
            return true
        }

        // Token-level check for multi-word artist names
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

    suspend fun fetchArtistPage(
        artistName: String,
        artistBrowseId: String? = null
    ): YtmArtistPageData = withContext(Dispatchers.IO) {
        val resolvedBrowse = if (!artistBrowseId.isNullOrBlank() &&
            (artistBrowseId.startsWith("UC") || artistBrowseId.startsWith("MPLA"))
        ) {
            artistBrowseId to ""
        } else {
            resolveOfficialArtistBrowseId(artistName)
        }

        val browseId = resolvedBrowse.first
        val fallbackAvatar = resolvedBrowse.second

        if (browseId.isNotBlank()) {
            val payload = webRemixPayload().apply { put("browseId", browseId) }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", payload)
            if (root != null) {
                val parsed = parseArtistBrowseResponse(root, browseId, artistName, fallbackAvatar)
                if (parsed.topSongs.isNotEmpty() || parsed.albums.isNotEmpty() || parsed.singles.isNotEmpty()) {
                    return@withContext parsed
                }
            }
        }

        val rawSongs = searchOfficialYtmSongs(artistName, limit = 24)
        val verifiedSongs = rawSongs.filter { belongsToArtist(artistName, it.title, itemArtist = it.artist) }
        val topSongs = verifiedSongs.ifEmpty { rawSongs.take(12) }

        val rawCollections = searchYtmCollections(artistName, "EgWKAQIYAWoKEAkQChADEAUQBA==", "ALBUM", 16)
            .filter { belongsToArtist(artistName, it.title, it.subtitle) }
        val albums = rawCollections.filterNot {
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true)
        }.distinctBy { it.browseId }
        val singles = rawCollections.filter {
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true)
        }.map { it.copy(type = "SINGLE") }.distinctBy { it.browseId }

        val verifiedAvatar = if (isAuthenticArtistAvatar(fallbackAvatar)) fallbackAvatar
        else resolveJioSaavnArtistAvatar(artistName)

        val searchedPlaylists = searchYtmCollections(artistName, "EgWKAQIoAWoKEAkQChADEAUQBA==", "PLAYLIST", 10)
            .filter { belongsToArtist(artistName, it.title, it.subtitle) }

        val authenticMixes = buildAuthenticArtistMixes(
            artistName = artistName,
            avatarUrl = verifiedAvatar,
            radioId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            shuffleId = topSongs.getOrElse(1) { topSongs.firstOrNull() }?.videoId?.let { "RDAMVM$it" },
            topSongs = topSongs
        )

        YtmArtistPageData(
            browseId = browseId.ifBlank { artistName },
            name = artistName,
            description = "Official tracks, albums, singles, and artist mixes by $artistName.",
            subscriberCountText = "Verified Artist",
            avatarUrl = verifiedAvatar,
            bannerUrl = verifiedAvatar,
            radioPlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            shufflePlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            playlists = (authenticMixes + searchedPlaylists).distinctBy { it.browseId },
            similarArtists = emptyList()
        )
    }

    private fun buildAuthenticArtistMixes(
        artistName: String,
        avatarUrl: String,
        radioId: String?,
        shuffleId: String?,
        topSongs: List<CandidateStream>
    ): List<YtmCollectionSummary> {
        val mixes = mutableListOf<YtmCollectionSummary>()
        val firstSong = topSongs.firstOrNull()
        val secondSong = topSongs.getOrElse(1) { firstSong }
        val primaryRadioId = radioId?.takeIf { it.isNotBlank() } ?: firstSong?.videoId?.let { "RDAMVM$it" }
        val primaryThumb = avatarUrl.ifBlank { firstSong?.thumbnailUrl.orEmpty() }

        if (!primaryRadioId.isNullOrBlank()) {
            mixes.add(
                YtmCollectionSummary(
                    browseId = primaryRadioId,
                    playlistId = primaryRadioId,
                    title = "$artistName Radio",
                    subtitle = "Official $artistName artist radio",
                    thumbnailUrl = primaryThumb,
                    type = "MIX"
                )
            )
        }

        val secondaryMixId = shuffleId?.takeIf { it.isNotBlank() && it != primaryRadioId }
            ?: secondSong?.videoId?.let { "RDAMVM$it" }?.takeIf { it != primaryRadioId }
        if (!secondaryMixId.isNullOrBlank()) {
            mixes.add(
                YtmCollectionSummary(
                    browseId = secondaryMixId,
                    playlistId = secondaryMixId,
                    title = "Best of $artistName Mix",
                    subtitle = "Continuous $artistName hits",
                    thumbnailUrl = secondSong?.thumbnailUrl?.ifBlank { primaryThumb } ?: primaryThumb,
                    type = "MIX"
                )
            )
        }
        return mixes
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
                ?: return@withContext "" to resolveJioSaavnArtistAvatar(artistName)

            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return@withContext "" to resolveJioSaavnArtistAvatar(artistName)

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
                                rawAvatar.replace(Regex("=w\\d+-h\\d+"), "=w500-h500")
                            } else {
                                resolveJioSaavnArtistAvatar(artistName)
                            }
                            return@withContext browseId to avatar
                        }
                    }
                }
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
        val rawBanner = extractBestThumbnail(bannerThumbs)
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

                    val watchPlaylistId = twoRow.optJSONObject("thumbnailOverlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")
                        ?.optJSONObject("watchPlaylistEndpoint")
                        ?.optString("playlistId")

                    when {
                        // Ignore music videos shelf so videos are never dumped into Playlists
                        shelfTitle.contains("video") || shelfTitle.contains("live performance") -> Unit

                        // Similar / Fans Also Like artists
                        shelfTitle.contains("fan") || shelfTitle.contains("similar") || shelfTitle.contains("like") ||
                            navBrowseId.startsWith("UC") || navBrowseId.startsWith("MPLA") -> {
                            if (navBrowseId.isNotBlank()) {
                                similarArtists.add(
                                    YtmArtistPreview(
                                        browseId = navBrowseId,
                                        name = itemTitle,
                                        subscribers = itemSubtitle.ifBlank { "Artist" },
                                        avatarUrl = itemThumb
                                    )
                                )
                            }
                        }

                        // Official Singles & EPs shelf
                        shelfTitle.contains("single") || shelfTitle.contains("ep") -> {
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

                        // Official Albums shelf (excluding "Featured on" third-party compilations unless explicitly by this artist)
                        (shelfTitle.contains("album") || shelfTitle.contains("release") || navBrowseId.startsWith("MPREb_")) &&
                            !shelfTitle.contains("featured") -> {
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

                        // Playlists / Artist Mixes — ONLY include if it genuinely belongs to this artist!
                        (shelfTitle.contains("playlist") || shelfTitle.contains("mix") || shelfTitle.contains("artist") || shelfTitle.contains("featured")) &&
                            (navBrowseId.startsWith("VL") || navBrowseId.startsWith("PL") || navBrowseId.startsWith("RD") || !watchPlaylistId.isNullOrBlank()) -> {
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
            val verified = searchedSongs.filter { belongsToArtist(name, it.title, itemArtist = it.artist) }
            topSongs.addAll(verified.ifEmpty { searchedSongs.take(10) })
        }

        // If the artist browse page did not inline Albums or Singles, fetch and strictly verify them by artist ownership
        if (albums.isEmpty() || singles.isEmpty()) {
            val searchedReleases = searchYtmCollections(name, "EgWKAQIYAWoKEAkQChADEAUQBA==", "ALBUM", 16)
                .filter { belongsToArtist(name, it.title, it.subtitle) }
            for (rel in searchedReleases) {
                val isSingle = rel.type == "SINGLE" ||
                    rel.subtitle.contains("Single", true) ||
                    rel.subtitle.contains("EP", true) ||
                    rel.subtitle.contains("1 song", true) ||
                    rel.subtitle.contains("1 track", true)
                if (isSingle) {
                    singles.add(rel.copy(type = "SINGLE"))
                } else {
                    albums.add(rel.copy(type = "ALBUM"))
                }
            }
        }

        // Always prepend authentic Artist Radio & Best-of Artist Mix, then append only verified artist playlists
        val authenticMixes = buildAuthenticArtistMixes(
            artistName = name,
            avatarUrl = avatarUrl,
            radioId = radioId,
            shuffleId = shuffleId,
            topSongs = topSongs
        )
        if (playlists.isEmpty()) {
            val searchedPlaylists = searchYtmCollections(name, "EgWKAQIoAWoKEAkQChADEAUQBA==", "PLAYLIST", 10)
                .filter { belongsToArtist(name, it.title, it.subtitle) }
            playlists.addAll(searchedPlaylists)
        }
        val combinedPlaylists = (authenticMixes + playlists).distinctBy { it.browseId }

        val cleanedAlbums = albums.filterNot {
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true)
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
            playlists = combinedPlaylists,
            similarArtists = similarArtists.distinctBy { it.browseId }
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

        return CandidateStream(
            videoId = videoId,
            title = title,
            artist = artist,
            artistBrowseId = artistBrowseId,
            channelName = "$artist - Topic",
            albumName = albumName,
            durationSeconds = if (durationSeconds > 0) durationSeconds else 210,
            videoType = "MUSIC_VIDEO_TYPE_ATV",
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
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()
    }

    private fun extractBestThumbnail(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
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
