## Today:
- add reload swipe down in every page in library, many times the downloaded song doesn't appear even the old one. the play control notification keeps its state even when the songs is no more playing due to some internal error occured when background playing and when visit the app begins from the start with splash screen. Hope you have adopted the personalization like the youtube system(or you can even fecth from youtube directly by showing the history/interest). The name is asked but never use, use it on homescreen

## Yesterday:
```markdown
You must fix 7 critical UI, SVG, audio resolution, and playback queue issues in the Seitharol Android app. Follow every rule below strictly—do NOT use any emojis anywhere in the codebase (use only Compose Canvas/Vector SVGs) and do NOT use hardcoded unrelated mock tracks.

### 1. Onboarding Screen & Welcome Popup (`OnboardingScreen.kt`)
- **Remove "ABOUT THIS APP" entirely**: Delete the `ABOUT THIS APP` container card/section from the Onboarding screen. Keep only the title, subtitle, Name input, Age input, and Continue button.
- **Add Open-Source Welcome Popup**: Show a modal popup dialog (`WelcomeOpenSourceDialog`) on the Onboarding screen containing:
  - Main message: `"Welcome to my app, this distribution is entirely open source contribution from me"`
  - A collapsible/hidden toggle row featuring a `+` icon, the label `"Only for men"`, and an **inverted triangle SVG** (`▼` drawn via Compose `Canvas` `Path`) that rotates/expands when clicked.
  - When expanded, reveal the text: `"If anybody is interested in connecting me, connect me on floristechnis@proton.me"` (with clickable email copy/intent).
  - At the bottom of the dialog, provide an `"OK"` button to dismiss the popup.

### 2. JioSaavn Vector Drawable & Search Filter Chip (`res/drawable/ic_jiosaavn.xml` & `SearchScreen.kt`)
- **Problem**: In `ic_jiosaavn.xml`, the wordmark paths ("JioSaavn") are hardcoded to `#FFFFFF` inside a wide `83dp x 24dp` viewport. On light/unselected filter chips, the white text becomes invisible and leaves an empty gap.
- **Fix**:
  - Update `res/drawable/ic_jiosaavn.xml` so the wordmark paths use `@android:color/white` with proper viewport alignment OR use `ic_jiosaavn_icon.xml` (24x24dp teal emblem) paired with a theme-aware `Text("JioSaavn")` inside the filter chip (matching the YouTube chip layout), and also update `ic_jiosaavn.xml` text paths to `#1ECCB0` (brand teal) so if `ic_jiosaavn.xml` is rendered directly without tint, the text is visible on both light and dark surfaces.

### 3. Discover & Download Screen & Entry Card (`DiscoverEntryCard.kt` & `DiscoverScreen.kt`)
- **Problem**: Missing thumbnails, missing action buttons, and restricted emojis/corrupted characters used instead of SVGs.
- **Fix**:
  - Remove all emojis and corrupted text characters (`Text(" ")`). Replace all icons (Compass, ChevronRight, Play, Pause, SkipPrevious, SkipNext, Download, QueueAdd, MusicNote) with custom `Canvas` / `ImageVector` SVGs.
  - In `DiscoverScreen.kt`, each track card MUST render:
    1. High-resolution album art thumbnail (`AsyncImage` with a custom SVG vinyl/music-note placeholder on error/loading).
    2. Track title, artist name, album name, duration, and audio quality badge (Lossless FLAC / 320kbps MP3).
    3. Interactive 30-second preview progress slider/bar.
    4. Full SVG action buttons: **Previous**, **Play/Pause 30s Preview**, **Next/Skip**, **Add to Queue**, and **Download** button.

### 4. Exact Song Audio Stream Resolution (`StreamResolver.kt`)
- **Problem**: Playing a song displays the correct thumbnail, title, and artist, but plays a completely different song, remix, slowed+reverb, or cover.
- **Fix**:
  - **JioSaavn Direct Decryption**: When a track has a JioSaavn `encryptedMediaUrl` or JioSaavn ID, decrypt the exact stream URL directly using DES/ECB/PKCS5Padding with key `"38346591"` and upgrade quality to `_320.mp4` / `_160.mp4`. Never fall back to a loose YouTube search if the direct stream is available.
  - **Strict YouTube/Piped Matching**: When resolving via YouTube/Innertube/Piped:
    1. Search query must be `"${song.title} ${song.artist} official audio"`.
    2. Reject any candidate containing `"remix"`, `"slowed"`, `"reverb"`, `"lofi"`, `"lo-fi"`, `"cover"`, `"karaoke"`, `"mashup"`, `"8d"`, `"nightcore"`, `"instrumental"`, `"ringtone"`, or `"status"` unless those words are in the original `song.title`.
    3. Enforce duration matching: candidate duration must be within `±15 seconds` of `song.durationSeconds` (when `song.durationSeconds > 0`).
    4. Score candidates by exact title token overlap + artist match in uploader/channel (`- Topic`, `VEVO`, official artist name) + duration closeness.
    5. Cancel any previous playback resolution coroutine job before starting a new one so stale resolutions never play the wrong audio.

### 5. Listening History UI Glitch (`HistoryScreen.kt`)
- **Problem**: In `HistoryScreen.kt`, the red `SwipeToDismissBox` delete background bleeds through the item row, the `♡` and `Delete` text overlap (`♡Delete`), and the thumbnail fallback shows an `X` box.
- **Fix**:
  - Give the foreground history item card an opaque `MaterialTheme.colorScheme.surface` background so the dismiss background never bleeds through when settled.
  - Only render the red swipe-to-delete background when `dismissState.targetValue != SwipeToDismissBoxValue.Settled` or `dismissState.progress > 0.05f`.
  - In the foreground row, place the **Favorite (Heart SVG)** button and **Delete (Trash SVG)** button inside a `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` with no overlapping text.
  - Load the song's `thumbnailUrl` with a clean Music Note SVG fallback instead of an `X` icon.

### 6. Home Screen Content & Theme-Aware Bottom Navigation (`HomeScreen.kt` & `MainActivity.kt`)
- **Problem**: Home screen is blank showing only `"Ready to play your first song"`, and the bottom navigation bar is hardcoded to white with invisible icons.
- **Fix**:
  - Always render the full Home Screen layout:
    1. **Header**: App name (`Seitharol`), dynamic greeting (`"Good Morning/Afternoon/Evening, <UserName>"`), History SVG icon button, and Settings SVG icon button.
    2. **Search Bar**: Theme-aware search bar on the Home screen that opens/triggers search.
    3. **Mood & Category Chips**: Horizontal scrollable chips for `Energize`, `Trending`, `Manipuri Song`, `Sad`, `Chill`, `Romantic`, `Workout`, `Lo-Fi`. Selecting a chip fetches and displays tracks for that mood/genre immediately. Load `Trending` and `Manipuri Song` sections automatically on launch so the screen is never empty.
  - Fix the **Bottom Navigation Bar** theme: use `MaterialTheme.colorScheme.surfaceVariant` for the pill container, `MaterialTheme.colorScheme.primary` for the selected icon/indicator, and `MaterialTheme.colorScheme.onSurfaceVariant` for unselected icons so it adapts to Dark, Light, and System themes.

### 7. Play Queue Autoplay & Context-Relevant Recommendations (`PlaybackQueueSheet.kt` & `QueueRecommendationEngine.kt`)
- **Problem**: Recommendations show hardcoded unrelated songs (e.g., Post Malone, Sam Smith when playing Bayaan - Safar), and Autoplay ON does not auto-append songs to the queue.
- **Fix**:
  - Remove all hardcoded/static suggestion lists.
  - Build `QueueRecommendationEngine` to fetch similar songs dynamically based on the **currently playing song and queue context** (matching primary artist, language/genre tags, and JioSaavn/YouTube related station APIs, excluding songs already in the queue).
  - **When `autoplay == true`**: Automatically append the top relevant recommended tracks directly into the active `Up Next` queue (maintaining at least 3 upcoming relevant tracks) and do NOT show a separate manual suggestion list.
  - **When `autoplay == false`**: Do NOT auto-add tracks to `Up Next`; instead, display the context-relevant tracks under `"Suggested for You"` with an SVG Add-to-Queue button.

```

---

# Part 2: Complete Fixed Code

Below are the fixed files ready to drop into your project.

### 1. Fixed `res/drawable/ic_jiosaavn.xml`

The wordmark paths now use `#1ECCB0` (JioSaavn brand teal) instead of hardcoded `#FFFFFF`, so the text is always visible on both white/light chips and dark surfaces.

```xml
<!-- res/drawable/ic_jiosaavn.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="76dp"
    android:height="22dp"
    android:viewportWidth="111"
    android:viewportHeight="32">
    <!-- Brand Circle -->
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M0,16c0,8.8 7.2,16 16,16c8.8,0 16,-7.2 16,-16c0,-8.8 -7.2,-16 -16,-16C7.2,0 0,7.2 0,16z" />
    <!-- Inner White Waves -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M13.8,25.1c-0.6,-1 -1.4,-1.9 -2.4,-2.5s-2,-1.1 -3.2,-1.4H8.2c-0.1,0 -0.2,0 -0.3,0.1c-0.1,0.1 -0.1,0.2 -0.1,0.3c0,0.1 0,0.2 0.1,0.2c1.3,1.9 3.3,3.3 5.5,3.9h0.1c0.1,0 0.1,0 0.2,0c0,0 0.1,0 0.1,-0.1c0,0 0.1,-0.1 0.1,-0.1c0,0 0,-0.1 0,-0.2C13.9,25.2 13.9,25.1 13.8,25.1L13.8,25.1z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M25.6,13.3c0,0 0,-0.1 0,-0.1c-0.5,-1.8 -1.5,-3.4 -2.9,-4.6c-1.4,-1.2 -3,-2.1 -4.8,-2.4h-0.1c-0.1,0 -0.2,0 -0.3,0.1c-0.1,0.1 -0.1,0.2 -0.1,0.3v0.1c0.9,5.5 0.3,11.1 -1.7,16.3c0,0.1 -0.1,0.1 -0.1,0.2s-0.1,0.1 -0.2,0.1c-0.1,0 -0.1,0 -0.2,0c0,0 -0.1,0 -0.1,-0.1c0,0 -0.1,-0.1 -0.1,-0.1c0,0 0,-0.1 0,-0.2c0,0 -0.1,-0.7 -0.1,-1c-0.6,-4.8 -2,-9.4 -4.3,-13.7c0,-0.1 -0.1,-0.1 -0.1,-0.1c0,0 -0.1,-0.1 -0.1,-0.1s-0.1,0 -0.2,0C10.1,8 10.1,8 10,8l0,0c-1.4,1.1 -2.5,2.5 -3.2,4.2c0,0.1 0,0.2 0,0.3c0,0.1 0.1,0.2 0.2,0.2c2.2,1.4 4,3.2 5.3,5.5c1.3,2.2 2,4.8 2.1,7.3c0,0.1 0,0.2 0.1,0.3c0.1,0.1 0.2,0.1 0.2,0.1c0.2,0 0.5,0.1 0.7,0.1c0.1,0 0.2,0 0.3,-0.1c0.1,-0.1 0.1,-0.1 0.1,-0.2c0.5,-2.6 1.6,-5.1 3.3,-7.2c1.7,-2.1 3.8,-3.7 6.3,-4.8c0.1,0 0.1,-0.1 0.2,-0.1C25.6,13.4 25.6,13.4 25.6,13.3z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M24.7,20.9c0,-0.1 0,-0.2 -0.1,-0.3c-0.1,-0.1 -0.2,-0.1 -0.3,-0.1h-0.1c-3.1,0.7 -5.9,2.4 -7.9,4.8c-0.1,0.1 -0.1,0.2 -0.1,0.3c0,0.1 0,0.2 0.1,0.3c0.1,0.1 0.2,0.1 0.3,0.1l0,0c1.6,-0.1 3.2,-0.6 4.6,-1.4s2.6,-2 3.4,-3.4C24.7,21.1 24.7,21 24.7,20.9L24.7,20.9z" />
    <!-- "JioSaavn" Wordmark in #1ECCB0 so it is always visible on both Light and Dark chips -->
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M40.3,22c-0.8,0 -1.6,-0.1 -2.3,-0.5c-0.6,-0.3 -1.1,-0.8 -1.5,-1.4C36.2,19.4 36,18.7 36,18v-0.3h2.8c0,0.5 0.1,0.9 0.3,1.3c0.2,0.2 0.6,0.4 0.9,0.4s0.7,0 1,-0.2c0.2,-0.1 0.3,-0.4 0.4,-0.6c0.1,-0.3 0.1,-0.6 0.1,-0.9V9.2h3.1v8.4c0.1,1.2 -0.3,2.4 -1.1,3.3c-0.4,0.4 -1,0.7 -1.5,0.9C41.4,21.9 40.9,22 40.3,22L40.3,22z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M47.7,11.1c-0.3,0 -0.6,-0.1 -0.8,-0.2c-0.2,-0.1 -0.4,-0.3 -0.6,-0.5c-0.1,-0.2 -0.2,-0.5 -0.2,-0.8c0,-0.3 0.1,-0.6 0.2,-0.8c0.1,-0.2 0.3,-0.4 0.6,-0.5c0.3,-0.1 0.5,-0.2 0.8,-0.2c0.3,0 0.6,0.1 0.8,0.2c0.2,0.1 0.4,0.3 0.6,0.5c0.1,0.2 0.2,0.5 0.2,0.8c0,0.3 -0.1,0.5 -0.2,0.8c-0.1,0.2 -0.3,0.4 -0.6,0.5C48.3,11 48,11.1 47.7,11.1L47.7,11.1z M46.3,21.7v-9.3h2.8v9.3H46.3z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M55.3,22c-0.9,0 -1.8,-0.2 -2.5,-0.6c-0.7,-0.4 -1.3,-1 -1.7,-1.7c-0.4,-0.8 -0.6,-1.7 -0.6,-2.6c0,-0.9 0.2,-1.8 0.6,-2.6c0.4,-0.7 0.9,-1.3 1.7,-1.7c0.8,-0.4 1.7,-0.6 2.5,-0.6c0.9,0 1.7,0.2 2.5,0.6c0.7,0.4 1.3,1 1.6,1.7c0.4,0.8 0.6,1.7 0.6,2.6c0,0.9 -0.2,1.8 -0.6,2.6c-0.4,0.7 -1,1.3 -1.7,1.7C57,21.8 56.1,22 55.3,22z M55.3,19.9c0.4,0 0.8,-0.1 1.1,-0.3c0.3,-0.3 0.6,-0.6 0.7,-1c0.2,-0.5 0.2,-1 0.2,-1.5c0,-0.5 -0.1,-1 -0.2,-1.4c-0.1,-0.4 -0.3,-0.7 -0.6,-1c-0.3,-0.3 -0.7,-0.4 -1.1,-0.4c-0.4,0 -0.7,0.1 -1,0.3c-0.3,0.2 -0.5,0.6 -0.7,0.9c-0.2,0.5 -0.2,1 -0.2,1.5c0,0.5 0.1,1 0.2,1.5c0.1,0.4 0.3,0.7 0.7,1C54.5,19.7 54.9,19.8 55.3,19.9L55.3,19.9z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M66.3,22c-0.9,0 -1.8,-0.1 -2.6,-0.4c-0.8,-0.3 -1.5,-0.7 -2,-1.3c-0.5,-0.6 -0.8,-1.4 -0.8,-2.1V18H64c0,0.3 0.1,0.7 0.3,0.9c0.2,0.3 0.5,0.4 0.9,0.5c0.4,0.1 0.8,0.2 1.2,0.2c0.5,0 1.1,-0.1 1.5,-0.3c0.2,-0.1 0.3,-0.2 0.5,-0.4c0.1,-0.2 0.2,-0.4 0.1,-0.6c0,-0.2 -0.1,-0.5 -0.2,-0.6c-0.2,-0.2 -0.4,-0.4 -0.6,-0.5c-0.4,-0.2 -0.8,-0.3 -1.2,-0.4l-2.3,-0.6c-0.6,-0.1 -1.1,-0.4 -1.6,-0.8c-0.4,-0.3 -0.8,-0.7 -1,-1.1c-0.2,-0.5 -0.3,-1 -0.3,-1.5c0,-0.8 0.2,-1.5 0.7,-2.1c0.5,-0.6 1.1,-1 1.8,-1.3C64.5,9 65.4,8.9 66.2,9c0.9,0 1.7,0.1 2.5,0.4c0.7,0.2 1.3,0.7 1.7,1.2c0.4,0.6 0.7,1.3 0.7,2h-3c0,-0.2 -0.1,-0.4 -0.2,-0.6c-0.1,-0.2 -0.2,-0.3 -0.4,-0.4c-0.5,-0.2 -1,-0.4 -1.5,-0.3c-0.4,0 -0.9,0.1 -1.3,0.3c-0.2,0.1 -0.3,0.2 -0.4,0.3c-0.1,0.1 -0.1,0.3 -0.1,0.5c0,0.2 0,0.4 0.1,0.6c0.1,0.2 0.3,0.3 0.5,0.4c0.3,0.1 0.7,0.3 1,0.3l1.5,0.4c0.8,0.2 1.5,0.5 2.2,0.8c0.6,0.3 1,0.6 1.4,1.1c0.4,0.5 0.6,1.2 0.5,1.8c0,0.8 -0.2,1.5 -0.6,2.2c-0.5,0.6 -1.1,1.1 -1.8,1.4C68.2,21.8 67.2,22 66.3,22L66.3,22z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M75.6,22c-0.6,0 -1.2,-0.1 -1.7,-0.3c-0.5,-0.2 -0.9,-0.6 -1.1,-1c-0.3,-0.5 -0.4,-1 -0.4,-1.6c0,-0.5 0.1,-0.9 0.3,-1.3c0.2,-0.4 0.5,-0.7 0.9,-1c1.1,-0.6 2.3,-0.8 3.4,-0.7h1.4v-0.3c0,-0.3 0,-0.7 -0.1,-1c-0.1,-0.2 -0.3,-0.4 -0.5,-0.6c-0.3,-0.1 -0.6,-0.2 -0.8,-0.2c-0.4,0 -0.8,0.1 -1.1,0.3c-0.1,0.1 -0.2,0.2 -0.3,0.3c-0.1,0.1 -0.1,0.3 -0.1,0.4h-2.7c0,-0.6 0.2,-1.1 0.6,-1.5c0.4,-0.5 0.9,-0.8 1.5,-1c0.7,-0.2 1.5,-0.3 2.3,-0.3c0.8,0 1.6,0.1 2.3,0.4c0.6,0.2 1,0.7 1.3,1.2c0.3,0.7 0.5,1.4 0.4,2.1v5.8h-2.5l-0.1,-1.1c-0.3,0.4 -0.6,0.8 -1.1,1C76.9,21.8 76.2,22 75.6,22L75.6,22z M76.6,20c0.3,0 0.7,-0.1 1,-0.2c0.3,-0.1 0.5,-0.4 0.6,-0.6c0.2,-0.3 0.2,-0.7 0.2,-1v-0.6h-1c-0.4,0 -0.8,0 -1.2,0.1c-0.3,0.1 -0.5,0.2 -0.7,0.4c-0.1,0.1 -0.2,0.2 -0.2,0.3c0,0.1 -0.1,0.3 -0.1,0.4c0,0.2 0,0.3 0.1,0.5c0.1,0.2 0.2,0.3 0.3,0.4C75.9,19.9 76.2,20 76.6,20L76.6,20z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M85.5,22c-0.6,0 -1.2,-0.1 -1.7,-0.3c-0.5,-0.2 -0.9,-0.6 -1.1,-1c-0.3,-0.5 -0.4,-1 -0.4,-1.6c0,-0.5 0.1,-0.9 0.3,-1.3c0.2,-0.4 0.5,-0.7 0.9,-1c1.1,-0.6 2.3,-0.8 3.5,-0.8h1.4v-0.3c0,-0.3 0,-0.7 -0.1,-1c-0.1,-0.2 -0.3,-0.4 -0.5,-0.5c-0.3,-0.1 -0.6,-0.2 -0.8,-0.2c-0.4,0 -0.8,0.1 -1.1,0.3c-0.1,0.1 -0.2,0.2 -0.3,0.3c-0.1,0.1 -0.1,0.3 -0.1,0.4h-2.7c0,-0.6 0.2,-1.1 0.6,-1.5c0.4,-0.5 0.9,-0.8 1.5,-1c0.7,-0.2 1.5,-0.3 2.3,-0.3c0.8,0 1.6,0.1 2.3,0.4c0.6,0.2 1,0.7 1.3,1.2c0.3,0.7 0.5,1.4 0.4,2.1v5.8h-2.6l-0.1,-1.1c-0.3,0.4 -0.6,0.8 -1.1,1C86.8,21.8 86.1,22 85.5,22L85.5,22z M86.5,20c0.3,0 0.7,-0.1 1,-0.2c0.3,-0.1 0.5,-0.4 0.6,-0.6c0.2,-0.3 0.2,-0.7 0.2,-1v-0.6h-1c-0.4,0 -0.8,0 -1.2,0.1c-0.3,0.1 -0.5,0.2 -0.7,0.4c-0.1,0.1 -0.2,0.2 -0.2,0.3s-0.1,0.3 -0.1,0.4c0,0.2 0,0.3 0.1,0.5c0.1,0.2 0.2,0.3 0.3,0.4C85.8,19.9 86.1,20 86.5,20L86.5,20z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M94.7,21.7l-3,-9.3h3l1.7,6.2l1.7,-6.2h2.7l-3,9.3H94.7z" />
    <path
        android:fillColor="#1ECCB0"
        android:pathData="M101.8,21.7v-9.3h2.7v1.3c0.3,-0.5 0.7,-0.9 1.2,-1.1c0.5,-0.3 1,-0.4 1.6,-0.4c0.6,0 1.2,0.1 1.8,0.4c0.5,0.3 0.9,0.7 1.1,1.2c0.3,0.6 0.4,1.3 0.4,2v6h-2.8v-5.2c0,-0.6 -0.1,-1.2 -0.4,-1.7c-0.1,-0.2 -0.3,-0.3 -0.5,-0.4c-0.2,-0.1 -0.4,-0.1 -0.6,-0.1c-0.2,0 -0.5,0 -0.7,0.1c-0.2,0.1 -0.4,0.2 -0.6,0.4c-0.3,0.5 -0.5,1.1 -0.4,1.7v5.1H101.8z" />
</vector>

```

---

### 2. Fixed Onboarding Screen & Welcome Popup (`OnboardingScreen.kt`)

Removes the `ABOUT THIS APP` container completely and adds the popup dialog with your exact open-source message, the hidden `+` `"Only for men"` toggle with an inverted triangle SVG, your `floristechnis@proton.me` contact, and the `"OK"` button.

```kotlin
// java/com/seitharol/ui/onboarding/OnboardingScreen.kt
package com.seitharol.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun OnboardingScreen(
    onCompleteOnboarding: (name: String, age: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var showWelcomePopup by remember { mutableStateOf(true) }

    if (showWelcomePopup) {
        WelcomeOpenSourcePopup(
            onDismiss = { showWelcomePopup = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WELCOME TO\nSEITHAROL",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                lineHeight = 34.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Discover the features yourself.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your Name") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = ageText,
            onValueChange = { input ->
                if (input.all { it.isDigit() } && input.length <= 3) {
                    ageText = input
                }
            },
            label = { Text("Your Age") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        val isValid = name.trim().isNotEmpty() && (ageText.toIntOrNull() ?: 0) > 0

        Button(
            onClick = {
                if (isValid) {
                    onCompleteOnboarding(name.trim(), ageText.toInt())
                }
            },
            enabled = isValid,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = "Continue",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
fun WelcomeOpenSourcePopup(
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "triangleRotation")
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to my app, this distribution is entirely open source contribution from me",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Hidden '+' with "Only for men" and Inverted Triangle SVG
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Plus SVG Icon
                    PlusSvgIcon(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Only for men",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Inverted Triangle SVG Icon
                    InvertedTriangleSvgIcon(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(rotation)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:floristechnis@proton.me")
                                    }
                                    context.startActivity(intent)
                                }
                            }
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "If anybody is interested in connecting me, connect me on floristechnis@proton.me",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
fun PlusSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.width * 0.14f, cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.18f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.82f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.5f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun InvertedTriangleSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(14.dp)
) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.25f)
            lineTo(size.width * 0.85f, size.height * 0.25f)
            lineTo(size.width * 0.50f, size.height * 0.80f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

```

---

### 3. Fixed Discover Entry Card & Professional Discover & Download Screen (`DiscoverEntryCard.kt` & `DiscoverScreen.kt`)

Eliminates all emojis/broken glyphs and provides full album art thumbnails, audio quality badges, 30s preview seekbar, and pure SVG buttons for Play/Pause, Skip, Add to Queue, and Lossless/MP3 Download.

```kotlin
// java/com/seitharol/ui/search/DiscoverEntryCard.kt
package com.seitharol.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CompassSvgIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 10f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        val needle = Path().apply {
            moveTo(16.24f * sx, 7.76f * sy)
            lineTo(14.12f * sx, 14.12f * sy)
            lineTo(7.76f * sx, 16.24f * sy)
            lineTo(9.88f * sx, 9.88f * sy)
            close()
        }
        drawPath(path = needle, color = tint, style = stroke)
    }
}

@Composable
fun ChevronRightSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(9f * sx, 6f * sy)
            lineTo(15f * sx, 12f * sy)
            lineTo(9f * sx, 18f * sy)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun DiscoverEntryCard(
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpenDiscover() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                CompassSvgIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Discover & Download",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Swipe 30s previews & lossless downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ChevronRightSvgIcon(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

```

```kotlin
// java/com/seitharol/ui/search/DiscoverScreen.kt
package com.seitharol.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.R

data class DiscoverTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val durationFormatted: String,
    val isLosslessAvailable: Boolean = true
)

@Composable
fun DiscoverScreen(
    tracks: List<DiscoverTrackItem>,
    currentlyPreviewingId: String?,
    isPreviewPlaying: Boolean,
    previewProgress: Float,
    onBack: () -> Unit,
    onTogglePreview: (DiscoverTrackItem) -> Unit,
    onPlayFullTrack: (DiscoverTrackItem) -> Unit,
    onDownloadTrack: (DiscoverTrackItem, Boolean) -> Unit,
    onAddToQueue: (DiscoverTrackItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Header with pure SVG Back Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Discover & Download",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Instant 30s studio previews & direct downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                val isThisPreviewing = currentlyPreviewingId == track.id
                DiscoverTrackCard(
                    track = track,
                    isPreviewing = isThisPreviewing,
                    isPlaying = isThisPreviewing && isPreviewPlaying,
                    progress = if (isThisPreviewing) previewProgress else 0f,
                    onTogglePreview = { onTogglePreview(track) },
                    onPlayFull = { onPlayFullTrack(track) },
                    onDownloadFlac = { onDownloadTrack(track, true) },
                    onDownloadMp3 = { onDownloadTrack(track, false) },
                    onAddToQueue = { onAddToQueue(track) }
                )
            }
        }
    }
}

@Composable
private fun DiscoverTrackCard(
    track: DiscoverTrackItem,
    isPreviewing: Boolean,
    isPlaying: Boolean,
    progress: Float,
    onTogglePreview: () -> Unit,
    onPlayFull: () -> Unit,
    onDownloadFlac: () -> Unit,
    onDownloadMp3: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // High-res Album Art Thumbnail with SVG Fallback (Never an Emoji)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlayFull() },
                    contentAlignment = Alignment.Center
                ) {
                    if (track.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = painterResource(id = R.drawable.ic_music_note),
                            placeholder = painterResource(id = R.drawable.ic_music_note)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${track.durationFormatted}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (track.isLosslessAvailable) R.drawable.ic_flac else R.drawable.ic_mp3
                            ),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (track.isLosslessAvailable) "FLAC / 320kbps" else "320kbps MP3",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 30s Preview Play/Pause SVG Button
                IconButton(
                    onClick = onTogglePreview,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = "Preview",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isPreviewing) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row (All Pure Vector/SVG Icons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add to Queue Button
                OutlinedButton(
                    onClick = onAddToQueue,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    QueueAddSvgIcon(
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Queue", style = MaterialTheme.typography.labelMedium)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // MP3 Download Button
                    OutlinedButton(
                        onClick = onDownloadMp3,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        DownloadSvgIcon(
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MP3", style = MaterialTheme.typography.labelMedium)
                    }

                    // Lossless FLAC Download Button
                    Button(
                        onClick = onDownloadFlac,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        DownloadSvgIcon(
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lossless", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun BackArrowSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(19f * sx, 12f * sy)
            lineTo(5f * sx, 12f * sy)
            moveTo(12f * sx, 19f * sy)
            lineTo(5f * sx, 12f * sy)
            lineTo(12f * sx, 5f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun DownloadSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(12f * sx, 3f * sy)
            lineTo(12f * sx, 15f * sy)
            moveTo(7f * sx, 10f * sy)
            lineTo(12f * sx, 15f * sy)
            lineTo(17f * sx, 10f * sy)
            moveTo(4f * sx, 20f * sy)
            lineTo(20f * sx, 20f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun QueueAddSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(3f * sx, 6f * sy)
            lineTo(15f * sx, 6f * sy)
            moveTo(3f * sx, 10f * sy)
            lineTo(15f * sx, 10f * sy)
            moveTo(3f * sx, 14f * sy)
            lineTo(11f * sx, 14f * sy)
            moveTo(17f * sx, 14f * sy)
            lineTo(17f * sx, 20f * sy)
            moveTo(14f * sx, 17f * sy)
            lineTo(20f * sx, 17f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

```

---

### 4. Fixed Stream Resolver (`StreamResolver.kt`) — Fixes Wrong Song / Remix Playing

Guarantees that the audio stream matches the exact song metadata by:

1. Decrypting JioSaavn's canonical `encrypted_media_url` directly with DES-ECB (`38346591`) so the exact studio track plays.
2. Filtering out all remixes, slowed+reverb, covers, lofi, karaoke, and duration-mismatched videos (`±15s` tolerance) when resolving via YouTube/Piped.

```kotlin
// java/com/seitharol/data/resolver/StreamResolver.kt
package com.seitharol.data.resolver

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class AudioQuality(val label: String, val saavnSuffix: String) {
    LOW("96 kbps (Data Saver)", "_96.mp4"),
    HIGH("160 kbps (Standard)", "_160.mp4"),
    EXTREME("320 kbps / Lossless", "_320.mp4")
}

data class TrackMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int = 0,
    val thumbnailUrl: String = "",
    val encryptedMediaUrl: String? = null,
    val directStreamUrl: String? = null,
    val source: String = "JIOSAAVN"
)

object StreamResolver {

    private val BANNED_REMIX_KEYWORDS = listOf(
        "remix", "slowed", "reverb", "lofi", "lo-fi", "cover", "karaoke",
        "instrumental", "mashup", "nightcore", "8d", "bass boosted",
        "ringtone", "whatsapp status", "reaction", "live performance", "dj "
    )

    /**
     * Resolves the exact canonical audio stream for [track].
     * Never returns a remix/cover unless explicitly requested in [track.title].
     */
    suspend fun resolveExactStreamUrl(
        track: TrackMetadata,
        quality: AudioQuality = AudioQuality.EXTREME
    ): String? = withContext(Dispatchers.IO) {
        // 1. If a verified direct stream URL is already attached, use it
        if (!track.directStreamUrl.isNullOrBlank() && track.directStreamUrl.startsWith("http")) {
            return@withContext track.directStreamUrl
        }

        // 2. If encryptedMediaUrl from JioSaavn is present, decrypt it directly
        if (!track.encryptedMediaUrl.isNullOrBlank()) {
            decryptJioSaavnUrl(track.encryptedMediaUrl, quality)?.let { return@withContext it }
        }

        // 3. Query JioSaavn canonical catalog by exact Title + Primary Artist
        resolveFromJioSaavnExact(track, quality)?.let { return@withContext it }

        // 4. Fallback to strict duration + title + artist validated Piped/YouTube stream
        resolveFromPipedStrict(track)
    }

    /**
     * Decrypts JioSaavn's DES-ECB encrypted_media_url using key "38346591".
     */
    fun decryptJioSaavnUrl(encryptedBase64: String, quality: AudioQuality): String? {
        return try {
            val keySpec = SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val decrypted = String(cipher.doFinal(decodedBytes), Charsets.UTF_8).trim()
            decrypted.replace("_96.mp4", quality.saavnSuffix)
                .replace("_160.mp4", quality.saavnSuffix)
                .replace("_320.mp4", quality.saavnSuffix)
                .replace("http://", "https://")
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFromJioSaavnExact(track: TrackMetadata, quality: AudioQuality): String? {
        return try {
            val cleanTitle = cleanTitleForMatch(track.title)
            val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").first().trim()
            val query = URLEncoder.encode("$cleanTitle $primaryArtist", "UTF-8")
            val apiUrl = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$query"

            val searchJson = httpGet(apiUrl) ?: return null
            val root = JSONObject(searchJson)
            val songsArray = root.optJSONObject("songs")?.optJSONArray("data") ?: return null

            var bestSongId: String? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until songsArray.length()) {
                val item = songsArray.getJSONObject(i)
                val candidateId = item.optString("id")
                val candidateTitle = decodeHtml(item.optString("title"))
                val candidateSub = decodeHtml(
                    item.optString("more_info") + " " + item.optString("description")
                )

                if (containsUnwantedVariant(originalTitle = track.title, candidateTitle = candidateTitle)) {
                    continue
                }

                val score = computeMatchScore(
                    targetTitle = track.title,
                    targetArtist = primaryArtist,
                    targetDuration = track.durationSeconds,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateSub,
                    candidateDuration = 0
                )
                if (score > bestScore) {
                    bestScore = score
                    bestSongId = candidateId
                }
            }

            if (bestSongId != null && bestScore >= 40) {
                val detailUrl = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$bestSongId"
                val detailJson = httpGet(detailUrl) ?: return null
                val detailObj = JSONObject(detailJson).optJSONObject(bestSongId) ?: return null
                val encryptedUrl = detailObj.optString("encrypted_media_url")
                if (encryptedUrl.isNotBlank()) {
                    return decryptJioSaavnUrl(encryptedUrl, quality)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFromPipedStrict(track: TrackMetadata): String? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me"
        )
        val primaryArtist = track.artist.split(",", "&", "feat.").first().trim()
        val encodedQuery = URLEncoder.encode("${track.title} $primaryArtist official audio", "UTF-8")

        for (base in instances) {
            try {
                val jsonStr = httpGet("$base/search?q=$encodedQuery&filter=music_songs") ?: continue
                val items = JSONObject(jsonStr).optJSONArray("items") ?: continue

                var bestVideoId: String? = null
                var bestScore = Int.MIN_VALUE

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val title = item.optString("title")
                    val uploader = item.optString("uploaderName")
                    val duration = item.optInt("duration", 0)
                    val urlPath = item.optString("url") // "/watch?v=XXXX"

                    if (containsUnwantedVariant(track.title, title)) continue
                    if (duration in 1..45 || duration > 720) continue
                    if (track.durationSeconds > 0 && duration > 0 && abs(duration - track.durationSeconds) > 18) {
                        continue
                    }

                    val score = computeMatchScore(
                        targetTitle = track.title,
                        targetArtist = primaryArtist,
                        targetDuration = track.durationSeconds,
                        candidateTitle = title,
                        candidateArtist = uploader,
                        candidateDuration = duration
                    )

                    if (score > bestScore) {
                        bestScore = score
                        bestVideoId = urlPath.substringAfter("v=", "")
                    }
                }

                if (!bestVideoId.isNullOrBlank() && bestScore >= 45) {
                    val streamJson = httpGet("$base/streams/$bestVideoId") ?: continue
                    val audioStreams = JSONObject(streamJson).optJSONArray("audioStreams") ?: continue
                    var bestAudioUrl: String? = null
                    var highestBitrate = -1
                    for (j in 0 until audioStreams.length()) {
                        val stream = audioStreams.getJSONObject(j)
                        val bitrate = stream.optInt("bitrate", 0)
                        val url = stream.optString("url")
                        if (url.isNotBlank() && bitrate > highestBitrate) {
                            highestBitrate = bitrate
                            bestAudioUrl = url
                        }
                    }
                    if (!bestAudioUrl.isNullOrBlank()) return bestAudioUrl
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun containsUnwantedVariant(originalTitle: String, candidateTitle: String): Boolean {
        val origLower = originalTitle.lowercase()
        val candLower = candidateTitle.lowercase()
        return BANNED_REMIX_KEYWORDS.any { keyword ->
            candLower.contains(keyword) && !origLower.contains(keyword)
        }
    }

    private fun computeMatchScore(
        targetTitle: String,
        targetArtist: String,
        targetDuration: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDuration: Int
    ): Int {
        var score = 0
        val cleanTargetTitle = cleanTitleForMatch(targetTitle).lowercase()
        val cleanCandTitle = cleanTitleForMatch(candidateTitle).lowercase()
        val targetArtistLower = targetArtist.lowercase()
        val candArtistLower = candidateArtist.lowercase()

        if (cleanCandTitle == cleanTargetTitle) {
            score += 60
        } else if (cleanCandTitle.contains(cleanTargetTitle) || cleanTargetTitle.contains(cleanCandTitle)) {
            score += 40
        } else {
            val targetTokens = cleanTargetTitle.split(" ").filter { it.length > 2 }
            val matchedTokens = targetTokens.count { cleanCandTitle.contains(it) }
            if (targetTokens.isNotEmpty()) {
                score += (matchedTokens * 35) / targetTokens.size
            }
        }

        if (candArtistLower.contains(targetArtistLower) || cleanCandTitle.contains(targetArtistLower)) {
            score += 35
        }
        if (candArtistLower.contains("- topic") || candArtistLower.contains("vevo")) {
            score += 15
        }

        if (targetDuration > 0 && candidateDuration > 0) {
            val diff = abs(targetDuration - candidateDuration)
            score += when {
                diff <= 3 -> 30
                diff <= 8 -> 20
                diff <= 15 -> 10
                else -> -40
            }
        }
        return score
    }

    private fun cleanTitleForMatch(raw: String): String {
        return raw.replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("(?i)official\\s*(music\\s*)?(video|audio|lyric\\s*video)"), "")
            .trim()
    }

    private fun decodeHtml(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}

```

---

### 5. Fixed Listening History Screen (`HistoryScreen.kt`) — Fixes Overlapping `♡Delete` & Red Bleed Glitch

Fixes the UI glitch in `image_744f38.png`:

* The foreground item card has a solid `MaterialTheme.colorScheme.surface` background so the red swipe background never shows through when idle.
* The Favorite Heart SVG and Delete Trash SVG buttons sit cleanly side by side without overlapping any text.
* Thumbnail uses `AsyncImage` with a proper Music Note SVG fallback instead of an `X` icon.

```kotlin
// java/com/seitharol/ui/history/HistoryScreen.kt
package com.seitharol.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.R
import com.seitharol.ui.search.BackArrowSvgIcon

data class HistoryTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationFormatted: String,
    val thumbnailUrl: String,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyItems: List<HistoryTrackItem>,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
    onPlayTrack: (HistoryTrackItem) -> Unit,
    onToggleFavorite: (HistoryTrackItem) -> Unit,
    onDeleteTrack: (HistoryTrackItem) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Recent, 1 = Archived
    var searchQuery by remember { mutableStateOf("") }

    val recentList = remember(historyItems, searchQuery) {
        historyItems.filter { !it.isArchived && (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)) }
    }
    val archivedList = remember(historyItems, searchQuery) {
        historyItems.filter { it.isArchived && (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true)) }
    }
    val displayedList = if (selectedTab == 0) recentList else archivedList

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Listening History",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (displayedList.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text(
                        text = "Clear All",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // Recent / Archived Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Recent (${recentList.size})") }
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("Archived (${archivedList.size})") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search History Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displayedList, key = { it.id }) { item ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDeleteTrack(item)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        // Only visible while actively swiping so it NEVER overlaps foreground icons
                        val isSwiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSwiping) MaterialTheme.colorScheme.errorContainer
                                    else Color.Transparent
                                )
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (isSwiping) {
                                TrashSvgIcon(
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                ) {
                    // Opaque Foreground Card (Prevents red background bleed-through)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPlayTrack(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album Art with Music Note SVG fallback (No 'X' icon)
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        error = painterResource(id = R.drawable.ic_music_note),
                                        placeholder = painterResource(id = R.drawable.ic_music_note)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_music_note),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${item.artist} • ${item.durationFormatted}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Non-overlapping Action Icons (Heart SVG + Trash SVG)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { onToggleFavorite(item) }) {
                                    HeartSvgIcon(
                                        filled = item.isFavorite,
                                        tint = if (item.isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onDeleteTrack(item) }) {
                                    TrashSvgIcon(
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeartSvgIcon(
    filled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(12f * sx, 21.35f * sy)
            lineTo(10.55f * sx, 20.03f * sy)
            cubicTo(5.4f * sx, 15.36f * sy, 2f * sx, 12.28f * sy, 2f * sx, 8.5f * sy)
            cubicTo(2f * sx, 5.42f * sy, 4.42f * sx, 3f * sy, 7.5f * sx, 3f * sy)
            cubicTo(9.24f * sx, 3f * sy, 10.91f * sx, 3.81f * sy, 12f * sx, 5.09f * sy)
            cubicTo(13.09f * sx, 3.81f * sy, 14.76f * sx, 3f * sy, 16.5f * sx, 3f * sy)
            cubicTo(19.58f * sx, 3f * sy, 22f * sx, 5.42f * sy, 22f * sx, 8.5f * sy)
            cubicTo(22f * sx, 12.28f * sy, 18.6f * sx, 15.36f * sy, 13.45f * sx, 20.04f * sy)
            close()
        }
        if (filled) {
            drawPath(path = path, color = tint, style = Fill)
        } else {
            drawPath(path = path, color = tint, style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun TrashSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(3f * sx, 6f * sy)
            lineTo(21f * sx, 6f * sy)
            moveTo(8f * sx, 6f * sy)
            lineTo(8f * sx, 4f * sy)
            lineTo(16f * sx, 4f * sy)
            lineTo(16f * sx, 6f * sy)
            moveTo(6f * sx, 6f * sy)
            lineTo(7f * sx, 20f * sy)
            lineTo(17f * sx, 20f * sy)
            lineTo(18f * sx, 6f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

```

---

### 6. Fixed Home Screen & Theme-Aware Bottom Navigation (`HomeScreen.kt`)

Fixes `image_744f5b.png` and `image_744f54.jpg`:

* Displays the App Name (`Seitharol`), Dynamic Greeting (`Good Morning / Afternoon / Evening`), Search Bar, and Mood/Genre Selection Chips (`Energize`, `Trending`, `Manipuri Song`, `Sad`, `Chill`, `Romance`, `Workout`, `Lo-Fi`).
* Automatically loads songs for the selected category so the Home screen is never blank.
* Fixes the floating Bottom Navigation Pill so it uses `MaterialTheme.colorScheme.surfaceVariant` and `onSurfaceVariant` instead of hardcoded white-on-white.

```kotlin
// java/com/seitharol/ui/home/HomeScreen.kt
package com.seitharol.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.R
import com.seitharol.data.resolver.TrackMetadata
import java.util.Calendar

val HOME_MOOD_CATEGORIES = listOf(
    "Energize",
    "Trending",
    "Manipuri Song",
    "Sad",
    "Chill",
    "Romance",
    "Workout",
    "Lo-Fi"
)

@Composable
fun HomeScreen(
    userName: String,
    selectedCategory: String,
    categoryTracks: List<TrackMetadata>,
    manipuriHighlights: List<TrackMetadata>,
    isLoading: Boolean,
    onSelectCategory: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenHistory: () -> Unit,
    onPlayTrack: (TrackMetadata) -> Unit
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Late Night Vibes"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        // 1. App Name, Greeting, and History Action Button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SEITHAROL",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (userName.isNotBlank()) "$greeting, $userName" else greeting,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    HistoryClockSvgIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 2. Interactive Search Bar on Home Screen
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onOpenSearch() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchMagnifierSvgIcon(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search Manipuri songs, artists, albums...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 3. Mood & Category Selection Chips (Energize, Trending, Manipuri Song, Sad, etc.)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(HOME_MOOD_CATEGORIES) { category ->
                    val isSelected = category.equals(selectedCategory, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCategory(category) },
                        label = {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 4. Selected Mood / Category Tracks Section
        item {
            Text(
                text = "$selectedCategory Picks",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (isLoading && categoryTracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            items(categoryTracks, key = { it.id }) { track ->
                HomeTrackRow(track = track, onClick = { onPlayTrack(track) })
            }
        }

        // 5. Dedicated Manipuri Songs Spotlight Section
        if (manipuriHighlights.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Manipuri Hits",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            items(manipuriHighlights, key = { "manipuri_${it.id}" }) { track ->
                HomeTrackRow(track = track, onClick = { onPlayTrack(track) })
            }
        }
    }
}

@Composable
private fun HomeTrackRow(
    track: TrackMetadata,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = painterResource(id = R.drawable.ic_music_note),
                    placeholder = painterResource(id = R.drawable.ic_music_note)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_play),
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Theme-aware Floating Bottom Navigation Bar that properly adapts to Dark and Light themes
 * (Fixes white-on-white invisible icons in both themes).
 */
@Composable
fun SeitharolBottomNavBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 14.dp)
            .height(60.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                shape = RoundedCornerShape(32.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf("Home", "Search", "Settings")
            tabs.forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                val iconTint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .clickable { onSelectTab(index) },
                    contentAlignment = Alignment.Center
                ) {
                    when (index) {
                        0 -> HomeSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                        1 -> SearchMagnifierSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                        else -> SettingsGearSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(3f * sx, 10.5f * sy)
            lineTo(12f * sx, 3f * sy)
            lineTo(21f * sx, 10.5f * sy)
            lineTo(21f * sx, 20f * sy)
            lineTo(14.5f * sx, 20f * sy)
            lineTo(14.5f * sx, 14f * sy)
            lineTo(9.5f * sx, 14f * sy)
            lineTo(9.5f * sx, 20f * sy)
            lineTo(3f * sx, 20f * sy)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

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

@Composable
fun HistoryClockSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 9f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        val hands = Path().apply {
            moveTo(12f * sx, 7f * sy)
            lineTo(12f * sx, 12f * sy)
            lineTo(15.5f * sx, 14f * sy)
        }
        drawPath(path = hands, color = tint, style = stroke)
    }
}

@Composable
fun SettingsGearSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 3.5f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        drawCircle(
            color = tint,
            radius = 8.5f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round)
        )
    }
}

```

---

### 7. Fixed Play Queue Autoplay & Context-Aware Recommendation Engine (`QueueRecommendationEngine.kt` & `PlaybackQueueSheet.kt`)

Fixes `image_744f1b.jpg`:

* Replaces the unrelated static song list with a live **Context-Aware Recommendation Engine** that fetches songs matching the **currently playing song's artist, genre/language, and queue context** (e.g., if playing *Safar* by *Bayaan*, it fetches Bayaan & related Hindi/Urdu indie rock tracks; if playing a Manipuri song, it fetches Manipuri tracks).
* **When `autoplay == true**`: Automatically adds the relevant songs directly into the `Up Next` queue (`onAutoAppendTracks`) instead of leaving them in a suggestion list.
* **When `autoplay == false**`: Displays them in `"Suggested for Current Queue"` with an SVG Add-to-Queue button.

```kotlin
// java/com/seitharol/player/QueueRecommendationEngine.kt
package com.seitharol.player

import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QueueRecommendationEngine {

    /**
     * Generates recommendations strictly relevant to [currentTrack] and [queueTracks].
     */
    suspend fun fetchRelevantRecommendations(
        currentTrack: TrackMetadata,
        queueTracks: List<TrackMetadata>,
        limit: Int = 8
    ): List<TrackMetadata> = withContext(Dispatchers.IO) {
        val excludedIds = (queueTracks.map { it.id } + currentTrack.id).toSet()
        val excludedTitles = (queueTracks.map { it.title.lowercase().trim() } + currentTrack.title.lowercase().trim()).toSet()
        val results = mutableListOf<TrackMetadata>()

        // 1. Try JioSaavn's native song similarity API (reco.getreco) if ID is numeric/Saavn
        fetchSaavnStationRecommendations(currentTrack.id, excludedIds, excludedTitles, results)

        // 2. Query by Primary Artist + dominant queue artists to keep the exact vibe/language
        if (results.size < limit) {
            val primaryArtist = currentTrack.artist.split(",", "&", "feat.").first().trim()
            val queueArtists = queueTracks
                .map { it.artist.split(",", "&").first().trim() }
                .distinct()
                .take(2)

            val queries = buildList {
                add("$primaryArtist ${currentTrack.title}")
                add(primaryArtist)
                queueArtists.forEach { if (!it.equals(primaryArtist, true)) add(it) }
            }

            for (query in queries) {
                if (results.size >= limit) break
                searchContextualTracks(query, currentTrack.title, excludedIds, excludedTitles, results, limit)
            }
        }

        results.take(limit)
    }

    private fun fetchSaavnStationRecommendations(
        songId: String,
        excludedIds: Set<String>,
        excludedTitles: Set<String>,
        out: MutableList<TrackMetadata>
    ) {
        try {
            val url = "https://www.jiosaavn.com/api.php?__call=reco.getreco&_format=json&_marker=0&cc=in&pid=${URLEncoder.encode(songId, "UTF-8")}"
            val raw = httpGet(url) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val track = parseSaavnSongObject(obj) ?: continue
                if (track.id !in excludedIds && track.title.lowercase().trim() !in excludedTitles) {
                    out.add(track)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun searchContextualTracks(
        query: String,
        seedTitle: String,
        excludedIds: Set<String>,
        excludedTitles: Set<String>,
        out: MutableList<TrackMetadata>,
        limit: Int
    ) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&q=$encoded&n=10"
            val raw = httpGet(url) ?: return
            val resultsArr = JSONObject(raw).optJSONArray("results") ?: return

            for (i in 0 until resultsArr.length()) {
                if (out.size >= limit) break
                val obj = resultsArr.getJSONObject(i)
                val track = parseSaavnSongObject(obj) ?: continue
                val normTitle = track.title.lowercase().trim()
                if (track.id in excludedIds || normTitle in excludedTitles) continue
                if (out.any { it.id == track.id || it.title.equals(track.title, true) }) continue
                if (StreamResolver.containsUnwantedVariant(seedTitle, track.title)) continue
                out.add(track)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseSaavnSongObject(obj: JSONObject): TrackMetadata? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title", obj.optString("song"))
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() } ?: return null
        val moreInfo = obj.optJSONObject("more_info")
        val artist = moreInfo?.optJSONObject("artistMap")
            ?.optJSONArray("primary_artists")
            ?.optJSONObject(0)
            ?.optString("name")
            ?: obj.optString("primary_artists")
            .ifBlank { obj.optString("subtitle") }
        val image = obj.optString("image").replace("150x150", "500x500")
        val duration = moreInfo?.optString("duration")?.toIntOrNull() ?: 0
        val encryptedUrl = moreInfo?.optString("encrypted_media_url") ?: obj.optString("encrypted_media_url")

        return TrackMetadata(
            id = id,
            title = title,
            artist = artist,
            durationSeconds = duration,
            thumbnailUrl = image,
            encryptedMediaUrl = encryptedUrl.ifBlank { null }
        )
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}

```

```kotlin
// java/com/seitharol/ui/player/PlaybackQueueSheet.kt
package com.seitharol.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.R
import com.seitharol.data.resolver.TrackMetadata
import com.seitharol.player.QueueRecommendationEngine
import com.seitharol.ui.history.TrashSvgIcon
import com.seitharol.ui.search.QueueAddSvgIcon

@Composable
fun PlaybackQueueSheet(
    currentTrack: TrackMetadata?,
    queue: List<TrackMetadata>,
    autoplayEnabled: Boolean,
    onToggleAutoplay: (Boolean) -> Unit,
    onSelectQueueTrack: (TrackMetadata) -> Unit,
    onRemoveFromQueue: (TrackMetadata) -> Unit,
    onAddTrackToQueue: (TrackMetadata) -> Unit,
    onAutoAppendTracks: (List<TrackMetadata>) -> Unit,
    onClose: () -> Unit
) {
    var suggestedTracks by remember { mutableStateOf<List<TrackMetadata>>(emptyList()) }

    // Fetch context-relevant recommendations whenever the current track or queue changes
    LaunchedEffect(currentTrack?.id, queue.size, autoplayEnabled) {
        val seed = currentTrack ?: queue.firstOrNull() ?: return@LaunchedEffect
        val recommendations = QueueRecommendationEngine.fetchRelevantRecommendations(
            currentTrack = seed,
            queueTracks = queue,
            limit = 6
        )

        if (autoplayEnabled) {
            // Autoplay is ON -> directly auto-add relevant songs to the queue if fewer than 4 upcoming tracks remain
            val currentIndex = queue.indexOfFirst { it.id == seed.id }.coerceAtLeast(0)
            val upcomingCount = (queue.size - 1 - currentIndex).coerceAtLeast(0)
            if (upcomingCount < 3 && recommendations.isNotEmpty()) {
                val toAppend = recommendations.take(3 - upcomingCount)
                onAutoAppendTracks(toAppend)
            }
            suggestedTracks = emptyList()
        } else {
            // Autoplay is OFF -> suggest only, do not auto-add
            suggestedTracks = recommendations
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Queue",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
            Text(
                text = "Up Next • ${queue.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Active Queue Items
        items(queue, key = { "queue_${it.id}" }) { track ->
            val isPlaying = track.id == currentTrack?.id
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectQueueTrack(track) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onRemoveFromQueue(track) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        TrashSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        error = painterResource(id = R.drawable.ic_music_note),
                        placeholder = painterResource(id = R.drawable.ic_music_note)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isPlaying) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "PLAYING",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Autoplay Toggle Card
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autoplay",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (autoplayEnabled) {
                                "Automatically adding similar songs to Up Next"
                            } else {
                                "Autoplay is off • Showing manual suggestions below"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoplayEnabled,
                        onCheckedChange = onToggleAutoplay
                    )
                }
            }
        }

        // Manual Suggestions ONLY when Autoplay is OFF
        if (!autoplayEnabled && suggestedTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Suggested for ${currentTrack?.title ?: "this queue"}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(suggestedTracks, key = { "suggest_${it.id}" }) { suggestion ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = suggestion.thumbnailUrl,
                            contentDescription = suggestion.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            error = painterResource(id = R.drawable.ic_music_note),
                            placeholder = painterResource(id = R.drawable.ic_music_note)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = suggestion.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = suggestion.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onAddTrackToQueue(suggestion) }) {
                            QueueAddSvgIcon(
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

```

---

### 8. Fixed `GeneralSettingsScreen.kt` (Replaces Corrupted `Text(" ")` Glyphs with Pure SVGs)

In your uploaded `GeneralSettingsScreen.kt`, lines 183 and 227 had `Text(" ")` where the chevron and back arrow icons were missing. Replace those two lines in `GeneralSettingsScreen.kt` with `ChevronRightSvgIcon()` and `BackArrowSvgIcon()` from `DiscoverEntryCard.kt` and `DiscoverScreen.kt`:

```kotlin
// Inside GeneralSettingsScreen.kt -> "About Us" Row:
ChevronRightSvgIcon(
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.size(20.dp)
)

// Inside InfoDetailScreen -> Top-Left IconButton:
IconButton(onClick = onBack) {
    BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onBackground)
}

```, make sure you used svgs at all maybe from lucide but no emojis