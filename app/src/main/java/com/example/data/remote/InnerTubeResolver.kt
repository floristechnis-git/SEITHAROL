package com.example.data.remote

import android.util.Log
import com.example.data.model.Track
import com.example.domain.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object InnerTubeResolver {
    private const val TAG = "InnerTubeResolver"

    // Upgrades low-res YouTube & YTMusic thumbnails to high-res
    fun upgradeThumbnailUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url
            .replace(Regex("=w\\d+-h\\d+[^&\\s]*"), "=w544-h544-l90-rj")
            .replace(Regex("=s\\d+[^&\\s]*"), "=s544")
            .replace("/default.jpg", "/hqdefault.jpg")
            .replace("/mqdefault.jpg", "/hqdefault.jpg")
            .replace("/sddefault.jpg", "/hqdefault.jpg")
    }

    /**
     * Resolves a playable audio stream URL for any track.
     * Works even if videoId is missing/fake by searching "title artist" first.
     */
    suspend fun resolveStreamUrl(
        videoId: String?,
        title: String,
        artist: String
    ): String? = withContext(Dispatchers.IO) {
        val validVideoId = com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(videoId)
            ?: searchFirstVideoId("$title $artist")
            ?: return@withContext null

        // 1. Direct fast concurrent InnerTube extraction
        val directStream = com.seitharol.data.resolver.InnerTubeStreamExtractor
            .resolveWorkingStreamUrl(validVideoId)
            .getOrNull()
        if (!directStream.isNullOrBlank()) return@withContext directStream

        // 2. Direct player endpoint fallback
        val iosStream = fetchAudioUrlFromPlayer(validVideoId, clientName = "IOS", clientVersion = "20.10.4")
        if (!iosStream.isNullOrBlank()) return@withContext iosStream

        null
    }

    private fun fetchAudioUrlFromPlayer(
        videoId: String,
        clientName: String,
        clientVersion: String
    ): String? {
        return try {
            val isIos = clientName == "IOS"
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        if (isIos) {
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("osName", "iOS")
                            put("osVersion", "18.3.1.22D72")
                        }
                    })
                })
            }
            val endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            val userAgent = when {
                isIos -> "com.google.ios.youtube/$clientVersion (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
            val response = postJson(endpoint, body, userAgent)
            val playability = response.optJSONObject("playabilityStatus")
            if (playability?.optString("status") != "OK") {
                Log.w(TAG, "Playability=${playability?.optString("status")} for $videoId on $clientName")
                return null
            }
            val streamingData = response.optJSONObject("streamingData") ?: return null
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

            var bestUrl: String? = null
            var bestBitrate = -1

            for (i in 0 until adaptiveFormats.length()) {
                val fmt = adaptiveFormats.getJSONObject(i)
                val mimeType = fmt.optString("mimeType", "")
                val url = fmt.optString("url", "")
                val bitrate = fmt.optInt("bitrate", 0)
                if (mimeType.startsWith("audio/") && url.isNotEmpty() && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = url
                }
            }
            bestUrl
        } catch (e: Exception) {
            Log.w(TAG, "Player endpoint failed for $videoId with client $clientName: ${e.message}")
            null
        }
    }

    private fun fetchAudioUrlFromPiped(videoId: String): String? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacydev.net",
            "https://piped-api.lunar.icu",
            "https://pipedapi.adminforge.de",
            "https://piped-api.garudalinux.org",
            "https://piped.yt/api"
        )
        for (instance in instances) {
            try {
                val conn = (URL("$instance/streams/$videoId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 2500
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        var bestUrl: String? = null
                        var bestBitrate = -1
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.getJSONObject(i)
                            val url = stream.optString("url")
                            val bitrate = stream.optInt("bitrate", 0)
                            if (url.isNotBlank() && bitrate > bestBitrate) {
                                bestBitrate = bitrate
                                bestUrl = url
                            }
                        }
                        if (!bestUrl.isNullOrBlank()) return bestUrl
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $instance failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    /**
     * Finds the exact 11-char YouTube videoId when a track only has title + artist.
     */
    suspend fun searchFirstVideoId(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = webRemixContext().apply {
                put("query", query)
                // Songs filter param in YouTube Music
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            }
            val res = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            val text = res.toString()
            val match = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"").find(text)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.w(TAG, "Search video ID failed for query '$query': ${e.message}")
            null
        }
    }

    /**
     * Fetches REAL songs, albums, and playlists for an Artist (replaces fake song generator).
     */
    suspend fun fetchRealArtistCatalog(artistName: String, browseId: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (!browseId.isNullOrBlank() && browseId.startsWith("UC")) {
                try {
                    val body = webRemixContext().apply {
                        put("browseId", browseId)
                    }
                    return@withContext postJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
                } catch (e: Exception) {
                    Log.w(TAG, "Browse artist failed for $browseId: ${e.message}")
                }
            }
            // Fetch exact Songs by this artist from YT Music search
            val songsBody = webRemixContext().apply {
                put("query", artistName)
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D") // Songs filter
            }
            postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", songsBody)
        }

    /**
     * Fetches real albums for an Artist from YouTube Music search.
     */
    suspend fun fetchRealArtistAlbums(artistName: String): List<Album> = withContext(Dispatchers.IO) {
        try {
            val albumsBody = webRemixContext().apply {
                put("query", artistName)
                put("params", "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D") // Albums filter
            }
            val res = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", albumsBody)
            parseAlbumsFromResponse(res)
        } catch (e: Exception) {
            Log.w(TAG, "Search artist albums failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches tracks inside a YouTube Mix (RD...), Playlist (VL.../PL...), or Album (MPREb_...).
     */
    suspend fun fetchPlaylistOrMixTracks(playlistId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val cleanId = playlistId.removePrefix("VL")
            if (cleanId.startsWith("MPREb_") || cleanId.startsWith("OLAK5uy_")) {
                // Album browse endpoint
                val browseBody = webRemixContext().apply {
                    put("browseId", cleanId)
                }
                return@withContext postJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", browseBody)
            }
            val body = webRemixContext().apply {
                put("playlistId", cleanId)
            }
            postJson("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body)
        }

    /**
     * Fetches and parses tracks into concrete domain Track objects.
     */
    suspend fun fetchAndParsePlaylistOrMixTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val json = fetchPlaylistOrMixTracks(playlistId)
            parseTracksFromResponse(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch and parse playlist/mix $playlistId: ${e.message}")
            emptyList()
        }
    }

    fun parseTracksFromResponse(json: JSONObject): List<Track> {
        val tracks = mutableListOf<Track>()
        val renderers = mutableListOf<JSONObject>()
        findItemRenderers(json, renderers)

        for (item in renderers) {
            val rawId = item.optString("videoId").takeIf { it.isNotBlank() }
                ?: item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?: item.optJSONObject("playlistItemData")?.optString("videoId")
                ?: item.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?: item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("url")
            val videoId = com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(rawId) ?: continue

            if (videoId.length != 11) continue

            var title = "Unknown Track"
            var artist = "Various Artists"
            var album = "Single"
            var durationMs = 210000L

            // Try playlistPanelVideoRenderer format
            val titleRuns = item.optJSONObject("title")?.optJSONArray("runs")
            if (titleRuns != null && titleRuns.length() > 0) {
                title = titleRuns.getJSONObject(0).optString("text", "Unknown Track")
            }

            val longByline = item.optJSONObject("longBylineText")?.optJSONArray("runs")
                ?: item.optJSONObject("shortBylineText")?.optJSONArray("runs")
            if (longByline != null && longByline.length() > 0) {
                val bylineTexts = mutableListOf<String>()
                for (i in 0 until longByline.length()) {
                    val t = longByline.getJSONObject(i).optString("text").trim()
                    if (t.isNotBlank() && t != "•") bylineTexts.add(t)
                }
                if (bylineTexts.isNotEmpty()) artist = bylineTexts[0]
                if (bylineTexts.size > 1) album = bylineTexts[1]
            }

            // Try flexColumns format (musicResponsiveListItemRenderer)
            val flexCols = item.optJSONArray("flexColumns")
            if (flexCols != null && flexCols.length() > 0) {
                val col0Runs = flexCols.getJSONObject(0)
                    .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                if (col0Runs != null && col0Runs.length() > 0) {
                    title = col0Runs.getJSONObject(0).optString("text", title)
                }
                if (flexCols.length() > 1) {
                    val col1Runs = flexCols.getJSONObject(1)
                        .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    if (col1Runs != null && col1Runs.length() > 0) {
                        val texts = mutableListOf<String>()
                        for (i in 0 until col1Runs.length()) {
                            val txt = col1Runs.getJSONObject(i).optString("text").trim()
                            if (txt.isNotBlank() && txt != "•") texts.add(txt)
                        }
                        if (texts.isNotEmpty()) artist = texts[0]
                        if (texts.size > 1) album = texts[1]
                    }
                }
            }

            val lengthText = item.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: item.optJSONObject("lengthText")?.optString("simpleText")
            if (!lengthText.isNullOrBlank()) {
                durationMs = parseDurationMs(lengthText)
            }

            var artUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val thumbObj = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                ?: item.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1).optString("url")
                if (lastThumb.isNotBlank()) artUrl = upgradeThumbnailUrl(lastThumb)
            }

            tracks.add(
                Track(
                    id = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    artUrl = upgradeThumbnailUrl(artUrl)
                )
            )
        }
        return tracks.distinctBy { it.id }
    }

    private fun parseAlbumsFromResponse(json: JSONObject): List<Album> {
        val albums = mutableListOf<Album>()
        val renderers = mutableListOf<JSONObject>()
        findItemRenderers(json, renderers)

        for (item in renderers) {
            val browseId = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?: item.optString("browseId").takeIf { it.isNotBlank() }
                ?: continue

            var title = "Album"
            val titleRuns = item.optJSONObject("title")?.optJSONArray("runs")
            if (titleRuns != null && titleRuns.length() > 0) {
                title = titleRuns.getJSONObject(0).optString("text", "Album")
            }

            var artUrl = ""
            val thumbObj = item.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                ?: item.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1).optString("url")
                if (lastThumb.isNotBlank()) artUrl = upgradeThumbnailUrl(lastThumb)
            }

            var year = ""
            val subtitleRuns = item.optJSONObject("subtitle")?.optJSONArray("runs")
                ?: item.optJSONObject("bylineText")?.optJSONArray("runs")
            if (subtitleRuns != null) {
                for (i in 0 until subtitleRuns.length()) {
                    val txt = subtitleRuns.getJSONObject(i).optString("text").trim()
                    if (txt.length == 4 && txt.all { it.isDigit() }) {
                        year = txt
                        break
                    }
                }
            }

            albums.add(Album(id = browseId, title = title, thumbnailUrl = artUrl, year = year))
        }
        return albums.distinctBy { it.id }
    }

    private fun findItemRenderers(obj: Any, outList: MutableList<JSONObject>) {
        when (obj) {
            is JSONObject -> {
                val resp = obj.optJSONObject("musicResponsiveListItemRenderer")
                if (resp != null) outList.add(resp)
                val panel = obj.optJSONObject("playlistPanelVideoRenderer")
                if (panel != null) outList.add(panel)
                val twoRow = obj.optJSONObject("musicTwoRowItemRenderer")
                if (twoRow != null) outList.add(twoRow)

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val child = obj.opt(keys.next())
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

    private fun parseDurationMs(durationStr: String): Long {
        return try {
            val parts = durationStr.split(":").map { it.trim().toLong() }
            if (parts.size == 2) {
                (parts[0] * 60 + parts[1]) * 1000L
            } else if (parts.size == 3) {
                (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            } else {
                210000L
            }
        } catch (e: Exception) {
            210000L
        }
    }

    private fun webRemixContext(): JSONObject = JSONObject().apply {
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20260101.01.00")
                put("hl", "en")
                put("gl", "IN")
            })
        })
    }

    private fun postJson(
        endpoint: String,
        payload: JSONObject,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    ): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("X-Goog-Api-Format-Version", "1")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(responseText)
    }
}
