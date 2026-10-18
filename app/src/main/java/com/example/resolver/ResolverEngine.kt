package com.example.resolver

import android.util.Log
import com.example.data.model.ResolverNode
import com.example.data.model.Track
import com.example.data.model.TrustZone as ModelTrustZone
import com.example.resolver.providers.JioSaavnProvider
import com.example.resolver.providers.NormalizedTrack
import com.example.resolver.providers.SourceType
import com.example.resolver.providers.SpotifyMetadataProvider
import com.example.resolver.providers.TrackMatcher
import com.example.resolver.providers.YouTubeInnerTubeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class CircuitBreakerState {
    CLOSED,    // Normal operation
    HALF_OPEN, // Testing probe
    OPEN       // Tripped, fallback active
}

/**
 * Modern On-Device Standalone Resolver Engine.
 * 100% on-device architecture without relying on external servers (such as FastAPI yt-dlp at 10.0.2.2:8000
 * or unstable third-party proxy nodes).
 *
 * Supported On-Device Adapters:
 * 1. On-Device YouTube Music InnerTube Provider (ANDROID_VR / IOS_MUSIC clients, Opus 160k / AAC 128k)
 * 2. On-Device JioSaavn Provider (hardware DES decryptor, up to 320kbps pure AAC)
 * 3. On-Device Spotify Metadata Provider & Cross-Source TrackMatcher
 * 4. Studio Master CDN Resolver
 * 5. High-Fi Offline Local Cache
 */
class ResolverEngine {
    private val TAG = "ResolverEngine"
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()

    // On-Device Providers
    val youtubeInnerTubeProvider = YouTubeInnerTubeProvider()
    val jioSaavnProvider = JioSaavnProvider()
    val spotifyProvider = SpotifyMetadataProvider()
    val trackMatcher = TrackMatcher(youtubeInnerTubeProvider, jioSaavnProvider)
    val studioResolver = StudioMasterResolver()

    private val _activeNodes = MutableStateFlow<List<ResolverNode>>(
        listOf(
            ResolverNode(
                id = "node_innertube",
                name = "On-Device InnerTube (YouTube Music)",
                url = "https://music.youtube.com",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 8,
                isOnline = true,
                isSelected = true,
                successRate = 0.999f
            ),
            ResolverNode(
                id = "node_jiosaavn",
                name = "On-Device JioSaavn (320kbps Hi-Fi)",
                url = "https://www.jiosaavn.com",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 12,
                isOnline = true,
                isSelected = false,
                successRate = 0.998f
            ),
            ResolverNode(
                id = "node_studio_master",
                name = "Studio Master CDN",
                url = "https://itunes.apple.com",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 14,
                isOnline = true,
                isSelected = false,
                successRate = 1.0f
            ),
            ResolverNode(
                id = "node_local_cache",
                name = "Offline Opus/AAC Store",
                url = "file://local_storage",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 1,
                isOnline = true,
                isSelected = false,
                successRate = 1.0f
            )
        )
    )
    val activeNodes: StateFlow<List<ResolverNode>> = _activeNodes.asStateFlow()

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _circuitBreakerState = MutableStateFlow(CircuitBreakerState.CLOSED)
    val circuitBreakerState: StateFlow<CircuitBreakerState> = _circuitBreakerState.asStateFlow()

    private val _currentPackVersion = MutableStateFlow("v2026.10-standalone-pure")
    val currentPackVersion: StateFlow<String> = _currentPackVersion.asStateFlow()

    fun setOfflineMode(enabled: Boolean) {
        _isOfflineMode.value = enabled
    }

    fun selectNode(nodeId: String) {
        _activeNodes.value = _activeNodes.value.map {
            it.copy(isSelected = it.id == nodeId)
        }
    }

    fun clearCache() {
        streamCache.clear()
    }

    suspend fun resolveTrack(track: Track): ResolvedStream {
        // Guard: validate critical metadata before any resolution attempt
        if (track.id.isBlank()) {
            throw IllegalStateException("Track has a blank ID — cannot resolve stream. Title='${track.title}' Artist='${track.artist}'")
        }
        if (track.title.isBlank() || track.artist.isBlank()) {
            Log.w(TAG, "Track metadata incomplete — id='${track.id}' title='${track.title}' artist='${track.artist}'. Falling back to ID-only resolution.")
        }

        val now = System.currentTimeMillis()

        // 1. Verified Local File Priority: must exist and have length > 10KB (eliminates dummy placeholder crashes)
        if (track.isDownloaded && !track.localPath.isNullOrBlank()) {
            val localFile = File(track.localPath)
            if (localFile.exists() && localFile.length() > 10_240L) {
                return ResolvedStream(
                    videoId = track.id,
                    streamUrl = if (track.localPath.startsWith("http")) track.localPath else "file://${track.localPath}",
                    itag = track.itag,
                    codec = track.codec,
                    bitrate = "160 kbps",
                    expiresAtMs = now + 86400000L,
                    isLocal = true,
                    resolvedBy = "Local Encrypted Store (Offline Ready)",
                    latencyMs = 1
                )
            }
        }

        // If offline mode is enabled by user and not cached locally
        if (_isOfflineMode.value) {
            val localFile = if (!track.localPath.isNullOrBlank()) File(track.localPath) else null
            if (localFile == null || !localFile.exists() || localFile.length() <= 10_240L) {
                throw IllegalStateException("Offline Mode active. Stream not cached locally.")
            }
        }

        // 2. Lookup in In-Memory Stream Cache
        val cached = streamCache[track.id]
        if (cached != null && now < cached.expiresAtMs - 120000L) {
            return cached
        }

        val startTime = System.currentTimeMillis()

        // 3. Direct verified stream if provided and non-placeholder
        if (track.audioUrl.isNotBlank() && !track.audioUrl.contains("SoundHelix")) {
            val resolved = ResolvedStream(
                videoId = track.id,
                streamUrl = track.audioUrl,
                itag = track.itag,
                codec = track.codec,
                bitrate = "256 kbps",
                expiresAtMs = now + (24 * 3600 * 1000L),
                isLocal = false,
                resolvedBy = "Direct Verified Stream",
                latencyMs = 6
            )
            streamCache[track.id] = resolved
            return resolved
        }

        // 4. On-Device Multi-Source Resolution
        var resolvedStream: ResolvedStream? = null

        // Check if track is a JioSaavn ID (e.g. numeric ID or explicitly tagged)
        val isSaavn = track.id.all { it.isDigit() } || track.genre.contains("Indian", ignoreCase = true)
        val isSpotify = track.id.length == 22 && !track.id.contains("_") && !track.id.contains("-")

        if (isSpotify) {
            try {
                val normalized = NormalizedTrack(
                    source = SourceType.SPOTIFY,
                    sourceId = track.id,
                    title = track.title,
                    artists = listOf(track.artist),
                    album = track.album,
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isPlayableNatively = false
                )
                resolvedStream = trackMatcher.matchAndResolve(normalized)
            } catch (e: Exception) {
                Log.w(TAG, "Spotify TrackMatcher failed for ${track.id}: ${e.message}")
            }
        }

        if (resolvedStream == null && isSaavn) {
            try {
                resolvedStream = jioSaavnProvider.resolveStream(track.id, preferredKbps = 320)
            } catch (e: Exception) {
                Log.w(TAG, "JioSaavn resolution failed for ${track.id}: ${e.message}")
            }
        }

        if (resolvedStream == null) {
            // Primary: On-Device YouTube InnerTube Provider
            // Accept any non-blank ID that looks like a YouTube videoId (11 chars, no spaces)
            // or attempt ID-based resolution for shorter IDs before falling back to search
            try {
                val cleanId = track.id.trim()
                if (cleanId.length == 11 && !cleanId.contains(" ") && !cleanId.startsWith("pl_")) {
                    resolvedStream = youtubeInnerTubeProvider.resolveStream(cleanId, preferredKbps = 160)
                } else if (cleanId.isNotBlank() && !cleanId.startsWith("pl_") && !cleanId.all { it.isDigit() }) {
                    // Non-standard YouTube ID — still try direct resolution
                    try {
                        resolvedStream = youtubeInnerTubeProvider.resolveStream(cleanId, preferredKbps = 160)
                    } catch (e2: Exception) {
                        Log.d(TAG, "Direct non-standard ID resolution failed, will search: ${e2.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "On-device InnerTube direct resolve failed for ${track.id}: ${e.message}")
            }
        }

        // Fallback 1: On-Device YouTube Music search and full audio stream extraction
        if (resolvedStream == null) {
            try {
                val title = track.title.trim()
                val artist = track.artist.trim()
                // Build the best possible search query from available metadata
                val query = when {
                    title.isNotBlank() && artist.isNotBlank() -> "$title $artist"
                    title.isNotBlank() -> title
                    artist.isNotBlank() -> artist
                    else -> ""
                }
                if (query.isNotBlank()) {
                    val ytResults = youtubeInnerTubeProvider.searchTracks(query, limit = 3)
                    val match = ytResults.firstOrNull()
                    if (match != null && match.sourceId.isNotBlank()) {
                        resolvedStream = youtubeInnerTubeProvider.resolveStream(match.sourceId, preferredKbps = 160)
                    }
                } else {
                    Log.e(TAG, "Cannot search YouTube: both title and artist are blank for track.id='${track.id}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "YouTube search fallback failed: ${e.message}")
            }
        }

        // Fallback 2: On-Device JioSaavn search match (Full 320kbps AAC stream)
        if (resolvedStream == null) {
            try {
                val artist = track.artist.trim()
                val title = track.title.trim()
                val query = when {
                    artist.isNotBlank() && title.isNotBlank() -> "$artist $title"
                    title.isNotBlank() -> title
                    artist.isNotBlank() -> artist
                    else -> ""
                }
                if (query.isNotBlank()) {
                    val results = jioSaavnProvider.searchTracks(query, limit = 3)
                    val match = results.firstOrNull()
                    if (match != null) {
                        resolvedStream = jioSaavnProvider.resolveStream(match.sourceId, preferredKbps = 320)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "JioSaavn search fallback failed: ${e.message}")
            }
        }

        // Fallback 3: Direct on-device YouTube Music InnerTube (ANDROID_VR / IOS / Piped)
        if (resolvedStream == null) {
            try {
                val directUrl = com.example.data.remote.InnerTubeResolver.resolveStreamUrl(track.id, track.title, track.artist)
                if (!directUrl.isNullOrBlank()) {
                    resolvedStream = ResolvedStream(
                        videoId = track.id,
                        streamUrl = directUrl,
                        itag = track.itag,
                        codec = if (directUrl.contains("opus") || directUrl.contains("webm")) "Opus" else "AAC",
                        bitrate = "160 kbps",
                        expiresAtMs = now + (6 * 3600 * 1000L),
                        isLocal = false,
                        resolvedBy = "YouTube Music InnerTube Direct (Android VR / iOS)",
                        latencyMs = 15
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTubeResolver direct stream fallback failed: ${e.message}")
            }
        }

        val finalStream = resolvedStream
            ?: throw IllegalStateException("Could not resolve stream for track '${track.title}' by '${track.artist}'")

        _circuitBreakerState.value = CircuitBreakerState.CLOSED
        streamCache[track.id] = finalStream
        return finalStream
    }
}
