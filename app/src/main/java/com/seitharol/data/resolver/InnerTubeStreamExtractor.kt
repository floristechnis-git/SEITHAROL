package com.seitharol.data.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class AudioQuality(val label: String, val maxBitrateKbps: Int) {
    LOW("Low (96 kbps)", 96),
    NORMAL("Normal (160 kbps)", 160),
    HIGH("High (320 kbps)", 320)
}

data class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val clientId: String
)

object InnerTubeStreamExtractor {

    val FALLBACK_CLIENTS = listOf(
        InnerTubeClientConfig(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            clientId = "5"
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28"
        ),
        InnerTubeClientConfig(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Safari/605.1.15",
            clientId = "85"
        ),
        InnerTubeClientConfig(
            clientName = "WEB_REMIX",
            clientVersion = "1.20250101.01.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            clientId = "67"
        )
    )

    /**
     * Cycles through valid InnerTube clients to extract a working audio stream URL,
     * preventing infinite retry loops on 403/expired URLs.
     */
    suspend fun resolveWorkingStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (client in FALLBACK_CLIENTS) {
            try {
                val url = extractStreamFromClient(videoId, client, preferredQuality)
                if (!url.isNullOrBlank()) {
                    return@withContext Result.success(url)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(lastError ?: IllegalStateException("Unable to resolve stream for $videoId"))
    }

    private fun extractStreamFromClient(
        videoId: String,
        client: InnerTubeClientConfig,
        quality: AudioQuality
    ): String? {
        val endpoint = URL("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("X-YouTube-Client-Name", client.clientId)
            setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }

        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode != 200) return null

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val playability = json.optJSONObject("playabilityStatus")?.optString("status")
        if (playability != "OK") return null

        val adaptiveFormats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: return null

        val audioStreams = mutableListOf<Pair<Int, String>>()
        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.getJSONObject(i)
            val mimeType = fmt.optString("mimeType", "")
            val url = fmt.optString("url", "")
            if (mimeType.startsWith("audio/") && url.isNotBlank()) {
                val bitrate = fmt.optInt("bitrate", 128000) / 1000
                audioStreams.add(bitrate to url)
            }
        }

        if (audioStreams.isEmpty()) return null

        // Select bitrate closest to user's persisted AudioQuality preference
        return audioStreams
            .sortedBy { kotlin.math.abs(it.first - quality.maxBitrateKbps) }
            .first().second
    }

    /**
     * Fetches real YouTube Music Automix / Radio tracks (RDAMVM{videoId}) for "Up Next"
     * and real Home Screen mixes.
     */
    suspend fun fetchAutomixQueue(seedVideoId: String): List<CandidateStream> = withContext(Dispatchers.IO) {
        val playlistId = "RDAMVM$seedVideoId"
        val endpoint = URL("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        val payload = JSONObject().apply {
            put("videoId", seedVideoId)
            put("playlistId", playlistId)
            put("isAudioOnly", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250101.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }

        runCatching {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return@withContext emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            parseAutomixPlaylistPanel(root, seedVideoId)
        }.getOrDefault(emptyList())
    }

    private fun parseAutomixPlaylistPanel(root: JSONObject, seedVideoId: String): List<CandidateStream> {
        val results = mutableListOf<CandidateStream>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: JSONArray()

        for (t in 0 until tabs.length()) {
            val contents = tabs.getJSONObject(t)
                .optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents") ?: continue

            for (i in 0 until contents.length()) {
                val renderer = contents.getJSONObject(i)
                    .optJSONObject("playlistPanelVideoRenderer") ?: continue
                val vid = renderer.optString("videoId")
                if (vid.isBlank() || vid == seedVideoId) continue

                val title = renderer.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: continue
                val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
                val artist = bylineRuns?.optJSONObject(0)?.optString("text") ?: "Unknown Artist"
                val artistBrowseId = bylineRuns?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                val durationText = renderer.optJSONObject("lengthText")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "0:00"
                val thumb = renderer.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")?.let { arr ->
                        arr.optJSONObject(arr.length() - 1)?.optString("url")
                    } ?: "https://i.ytimg.com/vi/$vid/hqdefault.jpg"

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

    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }
}
