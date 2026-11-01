package com.example.network

import android.util.Log
import com.example.data.model.LyricsLine
import com.example.data.model.Track
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val lyricsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
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
     * Multi-source parallel synchronized lyrics resolver:
     * Runs LRCLIB, SimpMusic VideoID API, YouTube Music Official Lyrics (`MPLYt_` + Timed Lyrics),
     * YouTube Timed Captions (`ANDROID_VR` / `IOS`), NetEase Cloud Music, KuGou, and JioSaavn
     * concurrently with fast per-request timeouts so blocked/slow providers never block others.
     */
    suspend fun fetchLrclibLyrics(
        artist: String,
        title: String,
        durationSec: Long,
        videoId: String = "",
        forceRefresh: Boolean = false
    ): List<LyricsLine> = withContext(Dispatchers.IO) {
        try {
            val effectiveDurSec = if (durationSec > 0L && durationSec != 210L) durationSec else 0L
            val candidatePairs = extractCandidateArtistTitlePairs(artist, title)
            val primaryPair = candidatePairs.firstOrNull() ?: Pair(cleanArtistName(artist), cleanTrackTitle(title))
            val cacheKey = "${primaryPair.first.lowercase()}|${primaryPair.second.lowercase()}|$videoId"
            if (!forceRefresh) {
                syncedLyricsCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            }

            val cleanVid = InnerTubeStreamExtractor.extractVideoId(videoId)

            coroutineScope {
                // 1. LRCLIB Search + Exact (parallel job)
                val lrclibJob = async {
                    withTimeoutOrNull(6500L) {
                        var plainFallback: String? = null
                        for ((candArtist, candTitle) in candidatePairs.take(3)) {
                            if (candTitle.isBlank()) continue
                            val (syncedSearch, plainSearch) = searchLrclibMultiStrategy(candArtist, candTitle, effectiveDurSec)
                            if (syncedSearch.isNotEmpty()) {
                                return@withTimeoutOrNull Pair(syncedSearch, plainSearch)
                            }
                            if (plainFallback == null && !plainSearch.isNullOrBlank()) {
                                plainFallback = plainSearch
                            }
                        }
                        Pair(emptyList<LyricsLine>(), plainFallback)
                    } ?: Pair(emptyList(), null)
                }

                // 2. SimpMusic VideoId Synced Lyrics API (parallel job)
                val simpJob = async {
                    withTimeoutOrNull(5500L) {
                        if (!cleanVid.isNullOrBlank()) {
                            fetchSimpMusicVideoLyrics(cleanVid)
                        } else {
                            Pair(emptyList<LyricsLine>(), null)
                        }
                    } ?: Pair(emptyList(), null)
                }

                // 3. YouTube Music Official Lyrics (MPLYt_ browse + timedLyricsData) & YouTube Timed Captions
                val ytmJob = async {
                    withTimeoutOrNull(7500L) {
                        fetchYoutubeTimedCaptionsOrYtmLyrics(
                            videoId = videoId,
                            artist = primaryPair.first,
                            title = primaryPair.second,
                            durationSec = effectiveDurSec.takeIf { it > 0L } ?: durationSec
                        )
                    } ?: YtmLyricsResult(emptyList(), null, emptyList())
                }

                // 4. NetEase Synced LRC + Translation (parallel job)
                val netEaseJob = async {
                    withTimeoutOrNull(5500L) {
                        for ((candArtist, candTitle) in candidatePairs.take(2)) {
                            if (candTitle.isBlank()) continue
                            val lines = fetchNetEaseSyncedLyrics(candArtist, candTitle, effectiveDurSec)
                            if (lines.isNotEmpty()) return@withTimeoutOrNull lines
                        }
                        emptyList<LyricsLine>()
                    } ?: emptyList()
                }

                // 5. KuGou Synced LRC (parallel job)
                val kugouJob = async {
                    withTimeoutOrNull(5500L) {
                        for ((candArtist, candTitle) in candidatePairs.take(2)) {
                            if (candTitle.isBlank()) continue
                            val lines = fetchKugouSyncedLyrics(candArtist, candTitle, effectiveDurSec)
                            if (lines.isNotEmpty()) return@withTimeoutOrNull lines
                        }
                        emptyList<LyricsLine>()
                    } ?: emptyList()
                }

                // 6. JioSaavn Official Lyrics (fast Indian CDN fallback)
                val saavnJob = async {
                    withTimeoutOrNull(5000L) {
                        fetchJioSaavnLyrics(videoId, primaryPair.first, primaryPair.second)
                    }
                }

                val (lrclibSynced, lrclibPlain) = lrclibJob.await()
                if (lrclibSynced.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = lrclibSynced
                    return@coroutineScope lrclibSynced
                }

                val (simpSynced, simpPlain) = simpJob.await()
                if (simpSynced.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = simpSynced
                    return@coroutineScope simpSynced
                }

                val ytmResult = ytmJob.await()
                if (ytmResult.syncedLines.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = ytmResult.syncedLines
                    return@coroutineScope ytmResult.syncedLines
                }

                val netEaseSynced = netEaseJob.await()
                if (netEaseSynced.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = netEaseSynced
                    return@coroutineScope netEaseSynced
                }

                val kugouSynced = kugouJob.await()
                if (kugouSynced.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = kugouSynced
                    return@coroutineScope kugouSynced
                }

                val saavnPlain = saavnJob.await()

                // Prefer official plain lyrics (YTM MPLYt_ -> LRCLIB -> SimpMusic -> JioSaavn) before ASR captions
                val bestPlain = listOfNotNull(
                    ytmResult.plainText?.takeIf { it.isNotBlank() },
                    lrclibPlain?.takeIf { it.isNotBlank() },
                    simpPlain?.takeIf { it.isNotBlank() },
                    saavnPlain?.takeIf { it.isNotBlank() }
                ).firstOrNull()

                if (!bestPlain.isNullOrBlank()) {
                    val plainLines = buildProportionalLyrics(
                        bestPlain,
                        effectiveDurSec.takeIf { it > 0L } ?: durationSec
                    )
                    if (plainLines.isNotEmpty()) {
                        syncedLyricsCache[cacheKey] = plainLines
                        return@coroutineScope plainLines
                    }
                }

                if (ytmResult.asrCaptionLines.isNotEmpty()) {
                    syncedLyricsCache[cacheKey] = ytmResult.asrCaptionLines
                    return@coroutineScope ytmResult.asrCaptionLines
                }

                emptyList()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error fetching multi-source lyrics: ${e.message}")
            emptyList()
        }
    }

    private data class YtmLyricsResult(
        val syncedLines: List<LyricsLine>,
        val plainText: String?,
        val asrCaptionLines: List<LyricsLine>
    )

    private fun extractCandidateArtistTitlePairs(rawArtist: String, rawTitle: String): List<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        val cleanedArtist = cleanArtistName(rawArtist)
        val cleanedTitle = cleanTrackTitle(rawTitle)

        // Strip trailing/leading artist name if embedded in title
        val dashSplit = cleanedTitle.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        if (dashSplit.size == 2) {
            val left = cleanTrackTitle(dashSplit[0])
            val right = cleanTrackTitle(dashSplit[1])
            val leftAsArtist = cleanArtistName(dashSplit[0])
            val rightAsArtist = cleanArtistName(dashSplit[1])
            if (left.isNotBlank() && right.isNotBlank()) {
                when {
                    cleanedArtist.isNotBlank() && (cleanedArtist.contains(leftAsArtist, true) || leftAsArtist.contains(cleanedArtist, true)) -> {
                        pairs.add(Pair(cleanedArtist, right))
                        pairs.add(Pair(leftAsArtist, right))
                    }
                    cleanedArtist.isNotBlank() && (cleanedArtist.contains(rightAsArtist, true) || rightAsArtist.contains(cleanedArtist, true)) -> {
                        pairs.add(Pair(cleanedArtist, left))
                        pairs.add(Pair(rightAsArtist, left))
                    }
                    else -> {
                        pairs.add(Pair(cleanedArtist, left))
                        pairs.add(Pair(rightAsArtist, left))
                        pairs.add(Pair(leftAsArtist, right))
                    }
                }
            }
        }

        pairs.add(Pair(cleanedArtist, cleanedTitle))
        val primaryArtistOnly = cleanedArtist.split(",", "&", "/", "feat.", "ft.", " x ", " X ").firstOrNull()?.trim().orEmpty()
        val coreTitle = cleanedTitle
            .substringBefore(" - ")
            .substringBefore(" – ")
            .substringBefore(" (")
            .substringBefore(" [")
            .trim()
        if (primaryArtistOnly.isNotBlank() && coreTitle.isNotBlank()) {
            pairs.add(Pair(primaryArtistOnly, coreTitle))
        }
        if (coreTitle.isNotBlank()) {
            pairs.add(Pair("", coreTitle))
        }
        return pairs.filter { it.second.isNotBlank() }.take(5)
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
                add("https://lrclib.net/api/search?track_name=$encTitle")
            }
        }.distinct()

        var bestPlain: String? = null
        val artistLower = artist.lowercase()
        val titleLower = title.lowercase()

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Seitharol-Music/3.5 (https://github.com/floristechnis-git)")
                    .build()
                val foundSynced = lyricsHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<LyricsLine>()
                    val bodyStr = response.body?.string() ?: return@use emptyList<LyricsLine>()
                    val array = JSONArray(bodyStr)
                    if (array.length() == 0) return@use emptyList<LyricsLine>()

                    var bestSyncedLines: List<LyricsLine> = emptyList()
                    var bestScore = Long.MAX_VALUE

                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val synced = item.optString("syncedLyrics", "").takeIf { it != "null" }.orEmpty()
                        val plain = item.optString("plainLyrics", "").takeIf { it != "null" }.orEmpty()
                        val itemTrack = item.optString("trackName", "").lowercase()
                        val itemArtist = item.optString("artistName", "").lowercase()
                        val itemDur = item.optDouble("duration", 0.0).toLong()

                        val durPenalty = if (durationSec > 0L && itemDur > 0L) {
                            abs(itemDur - durationSec) * 3L
                        } else {
                            i.toLong() * 5L
                        }
                        val artistPenalty = if (artistLower.isNotBlank() && !itemArtist.contains(artistLower) && !artistLower.contains(itemArtist)) 22L else 0L
                        val titlePenalty = if (titleLower.isNotBlank() && !itemTrack.contains(titleLower) && !titleLower.contains(itemTrack)) 25L else 0L
                        val totalPenalty = durPenalty + artistPenalty + titlePenalty

                        if (synced.isNotBlank() && totalPenalty < bestScore && totalPenalty <= 85L) {
                            val parsed = parseLrc(synced)
                            if (parsed.size >= 2) {
                                bestScore = totalPenalty
                                bestSyncedLines = parsed
                            }
                        }
                        if (bestPlain == null && plain.isNotBlank() && totalPenalty <= 75L) {
                            bestPlain = plain
                        }
                    }

                    bestSyncedLines
                }
                if (foundSynced.size >= 2) return Pair(foundSynced, bestPlain)
            } catch (_: Throwable) {}
        }
        return Pair(emptyList(), bestPlain)
    }

    private fun fetchSimpMusicVideoLyrics(cleanVid: String): Pair<List<LyricsLine>, String?> {
        return try {
            val req = Request.Builder()
                .url("https://api-lyrics.simpmusic.org/v1/$cleanVid")
                .header("User-Agent", "SimpMusicLyrics/1.0")
                .header("Accept", "application/json")
                .build()
            lyricsHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use Pair(emptyList(), null)
                val body = resp.body?.string() ?: return@use Pair(emptyList(), null)
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: return@use Pair(emptyList(), null)
                var plainFallback: String? = null
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics", "").takeIf { it != "null" }.orEmpty()
                    val plain = item.optString("plainLyrics", "").takeIf { it != "null" }.orEmpty()
                    if (synced.isNotBlank()) {
                        val parsed = parseLrc(synced)
                        if (parsed.size >= 2) return@use Pair(parsed, plain.takeIf { it.isNotBlank() })
                    }
                    if (plainFallback == null && plain.isNotBlank()) {
                        plainFallback = plain
                    }
                }
                Pair(emptyList(), plainFallback)
            }
        } catch (_: Throwable) {
            Pair(emptyList(), null)
        }
    }

    private fun fetchNetEaseSyncedLyrics(artist: String, title: String, durationSec: Long): List<LyricsLine> {
        return try {
            val query = "$title $artist".trim()
            val searchUrl = "https://music.163.com/api/search/get"
            val formBody = "s=${URLEncoder.encode(query, "UTF-8")}&type=1&offset=0&total=true&limit=5"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val searchReq = Request.Builder()
                .url(searchUrl)
                .post(formBody)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val songIds = lyricsHttpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Long>()
                val body = resp.body?.string() ?: return@use emptyList<Long>()
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return@use emptyList<Long>()
                val candidates = mutableListOf<Pair<Long, Long>>()
                for (i in 0 until songs.length()) {
                    val s = songs.optJSONObject(i) ?: continue
                    val id = s.optLong("id", 0L)
                    if (id <= 0L) continue
                    val durSec = s.optLong("duration", 0L) / 1000L
                    val penalty = if (durationSec > 0L && durSec > 0L) abs(durSec - durationSec) else i.toLong() * 5L
                    candidates.add(Pair(id, penalty))
                }
                candidates.sortedBy { it.second }.take(2).map { it.first }
            }

            for (songId in songIds) {
                val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                val lyricReq = Request.Builder()
                    .url(lyricUrl)
                    .header("Referer", "https://music.163.com")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val parsed = lyricsHttpClient.newCall(lyricReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<LyricsLine>()
                    val body = resp.body?.string() ?: return@use emptyList<LyricsLine>()
                    val json = JSONObject(body)
                    val lrc = json.optJSONObject("lrc")?.optString("lyric", "").orEmpty()
                    val tlyric = json.optJSONObject("tlyric")?.optString("lyric", "").orEmpty()
                    if (lrc.isNotBlank()) {
                        val primaryLines = parseLrc(lrc)
                        if (tlyric.isNotBlank() && primaryLines.isNotEmpty()) {
                            val transLines = parseLrc(tlyric).filter { lineItem: LyricsLine -> !lineItem.isInstrumental && lineItem.text.isNotBlank() }
                            primaryLines.map { line: LyricsLine ->
                                if (line.isInstrumental || line.text.isBlank()) {
                                    line
                                } else {
                                    val matchingTrans = transLines
                                        .minByOrNull { trItem: LyricsLine -> abs(trItem.timestampMs - line.timestampMs) }
                                        ?.takeIf { trItem: LyricsLine -> abs(trItem.timestampMs - line.timestampMs) <= 1500L }
                                        ?.text
                                    if (!matchingTrans.isNullOrBlank() && !matchingTrans.equals(line.text, ignoreCase = true)) {
                                        line.copy(translation = matchingTrans)
                                    } else {
                                        line
                                    }
                                }
                            }
                        } else primaryLines
                    } else emptyList()
                }
                if (parsed.size >= 2) return parsed
            }
            emptyList()
        } catch (_: Throwable) {
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
            val candidate = lyricsHttpClient.newCall(searchReq).execute().use { resp ->
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
            lyricsHttpClient.newCall(dlReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val b64 = JSONObject(body).optString("content", "")
                if (b64.isBlank()) return emptyList()
                val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val parsed = parseLrc(decoded)
                if (parsed.size >= 2) parsed else emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fetchJioSaavnLyrics(videoId: String, artist: String, title: String): String? {
        return try {
            val saavnId = if (videoId.startsWith("saavn_")) {
                videoId.removePrefix("saavn_").trim()
            } else {
                val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
                val searchUrl = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=android&q=$q&n=3"
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.jiosaavn.com/")
                    .build()
                lyricsHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val results = JSONObject(body).optJSONArray("results") ?: return@use null
                    if (results.length() == 0) return@use null
                    results.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                }
            } ?: return null

            val lyrUrl = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&ctx=web6dot0&api_version=4&_format=json&_marker=0%3F_marker%3D0&lyrics_id=$saavnId"
            val lyrReq = Request.Builder()
                .url(lyrUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.jiosaavn.com/")
                .build()
            lyricsHttpClient.newCall(lyrReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val raw = JSONObject(body).optString("lyrics", "")
                if (raw.isBlank()) return null
                raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&")
                    .trim()
                    .takeIf { it.lines().count { l -> l.isNotBlank() } >= 3 }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun fetchYoutubeTimedCaptionsOrYtmLyrics(
        videoId: String,
        artist: String,
        title: String,
        durationSec: Long
    ): YtmLyricsResult {
        return try {
            val candidateVideoIds = LinkedHashSet<String>()
            InnerTubeStreamExtractor.extractVideoId(videoId)?.let { candidateVideoIds.add(it) }

            // Also resolve the official YouTube Music Song (ATV) videoId when playing an OMV/video or non-YTM track
            if (title.isNotBlank()) {
                runCatching {
                    val ytmSongs = InnerTubeStreamExtractor.searchOfficialYtmSongs("$title $artist".trim(), limit = 2)
                    ytmSongs.forEach { song ->
                        InnerTubeStreamExtractor.extractVideoId(song.videoId)?.let { candidateVideoIds.add(it) }
                    }
                }
            }

            var discoveredPlainLyrics: String? = null
            var discoveredAsrCaptions: List<LyricsLine> = emptyList()

            val webRemixContext = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", "IN")
                })
            }

            // Step A: Check YouTube Music /next -> MPLYt_ browse for official synced or plain lyrics
            for (candVid in candidateVideoIds) {
                runCatching {
                    val nextBody = JSONObject().apply {
                        put("videoId", candVid)
                        put("context", webRemixContext)
                    }
                    val nextReq = Request.Builder()
                        .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
                        .post(nextBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .header("X-YouTube-Client-Name", "67")
                        .header("X-YouTube-Client-Version", "1.20250310.01.00")
                        .header("Origin", "https://music.youtube.com")
                        .header("Referer", "https://music.youtube.com/")
                        .build()

                    val lyricsBrowseId = lyricsHttpClient.newCall(nextReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val str = resp.body?.string() ?: return@use null
                        Regex("\"browseId\"\\s*:\\s*\"(MPLYt_[^\"]+)\"").find(str)?.groupValues?.getOrNull(1)
                    }

                    if (!lyricsBrowseId.isNullOrBlank()) {
                        val browseBody = JSONObject().apply {
                            put("browseId", lyricsBrowseId)
                            put("context", webRemixContext)
                        }
                        val browseReq = Request.Builder()
                            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                            .post(browseBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                            .header("X-YouTube-Client-Name", "67")
                            .header("X-YouTube-Client-Version", "1.20250310.01.00")
                            .header("Origin", "https://music.youtube.com")
                            .header("Referer", "https://music.youtube.com/")
                            .build()

                        lyricsHttpClient.newCall(browseReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val str = resp.body?.string().orEmpty()
                                if (str.isNotBlank()) {
                                    val root = JSONObject(str)
                                    val timedLines = extractYtmTimedLyrics(root)
                                    if (timedLines.size >= 2) {
                                        return YtmLyricsResult(timedLines, null, emptyList())
                                    }
                                    val shelfText = extractYtmDescriptionShelfText(root)
                                    if (!shelfText.isNullOrBlank()) {
                                        val asLrc = parseLrc(shelfText)
                                        if (asLrc.size >= 2) {
                                            return YtmLyricsResult(asLrc, shelfText, emptyList())
                                        }
                                        if (discoveredPlainLyrics == null) {
                                            discoveredPlainLyrics = shelfText
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Step B: Check YouTube Timed Captions via ANDROID_VR (1.61.48) player endpoint
            for (candVid in candidateVideoIds.take(2)) {
                runCatching {
                    val vrBody = JSONObject().apply {
                        put("videoId", candVid)
                        put("context", JSONObject().apply {
                            put("client", JSONObject().apply {
                                put("clientName", "ANDROID_VR")
                                put("clientVersion", "1.61.48")
                                put("deviceMake", "Oculus")
                                put("deviceModel", "Quest 3")
                                put("osName", "Android")
                                put("osVersion", "12L")
                                put("androidSdkVersion", 32)
                                put("hl", "en")
                                put("gl", "IN")
                            })
                        })
                    }
                    val vrUserAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                    val playerReq = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                        .post(vrBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .header("User-Agent", vrUserAgent)
                        .header("X-YouTube-Client-Name", "28")
                        .header("X-YouTube-Client-Version", "1.61.48")
                        .build()

                    val captionInfo = lyricsHttpClient.newCall(playerReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val str = resp.body?.string() ?: return@use null
                        val tracks = JSONObject(str)
                            .optJSONObject("captions")
                            ?.optJSONObject("playerCaptionsTracklistRenderer")
                            ?.optJSONArray("captionTracks") ?: return@use null
                        var manualUrl: String? = null
                        var asrUrl: String? = null
                        for (i in 0 until tracks.length()) {
                            val tr = tracks.optJSONObject(i) ?: continue
                            val baseUrl = tr.optString("baseUrl", "")
                            val kind = tr.optString("kind", "")
                            val lang = tr.optString("languageCode", "")
                            if (baseUrl.isNotBlank()) {
                                if (kind != "asr") {
                                    if (manualUrl == null || lang.startsWith("en", true)) {
                                        manualUrl = baseUrl
                                    }
                                } else if (asrUrl == null) {
                                    asrUrl = baseUrl
                                }
                            }
                        }
                        when {
                            manualUrl != null -> Pair(manualUrl, true)
                            asrUrl != null -> Pair(asrUrl, false)
                            else -> null
                        }
                    }

                    if (captionInfo != null) {
                        val (captionUrl, isManual) = captionInfo
                        val capLines = fetchAndParseYoutubeCaptionUrl(captionUrl, vrUserAgent)
                        if (capLines.size >= 4) {
                            if (isManual) {
                                return YtmLyricsResult(capLines, discoveredPlainLyrics, emptyList())
                            } else if (discoveredAsrCaptions.isEmpty()) {
                                discoveredAsrCaptions = capLines
                            }
                        }
                    }
                }
            }

            YtmLyricsResult(emptyList(), discoveredPlainLyrics, discoveredAsrCaptions)
        } catch (_: Throwable) {
            YtmLyricsResult(emptyList(), null, emptyList())
        }
    }

    private fun extractYtmTimedLyrics(node: Any?): List<LyricsLine> {
        val out = mutableListOf<LyricsLine>()
        fun walk(curr: Any?) {
            if (out.isNotEmpty()) return
            when (curr) {
                is JSONObject -> {
                    val arr = curr.optJSONArray("timedLyricsData")
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val text = item.optString("lyricLine", "").replace("♪", "").trim()
                            val cue = item.optJSONObject("cueRange")
                            val startMs = cue?.optString("startTimeMilliseconds", "")?.toLongOrNull()
                                ?: cue?.optLong("startTimeMilliseconds", -1L)
                                ?: -1L
                            if (startMs >= 0L && text.isNotBlank()) {
                                out.add(LyricsLine(timestampMs = startMs, text = text))
                            }
                        }
                        if (out.isNotEmpty()) return
                    }
                    val keys = curr.keys()
                    while (keys.hasNext()) {
                        walk(curr.opt(keys.next()))
                        if (out.isNotEmpty()) return
                    }
                }
                is JSONArray -> {
                    for (i in 0 until curr.length()) {
                        walk(curr.opt(i))
                        if (out.isNotEmpty()) return
                    }
                }
            }
        }
        walk(node)
        return out.sortedBy { it.timestampMs }
    }

    private fun extractYtmDescriptionShelfText(node: Any?): String? {
        var found: String? = null
        fun walk(curr: Any?) {
            if (found != null) return
            when (curr) {
                is JSONObject -> {
                    val shelf = curr.optJSONObject("musicDescriptionShelfRenderer")
                    if (shelf != null) {
                        val runs = shelf.optJSONObject("description")?.optJSONArray("runs")
                        if (runs != null && runs.length() > 0) {
                            val sb = StringBuilder()
                            for (i in 0 until runs.length()) {
                                sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
                            }
                            val text = sb.toString().trim()
                            if (text.length >= 16 && text.contains("\n")) {
                                found = text
                                return
                            }
                        }
                    }
                    val keys = curr.keys()
                    while (keys.hasNext()) {
                        walk(curr.opt(keys.next()))
                        if (found != null) return
                    }
                }
                is JSONArray -> {
                    for (i in 0 until curr.length()) {
                        walk(curr.opt(i))
                        if (found != null) return
                    }
                }
            }
        }
        walk(node)
        return found
    }

    private fun fetchAndParseYoutubeCaptionUrl(baseUrl: String, userAgent: String): List<LyricsLine> {
        val json3Url = if (baseUrl.contains("fmt=")) {
            baseUrl.replace(Regex("fmt=[^&]+"), "fmt=json3")
        } else {
            "$baseUrl&fmt=json3"
        }
        for (targetUrl in listOf(json3Url, baseUrl).distinct()) {
            try {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", userAgent)
                    .build()
                val lines = lyricsHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<LyricsLine>()
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.isBlank()) return@use emptyList<LyricsLine>()
                    if (body.startsWith("{")) {
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
                            val lineText = decodeHtmlEntities(sb.toString())
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .replace("♪", "")
                                .replace("♫", "")
                                .trim()
                            if (lineText.isNotBlank() && !lineText.startsWith("[")) {
                                out.add(LyricsLine(timestampMs = startMs, text = lineText))
                            }
                        }
                        out
                    } else {
                        val out = mutableListOf<LyricsLine>()
                        // Parse XML <p t="12340" d="3000">...</p> or <text start="12.34" dur="3.0">...</text>
                        val pRegex = Regex("<p[^>]*\\bt=\"(\\d+)\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                        for (m in pRegex.findAll(body)) {
                            val startMs = m.groupValues[1].toLongOrNull() ?: continue
                            val rawText = m.groupValues[2].replace(Regex("<[^>]+>"), "")
                            val cleanText = decodeHtmlEntities(rawText).replace("\n", " ").replace("♪", "").replace("♫", "").trim()
                            if (cleanText.isNotBlank() && !cleanText.startsWith("[")) {
                                out.add(LyricsLine(timestampMs = startMs, text = cleanText))
                            }
                        }
                        if (out.isEmpty()) {
                            val textRegex = Regex("<text[^>]*\\bstart=\"([0-9.]+)\"[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
                            for (m in textRegex.findAll(body)) {
                                val startSec = m.groupValues[1].toDoubleOrNull() ?: continue
                                val startMs = (startSec * 1000.0).toLong()
                                val rawText = m.groupValues[2].replace(Regex("<[^>]+>"), "")
                                val cleanText = decodeHtmlEntities(rawText).replace("\n", " ").replace("♪", "").replace("♫", "").trim()
                                if (cleanText.isNotBlank() && !cleanText.startsWith("[")) {
                                    out.add(LyricsLine(timestampMs = startMs, text = cleanText))
                                }
                            }
                        }
                        out
                    }
                }
                if (lines.size >= 4) return lines.distinctBy { "${it.timestampMs / 400L}|${it.text.lowercase()}" }
            } catch (_: Throwable) {}
        }
        return emptyList()
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    fun buildProportionalLyrics(plainLyrics: String, durationSec: Long): List<LyricsLine> {
        val validLines = plainLyrics.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("Source:", ignoreCase = true) && !it.startsWith("Lyrics licensed", ignoreCase = true) }
        if (validLines.isEmpty()) return emptyList()
        val stepMs = if (durationSec > 0L) {
            ((durationSec * 1000L) / (validLines.size + 1)).coerceIn(2000L, 4800L)
        } else 3400L
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
        val cleanId = playlistId.removePrefix("spotify:").substringBefore("?").trim()

        runCatching {
            val embedUrl = "https://open.spotify.com/embed/playlist/$cleanId"
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val nextData = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
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
                                val tSub = item.optString("subtitle", "").replace("\u00a0", " ").trim()
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
                val pageUrl = "https://open.spotify.com/playlist/$cleanId"
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    val ogTitle = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                    if (!ogTitle.isNullOrBlank()) title = ogTitle
                    val ogImage = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                    if (!ogImage.isNullOrBlank()) coverUrl = ogImage
                    Regex("<meta name=\"music:song\" content=\"([^\"]+)\"").findAll(html).take(40).forEach { m ->
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
        val cleanId = playlistId.removePrefix("apple:").trim()
        val targetUrl = if (rawUrl.startsWith("http")) rawUrl else "https://music.apple.com/us/playlist/$cleanId"

        runCatching {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val ogTitle = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                    ?.removeSuffix(" on Apple Music")?.trim()
                if (!ogTitle.isNullOrBlank()) title = ogTitle
                val ogImg = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                if (!ogImg.isNullOrBlank()) coverUrl = ogImg

                Regex("<script type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
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

                if (tracks.isEmpty()) {
                    val serverJson = Regex("<script type=\"application/json\" id=\"serialized-server-data\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                        .find(html)?.groupValues?.getOrNull(1)
                    if (!serverJson.isNullOrBlank()) {
                        val titleArtistRegex = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"artistName\"\\s*:\\s*\"([^\"]+)\"")
                        titleArtistRegex.findAll(serverJson).forEach { m ->
                            val t = m.groupValues[1].trim()
                            val a = m.groupValues[2].trim()
                            if (t.isNotBlank() && !t.equals(title, true)) {
                                tracks.add(Pair(t, a))
                            }
                        }
                        if (tracks.isEmpty()) {
                            val nameArtistRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"artistName\"\\s*:\\s*\"([^\"]+)\"")
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
     * Parses synchronized LRC text format ([mm:ss.xx], [mm:ss.xxx], [mm:ss], and multi-tag lines)
     * with instrumental gap support and inline word-sync tag cleanup.
     */
    fun parseLrc(lrcText: String): List<LyricsLine> {
        if (lrcText.isBlank()) return emptyList()
        val rawEntries = mutableListOf<Triple<Long, String, String?>>()
        val tagRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[:.](\\d{1,3}))?]")
        val creditLineRegex = Regex("^(?:作词|作曲|编曲|制作人|プロデューサー|lyrics\\s*by|composed\\s*by|arranged\\s*by|produced\\s*by)\\s*[:：]", RegexOption.IGNORE_CASE)

        for (rawLine in lrcText.lines()) {
            val trimmed = rawLine.trim()
            val matches = tagRegex.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val lastMatchEnd = matches.last().range.last + 1
                val lyricBody = if (lastMatchEnd <= trimmed.length) trimmed.substring(lastMatchEnd) else ""
                val cleanedBody = lyricBody
                    .replace(Regex("<\\d{1,2}:\\d{2}(?:[:.]\\d{1,3})?>"), "")
                    .replace("{", "")
                    .replace("}", "")
                    .trim()
                if (creditLineRegex.containsMatchIn(cleanedBody)) continue

                val parts = cleanedBody.split("^")
                val primary = parts[0].trim()
                val translation = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }

                for (match in matches) {
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val msStr = match.groupValues[3].padEnd(3, '0').take(3)
                    val ms = msStr.toLongOrNull() ?: 0L
                    val totalMs = (min * 60 * 1000L) + (sec * 1000L) + ms
                    rawEntries.add(Triple(totalMs, primary, translation))
                }
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
            .replace(Regex("(?i)\\b(?:official|vevo|music|channel|records|entertainment)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw
            .substringBefore("|")
            .replace(Regex("(?i)\\((?:official|music|lyric|lyrics|audio|video|full|hd|4k|8k|visualizer|live|remaster|from\\s+|feat|ft\\.|prod|manipuri|hindi|new\\s+song|song|\\d{4}).*?\\)"), "")
            .replace(Regex("(?i)\\[(?:official|music|lyric|lyrics|audio|video|full|hd|4k|8k|visualizer|live|remaster|from\\s+|feat|ft\\.|prod|manipuri|hindi|new\\s+song|song|\\d{4}).*?]"), "")
            .replace(Regex("(?i)\\b(?:official\\s+music\\s+video|official\\s+video|official\\s+audio|lyric\\s+video|lyrics\\s+video|full\\s+video|music\\s+video|full\\s+song|full\\s+audio|video\\s+song|audio\\s+song)\\b"), "")
            .replace(Regex("(?i)\\b(?:feat\\.|ft\\.|prod\\.).*"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '–', '—', ':')
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
