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
import kotlin.math.abs

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
     * Fetches real songs for Home screen based on Mood or Category (§D.1 / §E).
     */
    suspend fun fetchCategoryTracks(moodCategory: String): List<Track> = withContext(Dispatchers.IO) {
        val query = when (moodCategory.lowercase()) {
            "workout", "energize" -> "Workout Energy Motivation Hits"
            "relax", "chill" -> "Acoustic Chill Vibes Relaxing"
            "focus" -> "Lofi Focus Deep Study Beats"
            "party" -> "Party Dance Pop Club Hits"
            "romance", "romantic" -> "Romantic Love Acoustic Songs"
            "rock" -> "Top Rock Modern Classics"
            "folk", "manipuri song" -> "Manipuri Official Hit Songs"
            "trending" -> "Top Hits Trending Global & Regional Songs"
            else -> "Top Trending Hit Songs"
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

    private val syncedLyricsCache = java.util.concurrent.ConcurrentHashMap<String, List<LyricsLine>>()

    data class ImportedPlaylistData(
        val title: String,
        val coverUrl: String?,
        val tracks: List<Pair<String, String>> // (title, artist)
    )

    /**
     * Multi-source synchronized lyrics resolver with best-sync duration scoring:
     * 1. LRCLIB Exact `/api/get` (only passing duration when known and != 210s placeholder, plus durationless retry)
     * 2. LRCLIB Structured `/api/search?track_name=...&artist_name=...` & Fuzzy `/api/search?q=...`
     * 3. NetEase Cloud Music Synced LRC (`music.163.com/api/search/get` + `/api/song/lyric` with translation LRC merge)
     * 4. KuGou Synced LRC (`lyrics.kugou.com/search` + `/download` Base64 LRC decode)
     * 5. YouTube Timed Captions (`captionTracks` `fmt=json3`) & YouTube Music `MPLYt_` lyrics
     * 6. Plain lyrics proportional timestamp fallback
     */
    suspend fun fetchLrclibLyrics(
        artist: String,
        title: String,
        durationSec: Long,
        videoId: String = ""
    ): List<LyricsLine> = withContext(Dispatchers.IO) {
        try {
            val effectiveDurSec = if (durationSec > 0L && durationSec != 210L) durationSec else 0L
            val candidatePairs = extractCandidateArtistTitlePairs(artist, title)
            val primaryPair = candidatePairs.firstOrNull() ?: Pair(cleanArtistName(artist), cleanTrackTitle(title))
            val cacheKey = "${primaryPair.first.lowercase()}|${primaryPair.second.lowercase()}|$videoId"
            syncedLyricsCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

            var fallbackPlainLyrics: String? = null

            // Tier 1 & 2: LRCLIB Exact + Structured + Query Search across cleaned (artist, title) pairs
            for ((candArtist, candTitle) in candidatePairs) {
                if (candTitle.isBlank()) continue
                val (syncedFromExact, plainFromExact) = tryLrclibExact(candArtist, candTitle, effectiveDurSec)
                if (syncedFromExact.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = syncedFromExact
                    return@withContext syncedFromExact
                }
                if (fallbackPlainLyrics == null && !plainFromExact.isNullOrBlank()) {
                    fallbackPlainLyrics = plainFromExact
                }

                val (syncedFromSearch, plainFromSearch) = searchLrclibMultiStrategy(candArtist, candTitle, effectiveDurSec)
                if (syncedFromSearch.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = syncedFromSearch
                    return@withContext syncedFromSearch
                }
                if (fallbackPlainLyrics == null && !plainFromSearch.isNullOrBlank()) {
                    fallbackPlainLyrics = plainFromSearch
                }
            }

            // Tier 3: NetEase Cloud Music Synced LRC (+ Translation LRC)
            for ((candArtist, candTitle) in candidatePairs) {
                if (candTitle.isBlank()) continue
                val netEaseLines = fetchNetEaseSyncedLyrics(candArtist, candTitle, effectiveDurSec)
                if (netEaseLines.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = netEaseLines
                    return@withContext netEaseLines
                }
            }

            // Tier 4: KuGou Synced LRC
            for ((candArtist, candTitle) in candidatePairs) {
                if (candTitle.isBlank()) continue
                val kugouLines = fetchKugouSyncedLyrics(candArtist, candTitle, effectiveDurSec)
                if (kugouLines.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = kugouLines
                    return@withContext kugouLines
                }
            }

            // Tier 5: YouTube Timed Captions (json3) / YouTube Music Lyrics
            val ytLines = fetchYoutubeTimedCaptionsOrYtmLyrics(videoId, primaryPair.first, primaryPair.second, effectiveDurSec.takeIf { it > 0L } ?: durationSec)
            if (ytLines.isNotEmpty()) {
                syncedLyricsCache[cacheKey] = ytLines
                return@withContext ytLines
            }

            // Tier 6: Plain lyrics proportional distribution fallback
            if (!fallbackPlainLyrics.isNullOrBlank()) {
                val plainLines = buildProportionalLyrics(fallbackPlainLyrics!!, effectiveDurSec.takeIf { it > 0L } ?: durationSec)
                if (plainLines.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = plainLines
                    return@withContext plainLines
                }
            }

            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching multi-source lyrics: ${e.message}")
            emptyList()
        }
    }

    private fun extractCandidateArtistTitlePairs(rawArtist: String, rawTitle: String): List<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        val cleanedArtist = cleanArtistName(rawArtist)
        val cleanedTitle = cleanTrackTitle(rawTitle)

        // If title contains "Artist - Song Title" (common on YouTube uploads)
        val dashSplit = rawTitle.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        if (dashSplit.size == 2) {
            val left = cleanArtistName(dashSplit[0])
            val right = cleanTrackTitle(dashSplit[1])
            if (left.isNotBlank() && right.isNotBlank()) {
                if (cleanedArtist.isBlank() ||
                    cleanedArtist.equals("Unknown Artist", true) ||
                    cleanedArtist.contains(left, true) ||
                    left.contains(cleanedArtist, true)
                ) {
                    pairs.add(Pair(left, right))
                    pairs.add(Pair(cleanedArtist, right))
                } else {
                    pairs.add(Pair(cleanedArtist, cleanedTitle))
                    pairs.add(Pair(left, right))
                }
            }
        }

        pairs.add(Pair(cleanedArtist, cleanedTitle))
        val primaryArtistOnly = cleanedArtist.split(",", "&", "/", "feat.", "ft.").firstOrNull()?.trim().orEmpty()
        if (primaryArtistOnly.isNotBlank() && primaryArtistOnly != cleanedArtist) {
            pairs.add(Pair(primaryArtistOnly, cleanedTitle))
        }
        val strippedSubTitle = cleanedTitle.substringBefore(" - ").substringBefore(" (").trim()
        if (strippedSubTitle.isNotBlank() && strippedSubTitle != cleanedTitle) {
            pairs.add(Pair(primaryArtistOnly.ifBlank { cleanedArtist }, strippedSubTitle))
        }
        return pairs.toList()
    }

    private fun tryLrclibExact(artist: String, title: String, durationSec: Long): Pair<List<LyricsLine>, String?> {
        if (artist.isBlank() || title.isBlank()) return Pair(emptyList(), null)
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val urls = buildList {
            if (durationSec > 0L) {
                add("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle&duration=$durationSec")
            }
            add("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle")
        }
        var bestPlain: String? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Seitharol-Client/2.2.0 (Android)")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string().orEmpty()
                        if (bodyStr.isNotBlank()) {
                            val json = JSONObject(bodyStr)
                            val synced = json.optString("syncedLyrics", "")
                            if (synced.isNotBlank()) {
                                val parsed = parseLrc(synced)
                                if (parsed.size >= 3) return Pair(parsed, null)
                            }
                            val plain = json.optString("plainLyrics", "")
                            if (bestPlain == null && plain.isNotBlank()) {
                                bestPlain = plain
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return Pair(emptyList(), bestPlain)
    }

    private fun searchLrclibMultiStrategy(artist: String, title: String, durationSec: Long): Pair<List<LyricsLine>, String?> {
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val urls = buildList {
            if (artist.isNotBlank() && title.isNotBlank()) {
                add("https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist")
            }
            val combinedQ = "$artist $title".trim()
            if (combinedQ.isNotBlank()) {
                add("https://lrclib.net/api/search?q=${URLEncoder.encode(combinedQ, "UTF-8")}")
            }
            if (title.isNotBlank()) {
                add("https://lrclib.net/api/search?q=$encTitle")
            }
        }.distinct()

        var bestPlain: String? = null
        val artistLower = artist.lowercase()
        val titleLower = title.lowercase()

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Seitharol-Client/2.2.0 (Android)")
                    .build()
                val foundSynced = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<LyricsLine>()
                    val bodyStr = response.body?.string() ?: return@use emptyList<LyricsLine>()
                    val array = JSONArray(bodyStr)
                    if (array.length() == 0) return@use emptyList<LyricsLine>()

                    var bestSyncedText: String? = null
                    var bestScore = Long.MAX_VALUE

                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val synced = item.optString("syncedLyrics", "")
                        val plain = item.optString("plainLyrics", "")
                        val itemTrack = item.optString("trackName", "").lowercase()
                        val itemArtist = item.optString("artistName", "").lowercase()
                        val itemDur = item.optDouble("duration", 0.0).toLong()

                        val durPenalty = if (durationSec > 0L && itemDur > 0L) {
                            abs(itemDur - durationSec) * 3L
                        } else {
                            i.toLong() * 6L
                        }
                        val artistPenalty = if (artistLower.isNotBlank() && !itemArtist.contains(artistLower) && !artistLower.contains(itemArtist)) 25L else 0L
                        val titlePenalty = if (titleLower.isNotBlank() && !itemTrack.contains(titleLower) && !titleLower.contains(itemTrack)) 20L else 0L
                        val totalPenalty = durPenalty + artistPenalty + titlePenalty

                        if (synced.isNotBlank() && totalPenalty < bestScore) {
                            bestScore = totalPenalty
                            bestSyncedText = synced
                        }
                        if (bestPlain == null && plain.isNotBlank()) {
                            bestPlain = plain
                        }
                    }

                    if (!bestSyncedText.isNullOrBlank()) {
                        parseLrc(bestSyncedText!!)
                    } else emptyList()
                }
                if (foundSynced.size >= 3) return Pair(foundSynced, bestPlain)
            } catch (_: Exception) {}
        }
        return Pair(emptyList(), bestPlain)
    }

    private fun fetchNetEaseSyncedLyrics(artist: String, title: String, durationSec: Long): List<LyricsLine> {
        return try {
            val query = "$title $artist".trim()
            val searchUrl = "https://music.163.com/api/search/get"
            val formBody = "s=${URLEncoder.encode(query, "UTF-8")}&type=1&offset=0&total=true&limit=6"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val searchReq = Request.Builder()
                .url(searchUrl)
                .post(formBody)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val songIds = okHttpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Long>()
                val body = resp.body?.string() ?: return@use emptyList<Long>()
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return@use emptyList<Long>()
                val candidates = mutableListOf<Pair<Long, Long>>() // (songId, penalty)
                for (i in 0 until songs.length()) {
                    val s = songs.optJSONObject(i) ?: continue
                    val id = s.optLong("id", 0L)
                    if (id <= 0L) continue
                    val durSec = s.optLong("duration", 0L) / 1000L
                    val penalty = if (durationSec > 0L && durSec > 0L) abs(durSec - durationSec) else i.toLong() * 5L
                    candidates.add(Pair(id, penalty))
                }
                candidates.sortedBy { it.second }.take(3).map { it.first }
            }

            for (songId in songIds) {
                val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                val lyricReq = Request.Builder()
                    .url(lyricUrl)
                    .header("Referer", "https://music.163.com")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val parsed = okHttpClient.newCall(lyricReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<LyricsLine>()
                    val body = resp.body?.string() ?: return@use emptyList<LyricsLine>()
                    val json = JSONObject(body)
                    val lrc = json.optJSONObject("lrc")?.optString("lyric", "").orEmpty()
                    val tlyric = json.optJSONObject("tlyric")?.optString("lyric", "").orEmpty()
                    if (lrc.isNotBlank()) {
                        val primaryLines = parseLrc(lrc)
                        if (tlyric.isNotBlank() && primaryLines.isNotEmpty()) {
                            val transLines = parseLrc(tlyric).filter { !it.isInstrumental && it.text.isNotBlank() }
                            primaryLines.map { line ->
                                if (line.isInstrumental || line.text.isBlank()) line
                                else {
                                    val matchingTrans = transLines.minByOrNull { abs(it.timestampMs - line.timestampMs) }
                                        ?.takeIf { abs(it.timestampMs - line.timestampMs) <= 1500L }
                                        ?.text
                                    if (!matchingTrans.isNullOrBlank() && !matchingTrans.equals(line.text, true)) {
                                        line.copy(translation = matchingTrans)
                                    } else line
                                }
                            }
                        } else primaryLines
                    } else emptyList()
                }
                if (parsed.size >= 3) return parsed
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchKugouSyncedLyrics(artist: String, title: String, durationSec: Long): List<LyricsLine> {
        return try {
            val keyword = URLEncoder.encode("$artist - $title".trim(' ', '-'), "UTF-8")
            val durMs = if (durationSec > 0L) durationSec * 1000L else 210_000L
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword&duration=$durMs&hash="
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                .build()
            val candidate = okHttpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val cands = JSONObject(body).optJSONArray("candidates") ?: return@use null
                if (cands.length() == 0) return@use null
                val first = cands.optJSONObject(0) ?: return@use null
                val id = first.optString("id", "")
                val key = first.optString("accesskey", "")
                if (id.isNotBlank() && key.isNotBlank()) Pair(id, key) else null
            } ?: return emptyList()

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${candidate.first}&accesskey=${candidate.second}&fmt=lrc&charset=utf8"
            val dlReq = Request.Builder()
                .url(dlUrl)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                .build()
            okHttpClient.newCall(dlReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val b64 = JSONObject(body).optString("content", "")
                if (b64.isBlank()) return emptyList()
                val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val parsed = parseLrc(decoded)
                if (parsed.size >= 3) parsed else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchYoutubeTimedCaptionsOrYtmLyrics(
        videoId: String,
        artist: String,
        title: String,
        durationSec: Long
    ): List<LyricsLine> {
        return try {
            val cleanVid = videoId.removePrefix("yt_").trim()
            if (cleanVid.length != 11) return emptyList()

            // 1. Check YouTube player captionTracks (json3 timed subtitles)
            val playerBody = JSONObject().apply {
                put("videoId", cleanVid)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240726.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }
            val playerReq = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(playerBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val captionUrl = okHttpClient.newCall(playerReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val str = resp.body?.string() ?: return@use null
                val tracks = JSONObject(str)
                    .optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks") ?: return@use null
                var chosenUrl: String? = null
                for (i in 0 until tracks.length()) {
                    val tr = tracks.optJSONObject(i) ?: continue
                    val baseUrl = tr.optString("baseUrl", "")
                    val kind = tr.optString("kind", "")
                    if (baseUrl.isNotBlank()) {
                        // Prefer human-synced lyrics captions over ASR auto-captions
                        if (kind != "asr") {
                            chosenUrl = baseUrl
                            break
                        } else if (chosenUrl == null) {
                            chosenUrl = baseUrl
                        }
                    }
                }
                chosenUrl
            }

            if (!captionUrl.isNullOrBlank()) {
                val json3Url = if (captionUrl.contains("fmt=")) {
                    captionUrl.replace(Regex("fmt=[^&]+"), "fmt=json3")
                } else {
                    "$captionUrl&fmt=json3"
                }
                val capReq = Request.Builder().url(json3Url).build()
                val capLines = okHttpClient.newCall(capReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<LyricsLine>()
                    val body = resp.body?.string() ?: return@use emptyList<LyricsLine>()
                    val events = JSONObject(body).optJSONArray("events") ?: return@use emptyList<LyricsLine>()
                    val out = mutableListOf<LyricsLine>()
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val startMs = ev.optLong("tStartMs", -1L)
                        val segs = ev.optJSONArray("segs") ?: continue
                        if (startMs < 0L) continue
                        val sb = StringBuilder()
                        for (j in 0 until segs.length()) {
                            sb.append(segs.optJSONObject(j)?.optString("utf8", "").orEmpty())
                        }
                        val lineText = sb.toString()
                            .replace("
", " ")
                            .replace("♪", "")
                            .replace("♫", "")
                            .trim()
                        if (lineText.isNotBlank() && !lineText.startsWith("[")) {
                            out.add(LyricsLine(timestampMs = startMs, text = lineText))
                        }
                    }
                    out
                }
                if (capLines.size >= 4) return capLines
            }

            // 2. Check YouTube Music next -> MPLYt_ lyrics browse endpoint
            val nextBody = JSONObject().apply {
                put("videoId", cleanVid)
                put("context", INNER_TUBE_CONTEXT)
            }
            val nextReq = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
                .post(nextBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val lyricsBrowseId = okHttpClient.newCall(nextReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val str = resp.body?.string() ?: return@use null
                Regex(""""browseId"\s*:\s*"(MPLYt_[^"]+)"""").find(str)?.groupValues?.getOrNull(1)
            }
            if (!lyricsBrowseId.isNullOrBlank()) {
                val browseBody = JSONObject().apply {
                    put("browseId", lyricsBrowseId)
                    put("context", INNER_TUBE_CONTEXT)
                }
                val browseReq = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                    .post(browseBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(browseReq).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    val str = resp.body?.string() ?: return emptyList()
                    val descMatch = Regex(""""description"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"((?:\.|[^"\])*)"""").find(str)
                    val rawLyrics = descMatch?.groupValues?.getOrNull(1)
                        ?.replace("\n", "
")
                        ?.replace("\r", "")
                        ?.replace("\"", """)
                        .orEmpty()
                    if (rawLyrics.isNotBlank()) {
                        return buildProportionalLyrics(rawLyrics, durationSec)
                    }
                }
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildProportionalLyrics(plainLyrics: String, durationSec: Long): List<LyricsLine> {
        val validLines = plainLyrics.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (validLines.isEmpty()) return emptyList()
        val stepMs = if (durationSec > 0L) {
            ((durationSec * 1000L) / (validLines.size + 1)).coerceIn(2200L, 4800L)
        } else 3500L
        return validLines.mapIndexed { idx, line ->
            LyricsLine(timestampMs = idx * stepMs, text = line)
        }
    }

    /**
     * Scrapes/resolves playlist title, artwork, and (trackTitle, artistName) pairs from
     * Spotify or Apple Music playlist URLs without requiring any API keys or backend server.
     */
    suspend fun fetchExternalPlaylistTracks(
        platform: String,
        playlistId: String,
        rawUrl: String
    ): ImportedPlaylistData = withContext(Dispatchers.IO) {
        when (platform.uppercase()) {
            "SPOTIFY" -> fetchSpotifyPlaylistMetadata(playlistId)
            "APPLE_MUSIC" -> fetchAppleMusicPlaylistMetadata(playlistId, rawUrl)
            else -> ImportedPlaylistData("Imported Playlist", null, emptyList())
        }
    }

    private fun fetchSpotifyPlaylistMetadata(playlistId: String): ImportedPlaylistData {
        var title = "Spotify Playlist"
        var coverUrl: String? = null
        val tracks = mutableListOf<Pair<String, String>>()

        runCatching {
            val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)
                if (!nextData.isNullOrBlank()) {
                    val entity = JSONObject(nextData)
                        .optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("state")
                        ?.optJSONObject("data")
                        ?.optJSONObject("entity")
                    if (entity != null) {
                        title = entity.optString("name", entity.optString("title", title)).ifBlank { title }
                        coverUrl = entity.optJSONObject("coverArt")
                            ?.optJSONArray("sources")
                            ?.optJSONObject(0)
                            ?.optString("url")
                        val trackList = entity.optJSONArray("trackList")
                        if (trackList != null) {
                            for (i in 0 until trackList.length()) {
                                val item = trackList.optJSONObject(i) ?: continue
                                val tName = item.optString("title", "").trim()
                                val tSub = item.optString("subtitle", "").replace(" ", " ").trim()
                                if (tName.isNotBlank()) {
                                    tracks.add(Pair(tName, tSub))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            runCatching {
                val pageUrl = "https://open.spotify.com/playlist/$playlistId"
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    val ogTitle = Regex("""<meta property="og:title" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                    if (!ogTitle.isNullOrBlank()) title = ogTitle
                    val ogImage = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                    if (!ogImage.isNullOrBlank()) coverUrl = ogImage
                    Regex("""<meta name="music:song" content="([^"]+)"""").findAll(html).take(40).forEach { m ->
                        val trackUrl = m.groupValues[1]
                        val tid = trackUrl.substringAfterLast("/").substringBefore("?")
                        if (tid.isNotBlank()) {
                            runCatching {
                                val oReq = Request.Builder().url("https://open.spotify.com/oembed?url=https://open.spotify.com/track/$tid").build()
                                okHttpClient.newCall(oReq).execute().use { oResp ->
                                    val oJson = JSONObject(oResp.body?.string().orEmpty())
                                    val oTitle = oJson.optString("title", "")
                                    if (oTitle.isNotBlank()) {
                                        tracks.add(Pair(oTitle, ""))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return ImportedPlaylistData(title, coverUrl, tracks)
    }

    private fun fetchAppleMusicPlaylistMetadata(playlistId: String, rawUrl: String): ImportedPlaylistData {
        var title = "Apple Music Playlist"
        var coverUrl: String? = null
        val tracks = mutableListOf<Pair<String, String>>()
        val targetUrl = if (rawUrl.startsWith("http")) rawUrl else "https://music.apple.com/us/playlist/$playlistId"

        runCatching {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val ogTitle = Regex("""<meta property="og:title" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                    ?.removeSuffix(" on Apple Music")?.trim()
                if (!ogTitle.isNullOrBlank()) title = ogTitle
                val ogImg = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
                if (!ogImg.isNullOrBlank()) coverUrl = ogImg

                // 1. Parse JSON-LD schema:music-playlist or MusicPlaylist
                Regex("""<script type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(html)
                    .forEach { match ->
                        runCatching {
                            val jsonStr = match.groupValues[1].trim()
                            val root = if (jsonStr.startsWith("[")) JSONArray(jsonStr).optJSONObject(0) else JSONObject(jsonStr)
                            if (root != null) {
                                val pName = root.optString("name", "")
                                if (pName.isNotBlank()) title = pName
                                val trackArr = root.optJSONArray("track")
                                if (trackArr != null) {
                                    for (i in 0 until trackArr.length()) {
                                        val tr = trackArr.optJSONObject(i) ?: continue
                                        val tName = tr.optString("name", "").trim()
                                        val byArtist = tr.optJSONObject("byArtist")?.optString("name", "")
                                            ?: tr.optString("byArtist", "")
                                        if (tName.isNotBlank()) {
                                            tracks.add(Pair(tName, byArtist.trim()))
                                        }
                                    }
                                }
                            }
                        }
                    }

                // 2. Parse serialized-server-data JSON from Apple Music web app
                if (tracks.isEmpty()) {
                    val serverJson = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                        .find(html)?.groupValues?.getOrNull(1)
                    if (!serverJson.isNullOrBlank()) {
                        val titleArtistRegex = Regex(""""title"\s*:\s*"([^"]+)"\s*,\s*"artistName"\s*:\s*"([^"]+)"""")
                        titleArtistRegex.findAll(serverJson).forEach { m ->
                            val t = m.groupValues[1].trim()
                            val a = m.groupValues[2].trim()
                            if (t.isNotBlank() && !t.equals(title, true)) {
                                tracks.add(Pair(t, a))
                            }
                        }
                        if (tracks.isEmpty()) {
                            val nameArtistRegex = Regex(""""name"\s*:\s*"([^"]+)"[^{}]*?"artistName"\s*:\s*"([^"]+)"""")
                            nameArtistRegex.findAll(serverJson).forEach { m ->
                                val t = m.groupValues[1].trim()
                                val a = m.groupValues[2].trim()
                                if (t.isNotBlank() && !t.equals(title, true)) {
                                    tracks.add(Pair(t, a))
                                }
                            }
                        }
                    }
                }
            }
        }
        return ImportedPlaylistData(title, coverUrl, tracks.distinct())
    }

    /**
     * Parses synchronized LRC text format [mm:ss.xx] or [mm:ss.xxx] line with instrumental gap support
     */
    fun parseLrc(lrcText: String): List<LyricsLine> {
        val rawEntries = mutableListOf<Triple<Long, String, String?>>()
        val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[:.](\d{1,3}))?](.*)""")

        for (rawLine in lrcText.lines()) {
            val match = regex.find(rawLine.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3].padEnd(3, '0').take(3)
                val ms = msStr.toLongOrNull() ?: 0L
                val totalMs = (min * 60 * 1000L) + (sec * 1000L) + ms
                val sanitized = match.groupValues[4].replace("{", "").replace("}", "").trim()
                val parts = sanitized.split("^")
                val primary = parts[0].trim()
                val translation = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
                rawEntries.add(Triple(totalMs, primary, translation))
            }
        }

        if (rawEntries.isEmpty()) return emptyList()
        rawEntries.sortBy { it.first }

        val output = mutableListOf<LyricsLine>()
        val firstVocalTime = rawEntries.first().first
        if (firstVocalTime >= 4500L) {
            output.add(LyricsLine(timestampMs = 0L, text = "", isInstrumental = true))
        }

        for (i in rawEntries.indices) {
            val (startMs, primary, translation) = rawEntries[i]
            val nextStartMs = if (i + 1 < rawEntries.size) rawEntries[i + 1].first else startMs + 5000L
            if (primary.isBlank()) {
                output.add(LyricsLine(timestampMs = startMs, text = "", isInstrumental = true))
            } else {
                val vocalDuration = nextStartMs - startMs
                if (vocalDuration > 8500L) {
                    val vocalEnd = startMs + 4800L
                    output.add(LyricsLine(timestampMs = startMs, text = primary, translation = translation, isInstrumental = false))
                    output.add(LyricsLine(timestampMs = vocalEnd, text = "", isInstrumental = true))
                } else {
                    output.add(LyricsLine(timestampMs = startMs, text = primary, translation = translation, isInstrumental = false))
                }
            }
        }
        return output
    }

    private fun cleanArtistName(raw: String): String {
        return raw
            .removeSuffix(" - Topic")
            .removeSuffix("- Topic")
            .replace(Regex("""(?i)(?:official|vevo|music|channel)"""), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\((?:official|music|lyric|lyrics|audio|video|full|hd|4k|visualizer|live|remaster|from\s+).*?\)"""), "")
            .replace(Regex("""(?i)\[(?:official|music|lyric|lyrics|audio|video|full|hd|4k|visualizer|live|remaster|from\s+).*?]"""), "")
            .replace(Regex("""(?i)(?:official\s+music\s+video|official\s+video|official\s+audio|lyric\s+video|lyrics\s+video|full\s+video|music\s+video|full\s+song)"""), "")
            .replace(Regex("""(?i)(?:feat\.|ft\.|prod\.).*"""), "")
            .substringBefore("|")
            .trim()
            .ifBlank { raw.trim() }
    }
}

data class ResolvedStreamInfo(
    val streamUrl: String,
    val codec: String,
    val bitrate: String,
    val expiresAtMs: Long,
    val nodeUsed: String = "Federated Node"
)
