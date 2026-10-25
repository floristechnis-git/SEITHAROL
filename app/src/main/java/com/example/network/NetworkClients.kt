package com.example.network

import android.util.Log
import com.example.data.model.LyricsLine
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object NetworkClients {
    private const val TAG = "NetworkClients"

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // InnerTube Web Remix client context (Invariant I1, §D.1)
    private val INNER_TUBE_CONTEXT = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20240101.01.00")
            put("hl", "en")
            put("gl", "US")
        })
    }

    // Song filter base64 protobuf from ytmusicapi (§D.1 Search filters)
    private const val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ=="

    // Invidious / Piped federation nodes for stream resolution fallback (§A.6)
    private val INVIDIOUS_NODES = listOf(
        "https://invidious.f5.si",
        "https://inv.nadeko.net",
        "https://invidious.privacydev.net",
        "https://yewtu.be",
        "https://invidious.projectsegfau.lt"
    )

    private val PIPED_NODES = listOf(
        "https://pipedapi.tokhmi.xyz",
        "https://api.piped.yt",
        "https://pipedapi.kavin.rocks"
    )

    /**
     * Resolves audio stream using multi-tier resolver federation (Invidious -> Piped -> Direct audio fallback).
     * Selects adaptive Opus / AAC streams with highest bitrate.
     */
    suspend fun resolveStream(videoId: String, preferredNodeUrl: String? = null): ResolvedStreamInfo =
        withContext(Dispatchers.IO) {
            // 1. Try Invidious nodes
            val targetNodes = if (!preferredNodeUrl.isNullOrBlank() && preferredNodeUrl.contains("invidious")) {
                listOf(preferredNodeUrl) + INVIDIOUS_NODES
            } else {
                INVIDIOUS_NODES
            }

            for (node in targetNodes) {
                try {
                    val url = "$node/api/v1/videos/$videoId?fields=adaptiveFormats,title"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: ""
                            val json = JSONObject(bodyStr)
                            val formats = json.optJSONArray("adaptiveFormats")
                            if (formats != null && formats.length() > 0) {
                                var bestAacUrl: String? = null
                                var bestAacBitrate = 0
                                var bestOpusUrl: String? = null
                                var bestOpusBitrate = 0

                                for (i in 0 until formats.length()) {
                                    val f = formats.getJSONObject(i)
                                    val type = f.optString("type", "")
                                    val streamUrl = f.optString("url", "")
                                    val bitrate = f.optInt("bitrate", 0)
                                    val itag = f.optString("itag", "")

                                    if (type.contains("audio") && streamUrl.isNotBlank()) {
                                        val isAac = type.contains("mp4") || type.contains("aac") || itag == "140"
                                        if (isAac) {
                                            if (bestAacUrl == null || bitrate > bestAacBitrate) {
                                                bestAacUrl = streamUrl
                                                bestAacBitrate = bitrate
                                            }
                                        } else {
                                            if (bestOpusUrl == null || bitrate > bestOpusBitrate) {
                                                bestOpusUrl = streamUrl
                                                bestOpusBitrate = bitrate
                                            }
                                        }
                                    }
                                }

                                // Prioritize AAC (mp4a / itag 140) to prevent Android MediaCodec "wrong config length"
                                val finalUrl = bestAacUrl ?: bestOpusUrl
                                val finalCodec = if (bestAacUrl != null) "AAC (Native)" else "Opus"
                                val finalBitrate = if (bestAacUrl != null) bestAacBitrate else bestOpusBitrate

                                if (finalUrl != null) {
                                    return@withContext ResolvedStreamInfo(
                                        streamUrl = finalUrl,
                                        codec = finalCodec,
                                        bitrate = "${if (finalBitrate > 0) finalBitrate / 1000 else 128} kbps",
                                        expiresAtMs = System.currentTimeMillis() + (6 * 3600 * 1000L),
                                        nodeUsed = node
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Invidious node $node failed: ${e.message}")
                }
            }

            // 2. Try Piped nodes
            for (node in PIPED_NODES) {
                try {
                    val url = "$node/streams/$videoId"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: ""
                            val json = JSONObject(bodyStr)
                            val audioStreams = json.optJSONArray("audioStreams")
                            if (audioStreams != null && audioStreams.length() > 0) {
                                var bestUrl: String? = null
                                var bestBitrate = 0
                                var bestCodec = "AAC"

                                for (i in 0 until audioStreams.length()) {
                                    val s = audioStreams.getJSONObject(i)
                                    val sUrl = s.optString("url")
                                    val bitrate = s.optInt("bitrate", 0)
                                    val codec = s.optString("codec", "")
                                    val mimeType = s.optString("mimeType", "")

                                    if (sUrl.isNotBlank() && (mimeType.contains("audio") || codec.contains("opus") || codec.contains("mp4a"))) {
                                        val isAac = mimeType.contains("mp4") || codec.contains("mp4a") || codec.contains("aac")
                                        if (isAac && (bestUrl == null || bitrate > bestBitrate)) {
                                            bestUrl = sUrl
                                            bestBitrate = bitrate
                                            bestCodec = "AAC (Native)"
                                        } else if (bestUrl == null || bitrate > bestBitrate) {
                                            bestUrl = sUrl
                                            bestBitrate = bitrate
                                            bestCodec = codec
                                        }
                                    }
                                }

                                if (bestUrl != null) {
                                    return@withContext ResolvedStreamInfo(
                                        streamUrl = bestUrl,
                                        codec = bestCodec,
                                        bitrate = "${if (bestBitrate > 0) bestBitrate / 1000 else 128} kbps",
                                        expiresAtMs = System.currentTimeMillis() + (6 * 3600 * 1000L),
                                        nodeUsed = node
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Piped node $node failed: ${e.message}")
                }
            }

            throw IllegalStateException("Stream unavailable for track $videoId. Tap to retry resolution.")
        }

    /**
     * Performs live YouTube Music search via InnerTube API (§D.1 InnerTube Protocol).
     * Extracts real track titles, artists, albums, duration, and high-res thumbnails.
     */
    suspend fun searchInnerTube(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.trim().isBlank()) return@withContext emptyList()
        try {
            val url = "https://music.youtube.com/youtubei/v1/search"
            val payload = JSONObject().apply {
                put("context", INNER_TUBE_CONTEXT)
                put("query", query.trim())
                put("params", SEARCH_PARAMS_SONGS)
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                parseInnerTubeSongs(json, defaultGenre = "Online")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchInnerTube: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches real personalized songs for Home screen based on Mood or Category (§D.1 / §E).
     */
    suspend fun fetchCategoryTracks(moodCategory: String): List<Track> = withContext(Dispatchers.IO) {
        val query = when (moodCategory.lowercase()) {
            "workout", "energize" -> "Workout Energy Motivation Hits"
            "relax", "chill" -> "Acoustic Chill Vibes Relaxing"
            "focus" -> "Lofi Focus Deep Study Beats"
            "party" -> "Party Dance Pop Club Hits"
            "romance" -> "Romantic Love Acoustic Songs"
            "rock" -> "Top Rock Modern Classics"
            "folk" -> "Indian Folk Acoustic Melodies"
            "trending" -> "Top Hits Trending Global Songs"
            else -> "Top Global Songs 2024"
        }
        searchInnerTube(query)
    }

    /**
     * Fetches search suggestions for typeahead (§D.1 /youtubei/v1/music/get_search_suggestions)
     */
    suspend fun getSearchSuggestions(input: String): List<String> = withContext(Dispatchers.IO) {
        if (input.trim().length < 2) return@withContext emptyList()
        try {
            val url = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions"
            val payload = JSONObject().apply {
                put("context", INNER_TUBE_CONTEXT)
                put("input", input.trim())
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val suggestions = mutableListOf<String>()

                val contents = json.optJSONArray("contents")
                if (contents != null) {
                    for (i in 0 until contents.length()) {
                        val section = contents.getJSONObject(i).optJSONObject("searchSuggestionsSectionRenderer")
                        val items = section?.optJSONArray("contents") ?: continue
                        for (j in 0 until items.length()) {
                            val item = items.getJSONObject(j).optJSONObject("searchSuggestionRenderer") ?: continue
                            val suggestionRuns = item.optJSONObject("suggestion")?.optJSONArray("runs") ?: continue
                            val sb = StringBuilder()
                            for (k in 0 until suggestionRuns.length()) {
                                sb.append(suggestionRuns.getJSONObject(k).optString("text", ""))
                            }
                            if (sb.isNotBlank()) suggestions.add(sb.toString())
                        }
                    }
                }
                suggestions.take(8)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Robust recursive parser for YouTube Music InnerTube responses.
     */
    private fun parseInnerTubeSongs(json: JSONObject, defaultGenre: String): List<Track> {
        val renderers = mutableListOf<JSONObject>()
        findItemRenderers(json, renderers)

        val tracks = mutableListOf<Track>()
        for (item in renderers) {
            // 1. VideoId
            var videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            if (videoId.isNullOrBlank()) {
                val overlay = item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                videoId = overlay?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
            }

            // 2. Title from flexColumns[0]
            var title = "Unknown Title"
            val flexCols = item.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() > 0) {
                val col0Runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (col0Runs != null && col0Runs.length() > 0) {
                    title = col0Runs.getJSONObject(0).optString("text", "Unknown Title")
                }
            }

            // 3. Artist, Album, and Duration from flexColumns[1]
            var artist = "Various Artists"
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
                        artist = texts[0]
                    }
                    if (texts.size >= 2) {
                        val last = texts.last()
                        if (last.contains(":")) {
                            durationMs = parseDurationMs(last)
                            if (texts.size >= 3) {
                                album = texts[1]
                            }
                        } else {
                            album = texts[1]
                        }
                    }
                }
            }

            // 4. High-Res Thumbnail
            var artUrl = if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
            val thumbObj = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1).optString("url")
                if (lastThumb.isNotBlank()) {
                    artUrl = lastThumb
                }
            }

            if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                val moodX = ((Math.abs(videoId.hashCode()) % 100) / 100f).coerceIn(0.2f, 0.9f)
                val moodY = (((Math.abs(title.hashCode()) % 100)) / 100f).coerceIn(0.2f, 0.9f)

                tracks.add(
                    Track(
                        id = videoId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        artUrl = artUrl,
                        isDownloaded = false,
                        isLiked = false,
                        lyrics = "",
                        genre = defaultGenre,
                        moodX = moodX,
                        moodY = moodY,
                        audioUrl = "",
                        itag = 251,
                        codec = "Opus 160k"
                    )
                )
            }
        }
        return tracks
    }

    private fun findItemRenderers(obj: Any, outList: MutableList<JSONObject>) {
        when (obj) {
            is JSONObject -> {
                val optItem = obj.optJSONObject("musicResponsiveListItemRenderer")
                if (optItem != null) {
                    outList.add(optItem)
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = obj.opt(key)
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

    /**
     * Fetches real synchronized lyrics from LRCLIB (§E.3).
     */
    suspend fun fetchLrclibLyrics(artist: String, title: String, durationSec: Long): List<LyricsLine> =
        withContext(Dispatchers.IO) {
            try {
                val cleanTitle = cleanTrackTitle(title)
                val cleanArtist = artist.trim()
                val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
                val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")

                val url = "https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle&duration=$durationSec"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Seitharol-Client/2.0.1 (Android)")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext searchLrclibFallback(cleanArtist, cleanTitle)
                    }
                    val bodyStr = response.body?.string() ?: return@withContext emptyList()
                    val json = JSONObject(bodyStr)
                    val syncedLyrics = json.optString("syncedLyrics", "")
                    if (syncedLyrics.isNotBlank()) {
                        parseLrc(syncedLyrics)
                    } else {
                        val plainLyrics = json.optString("plainLyrics", "")
                        if (plainLyrics.isNotBlank()) {
                            plainLyrics.lines().mapIndexed { idx, line ->
                                LyricsLine(timestampMs = idx * 4000L, text = line)
                            }
                        } else emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching LRCLIB lyrics: ${e.message}")
                emptyList()
            }
        }

    private fun searchLrclibFallback(artist: String, title: String): List<LyricsLine> {
        return try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val url = "https://lrclib.net/api/search?q=$query"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Seitharol-Client/2.0.1 (Android)")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val array = JSONArray(bodyStr)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    val synced = first.optString("syncedLyrics", "")
                    if (synced.isNotBlank()) parseLrc(synced) else emptyList()
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parses synchronized LRC text format [mm:ss.xx] line
     */
    fun parseLrc(lrcText: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{2,3}))?](.*)""")

        for (rawLine in lrcText.lines()) {
            val match = regex.find(rawLine.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3]
                val ms = when (msStr.length) {
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (min * 60 * 1000L) + (sec * 1000L) + ms
                val sanitized = match.groupValues[4].replace("{", "").replace("}", "").trim()
                if (sanitized.isNotBlank()) {
                    val parts = sanitized.split("^")
                    val primary = parts[0].trim()
                    val translation = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
                    if (primary.isNotBlank()) {
                        lines.add(LyricsLine(timestampMs = totalMs, text = primary, translation = translation))
                    }
                }
            }
        }
        return lines.sortedBy { it.timestampMs }
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw.replace(Regex("""(?i)\(official.*?\)|\[official.*?]|feat\..*|ft\..*"""), "").trim()
    }
}

data class ResolvedStreamInfo(
    val streamUrl: String,
    val codec: String,
    val bitrate: String,
    val expiresAtMs: Long,
    val nodeUsed: String = "Federated Node"
)
