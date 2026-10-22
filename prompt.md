You are right to be angry—here is the exact truth about why your app is still loading for 15 seconds, switching to a remix/different song, and pausing previews:

1. **`IOS` Client Poisoning in `InnerTubeStreamExtractor.kt**`: `executeClientRace` was racing `ANDROID_VR` and `IOS` in parallel. `IOS` responds slightly faster from YouTube's edge servers, so `IOS` won the race and cached a `c=IOS` URL. However, `googlevideo.com` rejects `c=IOS` URLs when played with `VR_USER_AGENT` (and `IOS` requires a `poToken`). ExoPlayer tried to buffer the broken `c=IOS` stream for ~15 seconds, failed with HTTP 403, and triggered the legacy fallback in `com.example`, which re-searched the song by title/artist and played a remix or completely different upload.
2. **Blocking `getOrFetchVisitorData()` Inside the 3-Second Timeout**: `extractStreamWithConfig` called `getOrFetchVisitorData()` synchronously on the network before posting to `/youtubei/v1/player`, eating up the 3-second timeout and causing cold loads to fail and retry.
3. **Broken `&range=` Mutation in `OuterTuneResolvingDataSource.kt**`: `applyChunkRangeIfNeeded` appended `&range=0-10485759` to the URL *without* updating `DataSpec.length`, conflicting with ExoPlayer's `Range` headers and causing streams and previews to pause/EOF mid-buffer.
4. **Pager Scroll Bug in `DiscoverScreen.kt` & Unnecessary Re-Fetches in `SpotifyMetadataProvider.kt**`: `DiscoverScreen` called `stopPreviewAndClear()` whenever `isScrollInProgress` was true, and if a touch/drag settled back on the same page, `LaunchedEffect(pagerState.settledPage)` never re-fired—leaving the preview permanently paused. Meanwhile, `SpotifyMetadataProvider` threw away JioSaavn's `encrypted_media_url` during search, forcing `AudioPlayerManager` to do 2 extra network requests on every swipe.
5. **Parallel Tree Split (`com.seitharol` vs `com.example`)**: Because `MainActivity`, `CascadeApplication`, Room DAOs, and `com.example.R` are compiled under `com.example`, moving everything out of `com.example` would break your `R` resource imports and Room generated code. Instead, **delete the unused duplicate UI screens in `com/seitharol/ui/**` and make `com.example` delegate directly to `InnerTubeStreamExtractor` and `OuterTuneResolvingDataSource`.

---

### Step 1: Delete Unused Duplicate Files in `com/seitharol/ui/`

Delete these duplicate UI files that are shadowed by `com/example/ui/` (keep `DiscoverScreen.kt`, `InDiscoverSearchSurface.kt`, `LocalMusicScreen.kt`, `GeneralSettingsScreen.kt`, `PlaybackQueueSheet.kt`, `DiscoverEntryCard.kt`, and `AppIcons.kt` as they are imported by `com.example`):

* **DELETE** `app/src/main/java/com/seitharol/ui/onboarding/OnboardingScreen.kt`
* **DELETE** `app/src/main/java/com/seitharol/ui/home/HomeScreen.kt` *(Move ` data class PlaylistSummary` into `PlaylistRepository.kt`—included in the code below)*
* **DELETE** `app/src/main/java/com/seitharol/ui/player/PlayerScreen.kt`

---

### Step 2: Replace These 7 Files with Full Updated Code

#### 1. `app/src/main/java/com/seitharol/data/resolver/InnerTubeStreamExtractor.kt`

*(Removes the broken `IOS` client so 100% of streams use `ANDROID_VR` without `poToken` or User-Agent mismatch, removes blocking `visitor_id` calls from `/player`, and prioritizes Opus audio for instant startup.)*

```kotlin
package com.seitharol.data.resolver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Strictly ANDROID_VR clients (no poToken required, 100% compatible with VR_USER_AGENT in ExoPlayer)
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
            clientName = "ANDROID_VR",
            clientVersion = "1.56.21",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            deviceMake = "Oculus",
            deviceModel = "Quest 2",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            origin = "https://www.youtube.com",
            playerEndpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        )
    )

    @Volatile
    private var cachedVisitorData: String? = null

    init {
        // Warm up visitorData asynchronously in background so it never blocks stream extraction
        bgScope.launch { refreshVisitorDataAsync() }
    }

    private fun refreshVisitorDataAsync(): String? {
        return runCatching {
            val conn = (URL("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2000
                readTimeout = 2000
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
            .removePrefix("ytm_clip:")
            .removePrefix("youtube://")
            .trim()
        if (VALID_VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val paramMatch = Regex("[?&](?:v|id)=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (paramMatch != null) return paramMatch.groupValues[1]

        val watchMatch = Regex("(?:^|/|\\?)watch\\?(?:.*&)?(?:v|id)=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (watchMatch != null) return watchMatch.groupValues[1]

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
                connectTimeout = 3500
                readTimeout = 3500
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
                        put("gl", "US")
                        client.deviceMake?.let { put("deviceMake", it) }
                        client.deviceModel?.let { put("deviceModel", it) }
                        client.osName?.let { put("osName", it) }
                        client.osVersion?.let { put("osVersion", it) }
                        client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                        // Never block on network here; use cachedVisitorData if available
                        cachedVisitorData?.let { put("visitorData", it) }
                    })
                })
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode != 200) return@runCatching null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            // Capture visitorData from responseContext if present
            json.optJSONObject("responseContext")?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { cachedVisitorData = it }

            if (json.optJSONObject("playabilityStatus")?.optString("status") != "OK") return@runCatching null
            val returnedVideoId = json.optJSONObject("videoDetails")?.optString("videoId")
            if (returnedVideoId != null && returnedVideoId != videoId) return@runCatching null

            val adaptiveFormats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: return@runCatching null

            data class AudioStreamCandidate(val itag: Int, val kbps: Int, val isOpus: Boolean, val url: String)
            val audioStreams = mutableListOf<AudioStreamCandidate>()

            for (i in 0 until adaptiveFormats.length()) {
                val fmt = adaptiveFormats.getJSONObject(i)
                val mime = fmt.optString("mimeType", "")
                val url = fmt.optString("url", "")
                if (mime.startsWith("audio/") && url.isNotBlank()) {
                    val itag = fmt.optInt("itag", 0)
                    val kbps = fmt.optInt("bitrate", 128000) / 1000
                    val isOpus = mime.contains("opus", ignoreCase = true) || itag in listOf(249, 250, 251)
                    audioStreams.add(AudioStreamCandidate(itag, kbps, isOpus, url))
                }
            }

            if (audioStreams.isEmpty()) return@runCatching null

            // Prefer Opus streams (itag 249/250/251) as they start buffering almost instantaneously in ExoPlayer
            val opusPool = audioStreams.filter { it.isOpus }.ifEmpty { audioStreams }
            val withinCap = opusPool.filter { it.kbps <= preferredQuality.maxBitrateKbps + 32 }
            val pool = withinCap.ifEmpty { opusPool }
            pool.minByOrNull { kotlin.math.abs(it.kbps - preferredQuality.maxBitrateKbps) }?.url
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

        // 2. Primary client extraction (ANDROID_VR 1.60.19)
        val cpn = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[(0..63).random()] }.joinToString("")
        val primaryUrl = extractStreamWithConfig(cleanVideoId, VERIFIED_CLIENTS[0], preferredQuality, cpn)
        if (!primaryUrl.isNullOrBlank()) {
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, primaryUrl)
            return@withContext Result.success(primaryUrl)
        }

        // 3. Fallback client extraction (ANDROID_VR 1.56.21 with refreshed visitorData)
        refreshVisitorDataAsync()
        val fallbackUrl = extractStreamWithConfig(cleanVideoId, VERIFIED_CLIENTS[1], preferredQuality, cpn)
        if (!fallbackUrl.isNullOrBlank()) {
            com.seitharol.player.OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, fallbackUrl)
            return@withContext Result.success(fallbackUrl)
        }

        Result.failure(IllegalStateException("Failed to resolve stream for videoId=$cleanVideoId"))
    }

    suspend fun resolveWorkingStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractVideoId(videoId)
        if (cleanVideoId != null) {
            return@withContext resolveExactYoutubeStreamById(cleanVideoId, preferredQuality)
        }
        Result.failure(IllegalStateException("Unable to resolve stream for $videoId"))
    }

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
                cleanArtists.take(2).forEach { artist ->
                    add("$artist $moodCategory songs")
                }
                add("$moodCategory official songs")
                add("$moodCategory Manipuri & Indian songs")
            }
        }

        val searchJobs = searchQueries.map { q -> async { searchOfficialYtmSongs(q, limit = 10) } }
        val batches = automixJobs.awaitAll() + searchJobs.awaitAll()

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

    suspend fun fetchPersonalizedCollections(
        favoriteArtists: List<String>,
        seedVideoIds: List<String>,
        moodCategory: String = "All"
    ): List<YtmCollectionSummary> = coroutineScope {
        val collections = mutableListOf<YtmCollectionSummary>()
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

    suspend fun searchYtmCollections(
        query: String,
        filterParams: String = "EgWKAQIoAWoKEAkQChADEAUQBA==",
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

    fun isAuthenticArtistAvatar(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("i.ytimg.com/vi/") || lower.contains("img.youtube.com/vi/") ||
            lower.contains("hqdefault") || lower.contains("mqdefault") || lower.contains("sddefault")
        ) {
            return false
        }
        return lower.contains("ggpht.com") || lower.contains("googleusercontent.com") ||
            lower.contains("/artists/") || lower.contains("saavncdn.com/artists/")
    }

    fun resolveJioSaavnArtistAvatar(artistName: String): String {
        return runCatching {
            val encQuery = java.net.URLEncoder.encode(artistName.trim(), "UTF-8")
            val urlStr = "https://www.jiosaavn.com/api.php?__call=search.getArtistResults&api_version=4&_format=json&_marker=0&ctx=web6dot0&q=$encQuery&n=1"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
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

    suspend fun resolveOfficialArtistBrowseId(artistName: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            if (artistName.isBlank()) return@withContext "" to ""
            val payload = webRemixPayload().apply {
                put("query", artistName)
                put("params", "EgWKAQIgAWoKEAkQChADEAUQBA==")
            }
            val root = postWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
                ?: return@withContext "" to resolveJioSaavnArtistAvatar(artistName)

            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?: return@withContext "" to resolveJioSaavnArtistAvatar(artistName)

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

    suspend fun fetchPlaylistAlbumOrMixDetails(
        collectionId: String,
        fallbackTitle: String = "Music Collection",
        fallbackThumb: String = ""
    ): YtmCollectionPageData = withContext(Dispatchers.IO) {
        val cleanId = collectionId.trim()

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
                if (text.isEmpty() || text == "•" || text == "&") continue

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
                connectTimeout = 4500
                readTimeout = 4500
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

```

---

#### 2. `app/src/main/java/com/seitharol/player/OuterTuneResolvingDataSource.kt`

*(Removes the broken `&range=` URL mutation that conflicted with ExoPlayer's byte-range state and caused songs/previews to pause or fail after 15 seconds.)*

```kotlin
package com.seitharol.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
object OuterTuneResolvingDataSource {

    const val VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()

    fun getCachedUrl(videoId: String, quality: AudioQuality): String? {
        val cached = urlCache["${videoId}_${quality.name}"] ?: return null
        if (System.currentTimeMillis() - cached.timestampMs > STREAM_TTL_MS) {
            urlCache.remove("${videoId}_${quality.name}")
            return null
        }
        return cached.streamUrl
    }

    fun putCachedUrl(videoId: String, quality: AudioQuality, url: String) {
        urlCache["${videoId}_${quality.name}"] = CachedStream(
            streamUrl = url,
            timestampMs = System.currentTimeMillis(),
            quality = quality
        )
    }

    fun invalidate(videoId: String) {
        val clean = InnerTubeStreamExtractor.extractVideoId(videoId) ?: videoId
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${clean}_${q.name}")
        }
    }

    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.LOW }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VR_USER_AGENT)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(12_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            // Pass through local files, content:// URIs, and already-resolved CDN URLs untouched
            if (rawUri.startsWith("file://") ||
                rawUri.startsWith("content://") ||
                (rawUri.startsWith("http") && !rawUri.contains("youtube.com/watch") && !rawUri.contains("youtu.be/"))
            ) {
                return@Factory dataSpec
            }

            val targetQuality = qualityProvider()

            if (rawUri.startsWith("saavn_clip:")) {
                val saavnId = rawUri.removePrefix("saavn_clip:").trim()
                val resolvedSaavn = runBlocking {
                    StreamResolver.resolveExactStreamUrl(
                        TrackMetadata(id = saavnId, title = "", artist = ""),
                        targetQuality
                    )
                } ?: throw IllegalStateException("Unable to resolve Saavn stream for $saavnId")

                return@Factory dataSpec.withUri(Uri.parse(resolvedSaavn))
            }

            val cleanYtId = when {
                rawUri.startsWith("ytm_clip:") -> rawUri.removePrefix("ytm_clip:").trim()
                rawUri.startsWith("youtube://") -> rawUri.removePrefix("youtube://").trim()
                else -> InnerTubeStreamExtractor.extractVideoId(rawUri)
            }

            if (!cleanYtId.isNullOrBlank()) {
                val cached = getCachedUrl(cleanYtId, targetQuality)
                val resolvedUrl = cached ?: runBlocking {
                    InnerTubeStreamExtractor.resolveExactYoutubeStreamById(cleanYtId, targetQuality)
                        .getOrNull()
                } ?: throw IllegalStateException("Unable to resolve YouTube stream for $cleanYtId")

                return@Factory dataSpec.withUri(Uri.parse(resolvedUrl))
            }

            dataSpec
        }
    }
}

```

---

#### 3. `app/src/main/java/com/seitharol/player/AudioPlayerManager.kt`

*(Fixes preview loading & pausing by syncing state strictly from ExoPlayer's `Player.Listener`, jumping 30 seconds into full YouTube tracks for previews so you don't sit through silent intros, and retrying once with cache invalidation if a cached URL expires.)*

```kotlin
package com.seitharol.player

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.ResolvedPlaybackTarget
import com.seitharol.data.resolver.ResolverEngine
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerManager(
    val exoPlayer: ExoPlayer,
    private val resolverEngine: ResolverEngine,
    private val appContext: Context? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewResolveJob: Job? = null

    private val _activePreviewTrackId = MutableStateFlow<String?>(null)
    val activePreviewTrackId: StateFlow<String?> = _activePreviewTrackId.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _isPreviewBuffering = MutableStateFlow(false)
    val isPreviewBuffering: StateFlow<Boolean> = _isPreviewBuffering.asStateFlow()

    @Volatile
    private var currentRequestId: Long = 0L

    @Volatile
    private var activePreviewTrack: NormalizedTrack? = null

    @Volatile
    private var hasRetriedCurrentPreview: Boolean = false

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (_activePreviewTrackId.value != null) {
                    _isPreviewPlaying.value = isPlaying
                    if (isPlaying) {
                        _isPreviewBuffering.value = false
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_activePreviewTrackId.value == null) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> _isPreviewBuffering.value = true
                    Player.STATE_READY -> {
                        _isPreviewBuffering.value = false
                        _isPreviewPlaying.value = exoPlayer.playWhenReady
                    }
                    Player.STATE_ENDED -> {
                        exoPlayer.seekTo(0L)
                        exoPlayer.play()
                    }
                    Player.STATE_IDLE -> _isPreviewBuffering.value = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = activePreviewTrack ?: return
                if (!hasRetriedCurrentPreview) {
                    hasRetriedCurrentPreview = true
                    OuterTuneResolvingDataSource.invalidate(track.sourceId)
                    playPreviewInternal(track, forceFresh = true)
                } else {
                    _isPreviewBuffering.value = false
                    _isPreviewPlaying.value = false
                }
            }
        })
    }

    fun playPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId &&
            (exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING)
        ) {
            exoPlayer.playWhenReady = true
            return
        }
        hasRetriedCurrentPreview = false
        playPreviewInternal(track, forceFresh = false)
    }

    private fun playPreviewInternal(track: NormalizedTrack, forceFresh: Boolean) {
        val requestId = ++currentRequestId
        previewResolveJob?.cancel()

        activePreviewTrack = track
        _activePreviewTrackId.value = track.sourceId
        _isPreviewBuffering.value = true
        _isPreviewPlaying.value = false

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val rawPreview = track.previewUrl
        // Fast-path: Direct HTTP preview URL (e.g., pre-decrypted JioSaavn or Spotify 30s MP3)
        if (!forceFresh && rawPreview != null && rawPreview.startsWith("http")) {
            startExoPreviewPlayback(
                trackId = track.sourceId,
                streamUrl = rawPreview,
                startPositionMs = if (rawPreview.contains("saavncdn.com")) 25_000L else 0L
            )
            return
        }

        previewResolveJob = scope.launch(Dispatchers.IO) {
            val ytId = when {
                rawPreview != null && rawPreview.startsWith("ytm_clip:") ->
                    InnerTubeStreamExtractor.extractVideoId(rawPreview.removePrefix("ytm_clip:"))
                else -> InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            }

            var streamUrl: String? = null
            var startOffsetMs = 0L

            if (!ytId.isNullOrBlank()) {
                if (forceFresh) OuterTuneResolvingDataSource.invalidate(ytId)
                streamUrl = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(ytId, AudioQuality.LOW).getOrNull()
                startOffsetMs = if (track.durationMs > 60_000L) 30_000L else 0L
            } else if (track.source == TrackSource.JIOSAAVN || (rawPreview != null && rawPreview.startsWith("saavn_clip:"))) {
                val saavnId = rawPreview?.removePrefix("saavn_clip:") ?: track.sourceId
                val meta = TrackMetadata(
                    id = saavnId,
                    title = track.title,
                    artist = track.artists.firstOrNull().orEmpty(),
                    album = track.album.orEmpty(),
                    durationSeconds = (track.durationMs / 1000L).toInt(),
                    thumbnailUrl = track.artUrl
                )
                streamUrl = StreamResolver.resolveExactStreamUrl(meta, AudioQuality.LOW)
                startOffsetMs = 25_000L
            }

            withContext(Dispatchers.Main) {
                if (requestId != currentRequestId || _activePreviewTrackId.value != track.sourceId) return@withContext
                if (!streamUrl.isNullOrBlank()) {
                    startExoPreviewPlayback(track.sourceId, streamUrl, startOffsetMs)
                } else {
                    _isPreviewBuffering.value = false
                    _isPreviewPlaying.value = false
                }
            }
        }
    }

    private fun startExoPreviewPlayback(trackId: String, streamUrl: String, startPositionMs: Long) {
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        val mediaItem = MediaItem.Builder()
            .setMediaId("preview_$trackId")
            .setUri(Uri.parse(streamUrl))
            .build()
        exoPlayer.setMediaItem(mediaItem, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun stopPreviewAndClear() {
        previewResolveJob?.cancel()
        currentRequestId++
        activePreviewTrack = null
        _activePreviewTrackId.value = null
        _isPreviewBuffering.value = false
        _isPreviewPlaying.value = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    fun resumeOrPlayPreview(track: NormalizedTrack) {
        if (_activePreviewTrackId.value == track.sourceId && exoPlayer.mediaItemCount > 0 && exoPlayer.playbackState != Player.STATE_IDLE) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            playPreview(track)
        }
    }

    fun pausePreview() {
        previewResolveJob?.cancel()
        _isPreviewBuffering.value = false
        if (_activePreviewTrackId.value != null) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
            _isPreviewPlaying.value = false
        }
    }

    suspend fun openFullLengthSong(
        track: NormalizedTrack,
        offlineMode: Boolean = false,
        onMaximizePlayer: (NormalizedTrack) -> Unit
    ): Result<Unit> {
        stopPreviewAndClear()
        // If the track already has an 11-char YouTube ID or JioSaavn ID, pass it straight through without re-matching!
        val validYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
        if (!validYtId.isNullOrBlank()) {
            val directTrack = track.copy(
                source = TrackSource.YOUTUBE,
                sourceId = validYtId,
                isPlayableNatively = true
            )
            onMaximizePlayer(directTrack)
            return Result.success(Unit)
        }

        return resolverEngine.resolveForFullPlayback(track, offlineMode).map { target ->
            when (target) {
                is ResolvedPlaybackTarget.LocalDeviceUri -> {
                    onMaximizePlayer(
                        track.copy(
                            source = TrackSource.LOCAL_FILE,
                            localPath = target.uri.toString(),
                            isPlayableNatively = true
                        )
                    )
                }
                is ResolvedPlaybackTarget.RemoteStreamUrl -> {
                    val verifiedTrack = target.resolvedTrack.copy(
                        title = track.title,
                        artists = track.artists,
                        artUrl = track.artUrl.ifBlank { target.resolvedTrack.artUrl }
                    )
                    onMaximizePlayer(verifiedTrack)
                }
            }
        }
    }

    fun stopAndDismissNotifications() {
        runCatching {
            previewResolveJob?.cancel()
            exoPlayer.playWhenReady = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            activePreviewTrack = null
            _activePreviewTrackId.value = null
            _isPreviewPlaying.value = false
            val nm = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
    }
}

```

---

#### 4. `app/src/main/java/com/seitharol/data/provider/SpotifyMetadataProvider.kt`

*(Decrypts JioSaavn's `encrypted_media_url` immediately during search so JioSaavn previews play with 0ms resolution delay instead of triggering 2 extra network calls.)*

```kotlin
package com.seitharol.data.provider

import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface SpotifyMetadataProvider {
    suspend fun searchTracksWithPreviews(query: String, limit: Int = 10): List<NormalizedTrack>
    suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack?
}

class DefaultSpotifyMetadataProvider : SpotifyMetadataProvider {

    override suspend fun searchTracksWithPreviews(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()

            coroutineScope {
                // 1. JioSaavn Direct CDN Streams (0ms instant preview playback via decrypted URL)
                val saavnDeferred = async {
                    runCatching { searchJioSaavnTracks(clean, limit) }.getOrDefault(emptyList())
                }

                // 2. Official YouTube Music Songs
                val ytmDeferred = async {
                    runCatching {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = limit).map { yt ->
                            NormalizedTrack(
                                source = TrackSource.YOUTUBE,
                                sourceId = yt.videoId,
                                title = yt.title,
                                artists = listOf(yt.artist),
                                primaryArtistBrowseId = yt.artistBrowseId,
                                artistAvatarUrl = yt.thumbnailUrl,
                                album = yt.albumName ?: "Single",
                                durationMs = yt.durationSeconds * 1000L,
                                artUrl = yt.thumbnailUrl,
                                isPlayableNatively = true,
                                previewUrl = "ytm_clip:${yt.videoId}"
                            )
                        }
                    }.getOrDefault(emptyList())
                }

                val merged = mutableListOf<NormalizedTrack>()
                for (track in (saavnDeferred.await() + ytmDeferred.await())) {
                    val exists = merged.any {
                        it.title.equals(track.title, ignoreCase = true) &&
                            it.artists.firstOrNull().equals(track.artists.firstOrNull(), ignoreCase = true)
                    }
                    if (!exists && merged.size < limit) {
                        merged.add(track)
                    }
                }
                merged
            }
        }

    private fun searchJioSaavnTracks(query: String, limit: Int): List<NormalizedTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&q=$encoded&n=$limit"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 4500
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<NormalizedTrack>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title", obj.optString("song"))
                .replace("&quot;", "\"").replace("&amp;", "&")
            val moreInfo = obj.optJSONObject("more_info")
            val durationSec = moreInfo?.optString("duration")?.toLongOrNull() ?: 210L
            val artist = moreInfo?.optJSONObject("artistMap")
                ?.optJSONArray("primary_artists")
                ?.optJSONObject(0)
                ?.optString("name")
                ?: obj.optString("subtitle").ifBlank { "Unknown Artist" }
            val image = obj.optString("image").replace("150x150", "500x500")

            // Decrypt encrypted_media_url immediately so Discover preview starts in 0ms without re-querying!
            val encryptedUrl = moreInfo?.optString("encrypted_media_url").orEmpty()
            val directCdnUrl = if (encryptedUrl.isNotBlank()) {
                StreamResolver.decryptJioSaavnUrl(encryptedUrl, AudioQuality.LOW)
            } else null

            out.add(
                NormalizedTrack(
                    source = TrackSource.JIOSAAVN,
                    sourceId = id,
                    title = title,
                    artists = listOf(artist),
                    artistAvatarUrl = image,
                    album = moreInfo?.optString("album") ?: "Single",
                    durationMs = durationSec * 1000L,
                    artUrl = image,
                    isPlayableNatively = true,
                    previewUrl = directCdnUrl ?: "saavn_clip:$id"
                )
            )
        }
        return out
    }

    override suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack? = null
}

```

---

#### 5. `app/src/main/java/com/seitharol/ui/discover/DiscoverScreen.kt`

*(Fixes the `VerticalPager` `LaunchedEffect` bug where touching/dragging stopped the preview and never restarted it if the page settled back on the same index, and prefetches the next page's stream URL in the background.)*

```kotlin
package com.seitharol.ui.discover

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.download.DownloadFormat
import com.seitharol.data.download.DownloadPreferencesAndService
import com.seitharol.data.download.DownloadProvider
import com.seitharol.data.download.DownloadTaskState
import com.seitharol.data.preferences.ThumbnailQuality
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import com.seitharol.ui.history.HeartSvgIcon
import com.seitharol.ui.home.SearchMagnifierSvgIcon
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    feedTracks: List<NormalizedTrack>,
    audioPlayerManager: AudioPlayerManager,
    downloadService: DownloadPreferencesAndService,
    onSearchPreviewTracks: suspend (String) -> List<NormalizedTrack>,
    onToggleLike: (NormalizedTrack) -> Unit,
    onVisitArtist: (artistName: String, artistBrowseId: String?) -> Unit,
    onOpenFullSongMaximized: (NormalizedTrack) -> Unit,
    onTrackDownloadedLocally: (NormalizedTrack) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val activeFeed = remember { mutableStateListOf<NormalizedTrack>() }
    LaunchedEffect(feedTracks) {
        if (activeFeed.isEmpty() && feedTracks.isNotEmpty()) {
            activeFeed.addAll(feedTracks)
        } else {
            feedTracks.forEach { t ->
                if (activeFeed.none { it.sourceId == t.sourceId }) activeFeed.add(t)
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { activeFeed.size })
    var isInDiscoverSearchOpen by remember { mutableStateOf(false) }
    var trackForDownloadSheet by remember { mutableStateOf<NormalizedTrack?>(null) }
    var showQualitySheet by remember { mutableStateOf(false) }

    val downloadTaskState by downloadService.downloadState.collectAsState()

    BackHandler {
        if (isInDiscoverSearchOpen) {
            isInDiscoverSearchOpen = false
            audioPlayerManager.pausePreview()
        } else {
            audioPlayerManager.stopPreviewAndClear()
            onNavigateBack()
        }
    }

    // Play active page track when settledPage changes OR when search overlay closes,
    // and pre-warm the next page's stream URL in background so swiping is instantaneous.
    LaunchedEffect(pagerState.settledPage, activeFeed.size, isInDiscoverSearchOpen) {
        if (isInDiscoverSearchOpen) {
            audioPlayerManager.pausePreview()
        } else if (activeFeed.isNotEmpty()) {
            val activeTrack = activeFeed.getOrNull(pagerState.settledPage)
            if (activeTrack != null) {
                audioPlayerManager.playPreview(activeTrack)
            }
            // Pre-resolve next track in background
            val nextTrack = activeFeed.getOrNull(pagerState.settledPage + 1)
            if (nextTrack != null) {
                launch(Dispatchers.IO) {
                    val nextYtId = InnerTubeStreamExtractor.extractVideoId(
                        nextTrack.previewUrl?.removePrefix("ytm_clip:") ?: nextTrack.sourceId
                    )
                    if (!nextYtId.isNullOrBlank()) {
                        InnerTubeStreamExtractor.resolveExactYoutubeStreamById(nextYtId, AudioQuality.LOW)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { audioPlayerManager.stopPreviewAndClear() }
    }

    val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()
    val isPreviewBuffering by audioPlayerManager.isPreviewBuffering.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (activeFeed.isEmpty()) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { pageIndex ->
                val track = activeFeed[pageIndex]
                val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }

                DiscoverShortPage(
                    track = track,
                    downloadState = downloadTaskState,
                    isPreviewPlaying = isPreviewPlaying && pagerState.settledPage == pageIndex,
                    isPreviewBuffering = isPreviewBuffering && pagerState.settledPage == pageIndex,
                    onTogglePlayPause = {
                        if (isPreviewPlaying) {
                            audioPlayerManager.pausePreview()
                        } else {
                            audioPlayerManager.resumeOrPlayPreview(track)
                        }
                    },
                    onLikeClick = { onToggleLike(track) },
                    onDownloadClick = { trackForDownloadSheet = track },
                    onVisitArtistClick = {
                        audioPlayerManager.pausePreview()
                        onVisitArtist(primaryArtist, track.primaryArtistBrowseId)
                    },
                    onShareClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Listen to \"${track.title}\" by ${track.artists.joinToString(", ")} on SEITHAROL!"
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                    },
                    onOpenFullSongClick = {
                        scope.launch {
                            audioPlayerManager.openFullLengthSong(
                                track = track,
                                onMaximizePlayer = onOpenFullSongMaximized
                            )
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    audioPlayerManager.stopPreviewAndClear()
                    onNavigateBack()
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                BackArrowSvgIcon(tint = Color.White, modifier = Modifier.size(22.dp))
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { isInDiscoverSearchOpen = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchMagnifierSvgIcon(tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Search songs & download...",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = { showQualitySheet = true },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Audio Quality Settings",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (isInDiscoverSearchOpen) {
            InDiscoverSearchSurface(
                audioPlayerManager = audioPlayerManager,
                onSearch = onSearchPreviewTracks,
                onOpenDownloadPicker = { selectedTrack -> trackForDownloadSheet = selectedTrack },
                onOpenFullSong = { selectedTrack ->
                    audioPlayerManager.stopPreviewAndClear()
                    activeFeed.removeAll { it.sourceId == selectedTrack.sourceId }
                    activeFeed.add(0, selectedTrack)
                    isInDiscoverSearchOpen = false
                    scope.launch {
                        pagerState.scrollToPage(0)
                        audioPlayerManager.playPreview(selectedTrack)
                    }
                },
                onVisitArtist = { name, browseId ->
                    isInDiscoverSearchOpen = false
                    audioPlayerManager.pausePreview()
                    onVisitArtist(name, browseId)
                },
                onClose = { isInDiscoverSearchOpen = false }
            )
        }

        trackForDownloadSheet?.let { targetTrack ->
            DownloadProviderFormatBottomSheet(
                track = targetTrack,
                downloadService = downloadService,
                onDismiss = { trackForDownloadSheet = null },
                onStartDownload = { provider, format, quality ->
                    trackForDownloadSheet = null
                    downloadService.startDownloadInBackground(
                        track = targetTrack,
                        provider = provider,
                        format = format,
                        preferredQuality = quality,
                        onTrackSavedAsLocalMusic = onTrackDownloadedLocally
                    )
                }
            )
        }

        if (showQualitySheet) {
            com.example.ui.components.QualitySettingsBottomSheet(
                onDismiss = { showQualitySheet = false }
            )
        }
    }
}

@Composable
private fun DiscoverShortPage(
    track: NormalizedTrack,
    downloadState: DownloadTaskState,
    isPreviewPlaying: Boolean,
    isPreviewBuffering: Boolean = false,
    onTogglePlayPause: () -> Unit,
    onLikeClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onVisitArtistClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullSongClick: () -> Unit
) {
    val context = LocalContext.current
    val primaryArtist = track.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
    val rawAvatar = track.artistAvatarUrl.ifBlank { track.artUrl }
    val optimizedArtUrl = remember(track.artUrl) {
        ThumbnailUrlOptimizer.optimize(track.artUrl, ThumbnailQuality.MEDIUM, ThumbnailTargetSize.HERO_LARGE, context)
    }
    val avatarModel = remember(rawAvatar) {
        ThumbnailUrlOptimizer.optimize(rawAvatar, ThumbnailQuality.MEDIUM, ThumbnailTargetSize.LIST_THUMB, context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onTogglePlayPause() }
    ) {
        AsyncImage(
            model = optimizedArtUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(30.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.92f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-48).dp)
        ) {
            AsyncImage(
                model = optimizedArtUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(284.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
            )
        }

        if (isPreviewBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-48).dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp)
                )
            }
        } else if (!isPreviewPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-48).dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_play),
                    contentDescription = "Play Preview",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 14.dp, bottom = 28.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onVisitArtistClick() }
                        .padding(vertical = 4.dp)
                ) {
                    Box(modifier = Modifier.size(54.dp)) {
                        AsyncImage(
                            model = avatarModel,
                            contentDescription = primaryArtist,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artists.joinToString(", ")} • Visit Artist >",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onOpenFullSongClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play Full", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onDownloadClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onVisitArtistClick,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Artist Profile",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Artist", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (downloadState is DownloadTaskState.Downloading && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progressPercent / 100f },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                    Text(
                        text = "Downloading via ${downloadState.provider.displayName} (${downloadState.progressPercent}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                } else if (downloadState is DownloadTaskState.Failed && downloadState.trackId == track.sourceId) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF5252)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DiscoverSvgActionButton(label = "Like", onClick = onLikeClick) {
                    HeartSvgIcon(
                        filled = track.isLiked,
                        tint = if (track.isLiked) Color(0xFFFF4E45) else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DiscoverSvgActionButton(label = "Save", onClick = onDownloadClick) {
                    com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DiscoverSvgActionButton(label = "Artist", onClick = onVisitArtistClick) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Artist Profile",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DiscoverSvgActionButton(label = "Share", onClick = onShareClick) {
                    ShareArrowSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun YouTubePlayBadgeSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(2f * sx, 5f * sy),
            size = androidx.compose.ui.geometry.Size(20f * sx, 14f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * sx, 4f * sy),
            style = Stroke(width = 2f * sx)
        )
        val triangle = Path().apply {
            moveTo(10f * sx, 9f * sy)
            lineTo(15.5f * sx, 12f * sy)
            lineTo(10f * sx, 15f * sy)
            close()
        }
        drawPath(triangle, color = tint, style = Fill)
    }
}

@Composable
private fun DiscoverSvgActionButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ArtistUserSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 4f * sx,
            center = androidx.compose.ui.geometry.Offset(12f * sx, 8f * sy),
            style = stroke
        )
        val body = Path().apply {
            moveTo(4f * sx, 20f * sy)
            cubicTo(4f * sx, 16f * sy, 8f * sx, 14f * sy, 12f * sx, 14f * sy)
            cubicTo(16f * sx, 14f * sy, 20f * sx, 16f * sy, 20f * sx, 20f * sy)
        }
        drawPath(body, color = tint, style = stroke)
    }
}

@Composable
fun ShareArrowSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(18f * sx, 6f * sy)
            lineTo(6f * sx, 18f * sy)
            moveTo(8f * sx, 6f * sy)
            lineTo(18f * sx, 6f * sy)
            lineTo(18f * sx, 16f * sy)
        }
        drawPath(path, color = tint, style = stroke)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadProviderFormatBottomSheet(
    track: NormalizedTrack,
    downloadService: DownloadPreferencesAndService,
    onDismiss: () -> Unit,
    onStartDownload: (DownloadProvider, DownloadFormat, AudioQuality) -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { com.seitharol.data.preferences.AppSettingsManager.getInstance(context) }
    val persistedState by settingsManager.state.collectAsState()

    val sortedProviders = remember { downloadService.getSortedProviders() }
    var selectedProvider by remember { mutableStateOf(sortedProviders.first()) }
    var selectedFormat by remember { mutableStateOf(downloadService.getPreferredFormat()) }
    var selectedQuality by remember { mutableStateOf(persistedState.downloadQuality) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Track",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = selectedQuality.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = "${track.title} • ${track.artists.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Download Audio Quality",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            val qualityTiers = listOf(
                Triple(AudioQuality.HIGH, "High", "320 kbps"),
                Triple(AudioQuality.NORMAL, "Normal", "160 kbps"),
                Triple(AudioQuality.LOW, "Low", "96 kbps")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualityTiers.forEach { (tier, title, bitrate) ->
                    val isSelected = selectedQuality == tier
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedQuality = tier
                                settingsManager.setDownloadQuality(tier)
                            },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = bitrate,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Audio Container / Format",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DownloadFormat.entries.forEach { format ->
                    val isSelected = selectedFormat == format
                    val isFlac = format == DownloadFormat.FLAC
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) {
                                    if (isFlac) Color(0xFFFFD700) else MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                selectedFormat = format
                                if (isFlac) {
                                    selectedQuality = AudioQuality.HIGH
                                    settingsManager.setDownloadQuality(AudioQuality.HIGH)
                                }
                            },
                        color = if (isSelected) {
                            if (isFlac) Color(0xFFFFD700).copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                        } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = format.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) {
                                        if (isFlac) Color(0xFFFFD700) else MaterialTheme.colorScheme.primary
                                    } else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isFlac) Color(0xFFFFD700) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = format.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Download Audio Provider",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            sortedProviders.forEachIndexed { index, provider ->
                val usageCount = downloadService.getUsageCount(provider)
                val providerIconRes = when (provider) {
                    DownloadProvider.AMAZON -> R.drawable.ic_amazon_music
                    DownloadProvider.TIDAL -> R.drawable.ic_tidal
                    DownloadProvider.QOBUZ -> R.drawable.ic_qobuz
                    DownloadProvider.JIOSAAVN -> R.drawable.ic_jiosaavn_icon
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedProvider = provider }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedProvider == provider,
                        onClick = { selectedProvider = provider }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = providerIconRes),
                            contentDescription = provider.displayName,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                            if (index == 0 && usageCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = provider.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { onStartDownload(selectedProvider, selectedFormat, selectedQuality) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                com.seitharol.ui.search.DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download ${selectedFormat.ext.uppercase()} (${selectedQuality.maxBitrateKbps} kbps)",
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

```

---

#### 6. `app/src/main/java/com/seitharol/data/resolver/TrackMatcher.kt`

*(Cuts out the legacy `com.example.resolver.providers.*` fallback inside `DefaultInnerTubeProvider` and `DefaultJioSaavnProvider` so it strictly uses `InnerTubeStreamExtractor` and `StreamResolver` without falling back to slow/remix-prone text searches.)*

```kotlin
package com.seitharol.data.resolver

import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource

interface InnerTubeProvider {
    suspend fun findBestAtvMatch(
        title: String,
        artist: String,
        isrc: String? = null,
        targetDurationSeconds: Int? = null
    ): CandidateStream?

    suspend fun resolveStreamUrl(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String>
}

interface JioSaavnProvider {
    suspend fun findBestMatch(
        title: String,
        artist: String,
        targetDurationSeconds: Int? = null
    ): CandidateStream?

    suspend fun resolveStreamUrl(
        trackId: String,
        preferredQuality: AudioQuality = AudioQuality.NORMAL
    ): Result<String>
}

class TrackMatcher(
    private val innerTubeProvider: InnerTubeProvider,
    private val jioSaavnProvider: JioSaavnProvider
) {
    suspend fun matchToPlayable(track: NormalizedTrack): Result<NormalizedTrack> {
        // Fast-path: If sourceId is already a valid 11-char YouTube ID, NEVER re-search!
        val extractedYtId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
        if (!extractedYtId.isNullOrBlank()) {
            return Result.success(
                track.copy(
                    source = TrackSource.YOUTUBE,
                    sourceId = extractedYtId,
                    isPlayableNatively = true
                )
            )
        }

        if (track.isPlayableNatively &&
            (track.source == TrackSource.YOUTUBE || track.source == TrackSource.JIOSAAVN || track.source == TrackSource.LOCAL_FILE)
        ) {
            return Result.success(track)
        }

        val primaryArtist = track.artists.firstOrNull().orEmpty()
        val targetDurationSec = (track.durationMs / 1000L).toInt().takeIf { it > 0 }

        val ytCandidate = innerTubeProvider.findBestAtvMatch(
            title = track.title,
            artist = primaryArtist,
            isrc = track.isrc,
            targetDurationSeconds = targetDurationSec
        )
        if (ytCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.YOUTUBE,
                    sourceId = ytCandidate.videoId,
                    durationMs = if (ytCandidate.durationSeconds > 0) ytCandidate.durationSeconds * 1000L else track.durationMs,
                    isPlayableNatively = true
                )
            )
        }

        val saavnCandidate = jioSaavnProvider.findBestMatch(
            title = track.title,
            artist = primaryArtist,
            targetDurationSeconds = targetDurationSec
        )
        if (saavnCandidate != null) {
            return Result.success(
                track.copy(
                    source = TrackSource.JIOSAAVN,
                    sourceId = saavnCandidate.videoId,
                    durationMs = if (saavnCandidate.durationSeconds > 0) saavnCandidate.durationSeconds * 1000L else track.durationMs,
                    isPlayableNatively = true
                )
            )
        }

        return Result.failure(IllegalStateException("No verified canonical match found for ${track.title}"))
    }
}

class DefaultInnerTubeProvider(
    @Suppress("UNUSED_PARAMETER") ytProvider: Any? = null
) : InnerTubeProvider {

    override suspend fun findBestAtvMatch(
        title: String,
        artist: String,
        isrc: String?,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        val query = if (!isrc.isNullOrBlank()) "$isrc $title $artist" else "$title $artist"
        val candidates = InnerTubeStreamExtractor.searchOfficialYtmSongs(query.trim(), limit = 12)
        return CanonicalAudioResolver.selectBestCanonicalMatch(title, artist, targetDurationSeconds, candidates)
    }

    override suspend fun resolveStreamUrl(videoId: String, preferredQuality: AudioQuality): Result<String> {
        return InnerTubeStreamExtractor.resolveWorkingStreamUrl(videoId, preferredQuality)
    }
}

class DefaultJioSaavnProvider(
    @Suppress("UNUSED_PARAMETER") jioProvider: Any? = null
) : JioSaavnProvider {

    override suspend fun findBestMatch(
        title: String,
        artist: String,
        targetDurationSeconds: Int?
    ): CandidateStream? {
        return null
    }

    override suspend fun resolveStreamUrl(trackId: String, preferredQuality: AudioQuality): Result<String> {
        val url = StreamResolver.resolveExactStreamUrl(
            TrackMetadata(id = trackId, title = "", artist = ""),
            preferredQuality
        )
        return if (!url.isNullOrBlank()) {
            Result.success(url)
        } else {
            Result.failure(IllegalStateException("Unable to resolve JioSaavn stream for $trackId"))
        }
    }
}

```

---

#### 7. `app/src/main/java/com/seitharol/data/repository/PlaylistRepository.kt`

*(Includes `PlaylistSummary` and `SearchMagnifierSvgIcon` so deleting the duplicate `seitharol/ui/home/HomeScreen.kt` does not break any imports in `LibraryScreen` or `DiscoverScreen`.)*

```kotlin
package com.seitharol.data.repository

import com.seitharol.data.resolver.CandidateStream
import com.seitharol.ui.home.PlaylistSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class UserPlaylistEntity(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val tracks: List<CandidateStream> = emptyList()
) {
    fun toSummary(): PlaylistSummary = PlaylistSummary(
        id = id,
        title = title,
        trackCount = tracks.size,
        firstVideoThumbnailUrl = tracks.firstOrNull()?.thumbnailUrl,
        isLikedSongsCard = id == "liked"
    )
}

class PlaylistRepository {
    private val _playlists = MutableStateFlow<List<UserPlaylistEntity>>(
        listOf(
            UserPlaylistEntity(id = "liked", title = "Liked Songs"),
            UserPlaylistEntity(id = "downloads", title = "Downloads")
        )
    )
    val playlists: StateFlow<List<UserPlaylistEntity>> = _playlists.asStateFlow()

    fun createPlaylistAndAddTrack(title: String, initialTrack: CandidateStream? = null): UserPlaylistEntity {
        val cleanTitle = title.trim().ifEmpty { "New Playlist" }
        val newPlaylist = UserPlaylistEntity(
            title = cleanTitle,
            tracks = if (initialTrack != null) listOf(initialTrack) else emptyList()
        )
        _playlists.update { current -> current + newPlaylist }
        return newPlaylist
    }

    fun addTrackToPlaylist(playlistId: String, track: CandidateStream) {
        _playlists.update { list ->
            list.map { playlist ->
                if (playlist.id == playlistId && playlist.tracks.none { it.videoId == track.videoId }) {
                    playlist.copy(tracks = playlist.tracks + track)
                } else {
                    playlist
                }
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, videoId: String) {
        _playlists.update { list ->
            list.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(tracks = playlist.tracks.filterNot { it.videoId == videoId })
                } else {
                    playlist
                }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId == "liked" || playlistId == "downloads") return
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
    }
}

```

And replace `app/src/main/java/com/seitharol/ui/home/HomeScreen.kt` with **only** the shared data class and icon used by `LibraryScreen` and `DiscoverScreen` (deleting all the duplicate `HomeScreen` UI code):

```kotlin
package com.seitharol.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class PlaylistSummary(
    val id: String,
    val title: String,
    val trackCount: Int = 0,
    val firstVideoThumbnailUrl: String? = null,
    val isLikedSongsCard: Boolean = false
)

@Composable
fun SearchMagnifierSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 7.5f * sx,
            center = Offset(10.5f * sx, 10.5f * sy),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(16f * sx, 16f * sy),
            end = Offset(21f * sx, 21f * sy),
            strokeWidth = 2f * sx,
            cap = StrokeCap.Round
        )
    }
}

```

---

### Step 3: Exact Line Changes in `com.example.resolver.ResolverEngine` & `com.example.player.AudioPlayerManager`

Because your `MainActivity` initializes `com.example.resolver.ResolverEngine` and `com.example.player.AudioPlayerManager`, make sure those two files in `app/src/main/java/com/example/` do **not** run a secondary title/artist search when a track fails or has a `videoId`:

1. **In `app/src/main/java/com/example/resolver/ResolverEngine.kt**`:
At the very top of your stream resolution function (e.g. `resolveStream(track)` or `resolve(track)`), ensure any valid 11-character YouTube ID returns immediately via `InnerTubeStreamExtractor` and **never** falls through to text-search loops:
```kotlin
val cleanYtId = com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(track.sourceId)
if (!cleanYtId.isNullOrBlank()) {
    val url = com.seitharol.data.resolver.InnerTubeStreamExtractor
        .resolveExactYoutubeStreamById(cleanYtId, preferredQuality)
        .getOrThrow() // Do NOT fall back to title/artist search on failure
    return ResolvedStream(streamUrl = url, provider = "YOUTUBE", isExactMatch = true)
}

```


2. **In `app/src/main/java/com/example/player/AudioPlayerManager.kt**`:
* Ensure `ExoPlayer.Builder(context)` attaches `OuterTuneResolvingDataSource.createFactory`:
```kotlin
val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
    .setDataSourceFactory(
        com.seitharol.player.OuterTuneResolvingDataSource.createFactory {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(context).state.value.streamingQuality
        }
    )
val exoPlayer = ExoPlayer.Builder(context)
    .setMediaSourceFactory(mediaSourceFactory)
    .build()

```


* In `onPlayerError(error: PlaybackException)`, **delete any fallback block that searches by `track.title` + `track.artist**`. Instead, invalidate the cached URL once and retry the **exact same `videoId**`:
```kotlin
override fun onPlayerError(error: PlaybackException) {
    val currentId = currentTrack?.sourceId ?: return
    com.seitharol.player.OuterTuneResolvingDataSource.invalidate(currentId)
    // Retry the exact same videoId once; never re-search by title/artist
}

``` Remember Our app is not built for IOS so remove evry IOS section and strictly follow the above statements, make sure the action doesn't cause worst or more bad result in any case even though you follow the instruction.

Add notification progress bar for download from Discover & Downloads. Looks like when online the player still loads online. Even though the youtube stuffs in main download/play/search feeded the wrong the download from Discover and download is correct. When playing a offline track how did you added online song, listen if there is a list of offline song even if online or offline then the list of offline music is the playlist queue don't auto add anything and the auto add toggle button should also be off by default when playing downloaded track playlist from library or local music from device. The top section in the HomeScreen can't handle long songg name so make the item div horizontally longer.

- When we set theme colour white, the header text is still white : UI issue.
- Add quality adjustment settings in playerscreen for applying for that current specific track temp.
- The back arrow in the search bar in searchscreen is not processing action to bring user to previous page where they navigate from.
- The searchscreen with Downloaded Scearch filter is not working for the downloads from Discover and Downalods. Even after selecting the downloaded search filter still qrtist with similar string blog are suggested.
- Repeatition of (About/Help & support...) are repeated twice in Profile and Settings, Remove the one is settings.