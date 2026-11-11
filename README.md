<div align="center">

  <img src="icon.png" width="132" height="132" alt="SEITHAROL Logo" style="border-radius: 24px;" />

  # SEITHAROL

  **A modern, privacy-first Material 3 Music Player for Android with native YouTube Music streaming, on-device audio resolution, and local library playback.**

  [![Android Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
  [![Version](https://img.shields.io/badge/Version-v3.2.4-blue?style=for-the-badge)](https://github.com/floristechnis-git/SEITHAROL/releases)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
  [![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
  [![ExoPlayer Media3](https://img.shields.io/badge/Audio-AndroidX%20Media3%201.5.1-FF6F00?style=for-the-badge)](https://developer.android.com/guide/topics/media/media3)
  [![Privacy](https://img.shields.io/badge/Privacy-Ad--Free%20%7C%20No%20Telemetry-2ea44f?style=for-the-badge)](LICENSE)

  <p align="center">
    <a href="#overview">Overview</a> •
    <a href="#features">Features</a> •
    <a href="#architecture--tech-stack">Architecture</a> •
    <a href="#installation">Installation</a> •
    <a href="#building-from-source">Building from Source</a> •
    <a href="#privacy--permissions">Permissions</a> •
    <a href="#contributing">Contributing</a>
  </p>

</div>

---

## Overview

**Seitharol** is an offline-first, native Android music application built entirely with **Kotlin** and **Jetpack Compose (Material 3)**. Designed for speed, aesthetic precision, and privacy, Seitharol merges high-fidelity YouTube Music streaming with local device media playback in a unified, modern interface.

Unlike web-wrapped clients or players that depend on third-party proxy servers, Seitharol resolves and extracts media streams **entirely on-device** using native Kotlin InnerTube extractors. This architecture guarantees zero-latency stream resolution, privacy preservation, and independence from cloud relay backends.

---

## Features

### Streaming & On-Device Resolution
- **Client-Side InnerTube Extractor**: Resolves YouTube Music audio streams directly on the device without relay servers, external scrapers, or user authentication.
- **Adaptive Codec Selection**: Automatically selects the highest available audio quality, prioritizing 256 kbps Opus and 128–256 kbps AAC (m4a) streams with progressive stream recovery.
- **Federated Metadata Matching**: High-accuracy track matching engine aligning YouTube Music, JioSaavn, and Spotify catalogs.
- **Exploration & Discovery**: Search tracks, albums, artists, and community playlists, accompanied by dynamic mood and genre discovery feeds.

### Audio Engine & Playback (AndroidX Media3)
- **Foreground Background Playback**: Reliable, uninterrupted background playback managed via a dedicated `MediaPlaybackService`.
- **System MediaSession Integration**: Comprehensive lock screen controls, Bluetooth AVRCP support, Android Auto compatibility, and dynamic system notification controls.
- **Gapless Playback & Crossfade**: Configurable crossfade durations and seamless gapless playback transitions between tracks.
- **Multi-Tier Audio Controls**: Real-time playback speed modification (0.5x to 2.0x), pitch controls, volume boost, and audio normalization.
- **Picture-in-Picture & Miniplayer**: Persistent, responsive miniplayer bar with support for Android system Picture-in-Picture (PiP) mode.
- **Real-Time Visualizer**: Audio waveform visualizer and interactive mood map canvas.

### Unified Library & Local Media
- **Local Media Indexing**: Automatic querying of Android's MediaStore to catalog local audio files across MP3, FLAC, M4A, OGG, WAV, and OPUS formats.
- **Unified Playlists**: Create, edit, and organize custom playlists combining both local storage tracks and online streams.
- **Listening History & Favorites**: Indexed playback history and instant one-tap favorite management.
- **Intelligent Queue Recommendations**: Real-time auto-suggest recommendation engine predicting related tracks based on queue context.

### Synchronized Lyrics
- **LRC Synchronized Parser**: High-precision LRC parser delivering line-by-line synchronized lyrics.
- **Smooth Auto-Scroll**: Real-time timeline highlighting synchronized with playback progression.
- **Manual Search**: Fallback lyrics resolution for tracks lacking embedded tags.

### Offline Downloads & Caching
- **Direct Downloader**: Download audio streams with embedded metadata and album art directly to local storage.
- **Database Caching**: Persistent Room database (`CascadeDatabase`) caching metadata, recent queries, and stream references.
- **Storage Management**: Detailed cache oversight and storage quota management in application settings.

### Material 3 Design
- **Dynamic Theming**: Color extraction engine that dynamically generates Material 3 color palettes from album artwork.
- **AMOLED & Dark Themes**: Optimized true-black dark theme for OLED power efficiency alongside light mode support.
- **Custom Typography**: Curated pairing featuring **Syne** for display headlines and **Outfit** for clean UI readability.
- **Adaptive Layouts**: Fluid, responsive interface scaling across standard smartphones, foldables, and tablets.

---

## Architecture & Tech Stack

Seitharol follows official Modern Android Architecture principles, combining a single-activity Compose navigation architecture with unidirectional data flow (UDF) and coroutine-driven reactive repositories.

| Layer | Component | Technologies | Purpose |
|---|---|---|---|
| **UI** | Presentation | Jetpack Compose, Material 3, Coil | Declarative, dynamic user interface with reactive animations |
| **Navigation** | App Navigation | Navigation Compose | Type-safe in-app navigation routes and deep linking |
| **State** | ViewModel & UDF | AndroidX Lifecycle, Kotlin StateFlow | Lifecycle-aware state management and business logic |
| **Audio** | Playback Engine | AndroidX Media3 ExoPlayer (`v1.5.1`) | Audio pipeline, stream buffering, crossfade, and hardware decoding |
| **Service** | Background Playback | AndroidX MediaSession, Foreground Service | System notification controls and persistent background execution |
| **Resolver** | Stream Extraction | Native Kotlin InnerTube Resolver, OkHttp | On-device signature deciphering, stream extraction, and CDN resolution |
| **Storage** | Database & Cache | AndroidX Room (`v2.7.0`), KSP | Offline track storage, playlist persistence, and search indices |
| **Local Media** | File Indexing | Android MediaStore API | Local storage scanning and audio tag extraction |
| **Network** | HTTP Client | OkHttp 4, Moshi | Connection pooling, payload parsing, and HTTP/2 transport |

---

## Installation

### Pre-built APK
1. Navigate to the **[Releases](https://github.com/floristechnis-git/SEITHAROL/releases)** section.
2. Download the latest `in.floristechnis.seitharol-v3.2.4.apk`.
3. Open the file on your device and confirm installation (grant *Install from Unknown Sources* if prompted).
4. Launch **Seitharol**.

> [!NOTE]
> Seitharol requires **Android 7.0 (API Level 24)** or newer.

---

## Building from Source

Seitharol compiles cleanly without requiring external secrets, companion servers, or `.env` configuration. All stream extraction is self-contained.

### Prerequisites
- **Android Studio**: Ladybug / Meerkat (2024.2+) or higher
- **JDK**: Java Development Kit 17 or 21
- **Android SDK**: Compile SDK 36, Target SDK 36, Min SDK 24

### Build Steps

1. **Clone the repository**:
   ```bash
   git clone https://github.com/floristechnis-git/SEITHAROL.git
   cd SEITHAROL
   ```

2. **Open in Android Studio**:
   - Launch Android Studio, select **Open**, and choose the cloned `SEITHAROL` directory.
   - Allow Gradle to sync dependencies defined in `gradle/libs.versions.toml`.

3. **Compile the Debug APK**:
   ```bash
   # Linux / macOS
   ./gradlew assembleDebug

   # Windows (PowerShell)
   .\gradlew.bat assembleDebug
   ```

4. **Install to Connected Device or Emulator**:
   ```bash
   .\gradlew.bat installDebug
   ```

The compiled output will be generated at:
```
app/build/outputs/apk/debug/in.floristechnis.seitharol-v3.2.4.apk
```

---

## Privacy & Permissions

Seitharol is dedicated to user privacy. It operates without tracking libraries, third-party analytics, or advertising SDKs.

| Permission | Technical Requirement |
|---|---|
| `INTERNET` | Retrieve audio streams and metadata from online endpoints |
| `ACCESS_NETWORK_STATE` | Monitor network connectivity to manage playback and offline fallbacks |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Maintain uninterrupted playback when the application is minimized |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | Discover and play audio tracks stored on device storage |
| `POST_NOTIFICATIONS` | Present interactive playback controls in the system notification shade |
| `MODIFY_AUDIO_SETTINGS` | Integrate with equalizer settings, volume boost, and audio routing |

---

## Contributing

Contributions, bug reports, and suggestions are welcome.

- **Issue Tracker**: Use GitHub Issues to submit structured bug reports or feature requests.
- **Code Style**: Adhere to standard Kotlin coding conventions and ensure all Compose components use design tokens.
- **Verification**: Verify that project builds cleanly before opening a pull request:
  ```bash
  .\gradlew.bat check
  ```

---

## Acknowledgments

Seitharol is developed with inspiration and tools from the broader open-source Android ecosystem:

- **[OuterTune](https://github.com/OuterTune/OuterTune)** and **[InnerTune](https://github.com/z-huang/InnerTune)** for inspiring the Material 3 audio UI experience.
- **[NewPipe](https://github.com/TeamNewPipe/NewPipe)** for foundational research in open media extraction.
- **[AndroidX Media3](https://developer.android.com/guide/topics/media/media3)** for the core audio playback framework.
- **[Coil](https://coil-kt.github.io/coil/)** for image loading in Jetpack Compose.

---

<div align="center">
  <sub>Seitharol • Open Source Music for Android</sub>
</div>
