package com.example.resolver

import android.util.Log
import com.example.network.NetworkClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Zone 3 Community Resolvers: Federated Invidious and Piped fallback resolvers (§A.6).
 */

class InvidiousResolver(
    private val candidateNodes: List<String> = listOf(
        "https://invidious.f5.si",
        "https://inv.nadeko.net",
        "https://invidious.privacydev.net",
        "https://yewtu.be",
        "https://invidious.projectsegfau.lt"
    )
) : Resolver {

    override val id: String = "resolver_invidious"
    override val priority: Int = 3
    override val capabilities: Set<Capability> = setOf(Capability.SIGNATURE)
    override val trust: TrustZone = TrustZone.ZONE_3_COMMUNITY_RESOLVER

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (node in candidateNodes) {
            try {
                val url = "$node/api/v1/videos/${req.videoId}?fields=adaptiveFormats,title,author,videoThumbnails,lengthSeconds"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val json = JSONObject(bodyStr)
                        val formats = json.optJSONArray("adaptiveFormats")
                        if (formats != null && formats.length() > 0) {
                            val items = mutableListOf<FormatItem>()
                            for (i in 0 until formats.length()) {
                                val f = formats.getJSONObject(i)
                                val type = f.optString("type", "")
                                val sUrl = f.optString("url", "")
                                val bitrate = f.optInt("bitrate", 0)
                                val itag = f.optString("itag", "251")

                                if (type.contains("audio") && sUrl.isNotBlank()) {
                                    val codec = if (type.contains("opus")) "opus" else "aac"
                                    items.add(
                                        FormatItem(
                                            id = itag,
                                            codec = codec,
                                            container = if (codec == "opus") "webm" else "m4a",
                                            bitrate = bitrate,
                                            url = sUrl
                                        )
                                    )
                                }
                            }

                            if (items.isNotEmpty()) {
                                // Prioritize AAC / MP4 (itag 140) for native Android MediaPlayer compatibility
                                // to eliminate "wrong config length" MediaCodec errors
                                items.sortWith(
                                    compareByDescending<FormatItem> { it.codec == "aac" || it.id == "140" || it.container == "m4a" }
                                        .thenByDescending { it.bitrate }
                                )
                                return@withContext ResolveResult(
                                    videoId = req.videoId,
                                    resolveId = "inv_${req.videoId}",
                                    expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                                    durationMs = json.optLong("lengthSeconds", 210L) * 1000L,
                                    title = json.optString("title", "Track"),
                                    artist = json.optString("author", "Artist"),
                                    artUrl = "",
                                    formats = items,
                                    resolverId = "$id ($node)",
                                    packVersion = "community-invidious"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("All Invidious nodes failed for ${req.videoId}")
    }
}

class PipedResolver(
    private val candidateNodes: List<String> = listOf(
        "https://pipedapi.tokhmi.xyz",
        "https://api.piped.yt",
        "https://pipedapi.kavin.rocks"
    )
) : Resolver {

    override val id: String = "resolver_piped"
    override val priority: Int = 4
    override val capabilities: Set<Capability> = setOf(Capability.SIGNATURE)
    override val trust: TrustZone = TrustZone.ZONE_3_COMMUNITY_RESOLVER

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (node in candidateNodes) {
            try {
                val url = "$node/streams/${req.videoId}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val json = JSONObject(bodyStr)
                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null && audioStreams.length() > 0) {
                            val items = mutableListOf<FormatItem>()
                            for (i in 0 until audioStreams.length()) {
                                val s = audioStreams.getJSONObject(i)
                                val sUrl = s.optString("url")
                                val bitrate = s.optInt("bitrate", 0)
                                val codec = s.optString("codec", "opus")
                                val mimeType = s.optString("mimeType", "")

                                if (sUrl.isNotBlank() && (mimeType.contains("audio") || codec.contains("opus") || codec.contains("mp4a"))) {
                                    items.add(
                                        FormatItem(
                                            id = s.optString("itag", "251"),
                                            codec = codec,
                                            container = if (codec.contains("opus")) "webm" else "m4a",
                                            bitrate = bitrate,
                                            url = sUrl
                                        )
                                    )
                                }
                            }

                            if (items.isNotEmpty()) {
                                items.sortWith(
                                    compareByDescending<FormatItem> { it.codec.contains("aac") || it.codec.contains("mp4a") || it.container == "m4a" }
                                        .thenByDescending { it.bitrate }
                                )
                                return@withContext ResolveResult(
                                    videoId = req.videoId,
                                    resolveId = "piped_${req.videoId}",
                                    expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                                    durationMs = json.optLong("duration", 210L) * 1000L,
                                    title = json.optString("title", "Track"),
                                    artist = json.optString("uploader", "Artist"),
                                    artUrl = "",
                                    formats = items,
                                    resolverId = "$id ($node)",
                                    packVersion = "community-piped"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("All Piped nodes failed for ${req.videoId}")
    }
}
