package com.seitharol.data.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    val origin: String = "https://www.youtube.com",
    val playerEndpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
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
            origin = "https://www.youtube.com",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        ),
        InnerTubeClientConfig(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            clientId = "5",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.1.0.22B83",
            origin = "https://www.youtube.com",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        )
    )

    @Volatile
    private var cachedVisitorData: String? = null

    private fun getOrFetchVisitorData(): String? {
        cachedVisitorData?.let { return it }
        return runCatching {
            val conn = (URL("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 2500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", com.seitharol.player.OuterTuneResolvingDataSource.VR_USER_AGENT)
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
            }
            conn.outputStream.use { it.write(webRemixPayload().toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optJSONObject("responseContext")?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                    ?.also { cachedVisitorData = it }
            } else null
        }.getOrNull()
    }

    val VALID_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    fun extractVideoId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (VALID_VIDEO_ID_REGEX.matches(trimmed)) return trimmed
        // 1. Check for query parameter v= or id= (e.g. ?v=..., &v=..., ?id=..., &id=...)
        val paramMatch = Regex("[?&](?:v|id)=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (paramMatch != null) return paramMatch.groupValues[1]
        // 2. Check for watch?v= or watch?id= prefix (e.g. watch?v=..., /watch?id=...)
        val watchMatch = Regex("(?:^|/|\\?)watch\\?(?:.*&)?(?:v|id)=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (watchMatch != null) return watchMatch.groupValues[1]
        // 3. Short URLs or paths (youtu.be/..., /embed/..., /v/..., /shorts/..., /live/...)
        val pathMatch = Regex("(?:youtu\\.be/|/(?:embed|v|shorts|live)/)([a-zA-Z0-9_-]{11})").find(trimmed)
        if (pathMatch != null) return pathMatch.groupValues[1]
        return null
    }

    fun isValidYoutubeVideoId(id: String?): Boolean =
        extractVideoId(id) != null

    private fun extractStreamWithConfig(
        videoId: String,
        client: InnerTubeClientConfig,
        preferredQuality: AudioQuality,
        cpn: String
    ): String? {
        return runCatching {
            val endpoint = URL(client.playerEndpoint)
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", client.userAgent)
                setRequestProperty("X-YouTube-Client-Name", client.clientId)
                setRequestProperty("X-YouTube-Client-Version", client.clientVersion)
                setRequestProperty("Origin", client.origin)
                setRequestProperty("Referer", "${client.origin}/")
            }
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("hl", "en")
                        put("gl", "IN")
                        client.deviceMake?.let { put("deviceMake", it) }
                        client.deviceModel?.let { put("deviceModel", it) }
                        client.osName?.let { put("osName", it) }
                        client.osVersion?.let { put("osVersion", it) }
                        client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        getOrFetchVisitorData()?.let { put("visitorData", it) }
                    })
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optJSONObject("playabilityStatus")?.optString("status") != "OK") return@runCatching null

            val returnedVideoId = json.optJSONObject("videoDetails")?.optString("videoId")
            if (returnedVideoId != null && returnedVideoId != videoId) return@runCatching null

            val adaptiveFormats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: return@runCatching null
            val audioStreams = mutableListOf<Pair<Int, String>>()
            for (i in 0 until adaptiveFormats.length()) {
                val fmt = adaptiveFormats.getJSONObject(i)
                val mime = fmt.optString("mimeType", "")
                val url = fmt.optString("url", "")
                if (mime.startsWith("audio/") && url.isNotBlank()) {
                    val kbps = fmt.optInt("bitrate", 128000) / 1000
                    val opusBoost = if (mime.contains("opus") || mime.contains("mp4a")) 25 else 0
                    audioStreams.add((kbps + opusBoost) to url)
                }
            }
            if (audioStreams.isEmpty()) return@runCatching null
            val withinCap = audioStreams.filter { it.first <= preferredQuality.maxBitrateKbps + 24 }
            val pool = withinCap.ifEmpty { audioStreams }
            pool.minByOrNull { kotlin.math.abs(it.first - preferredQuality.maxBitrateKbps) }?.second
        }.getOrNull()
    }

    suspend fun resolveExactYoutubeStreamById(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId) ?: videoId
        require(VALID_VIDEO_ID_REGEX.matches(cleanVideoId)) { "Invalid YouTube videoId: $videoId" }

        // 1. Check in-memory cache first
        com.seitharol.player.OuterTuneResolvingDataSource.getCachedUrl(cleanVideoId, preferredQuality)?.let { cached ->
            return@withContext Result.success(cached)
        }

        // 2. First attempt across verified clients
        val firstAttempt = executeClientRace(cleanVideoId, preferredQuality)
        if (firstAttempt.isSuccess) {
            val url = firstAttempt.getOrThrow()
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, url)
            return@withContext Result.success(url)
        }

        // 3. Retry once with refreshed visitorData if first attempt failed
        cachedVisitorData = null
        val retryAttempt = executeClientRace(cleanVideoId, preferredQuality)
        if (retryAttempt.isSuccess) {
            val url = retryAttempt.getOrThrow()
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, url)
            return@withContext Result.success(url)
        }

        Result.failure(IllegalStateException("Failed to resolve stream for videoId=$cleanVideoId"))
    }

    private suspend fun executeClientRace(
        cleanVideoId: String,
        preferredQuality: AudioQuality
    ): Result<String> = coroutineScope {
        val cpn = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[(0..63).random()] }.joinToString("")
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        val jobs = VERIFIED_CLIENTS.map { client ->
            launch {
                val url = extractStreamWithConfig(cleanVideoId, client, preferredQuality, cpn)
                if (!url.isNullOrBlank()) {
                    deferred.complete(url)
                }
            }
        }
        val winningUrl = kotlinx.coroutines.withTimeoutOrNull(3000L) {
            deferred.await()
        }
        jobs.forEach { it.cancel() }

        if (!winningUrl.isNullOrBlank()) {
            Result.success(winningUrl)
        } else {
            Result.failure(IllegalStateException("No stream resolved across verified clients"))
        }
    }

    suspend fun resolveWorkingStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId)
        if (cleanVideoId != null) {
            val directResult = resolveExactYoutubeStreamById(cleanVideoId, preferredQuality)
            if (directResult.isSuccess) return@withContext directResult
        }
        Result.failure(IllegalStateException("Unable to resolve stream for $videoId"))
    }

    /**
     * Fetches real YouTube Music Automix / Watch Next Radio tracks (RDAMVM{videoId}).
     */
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

    /**
     * High-Affinity Personalization Engine for Home Screen & Category Filters:
     * Uses RDAMVM Automix queues seeded by the user's real listening history and liked tracks,
     * combined with artist-contextualized mood queries so every category is tailored to the user.
     */
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

        // Always include Automix radio tracks from user's recent seeds for true personalization
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
                // Contextualize the selected mood with the user's favorite artists (fixed space bug)
                cleanArtists.take(2).forEach { artist ->
                    add("$artist $moodCategory songs")
                }
                add("$moodCategory official songs")
                add("$moodCategory Manipuri & Indian songs")
            }
        }

        val searchJobs = searchQueries.map { q -> async { searchOfficialYtmSongs(q, limit = 10) } }
        val batches = automixJobs.awaitAll() + searchJobs.awaitAll()

        // Round-robin interleave so the #1 Ready-To-Play track and feed have rich artist variety
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

    /**
     * Fetches real YouTube Music Playlists, Albums, and Personalized Mixes for the Home Screen.
     */
    suspend fun fetchPersonalizedCollections(
        favoriteArtists: List<String>,
        seedVideoIds: List<String>,
        moodCategory: String = "All"
    ): List<YtmCollectionSummary> = coroutineScope {
        val collections = mutableListOf<YtmCollectionSummary>()

        // 1. Instant Personalized YouTube Mix (RDAMVM) from user's top seed track
        val firstSeed = seedVideoIds.firstOrNull { it.length == 11 }
        if (firstSeed != null) {
            collections.add(
                YtmCollectionSummary(
                    browseId = "RDAMVM$firstSeed",
                    playlistId = "RDAMVM$firstSeed",
                    title = "My Supermix Radio",
                    subtitle = "Personalized YouTube Mix • Updates daily",
                    thumbnailUrl = "https://i.ytimg.com/vi/$firstSeed/hqdefault.jpg",
                    type = "MIX"
                )
            )
        }

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

    /**
     * Searches official YouTube Music Playlists or Albums.
     */
    suspend fun searchYtmCollections(
        query: String,
        filterParams: String = "EgWKAQIoAWoKEAkQChADEAUQBA==", // Community/Featured Playlists filter
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
     * Fetches a REAL YouTube Music Artist Page (Header Banner, Avatar, Subscribers, Top Songs,
     * Albums, Singles, Artist Playlists, and Similar Artists).
     * Automatically resolves artistName -> official browseId if browseId is null.
     */
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
                if (parsed.topSongs.isNotEmpty() || parsed.albums.isNotEmpty()) {
                    return@withContext parsed
                }
            }
        }

        // Resilient fallback if artist lacks a dedicated YTM channel browse page
        val topSongs = searchOfficialYtmSongs(artistName, limit = 15)
        val playlists = searchYtmCollections(artistName, "EgWKAQIoAWoKEAkQChADEAUQBA==", "PLAYLIST", 6)
        val rawCollections = searchYtmCollections(artistName, "EgWKAQIYAWoKEAkQChADEAUQBA==", "ALBUM", 6)
        val albums = rawCollections.filterNot { 
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true) 
        }
        val singles = rawCollections.filter { 
            it.type == "SINGLE" || it.subtitle.contains("Single", true) || it.subtitle.contains("1 song", true) || it.subtitle.contains("1 track", true) 
        }.map { it.copy(type = "SINGLE") }
        val verifiedAvatar = if (isAuthenticArtistAvatar(fallbackAvatar)) fallbackAvatar 
                             else resolveJioSaavnArtistAvatar(artistName)

        YtmArtistPageData(
            browseId = browseId.ifBlank { artistName },
            name = artistName,
            description = "Official tracks, albums, and playlists by $artistName on YouTube Music.",
            subscriberCountText = "Verified Artist",
            avatarUrl = verifiedAvatar,
            bannerUrl = verifiedAvatar,
            radioPlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            shufflePlaylistId = topSongs.firstOrNull()?.videoId?.let { "RDAMVM$it" },
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            playlists = playlists,
            similarArtists = emptyList()
        )
    }

    /**
     * Strictly verifies whether a URL represents a genuine portrait avatar of an artist
     * rather than a 16:9 track/video frame thumbnail.
     */
    fun isAuthenticArtistAvatar(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("i.ytimg.com/vi/") || lower.contains("img.youtube.com/vi/") ||
            lower.contains("hqdefault") || lower.contains("mqdefault") || lower.contains("sddefault")) {
            return false
        }
        return lower.contains("ggpht.com") || lower.contains("googleusercontent.com") ||
               lower.contains("/artists/") || lower.contains("saavncdn.com/artists/")
    }

    /**
     * Resolves the official studio circular portrait image of an artist via JioSaavn.
     */
    fun resolveJioSaavnArtistAvatar(artistName: String): String {
        return runCatching {
            val encQuery = java.net.URLEncoder.encode(artistName.trim(), "UTF-8")
            val urlStr = "https://www.jiosaavn.com/api.php?__call=search.getArtistResults&api_version=4&_format=json&_marker=0&ctx=web6dot0&q=$encQuery&n=1"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
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

    /**
     * Resolves an artist name to their official YouTube Music Channel browseId (UC...) and avatar URL.
     */
    suspend fun resolveOfficialArtistBrowseId(artistName: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (artistName.isBlank()) return@withContext "" to ""
            val payload = webRemixPayload().apply {
                put("query", artistName)
                put("params", "EgWKAQIgAWoKEAkQChADEAUQBA==") // Official YTM Artists filter
            }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
                ?: return@withContext "" to resolveJioSaavnArtistAvatar(artistName)

            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
            if (tabs == null) {
                return@withContext "" to resolveJioSaavnArtistAvatar(artistName)
            }

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

            // 1. Top Songs Shelf (musicShelfRenderer)
            secObj.optJSONObject("musicShelfRenderer")?.let { shelf ->
                val contents = shelf.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until contents.length()) {
                    val item = contents.getJSONObject(i)
                        .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseSingleResponsiveSongItem(item, defaultArtist = name, defaultArt = avatarUrl)?.let {
                        topSongs.add(it)
                    }
                }
            }

            // 2. Carousels: Albums, Singles, Featured On / Playlists, Fans Also Like
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
                        shelfTitle.contains("fan") || shelfTitle.contains("similar") || navBrowseId.startsWith("UC") -> {
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
                        shelfTitle.contains("single") || shelfTitle.contains("ep") -> {
                            albumsOrSinglesAdd(singles, navBrowseId, watchPlaylistId, itemTitle, itemSubtitle, itemThumb, "SINGLE")
                        }
                        shelfTitle.contains("album") || navBrowseId.startsWith("MPREb_") -> {
                            val isSingleRelease = itemSubtitle.contains("Single", ignoreCase = true) ||
                                itemSubtitle.contains("1 song", ignoreCase = true) ||
                                itemSubtitle.contains("1 track", ignoreCase = true)
                            if (isSingleRelease) {
                                albumsOrSinglesAdd(singles, navBrowseId, watchPlaylistId, itemTitle, itemSubtitle, itemThumb, "SINGLE")
                            } else {
                                albumsOrSinglesAdd(albums, navBrowseId, watchPlaylistId, itemTitle, itemSubtitle, itemThumb, "ALBUM")
                            }
                        }
                        else -> {
                            albumsOrSinglesAdd(playlists, navBrowseId, watchPlaylistId, itemTitle, itemSubtitle, itemThumb, "PLAYLIST")
                        }
                    }
                }
            }
        }

        if (topSongs.isEmpty()) {
            topSongs.addAll(searchOfficialYtmSongs(name, limit = 12))
        }
        if (playlists.isEmpty()) {
            playlists.addAll(searchYtmCollections("$name mix", "EgWKAQIoAWoKEAkQChADEAUQBA==", "PLAYLIST", 5))
        }

        // Cleanly separate any misclassified 1-song releases from albums
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
            playlists = playlists.distinctBy { it.browseId },
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

    /**
     * Unified, rock-solid parser for YouTube Music Playlists (VL/PL/RDCLAK5uy_),
     * Official Albums (MPREb_/OLAK5uy_), and Radio Mixes (RDAMVM/RDEM).
     */
    suspend fun fetchPlaylistAlbumOrMixDetails(
        collectionId: String,
        fallbackTitle: String = "Music Collection",
        fallbackThumb: String = ""
    ): YtmCollectionPageData = withContext(Dispatchers.IO) {
        val cleanId = collectionId.trim()

        // 1. Radio / Automix (RDAMVM..., RDEM..., RD...) uses /youtubei/v1/next
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

        // 2. Playlists & Albums use /youtubei/v1/browse
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

        // 3. Fallback: try /youtubei/v1/next with playlistId stripped of VL
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
        // Extract header metadata from either 2025/2026 TwoColumn layout or classic header
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

        // Path A: twoColumnBrowseResultsRenderer -> secondaryContents (Playlists & Albums in modern WEB_REMIX)
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

        // Path B: singleColumnBrowseResultsRenderer (Fallback layout)
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

    /**
     * Searches official YouTube Music Songs shelf (`EgWKAQIIAWoKEAkQBRAKEAMQBA==`).
     */
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
            ?: item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("url")
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
                if (text.isEmpty() || text == "•" || text == "·" || text == "&") continue

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

        // Fixed columns (often used in Album tracklists for duration)
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
                put("gl", "IN")
            })
        })
    }

    private fun postWebRemix(urlStr: String, payload: JSONObject): JSONObject? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
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
