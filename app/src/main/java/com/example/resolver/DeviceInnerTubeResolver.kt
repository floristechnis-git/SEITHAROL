package com.example.resolver

import android.util.Log
import com.example.network.NetworkClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Zone 1 Device Resolver: Direct on-device InnerTube transport + decipher (§A.3).
 * Prioritizes WEB_REMIX client context, parses adaptiveFormats, and selects high-bitrate Opus streams.
 */
class DeviceInnerTubeResolver : Resolver {
    override val id: String = "resolver_device_innertube"
    override val priority: Int = 1
    override val capabilities: Set<Capability> = setOf(
        Capability.SIGNATURE,
        Capability.THROTTLE
    )
    override val trust: TrustZone = TrustZone.ZONE_1_DEVICE

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        // 1. InnerTube transport POST /youtubei/v1/player
        val url = "https://music.youtube.com/youtubei/v1/player"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20240101.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", req.videoId)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://music.youtube.com/")
            .build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("InnerTube player returned HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IllegalStateException("Empty player response")
            val json = JSONObject(bodyStr)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status", "")
            if (status != "OK") {
                throw IllegalStateException("Playability status was $status")
            }

            val streamingData = json.optJSONObject("streamingData") ?: throw IllegalStateException("No streamingData")
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: throw IllegalStateException("No adaptiveFormats")

            val candidateFormats = mutableListOf<FormatItem>()
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio/")) continue

                var directUrl = f.optString("url", "")
                val cipher = f.optString("signatureCipher", "")
                val itag = f.optString("itag", "251")
                val bitrate = f.optInt("bitrate", 160000)

                if (directUrl.isBlank() && cipher.isNotBlank()) {
                    // Decipher signature using KotlinJsInterp (§A.4)
                    val params = parseQueryString(cipher)
                    val s = params["s"] ?: ""
                    val sp = params["sp"] ?: "sig"
                    val baseUrl = params["url"] ?: ""
                    if (baseUrl.isNotBlank()) {
                        val decipheredSig = KotlinJsInterp.decipherSignature(s, emptyList())
                        directUrl = "$baseUrl&$sp=$decipheredSig"
                    }
                }

                if (directUrl.isNotBlank()) {
                    // Check and transform 'n' parameter if throttled (§A.4)
                    if (directUrl.contains("&n=")) {
                        val nMatch = Regex("&n=([^&]+)").find(directUrl)
                        if (nMatch != null) {
                            val originalN = nMatch.groupValues[1]
                            val transformedN = KotlinJsInterp.transformNParam(originalN)
                            directUrl = directUrl.replace("&n=$originalN", "&n=$transformedN")
                        }
                    }

                    val codec = if (mime.contains("opus")) "opus" else "aac"
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

            if (candidateFormats.isEmpty()) {
                throw IllegalStateException("No usable audio streams found")
            }

            candidateFormats.sortByDescending { it.bitrate }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", "Track") ?: "Track"
            val artist = videoDetails?.optString("author", "Artist") ?: "Artist"
            val durationSec = videoDetails?.optLong("lengthSeconds", 210L) ?: 210L

            ResolveResult(
                videoId = req.videoId,
                resolveId = "device_${req.videoId}",
                expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                durationMs = durationSec * 1000L,
                title = title,
                artist = artist,
                artUrl = "",
                formats = candidateFormats,
                resolverId = id,
                packVersion = "device-v2026.10"
            )
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf("=")
            if (idx > 0) {
                val key = part.substring(0, idx)
                val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }
}
