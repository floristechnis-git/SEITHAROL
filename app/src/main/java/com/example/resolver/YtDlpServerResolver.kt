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
 * Zone 2 Own Resolver: Connects to a self-hosted FastAPI + yt-dlp resolver node (§A.6, §H.4).
 * Implements GET /healthz and POST /resolve.
 */
class YtDlpServerResolver(
    var serverBaseUrl: String = "http://10.0.2.2:8000" // Android emulator loopback to host or custom node
) : Resolver {

    override val id: String = "resolver_ytdlp_fastapi"
    override val priority: Int = 2
    override val capabilities: Set<Capability> = setOf(
        Capability.SIGNATURE,
        Capability.THROTTLE,
        Capability.PO_TOKEN,
        Capability.PODCAST
    )
    override val trust: TrustZone = TrustZone.ZONE_2_OWN_RESOLVER

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$serverBaseUrl/healthz"
            val request = Request.Builder().url(url).build()
            NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/v1/resolve"
        val payload = JSONObject().apply {
            put("videoId", req.videoId)
            put("kind", req.kind)
            put("accountMode", req.accountMode)
            put("formatPolicy", JSONObject().apply {
                put("maxBitrate", req.formatPolicy.maxBitrate)
                put("preferContainer", req.formatPolicy.preferContainer)
                put("allowProgressive", req.formatPolicy.allowProgressive)
            })
            if (!req.visitorData.isNullOrBlank()) put("visitorData", req.visitorData)
            if (!req.poToken.isNullOrBlank()) put("poToken", req.poToken)
            put("clientHint", req.clientHint)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        NetworkClients.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("yt-dlp server returned HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IllegalStateException("Empty response from yt-dlp node")
            val json = JSONObject(bodyStr)

            val formatsArray = json.optJSONArray("formats")
            val formatItems = mutableListOf<FormatItem>()
            if (formatsArray != null) {
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.getJSONObject(i)
                    formatItems.add(
                        FormatItem(
                            id = f.optString("id", "251"),
                            codec = f.optString("codec", "opus"),
                            container = f.optString("container", "webm"),
                            bitrate = f.optInt("bitrate", 160000),
                            sampleRate = f.optInt("sampleRate", 48000),
                            channels = f.optInt("channels", 2),
                            byteLength = f.optLong("byteLength", 0L),
                            url = f.optString("url", ""),
                            rangeSupported = f.optBoolean("rangeSupported", true)
                        )
                    )
                }
            }

            if (formatItems.isEmpty()) {
                throw IllegalStateException("No formats found in yt-dlp response")
            }

            ResolveResult(
                videoId = json.optString("videoId", req.videoId),
                resolveId = json.optString("resolveId", "rs_${req.videoId}"),
                expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 6 * 3600 * 1000L),
                durationMs = json.optLong("durationMs", 210000L),
                title = json.optString("title", "Track"),
                artist = json.optString("artist", "Artist"),
                artUrl = json.optString("artUrl", ""),
                formats = formatItems,
                resolverId = id,
                packVersion = json.optString("packVersion", "2026.10.14-1")
            )
        }
    }
}
