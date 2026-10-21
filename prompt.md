### 1. Additional Bugs Caught from Your Source Files

Inspecting your source files revealed four additional bugs that explain the remaining playback, search, and download failures:

* **`InDiscoverSearchSurface.kt` Never Calls `onOpenFullSong`:** Although `InDiscoverSearchSurface` declares the `onOpenFullSong: (NormalizedTrack) -> Unit` parameter, neither the search result card's `.clickable` modifier nor the `"Play"` `OutlinedButton` ever calls `onOpenFullSong(item)`. Instead, both call `audioPlayerManager.playPreview(item)`, which traps you inside the search overlay and immediately pauses the audio as soon as you press Back.


* **Shared `ExoPlayer` Instance Between Main Player and Discover:** In `CascadeApp.kt`, `seitharolPlayerManager` is initialized with `viewModel.playerManager.playerInstance`, sharing the exact same `ExoPlayer` instance between the main app and Discover. When `CascadeApp.kt` runs `LaunchedEffect(currentSubScreen) { if (currentSubScreen is SubScreen.Discover) viewModel.pause() }`, it races against `DiscoverScreen`'s initial `playPreview` call on the same player instance and pauses the first song.


* **`DownloadPreferencesAndService.kt` Hardcodes a Windows Chrome User-Agent on Mobile Streams:** In `InnerTubeStreamExtractor.kt`, stream URLs are extracted using mobile clients (`IOS`, `ANDROID_VR`, `ANDROID_TESTSUITE`), but `DownloadPreferencesAndService.kt` requests those `googlevideo.com` URLs using a desktop browser header (`Mozilla/5.0 (Windows NT 10.0; Win64; x64)...` and `Origin: [https://music.youtube.com](https://music.youtube.com)`), which causes Google's CDN to reject the download with `HTTP 403 Forbidden`.


* **Mismatched `WEB_REMIX` Client Version Header:** In `InnerTubeStreamExtractor.kt`, `webRemixPayload()` specifies `clientVersion = "1.20250310.01.00"`, whereas `postWebRemix()` sends `X-YouTube-Client-Version: 1.20250101.01.00` with a 4500ms timeout, causing intermittent blank search responses.



---

### 2. Standalone `OuterTuneResolvingDataSource.kt` (Drop-In Media3 Architecture)

Below is the standalone `OuterTuneResolvingDataSource.kt` file. It hooks into Media3 `ExoPlayer` via `ResolvingDataSource.Factory`, resolves 11-character YouTube `videoId`s lazily at the exact moment `ExoPlayer` opens the stream, enforces the `ANDROID_VR` User-Agent across both extraction and CDN playback, caches resolved URLs for 4 hours, and appends chunked range parameters so YouTube never throttles playback.

```kotlin
// File: seitharol/player/OuterTuneResolvingDataSource.kt
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

    private const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
    private const val CHUNK_LENGTH_BYTES = 10L * 1024L * 1024L // 10 MB anti-throttle chunking

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
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${videoId}_${q.name}")
        }
    }

    /**
     * Creates an OuterTune-grade Media3 DataSource.Factory that:
     * 1. Sends the exact ANDROID_VR User-Agent required by googlevideo.com streams.
     * 2. Lazily resolves YouTube 11-char videoIds, "ytm_clip:{videoId}", or "saavn_clip:{id}" URIs.
     * 3. Never re-searches a known YouTube videoId by title/artist (0% chance of playing a remix).
     */
    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.LOW }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VR_USER_AGENT)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            // Pass through local files, content:// URIs, and already-resolved googlevideo/saavn HTTP URLs
            if (rawUri.startsWith("file://") ||
                rawUri.startsWith("content://") ||
                (rawUri.startsWith("http") && !rawUri.contains("youtube.com/watch"))
            ) {
                return@Factory applyChunkRangeIfNeeded(dataSpec, rawUri)
            }

            val targetQuality = qualityProvider()
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
                }?.also { freshUrl ->
                    putCachedUrl(cleanYtId, targetQuality, freshUrl)
                } ?: throw IllegalStateException("Unable to resolve YouTube stream for $cleanYtId")

                return@Factory applyChunkRangeIfNeeded(
                    dataSpec.withUri(Uri.parse(resolvedUrl)),
                    resolvedUrl
                )
            }

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

            dataSpec
        }
    }

    private fun applyChunkRangeIfNeeded(dataSpec: DataSpec, url: String): DataSpec {
        if (!url.contains("googlevideo.com")) return dataSpec
        // If URL already has range query param or bounded DataSpec length, leave as-is
        if (url.contains("&range=") || dataSpec.length != -1L) return dataSpec
        val start = dataSpec.position
        val end = start + CHUNK_LENGTH_BYTES - 1L
        val rangedUrl = "$url&range=$start-$end"
        return dataSpec.withUri(Uri.parse(rangedUrl))
    }
}

```

---

### 3. Ultimate Copy-Paste Master Prompt for Your Coding Agent

Copy and paste the entire block below into your Coding Agent. It includes `OuterTuneResolvingDataSource.kt` and addresses every single architectural, UI, playback, navigation, search, and download bug across both `com.example.*` and `com.seitharol.*`.

```markdown
You must fix all playback, stream resolution, UI, navigation, Light/Dark theme, search, and download issues across the codebase. Follow every task below strictly and completely.

### CRITICAL RULE 0: UNIFY `com.example.*` AND `com.seitharol.*` (DO NOT EDIT DEAD FILES ONLY)
The project currently has two parallel package trees:
- `com.example.*` (mounted by `MainActivity.kt` -> `CascadeApp.kt`, which imports `com.example.ui.screens.*`, `com.example.data.AppPreferences`, `com.example.player.AudioPlayerManager`, and `com.example.resolver.ResolverEngine`)
- `com.seitharol.*` (which contains `InnerTubeStreamExtractor`, `DiscoverScreen`, `InDiscoverSearchSurface`, `GeneralSettingsScreen`, `AppSettingsManager`, `DownloadPreferencesAndService`, and duplicate screens)

**MANDATORY REQUIREMENT:**
1. First, inspect `MainActivity.kt` and `CascadeApp.kt` to verify every active screen and ViewModel they render.
2. Apply all UI, player, resolver, and preference fixes to **BOTH** the active `com.example.*` classes AND their `com.seitharol.*` counterparts so there is zero divergence between the two packages.

---

### TASK 1: Adopt OuterTune Architecture (`OuterTuneResolvingDataSource`) — Instant Playback (<1s), Zero Remix Fallbacks, Consistent User-Agent

**Root Causes to Fix:**
1. `InnerTubeStreamExtractor.kt` calls `/youtubei/v1/player` without `visitorData`, causing YouTube to throttle or reject `IOS` and `ANDROID_TESTSUITE`.
2. `ExoPlayer` and `DownloadPreferencesAndService.kt` request `googlevideo.com` URLs using a Windows Desktop Chrome User-Agent while the URL was signed for `ANDROID_VR` / `IOS`, causing immediate `HTTP 403 Forbidden`.
3. When a stream fails with 403, `StreamResolver.kt` waits 18 seconds in `resolveFromPipedStrict` (looping through 3 dead Piped servers) and `com.example.resolver.ResolverEngine` falls back to re-searching the song by Title/Artist across secondary providers, which plays a random remix minutes later.

**Implementation Steps:**

#### 1A. Create `seitharol/player/OuterTuneResolvingDataSource.kt`
Create `seitharol/player/OuterTuneResolvingDataSource.kt` with the following implementation:

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

    private const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
    private const val CHUNK_LENGTH_BYTES = 10L * 1024L * 1024L // 10 MB

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
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${videoId}_${q.name}")
        }
    }

    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.LOW }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VR_USER_AGENT)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            if (rawUri.startsWith("file://") ||
                rawUri.startsWith("content://") ||
                (rawUri.startsWith("http") && !rawUri.contains("[youtube.com/watch](https://youtube.com/watch)"))
            ) {
                return@Factory applyChunkRangeIfNeeded(dataSpec, rawUri)
            }

            val targetQuality = qualityProvider()
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
                }?.also { freshUrl ->
                    putCachedUrl(cleanYtId, targetQuality, freshUrl)
                } ?: throw IllegalStateException("Unable to resolve YouTube stream for $cleanYtId")

                return@Factory applyChunkRangeIfNeeded(
                    dataSpec.withUri(Uri.parse(resolvedUrl)),
                    resolvedUrl
                )
            }

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

            dataSpec
        }
    }

    private fun applyChunkRangeIfNeeded(dataSpec: DataSpec, url: String): DataSpec {
        if (!url.contains("googlevideo.com")) return dataSpec
        if (url.contains("&range=") || dataSpec.length != -1L) return dataSpec
        val start = dataSpec.position
        val end = start + CHUNK_LENGTH_BYTES - 1L
        return dataSpec.withUri(Uri.parse("$url&range=$start-$end"))
    }
}
```

#### 1B. Update `InnerTubeStreamExtractor.kt` with `visitorData`, Prioritized `ANDROID_VR`, and Cache
In `seitharol/data/resolver/InnerTubeStreamExtractor.kt`:
1. Put `ANDROID_VR` (`1.60.19`, `clientId = "28"`) FIRST in `VERIFIED_CLIENTS`, followed by `IOS` (`19.45.4`, `clientId = "5"`). Remove `ANDROID_TESTSUITE`.
2. Add `visitorData` fetching and caching, and pass it inside `context.client` in `extractStreamWithConfig`:

```kotlin
@Volatile
private var cachedVisitorData: String? = null

private fun getOrFetchVisitorData(): String? {
    cachedVisitorData?.let { return it }
    return runCatching {
        val conn = (URL("[https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false](https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false)").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 2500
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", com.seitharol.player.OuterTuneResolvingDataSource.VR_USER_AGENT)
            setRequestProperty("Origin", "[https://music.youtube.com](https://music.youtube.com)")
            setRequestProperty("Referer", "[https://music.youtube.com/](https://music.youtube.com/)")
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
```

3. Inside `extractStreamWithConfig`, include `getOrFetchVisitorData()?.let { put("visitorData", it) }` inside `put("client", JSONObject().apply { ... })`.
4. Inside `resolveExactYoutubeStreamById(videoId, preferredQuality)`:
   - Check `OuterTuneResolvingDataSource.getCachedUrl(cleanVideoId, preferredQuality)` first and return immediately if cached.
   - When resolved, store via `OuterTuneResolvingDataSource.putCachedUrl(cleanVideoId, preferredQuality, winningUrl)`.
   - If the first attempt fails, clear `cachedVisitorData = null` and retry once with a fresh `visitorData`.
5. In `postWebRemix`, fix `X-YouTube-Client-Version` so it matches `webRemixPayload()` (`"1.20250310.01.00"`) and increase `connectTimeout` and `readTimeout` to `6000`.

#### 1C. Delete Dead Piped Loops & Ban Blind Title Re-Searching on Known VideoIds
1. In `seitharol/data/resolver/StreamResolver.kt`:
   - Delete `resolveFromPipedStrict` completely.
   - In `resolveExactStreamUrl`, if `InnerTubeStreamExtractor.extractVideoId(track.id)` is non-null, resolve ONLY that exact `videoId` via `InnerTubeStreamExtractor.resolveExactYoutubeStreamById(extractedYtId, quality)` and return immediately. NEVER fall back to searching by text when a `videoId` is already present.
2. In `com.example.resolver.ResolverEngine` and `com.example.player.AudioPlayerManager`:
   - Wire `ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OuterTuneResolvingDataSource.createFactory { AudioQuality.LOW }))` into the player initialization.
   - If a `Track` has a valid 11-character YouTube `videoId` (or `sourceId`), NEVER fall back to re-searching by title/artist on failure and NEVER replace its `videoId` with another video's ID.

---

### TASK 2: Set Default Settings — Data Saver Audio + Normal (Medium) Thumbnails Everywhere

1. In `seitharol/data/preferences/AppSettingsManager.kt` AND `com.example.data.AppPreferences`:
   - Default `streamingQuality` = `AudioQuality.LOW` (Data Saver 96kbps)
   - Default `downloadQuality` = `AudioQuality.LOW` (Data Saver 96kbps)
   - Default `thumbnailQuality` = `ThumbnailQuality.MEDIUM` (Normal)
   - In `loadFromPrefs()` / `AppPreferences`, ensure these defaults are used and synchronize preferences between `AppPreferences` and `AppSettingsManager` so changing settings in Profile/Settings updates both immediately.
2. In `DiscoverScreen.kt` (`DiscoverShortPage`), optimize `track.artUrl` and `avatarModel` with `ThumbnailUrlOptimizer.optimize(track.artUrl, ThumbnailQuality.MEDIUM, ThumbnailTargetSize.HERO_LARGE, context)` instead of loading raw unoptimized URLs.

---

### TASK 3: Fix Light Theme Broken White Text & Grey-on-Grey Airplane/Offline Mode Switch

1. Audit `com.example.ui.screens.ProfileScreen.kt`, `com.example.ui.screens.PlayerScreen.kt`, `com.example.ui.screens.ArtistScreen.kt`, `com.example.ui.screens.LibraryScreen.kt`, `com.example.ui.screens.HomeScreen.kt`, `com.example.ui.screens.SearchScreen.kt`, and `seitharol/ui/settings/GeneralSettingsScreen.kt`:
   - Replace every hardcoded `Color.White` or `Color(0xFFF1F1F1)` on theme-dependent backgrounds (`MaterialTheme.colorScheme.background` or `surface`) with `MaterialTheme.colorScheme.onBackground` or `MaterialTheme.colorScheme.onSurface`.
   - Replace hardcoded grey subtitle text colors with `MaterialTheme.colorScheme.onSurfaceVariant`.
2. In `com.example.ui.screens.ProfileScreen.kt` (specifically the Airplane / Offline Mode switch) and `seitharol/ui/settings/GeneralSettingsScreen.kt` (lines 163–169 and 310–316), replace the grey-on-grey `SwitchDefaults.colors(...)` with high-contrast Material 3 colors:

```kotlin
SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline
)
```

---

### TASK 4: Fix PlayerScreen — Song Title Click Bug & Strict "Up Next" Auto-Popup Rule

Apply in **BOTH** `com.example.ui.screens.PlayerScreen.kt` (rendered by `CascadeApp.kt`) AND `seitharol/ui/player/PlayerScreen.kt`:

1. **Song Name Must Not Trigger Playback or Change Track:**
   - Remove any `.clickable` modifier from the Song Title `Text` and its parent `Row`/`Column` container in `PlayerScreen`.
   - Attach `.clickable` **ONLY** to the Artist Name `Text`, and ensure its `onClick` ONLY triggers `onArtistClick(currentTrack.artist, currentTrack.artistBrowseId)` (or `viewModel.navigateToArtist(...)`) without calling `playTrack()`, `seekToNext()`, or modifying playback state.
2. **Strict "Up Next" Visibility Rule:**
   - Track whether the current playback session was explicitly started from a **Playlist or Album** (`isPlayedFromPlaylistOrAlbum: Boolean` / `PlaybackSourceType.GROUP_CONTEXT`).
   - Playing a track from Home, Search, Discover, History, Local Music, or Artist Top Songs/Singles MUST set `isPlayedFromPlaylistOrAlbum = false` (`PlaybackSourceType.SINGLE_TRACK`).
   - In `PlayerScreen`, initialize:
     ```kotlin
     var isUpNextVisible by remember(currentTrack.id, isPlayedFromPlaylistOrAlbum) {
         mutableStateOf(isPlayedFromPlaylistOrAlbum)
     }
     ```
   - Never auto-pop the "Up Next" bar when `isPlayedFromPlaylistOrAlbum == false` (even if Autoplay populates background recommendations). It must only appear for non-playlist songs if the user explicitly taps the Queue/Up Next button.

---

### TASK 5: Fix Discover & Download — Dedicated Preview Player, Hide MiniPlayer, Instant First Play, Zero Scroll Bleed, In-Place Search Playback, and Real Downloads

#### 5A. Hide MiniPlayer & Pause Main Player When Entering Discover (`CascadeApp.kt`)
1. In `CascadeApp.kt`, hide the MiniPlayer whenever `currentSubScreen is SubScreen.Discover`:
   ```kotlin
   val showMiniPlayer = playerState.currentTrack != null &&
       !isPlayerExpanded &&
       currentSubScreen !is SubScreen.Discover
   ```
2. **Dedicated Preview ExoPlayer:** In `CascadeApp.kt`, do NOT pass `viewModel.playerManager.playerInstance` into `com.seitharol.player.AudioPlayerManager`! Instead, create a dedicated preview `ExoPlayer` instance configured with `OuterTuneResolvingDataSource.createFactory { AudioQuality.LOW }`, and dispose it cleanly in `DisposableEffect`:
   ```kotlin
   val previewExoPlayer = remember {
       androidx.media3.exoplayer.ExoPlayer.Builder(context)
           .setMediaSourceFactory(
               androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                   com.seitharol.player.OuterTuneResolvingDataSource.createFactory {
                       com.seitharol.data.resolver.AudioQuality.LOW
                   }
               )
           )
           .build()
   }
   DisposableEffect(Unit) {
       onDispose { previewExoPlayer.release() }
   }
   val seitharolPlayerManager = remember(previewExoPlayer) {
       val innerTube = com.seitharol.data.resolver.DefaultInnerTubeProvider(viewModel.resolverEngine.youtubeInnerTubeProvider)
       val jioSaavn = com.seitharol.data.resolver.DefaultJioSaavnProvider(viewModel.resolverEngine.jioSaavnProvider)
       val matcher = com.seitharol.data.resolver.TrackMatcher(innerTube, jioSaavn)
       val engine = com.seitharol.data.resolver.ResolverEngine(context, matcher, innerTube, jioSaavn)
       com.seitharol.player.AudioPlayerManager(previewExoPlayer, engine, context.applicationContext)
   }
   ```
   Because `previewExoPlayer` is separate from `viewModel.playerManager`, calling `viewModel.pause()` when entering `SubScreen.Discover` will pause the main song immediately and will NEVER pause the first Discover preview!

#### 5B. Fix First-Song Auto-Play & Scroll Audio Bleed (`DiscoverScreen.kt` & `seitharol/player/AudioPlayerManager.kt`)
1. In `seitharol/player/AudioPlayerManager.kt`:
   - Add `@Volatile private var currentRequestId: Long = 0L`.
   - In `playPreview(track: NormalizedTrack)`, increment `val requestId = ++currentRequestId`, immediately call `exoPlayer.stop()` and `exoPlayer.clearMediaItems()` on the Main thread, set `_isPreviewBuffering.value = true`, and before setting the resolved `MediaItem` on the Main thread, check `if (requestId != currentRequestId || _activePreviewTrackId.value != track.sourceId) return@withContext`.
   - Always resolve streams using `AudioQuality.LOW` (Data Saver) and do NOT use `ClippingConfiguration` on raw adaptive streams if it causes ExoPlayer range errors—let the stream play from `0L` with `Player.REPEAT_MODE_ONE`.
2. In `seitharol/ui/discover/DiscoverScreen.kt` (and any `com.example` equivalent):
   - Maintain a mutable feed list so search results can be injected at index 0:
     ```kotlin
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
     ```
   - Replace the 3 conflicting `LaunchedEffect` blocks with these exact 2 blocks:
     ```kotlin
     // 1. Immediately silence previous track as soon as user drags away from settled page
     LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
         if (pagerState.isScrollInProgress && pagerState.currentPage != pagerState.settledPage) {
             audioPlayerManager.stopPreviewAndClear()
         }
     }

     // 2. Play active track immediately on enter and when swipe settles
     LaunchedEffect(pagerState.settledPage, activeFeed.size, activeFeed.firstOrNull()?.sourceId, isInDiscoverSearchOpen) {
         if (isInDiscoverSearchOpen) {
             audioPlayerManager.pausePreview()
         } else if (activeFeed.isNotEmpty()) {
             val activeTrack = activeFeed.getOrNull(pagerState.settledPage)
             if (activeTrack != null) {
                 audioPlayerManager.playPreview(activeTrack)
             }
         }
     }
     ```

#### 5C. Fix Search Result Click in `InDiscoverSearchSurface.kt` — Play Directly on Discover UI
1. In `seitharol/ui/discover/InDiscoverSearchSurface.kt`:
   - Fix blank search results on rapid typing by catching `CancellationException` and rethrowing it instead of setting `results = emptyList()` on cancelled jobs:
     ```kotlin
     searchJob = scope.launch {
         delay(280L)
         isLoading = true
         try {
             val fetched = onSearch(newText.trim())
             results = fetched
         } catch (e: kotlinx.coroutines.CancellationException) {
             throw e
         } catch (_: Exception) {
             // Keep previous results or empty if none
         } finally {
             isLoading = false
         }
     }
     ```
   - When the user clicks a search result card OR clicks the `"Play"` button on a search result card in `InDiscoverSearchSurface.kt`, call `onOpenFullSong(item)`! (And add a small Play/Pause icon tap on the 64.dp thumbnail if they just want to preview inside the list, while clicking the card or the `"Play"` button calls `onOpenFullSong(item)`).
2. In `DiscoverScreen.kt`, wire `onOpenFullSong` in `InDiscoverSearchSurface` so it injects `selectedTrack` at index 0 of `activeFeed`, closes the search overlay, scrolls the pager to page 0, and plays it on the Discover & Download UI:
   ```kotlin
   onOpenFullSong = { selectedTrack ->
       audioPlayerManager.stopPreviewAndClear()
       activeFeed.removeAll { it.sourceId == selectedTrack.sourceId }
       activeFeed.add(0, selectedTrack)
       isInDiscoverSearchOpen = false
       scope.launch {
           pagerState.scrollToPage(0)
           audioPlayerManager.playPreview(selectedTrack)
       }
   }
   ```

#### 5D. Fix Real Downloads in `DownloadPreferencesAndService.kt` & `DiscoverScreen.kt`
1. In `DiscoverScreen.kt`, inside `DownloadProviderFormatBottomSheet`'s `onStartDownload` callback, call `downloadService.startDownloadInBackground(...)` instead of `scope.launch { downloadService.downloadTrackToPublicMusicStorage(...) }` so downloads continue reliably in `serviceScope`.
2. In `seitharol/data/download/DownloadPreferencesAndService.kt`:
   - When downloading from `googlevideo.com` (`isYt`), NEVER send the Windows Chrome User-Agent or `Origin: https://music.youtube.com`! Use `OuterTuneResolvingDataSource.VR_USER_AGENT`:
     ```kotlin
     if (isYt) {
         setRequestProperty("User-Agent", com.seitharol.player.OuterTuneResolvingDataSource.VR_USER_AGENT)
     } else {
         setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
     }
     ```
   - Ensure the downloaded file is saved to public `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (`Music/Seitharol`), persisted in `DownloadPreferencesAndService`, AND registered in `com.example.data.repository.DownloadRepository` / `CascadeViewModel` via `onTrackSavedAsLocalMusic` so it appears in Library -> Downloads and Local Music immediately.

---

### TASK 6: Fix Artist Page — Button Overlap, Singles Playing Directly as Songs, and Back Navigation

Apply in `com.example.ui.screens.ArtistScreen.kt`, `CascadeApp.kt`, and `CascadeViewModel.kt`:

1. **Fix Overlapping "Play Top" and "Artist" Action Buttons:**
   - In `ArtistScreen.kt`, place the header action buttons (`Play Top`, `Shuffle` / `Radio`) in a `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp))` where each button uses `modifier = Modifier.weight(1f).height(42.dp)`, `contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)`, and `Text(..., maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)`.
2. **Play Singles Directly (Never Open a Single as a 1-Song Playlist):**
   - In `ArtistScreen.kt` (second section: Albums and Singles), separate the click actions for Albums vs. Singles.
   - When the user clicks a Single (`collection.type == "SINGLE"` or `collection.subtitle.contains("Single", ignoreCase = true)` or clicked from the Singles carousel):
     - Do **NOT** navigate to `SubScreen.Playlist(collection.browseId)`!
     - Instead, call `viewModel.playSingleRelease(collection, artistName)` which resolves the track via `InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(collection.browseId).tracks.firstOrNull() ?: InnerTubeStreamExtractor.searchOfficialYtmSongs("${collection.title} $artistName", limit = 1).firstOrNull()` and plays it immediately as a single song (`isPlayedFromPlaylistOrAlbum = false`).
3. **Fix Back Navigation from Playlist/Album Inside Artist Page:**
   - In `CascadeApp.kt`, when opening an Album or Playlist from `SubScreen.Artist(artistName)`, always pass the current `SubScreen.Artist` as `returnSubScreen`:
     ```kotlin
     currentSubScreen = SubScreen.Playlist(
         playlistId = albumOrPlaylistId,
         returnSubScreen = currentSubScreen // Preserves SubScreen.Artist(artistName)
     )
     ```
   - In `CascadeApp.kt`, ensure both the system `BackHandler` and the `PlaylistScreen` `onBack` callback restore `returnSubScreen`:
     ```kotlin
     val returnScreen = (currentSubScreen as? SubScreen.Playlist)?.returnSubScreen
     currentSubScreen = returnScreen ?: SubScreen.None
     ```

---

### TASK 7: Fix Singer Search — Eliminate `"random_name, artist_name"` Collaborator Cards & Wrong Artist Channels

Apply in `seitharol/data/resolver/CanonicalAudioResolver.kt`, `seitharol/data/resolver/InnerTubeStreamExtractor.kt`, and `com.example.ui.screens.SearchScreen.kt` / `CascadeViewModel.kt`:

1. **Split Collaborator Strings & Filter by Search Query in `CanonicalAudioResolver.kt`:**
   - Update `filterVerifiedArtistsOnly` (and any artist extraction logic in `CascadeViewModel` / `SearchScreen`) so it splits multi-artist strings (`"Random Name, Artist Name"`) on `","`, `"&"`, `"feat."`, `"ft."`, and `"/"`, and only emits individual artist names that match the search query:
   ```kotlin
   fun filterVerifiedArtistsOnly(
       tracks: List<CandidateStream>,
       searchQuery: String = ""
   ): List<Pair<String, String?>> {
       val normQuery = normalizeText(searchQuery)
       return tracks
           .asSequence()
           .filter { track ->
               val isOfficialSource = track.videoType == "MUSIC_VIDEO_TYPE_ATV" ||
                   track.channelName.endsWith("- Topic", ignoreCase = true) ||
                   track.isVerifiedArtist ||
                   (track.artistBrowseId?.startsWith("UC") == true || track.artistBrowseId?.startsWith("MPLA") == true)
               val hasRemixUploaderName = BANNED_MODIFIERS.any { mod ->
                   track.artist.contains(mod, ignoreCase = true) || track.channelName.contains(mod, ignoreCase = true)
               }
               isOfficialSource && !hasRemixUploaderName &&
                   !track.artist.equals("Unknown Artist", ignoreCase = true) &&
                   !track.artist.equals("Unknown", ignoreCase = true)
           }
           .flatMap { track ->
               val parts = track.artist
                   .removeSuffix(" - Topic")
                   .split(",", "&", "feat.", "ft.", "/")
                   .map { it.trim() }
                   .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
               parts.mapIndexedNotNull { index, singleArtist ->
                   val normArtist = normalizeText(singleArtist)
                   if (normQuery.isNotBlank() && !normArtist.contains(normQuery) && !normQuery.contains(normArtist)) {
                       null
                   } else {
                       // Only attach track.artistBrowseId if this artist was the sole/primary artist
                       val validBrowseId = if (index == 0 && parts.size == 1) track.artistBrowseId else null
                       singleArtist to validBrowseId
                   }
               }.asSequence()
           }
           .distinctBy { normalizeText(it.first) }
           .toList()
   }
   ```
2. **Verify Artist Name Match in `InnerTubeStreamExtractor.resolveOfficialArtistBrowseId(artistName)`:**
   - Inside `resolveOfficialArtistBrowseId`, extract the candidate item's title from `flexColumns.optJSONObject(0)` and verify that `CanonicalAudioResolver.normalizeText(itemTitle)` shares core tokens with `CanonicalAudioResolver.normalizeText(artistName)` before accepting its `UC...` or `MPLA...` `browseId`.

---

### TASK 8: Move Library Filter Row Below Category Chips in the Active `com.example.ui.screens.LibraryScreen`

In `com.example.ui.screens.LibraryScreen.kt` (matching `seitharol/ui/library/LibraryScreen.kt`):
1. **Row 1 (Full-Width Category Chips):** Render a full-width `LazyRow` containing only the scrollable category `FilterChip` items (`All`, `Playlists`, `Downloads`, `Liked`) with `contentPadding = PaddingValues(horizontal = 16.dp)` and `horizontalArrangement = Arrangement.spacedBy(8.dp)`.
2. **Row 2 (Filter Button Directly Below):** Immediately below the `LazyRow`, render a separate full-width `Row` (`modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)`, `horizontalArrangement = Arrangement.End`) containing the compact Filter pill button and its `DropdownMenu`.

```