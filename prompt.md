Splited-Brain Package Duplication (com.example.* vs com.seitharol.* vs com.praxper.seitharol.*): Your project contains three parallel package trees (com.example.ui, com.seitharol.ui, and com.praxper.seitharol.ui) [cite: 1]. MainActivity.kt launches CascadeApp(viewModel = viewModel) inside com.example [cite: 1], while the AI repeatedly wrote fixes into disconnected files in com.seitharol or com.praxper.seitharol without wiring them to the active CascadeApp / CascadeViewModel [cite: 1]. I want only one not splited brain.

# CRITICAL ARCHITECTURAL & UI FIX SPECIFICATION (DO NOT SKIP OR STUB ANY SECTION)

You have repeatedly failed to fix 10 critical bugs because you either edited inactive duplicate files across `com.seitharol`, `com.praxper.seitharol`, and `com.example`, or applied partial workarounds. 

Before writing any code, verify which screen and player classes are actively mounted by `com.example.MainActivity` -> `com.example.ui.CascadeApp` and apply EVERY fix below to the ACTIVE classes used at runtime.

---

## 1. FIX PLAYING THE WRONG SONG (OUTERTUNE / INNERTUNE DIRECT-ID ARCHITECTURE)

### Root Cause
1. When a user clicks a song in Search, Home, Artist, or Playlist, the app currently passes metadata into `StreamResolver` / `TrackMatcher`, which performs a secondary text search (`title + artist`) or hits JioSaavn's fuzzy `autocomplete.get` (where `candidateDuration = 0` is hardcoded) instead of playing the exact YouTube `videoId`.
2. `CanonicalAudioResolver.selectBestCanonicalMatch` uses `.filter { (_, score) -> score > -40 }` and awards `+110` for `MUSIC_VIDEO_TYPE_ATV`, allowing wrong songs with mismatched titles/artists to pass.
3. `InnerTubeStreamExtractor.extractStreamFromClient` lacks OuterTune's `IOS` / `ANDROID_VR` / `TVHTML5_SIMPLY_EMBEDDED_PLAYER` client rotation and proper playback nonce (`cpn`), causing direct `videoId` extraction to fail and fall back to fuzzy text matching.

### Required Implementation
1. **Direct `videoId` Passthrough (Zero Re-Search for Native YTM Tracks)**: If a track already has a valid 11-character YouTube `videoId` (Regex `^[a-zA-Z0-9_-]{11}$`), NEVER call `TrackMatcher`, `resolveFromJioSaavnExact`, or re-search by `title + artist`. Resolve the audio stream directly by `videoId` via `/youtubei/v1/player`.
2. **Strict External Matching (For Spotify / Discover Tracks Only)**: When matching a non-YouTube track (e.g., from Discover/Spotify) to YouTube Music, require BOTH normalized title similarity (`>= 0.75`) AND primary artist match, plus duration delta `<= 8 seconds`, with a minimum threshold of `score >= 160`. Remove `autocomplete.get` from `StreamResolver` completely.

```kotlin
// Apply to InnerTubeStreamExtractor.kt AND the active YouTubeInnerTubeProvider
private val VALID_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

fun isValidYoutubeVideoId(id: String?): Boolean =
    !id.isNullOrBlank() && VALID_VIDEO_ID_REGEX.matches(id.trim())

suspend fun resolveExactYoutubeStreamById(
    videoId: String,
    preferredQuality: AudioQuality = AudioQuality.HIGH
): Result<String> = withContext(Dispatchers.IO) {
    require(isValidYoutubeVideoId(videoId)) { "Invalid YouTube videoId: $videoId" }
    val cpn = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[(0..63).random()] }.joinToString("")

    // OuterTune / InnerTune verified client priority order
    val clients = listOf(
        InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientId = "28",
            androidSdkVersion = 32
        ),
        InnerTubeClientConfig(
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            clientId = "5"
        ),
        InnerTubeClientConfig(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 13; en_US) gzip",
            clientId = "30",
            androidSdkVersion = 33
        )
    )

    for (client in clients) {
        val streamUrl = runCatching {
            val endpoint = URL("[https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false](https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false)")
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
                put("cpn", cpn)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("hl", "en")
                        put("gl", "IN")
                        client.androidSdkVersion?.let { put("androidSdkVersion", it) }
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
            audioStreams.maxByOrNull { it.first }?.second
        }.getOrNull()

        if (!streamUrl.isNullOrBlank()) {
            return@withContext Result.success(streamUrl)
        }
    }
    Result.failure(IllegalStateException("Failed to extract direct stream for videoId=$videoId"))
}
```

And in `CanonicalAudioResolver.selectBestCanonicalMatch`, enforce strict title + artist validation:
```kotlin
fun selectBestCanonicalMatch(
    queryTitle: String,
    queryArtist: String,
    targetDurationSeconds: Int?,
    candidates: List<CandidateStream>
): CandidateStream? {
    if (candidates.isEmpty()) return null
    val rawQueryLower = "$queryTitle $queryArtist".lowercase()
    val normTitle = normalizeText(queryTitle)
    val normArtist = normalizeText(queryArtist.split(",", "&", "feat.", "ft.").first())

    return candidates
        .mapNotNull { candidate ->
            val candTitle = normalizeText(candidate.title)
            val candArtist = normalizeText(candidate.artist)
            val candChannel = normalizeText(candidate.channelName.removeSuffix("- Topic"))

            // HARD GATE 1: Reject if title does not share core words with queryTitle
            val titleTokens = normTitle.split(" ").filter { it.length >= 2 }
            val matchedTitleTokens = titleTokens.count { candTitle.contains(it) }
            val titleMatchRatio = if (titleTokens.isNotEmpty()) matchedTitleTokens.toFloat() / titleTokens.size else 1f
            if (candTitle != normTitle && !candTitle.contains(normTitle) && titleMatchRatio < 0.65f) {
                return@mapNotNull null
            }

            // HARD GATE 2: Reject unrequested remixes/covers/slowed/live
            if (containsBannedModifier(rawQueryLower, "${candidate.title} ${candidate.channelName}")) {
                return@mapNotNull null
            }

            // HARD GATE 3: Artist must match or be contained in candidate artist/channel/title
            val artistMatches = normArtist.isBlank() ||
                candArtist.contains(normArtist) ||
                normArtist.contains(candArtist) ||
                candChannel.contains(normArtist) ||
                candTitle.contains(normArtist)
            if (!artistMatches) return@mapNotNull null

            val score = scoreCandidate(candidate, normTitle, normArtist, rawQueryLower, targetDurationSeconds)
            if (score >= 120) candidate to score else null
        }
        .maxByOrNull { it.second }
        ?.first
}
```

---

## 2. FIX LIBRARY NAVIGATION FILTER BAR & VERTICAL DIVIDER HEIGHT

### Root Cause
In the Library screen navigation row (where the category chips, vertical divider bar, and Filter button sit), the vertical divider bar and filter button stretch taller than the `32.dp` chips because the `Row` or divider uses unconstrained height.

### Required Implementation
Constrain the Library filter row to a strict `36.dp` height, set the vertical divider to `height(20.dp).width(1.dp)`, and make the filter trigger a compact `32.dp`-high pill/icon button aligned with the chips:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
        .height(36.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
    ) {
        items(navTabs) { tab ->
            val isSelected = selectedNavTab == tab
            FilterChip(
                selected = isSelected,
                onClick = { selectedNavTab = tab },
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = null,
                label = {
                    Text(
                        text = tab,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }

    // Compact Vertical Bar (Strictly 20.dp tall so it NEVER stretches)
    HorizontalDivider(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .height(20.dp)
            .width(1.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    )

    // Compact Filter Button (Strictly 32.dp tall to match chips)
    Box {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { showFilterMenu = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterSlidersSvgIcon(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = activeFilter.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        DropdownMenu(
            expanded = showFilterMenu,
            onDismissRequest = { showFilterMenu = false }
        ) {
            LibraryFilterOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        activeFilter = option
                        showFilterMenu = false
                    }
                )
            }
        }
    }
}
```

---

## 3. FIX DISCOVER & DOWNLOAD: EMPTY SEARCH RESULTS, TWO-LINE SEARCH BAR, AND TAP-FRAME TO PAUSE/PLAY

### Root Cause
1. **Two-Line Search Bar**: In `InDiscoverSearchSurface.kt` and `SearchScreen.kt`, the `Text` inside `placeholder = { Text(...) }` is missing `maxLines = 1`, `softWrap = false`, and `overflow = TextOverflow.Ellipsis`, causing Material3 `TextField` to wrap the placeholder into two lines.
2. **No Search Results in Discover**: `onSearchPreviewTracks` either fails when Deezer/iTunes time out or filters out tracks where `previewUrl` uses `"ytm_clip:{videoId}"`. `SpotifyMetadataProvider.searchTracksWithPreviews` must run iTunes, Deezer, AND YouTube Music (`InnerTubeStreamExtractor.searchOfficialYtmSongs`) in parallel so results ALWAYS return immediately.
3. **No Pause on Click to the Frame**: `DiscoverShortPage` in `DiscoverScreen.kt` has no `.clickable` handler on the frame/artwork to toggle `playPreview` / `pausePreview`.

### Required Implementation
1. **Single-Line Search Bar Fix (Apply to `DiscoverScreen`, `InDiscoverSearchSurface`, and `SearchScreen`)**:
```kotlin
placeholder = {
    Text(
        text = "Search songs, artists...",
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
},
singleLine = true,
maxLines = 1,
```
2. **Tap Anywhere on Frame to Pause/Resume Preview in `DiscoverScreen.kt`**:
```kotlin
// Inside DiscoverScreen:
val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()

// Pass onTogglePlayPause into DiscoverShortPage:
onTogglePlayPause = {
    if (isPreviewPlaying) {
        audioPlayerManager.pausePreview()
    } else {
        audioPlayerManager.resumeOrPlayPreview(track)
    }
}

// Inside DiscoverShortPage root Box:
Box(
    modifier = Modifier
        .fillMaxSize()
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onTogglePlayPause() }
) {
    // ... existing background & artwork ...
    // Show Play indicator overlay in center when paused:
    AnimatedVisibility(
        visible = !isPreviewPlaying,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = (-48).dp)
    ) {
        Box(
            modifier = Modifier
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
}
```
3. **Ensure `AudioPlayerManager.resumeOrPlayPreview` Resumes cleanly**:
```kotlin
fun resumeOrPlayPreview(track: NormalizedTrack) {
    if (_activePreviewTrackId.value == track.sourceId && exoPlayer.mediaItemCount > 0) {
        exoPlayer.playWhenReady = true
        exoPlayer.play()
        _isPreviewPlaying.value = true
    } else {
        playPreview(track)
    }
}
```
4. **Parallel Search in `SpotifyMetadataProvider.searchTracksWithPreviews`**: Run iTunes, Deezer, and `InnerTubeStreamExtractor.searchOfficialYtmSongs` concurrently with `async`/`awaitAll` (with a 4-second timeout on Deezer/iTunes) so YouTube Music results (`previewUrl = "ytm_clip:${yt.videoId}"`) are always returned even if Deezer is unreachable.

---

## 4. FIX PLAYERSCREEN "ADJUST" BUTTON: OPEN SLIDE-UP BOTTOM SHEET INSTEAD OF FULL SETTINGS SCREEN

### Root Cause
Clicking the Adjust/Equalizer button in `PlayerScreen` currently navigates to `SubScreen.SoundSettings` / `GeneralSettingsScreen` (which has no Back button and leaves the player).

### Required Implementation
1. Add a back button to `GeneralSettingsScreen` when opened as a subscreen.
2. In `PlayerScreen`, clicking the **Adjust** button MUST set `var showAudioAdjustSheet by remember { mutableStateOf(false) }` and open a `ModalBottomSheet` (slide-up panel) directly inside `PlayerScreen`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAudioAdjustmentSheet(
    settingsManager: AppSettingsManager,
    onDismiss: () -> Unit
) {
    val state by settingsManager.state.collectAsState()
    val eqFrequencies = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Adjustments",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { settingsManager.resetEqualizer() }) {
                    Text("Reset Flat", color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Equalizer Master Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("5-Band Studio Equalizer", fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = state.eqEnabled,
                    onCheckedChange = { settingsManager.setEqEnabled(it) }
                )
            }

            eqFrequencies.forEachIndexed { idx, freq ->
                val gain = state.eqBandsDb.getOrElse(idx) { 0f }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(freq, style = MaterialTheme.typography.labelMedium)
                        Text("${"%.1f".format(gain)} dB", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = gain,
                        onValueChange = { settingsManager.setEqBand(idx, it) },
                        valueRange = -12f..12f,
                        enabled = state.eqEnabled
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Normalize Volume & Gapless Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Normalize Volume", fontWeight = FontWeight.Medium)
                Switch(
                    checked = state.audioNormalization,
                    onCheckedChange = { settingsManager.setAudioNormalization(it) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gapless Playback", fontWeight = FontWeight.Medium)
                Switch(
                    checked = state.gaplessPlayback,
                    onCheckedChange = { settingsManager.setGaplessPlayback(it) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

---

## 5. FIX AUTOPLAY & ALWAYS-VISIBLE QUEUE SUGGESTIONS

### Root Cause
1. In `PlaybackQueueSheet.kt`, `suggestedTracks` is cleared (`suggestedTracks = emptyList()`) when `autoplayEnabled == true`, and the UI hides suggestions behind `if (!autoplayEnabled && suggestedTracks.isNotEmpty())`.
2. Autoplay track-appending is currently inside `PlaybackQueueSheet` (a UI composable), meaning if the queue sheet is closed, Autoplay never triggers when the last song finishes!

### Required Implementation
1. **Always Show Suggestions in `PlaybackQueueSheet.kt`**: Keep `suggestedTracks = recommendations` regardless of whether `autoplayEnabled` is `true` or `false`, and change `if (!autoplayEnabled && suggestedTracks.isNotEmpty())` to `if (suggestedTracks.isNotEmpty())`.
2. **Move Autoplay End-of-Queue Trigger into the Player Controller (`ViewModel` / `AudioPlayerManager`)**:
   In `ExoPlayer`'s `Player.Listener` (`onMediaItemTransition` and `onPlaybackStateChanged(Player.STATE_ENDED)`), check if the player is on the last song of the queue (or has reached the end) and `autoplayEnabled == true`. Automatically fetch the next recommended track from `QueueRecommendationEngine.fetchRelevantRecommendations` (excluding already-queued IDs), append it to the queue and `ExoPlayer`, and continue playback seamlessly.

```kotlin
// Inside PlaybackQueueSheet.kt:
LaunchedEffect(currentTrack?.id, queue.size) {
    val seed = currentTrack ?: queue.firstOrNull() ?: return@LaunchedEffect
    suggestedTracks = QueueRecommendationEngine.fetchRelevantRecommendations(
        currentTrack = seed,
        queueTracks = queue,
        limit = 8
    )
}

// Render Suggestions ALWAYS (whether Autoplay is ON or OFF):
if (suggestedTracks.isNotEmpty()) {
    item {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Suggested for ${currentTrack?.title ?: "this queue"}",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    items(suggestedTracks, key = { "suggest_${it.id}" }) { suggestion ->
        // ... render suggestion row with + button that calls onAddTrackToQueue(suggestion)
        // and removes `suggestion` from `suggestedTracks` ...
    }
}
```

---

## 6. FIX "READY TO PLAY PERSONALIZED" HERO UI, DEDUPLICATE NEXT SECTION, AND DYNAMIC MIX RE-SEEDING

### Root Cause
1. `ReadyToPlayHeroCard` uses a rigid `height(206.dp)` while stacking a long badge, 2-line title, artist, and two wide buttons next to a `132.dp` image, causing text/button clipping and layout overflow.
2. In `HomeScreen.kt`, the song displayed in `ReadyToPlayHeroCard` (and the song just played by the user) is not excluded from the section immediately below ("Jump Back In" / "Quick Picks"), causing duplicate tracks back-to-back.
3. "My Personalized Mix" does not dynamically reload from the top when the user clicks a new song.

### Required Implementation
1. **Fix `ReadyToPlayHeroCard` Layout**: Replace fixed `.height(206.dp)` with `.wrapContentHeight().heightIn(min = 184.dp)`, shorten badge text to `"READY TO PLAY • FOR YOU"` with `maxLines = 1`, and use compact single-line buttons.
2. **Exclude Hero & Currently Playing Track from Immediate Next Sections**:
```kotlin
val firstReadyToPlaySong = personalizedFeed.firstOrNull { it.id != currentlyPlayingSongId }
    ?: personalizedFeed.firstOrNull()

val excludedHeroIds = setOfNotNull(firstReadyToPlaySong?.id, currentlyPlayingSongId)

val listenAgainSongs = remember(historySongs, excludedHeroIds) {
    historySongs.distinctBy { it.id }.filterNot { it.id in excludedHeroIds }.take(10)
}

val quickPickSongs = remember(personalizedFeed, excludedHeroIds) {
    personalizedFeed.filterNot { it.id in excludedHeroIds }.take(8)
}
```
3. **Dynamic Mix Reloading From Top on Every Song Click**:
   Whenever the user clicks any song (`onSongClick(song)`):
   - Immediately prepend `song` to index `0` of `historySongs`.
   - Recompute `My Personalized Mix` using `RDAMVM${song.id}` and fetch fresh Automix tracks (`InnerTubeStreamExtractor.fetchAutomixQueue(song.id)`), placing the newly clicked song at index `0` (the top of the mix) and resetting the mix/queue scroll state to index `0`.

---

## 7. FIX THEME CONTAINER COLOR GLITCHES IN ONBOARDING & SEARCH SCREEN ("ALL" FILTER CHIP)

### Root Cause
In `SearchScreen` and `OnboardingScreen`, text colors were updated to use theme colors, but the container `div`s (`Column`, `Surface`, `FilterChip`, `OutlinedTextField`) still use hardcoded colors (`Color(0xFF0F0F0F)`, `Color(0xFF1B1C1C)`, `Color.White`, `Color.Black`). Specifically, on the `"All"` filter chip in `SearchScreen`, the container color does not react to Light/Dark theme changes.

### Required Implementation
Audit `OnboardingScreen.kt` and `SearchScreen.kt` (across all packages) and replace EVERY hardcoded `Color(...)`, `Color.White`, and `Color.Black` on containers and chips with `MaterialTheme.colorScheme` tokens:

```kotlin
// SearchScreen Filter Chips (including "All", "Songs", "Downloaded", etc.):
FilterChip(
    selected = isSelected,
    onClick = { selectedTab = tab },
    label = {
        Text(
            text = tab,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    },
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    border = null
)
```
Ensure all `OnboardingScreen` cards, popup surfaces, and input fields use `MaterialTheme.colorScheme.background`, `MaterialTheme.colorScheme.surface`, `MaterialTheme.colorScheme.surfaceVariant`, and `MaterialTheme.colorScheme.onSurface`.

---

## 8. FIX PLAYBACK NOTIFICATION PERSISTING AFTER APP IS SWIPED AWAY FROM RECENTS

### Root Cause
When the user swipes the app away from Android's Recent Apps window, `MainActivity.onDestroy()` either has `isFinishing == false` or the foreground `MediaSessionService` / `PlayerNotificationManager` keeps running and re-posting the notification because `Service.onTaskRemoved` is not tearing down the `MediaSession` and `PlayerNotificationManager`.

### Required Implementation
1. In your playback `Service` (or `MediaSessionService`), override `onTaskRemoved` to detach the notification manager, release the session, stop `ExoPlayer`, and remove the foreground notification:
```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    runCatching {
        playerNotificationManager?.setPlayer(null)
        mediaSession?.run {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancelAll()
    }
    stopSelf()
}
```
2. In `MainActivity.onDestroy()`, remove the `if (isFinishing)` guard and check ONLY `if (!isChangingConfigurations)` so swiping away from Recents always stops playback and cancels notifications:
```kotlin
override fun onDestroy() {
    if (!isChangingConfigurations) {
        runCatching {
            viewModel.stopPlayback()
            (application as? CascadeApplication)?.audioPlayerManager?.release()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        }
    }
    super.onDestroy()
}
```

---

## 9. FIX PLAYBACK QUEUE DRAG-AND-DROP REORDERING WITH SMOOTH EDGE AUTO-SCROLL

### Root Cause
In `PlaybackQueueSheet.kt`, dragging a song to reorder it fails when trying to move it past the bottom or top edge of the visible screen because the `LazyColumn` lacks an edge-proximity auto-scroll loop during drag gestures.

### Required Implementation
Implement drag-to-reorder with continuous 60fps edge auto-scrolling when the dragged finger approaches within `72.dp` of the top or bottom of the `LazyColumn`:

```kotlin
@Composable
fun ReorderableQueueLazyColumn(
    queue: List<TrackMetadata>,
    currentTrackId: String?,
    onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit,
    onSelectTrack: (TrackMetadata) -> Unit,
    onRemoveTrack: (TrackMetadata) -> Unit,
    modifier: Modifier = Modifier,
    footerContent: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 72.dp.toPx() }
    val maxScrollSpeedPx = with(density) { 18.dp.toPx() }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    val currentQueue by rememberUpdatedState(queue)
    val currentOnMove by rememberUpdatedState(onMoveTrack)

    // Continuous smooth 60fps auto-scroll loop while dragging near top/bottom edges
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            val scrolled = listState.scrollBy(autoScrollSpeed)
            if (scrolled != 0f) {
                val activeIdx = draggingIndex
                if (activeIdx != null) {
                    val layoutInfo = listState.layoutInfo
                    val draggedItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                    if (draggedItemInfo != null) {
                        val itemCenterY = draggedItemInfo.offset + (draggedItemInfo.size / 2f) + draggingOffsetY
                        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            item.index in currentQueue.indices &&
                                itemCenterY.toInt() in item.offset..(item.offset + item.size)
                        }
                        if (targetItem != null && targetItem.index != activeIdx) {
                            currentOnMove(activeIdx, targetItem.index)
                            draggingIndex = targetItem.index
                            draggingOffsetY += (draggedItemInfo.offset - targetItem.offset)
                        }
                    }
                }
            }
            delay(16L)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hitItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            offset.y.toInt() in item.offset..(item.offset + item.size) &&
                                item.index in currentQueue.indices
                        }
                        if (hitItem != null) {
                            draggingIndex = hitItem.index
                            draggingOffsetY = 0f
                        }
                    },
                    onDragEnd = {
                        draggingIndex = null
                        draggingOffsetY = 0f
                        autoScrollSpeed = 0f
                    },
                    onDragCancel = {
                        draggingIndex = null
                        draggingOffsetY = 0f
                        autoScrollSpeed = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val activeIdx = draggingIndex ?: return@detectDragGesturesAfterLongPress
                        draggingOffsetY += dragAmount.y

                        val layoutInfo = listState.layoutInfo
                        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                        val pointerY = change.position.y

                        // Compute smooth edge auto-scroll speed
                        autoScrollSpeed = when {
                            pointerY > viewportHeight - edgeThresholdPx -> {
                                val ratio = ((pointerY - (viewportHeight - edgeThresholdPx)) / edgeThresholdPx).coerceIn(0f, 1f)
                                maxScrollSpeedPx * ratio
                            }
                            pointerY < edgeThresholdPx -> {
                                val ratio = ((edgeThresholdPx - pointerY) / edgeThresholdPx).coerceIn(0f, 1f)
                                -maxScrollSpeedPx * ratio
                            }
                            else -> 0f
                        }

                        val draggedItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIdx }
                        if (draggedItemInfo != null) {
                            val currentCenterY = draggedItemInfo.offset + (draggedItemInfo.size / 2f) + draggingOffsetY
                            val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                item.index in currentQueue.indices &&
                                    currentCenterY.toInt() in item.offset..(item.offset + item.size)
                            }
                            if (targetItem != null && targetItem.index != activeIdx) {
                                currentOnMove(activeIdx, targetItem.index)
                                draggingIndex = targetItem.index
                                draggingOffsetY += (draggedItemInfo.offset - targetItem.offset)
                            }
                        }
                    }
                )
            },
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(queue, key = { _, track -> "queue_${track.id}" }) { index, track ->
            val isDragging = index == draggingIndex
            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 10f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) draggingOffsetY else 0f
                        scaleX = if (isDragging) 1.03f else 1f
                        scaleY = if (isDragging) 1.03f else 1f
                        shadowElevation = if (isDragging) 16f else 0f
                    }
            ) {
                // Render queue item row with drag handle icon
            }
        }
        footerContent()
    }
}
```