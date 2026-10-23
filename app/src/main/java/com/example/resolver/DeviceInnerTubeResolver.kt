package com.example.resolver

import android.util.Log
import com.example.network.NetworkClients
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * Zone 1 Device Resolver: Direct on-device InnerTube transport + dynamic `base.js` JS decipher,
 * `n`-parameter de-throttling, and PoToken/VisitorData attestation (§A.3 & §A.4).
 */
class DeviceInnerTubeResolver : Resolver {
    override val id: String = "resolver_device_innertube"
    override val priority: Int = 1
    override val capabilities: Set<Capability> = setOf(
        Capability.SIGNATURE,
        Capability.THROTTLE
    )
    override val trust: TrustZone = TrustZone.ZONE_1_DEVICE

    private val TAG = "DeviceInnerTubeResolver"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val cleanVideoId = InnerTubeStreamExtractor.extractVideoId(req.videoId) ?: req.videoId.trim()
        val userGl = Locale.getDefault().country.takeIf { it.length == 2 } ?: "IN"

        // 1. Pre-load YouTube's active base.js player script so signatureTimestamp (sts),
        //    structural cipher operations (REVERSE, SPLICE, SWAP), and the n-parameter JS
        //    function are ready before invoking /youtubei/v1/player.
        KotlinJsInterp.ensurePlayerJsLoaded(forceRefresh = false)
        val sts = KotlinJsInterp.getSignatureTimestamp()
        val visitorData = InnerTubeStreamExtractor.ensureVisitorData(forceFresh = false)
        val poToken = KotlinJsInterp.generatePoToken(visitorData)
        val cpn = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".random() }.joinToString("")

        // Try ANDROID_VR (direct unciphered high-bitrate streams) first, then WEB_REMIX with
        // full PoToken + visitorData attestation + dynamic base.js signature deciphering & n-param transformation.
        val clientConfigs = listOf(
            ClientAttempt(
                endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                clientId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                gl = userGl,
                sendOrigin = false,
                includePoToken = false
            ),
            ClientAttempt(
                endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
                clientName = "WEB_REMIX",
                clientVersion = "1.20250310.01.00",
                clientId = "67",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                gl = userGl,
                sendOrigin = true,
                includePoToken = true
            ),
            ClientAttempt(
                endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientId = "85",
                userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15",
                gl = userGl,
                sendOrigin = true,
                includePoToken = true,
                isEmbedded = true
            )
        )

        val errors = mutableListOf<String>()
        for (attempt in clientConfigs) {
            try {
                val result = executePlayerRequest(
                    req = req,
                    videoId = cleanVideoId,
                    attempt = attempt,
                    sts = sts,
                    visitorData = visitorData,
                    poToken = poToken,
                    cpn = cpn
                )
                if (result != null) return@withContext result
            } catch (e: Exception) {
                errors.add("${attempt.clientName}: ${e.message}")
                Log.w(TAG, "Client ${attempt.clientName} failed for $cleanVideoId: ${e.message}")
            }
        }

        throw IllegalStateException("DeviceInnerTubeResolver failed for $cleanVideoId: ${errors.joinToString("; ")}")
    }

    private data class ClientAttempt(
        val endpoint: String,
        val clientName: String,
        val clientVersion: String,
        val clientId: String,
        val userAgent: String,
        val gl: String,
        val sendOrigin: Boolean,
        val includePoToken: Boolean,
        val isEmbedded: Boolean = false
    )

    private fun executePlayerRequest(
        req: ResolveRequest,
        videoId: String,
        attempt: ClientAttempt,
        sts: Int,
        visitorData: String?,
        poToken: String?,
        cpn: String
    ): ResolveResult? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("signatureTimestamp", sts)
                })
            })
            if (attempt.includePoToken && !poToken.isNullOrBlank()) {
                put("serviceIntegrityDimensions", JSONObject().apply {
                    put("poToken", poToken)
                })
            }
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", attempt.clientName)
                    put("clientVersion", attempt.clientVersion)
                    put("hl", "en")
                    put("gl", attempt.gl)
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                    if (attempt.clientName == "ANDROID_VR") {
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12L")
                        put("androidSdkVersion", 32)
                    }
                })
                if (attempt.isEmbedded) {
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", "https://www.youtube.com/")
                    })
                }
            })
        }

        val origin = if (attempt.endpoint.contains("music.youtube.com")) {
            "https://music.youtube.com"
        } else {
            "https://www.youtube.com"
        }

        val reqBuilder = Request.Builder()
            .url(attempt.endpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", attempt.userAgent)
            .header("X-YouTube-Client-Name", attempt.clientId)
            .header("X-YouTube-Client-Version", attempt.clientVersion)
            .header("X-Goog-Api-Format-Version", "2")

        if (attempt.sendOrigin) {
            reqBuilder.header("Origin", origin)
            reqBuilder.header("Referer", "$origin/")
        }
        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        NetworkClients.okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IllegalStateException("Empty player response")
            val json = JSONObject(bodyStr)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "")
            if (status != "OK") {
                throw IllegalStateException("Playability status was $status")
            }

            val streamingData = json.optJSONObject("streamingData") ?: throw IllegalStateException("No streamingData")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
            val legacyFormats = streamingData.optJSONArray("formats") ?: JSONArray()

            val activeCipherOps = KotlinJsInterp.getActiveCipherOperations()
            val candidateFormats = mutableListOf<FormatItem>()

            fun processFormatArray(formats: JSONArray) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType", "")
                    val itagInt = f.optInt("itag", 0)
                    if (!mime.startsWith("audio/") && itagInt !in listOf(140, 141, 139, 251, 250, 249, 18)) continue

                    var directUrl = f.optString("url", "")
                    val cipher = f.optString("signatureCipher", "").ifBlank { f.optString("cipher", "") }
                    val itag = f.optString("itag", "140")
                    val bitrate = f.optInt("bitrate", 160000)

                    if (directUrl.isBlank() && cipher.isNotBlank()) {
                        val params = parseQueryString(cipher)
                        val s = params["s"] ?: ""
                        val sp = params["sp"] ?: "sig"
                        val baseUrl = params["url"] ?: ""
                        if (baseUrl.isNotBlank() && s.isNotBlank()) {
                            val decipheredSig = KotlinJsInterp.decipherSignature(s, activeCipherOps)
                            if (decipheredSig.isNotBlank() && decipheredSig != s) {
                                val sep = if (baseUrl.contains("?")) "&" else "?"
                                directUrl = "$baseUrl$sep$sp=$decipheredSig"
                            }
                        }
                    }

                    if (directUrl.isNotBlank()) {
                        // Transform 'n' throttling challenge parameter using base.js JS engine
                        if (directUrl.contains("&n=") || directUrl.contains("?n=")) {
                            val nMatch = Regex("[?&]n=([^&]+)").find(directUrl)
                            if (nMatch != null) {
                                val originalN = nMatch.groupValues[1]
                                val transformedN = KotlinJsInterp.transformNParam(originalN)
                                if (transformedN.isNotBlank()) {
                                    directUrl = directUrl.replace("n=$originalN", "n=$transformedN")
                                }
                            }
                        }
                        if (!directUrl.contains("cpn=")) {
                            val sep = if (directUrl.contains("?")) "&" else "?"
                            directUrl = "$directUrl${sep}cpn=$cpn"
                        }
                        if (attempt.includePoToken && !poToken.isNullOrBlank() && !directUrl.contains("pot=")) {
                            directUrl = "$directUrl&pot=$poToken"
                        }

                        val isOpus = mime.contains("opus", ignoreCase = true) || itagInt in listOf(249, 250, 251)
                        val codec = if (isOpus) "opus" else "aac"
                        candidateFormats.add(
                            FormatItem(
                                id = itag,
                                codec = codec,
                                container = if (codec == "opus") "webm" else "m4a",
                                bitrate = bitrate,
                                url = directUrl
                            )
                        )
                    }
                }
            }

            processFormatArray(adaptiveFormats)
            processFormatArray(legacyFormats)

            if (candidateFormats.isEmpty()) {
                throw IllegalStateException("No usable audio streams found")
            }

            // Prefer M4A AAC (itag 140) first for universal hardware MediaCodec support, then high-bitrate Opus (itag 251)
            candidateFormats.sortWith(
                compareByDescending<FormatItem> { it.id == "140" || it.codec == "aac" }
                    .thenByDescending { it.bitrate }
            )

            // Verify playability on the top candidate before returning
            val verifiedFormats = mutableListOf<FormatItem>()
            for (fmt in candidateFormats.distinctBy { it.id }.take(2)) {
                val verifiedUrl = InnerTubeStreamExtractor.verifyStreamPlayable(fmt.url)
                if (!verifiedUrl.isNullOrBlank()) {
                    verifiedFormats.add(fmt.copy(url = verifiedUrl))
                    break
                }
            }

            if (verifiedFormats.isEmpty()) {
                throw IllegalStateException("Stream URL failed HTTP byte-range verification")
            }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", req.title ?: "Track") ?: "Track"
            val artist = videoDetails?.optString("author", req.artist ?: "Artist") ?: "Artist"
            val durationSec = videoDetails?.optLong("lengthSeconds", 210L) ?: 210L

            return ResolveResult(
                videoId = videoId,
                resolveId = "device_${videoId}_${attempt.clientName}",
                expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                durationMs = durationSec * 1000L,
                title = title,
                artist = artist,
                artUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                formats = verifiedFormats,
                resolverId = id,
                packVersion = "device-v2026.10-js"
            )
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }
}
