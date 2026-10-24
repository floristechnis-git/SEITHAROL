package com.example.resolver

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.CascadeApplication
import com.example.data.model.ResolverNode
import com.example.data.model.Track
import com.example.data.model.TrustZone as ModelTrustZone
import com.example.network.NetworkClients
import com.example.resolver.providers.JioSaavnProvider
import com.example.resolver.providers.NormalizedTrack
import com.example.resolver.providers.SourceType
import com.example.resolver.providers.SpotifyMetadataProvider
import com.example.resolver.providers.TrackMatcher
import com.example.resolver.providers.YouTubeInnerTubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class CircuitBreakerState {
    CLOSED,    // Normal operation
    HALF_OPEN, // Testing probe
    OPEN       // Tripped, fallback active
}

class ResolverEngine {
    private val TAG = "ResolverEngine"
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()

    // On-Device Providers & Fallback Resolvers
    val youtubeInnerTubeProvider = YouTubeInnerTubeProvider()
    val jioSaavnProvider = JioSaavnProvider()
    val spotifyProvider = SpotifyMetadataProvider()
    val trackMatcher = TrackMatcher(youtubeInnerTubeProvider, jioSaavnProvider)
    val deviceInnerTubeResolver = DeviceInnerTubeResolver()
    val studioResolver = StudioMasterResolver()
    private val pipedResolver = PipedResolver()
    private val invidiousResolver = InvidiousResolver()

    private val _activeNodes = MutableStateFlow<List<ResolverNode>>(
        listOf(
            ResolverNode(
                id = "node_innertube",
                name = "On-Device InnerTube (ANDROID_VR / YTM)",
                url = "https://music.youtube.com",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 8,
                isOnline = true,
                isSelected = true,
                successRate = 0.999f
            ),
            ResolverNode(
                id = "node_jiosaavn",
                name = "On-Device JioSaavn (320kbps / 160kbps AAC)",
                url = "https://www.jiosaavn.com",
                trustZone = ModelTrustZone.ZONE_1_DEVICE,
                pingMs = 12,
                isOnline = true,
                isSelected = false,
                successRate = 0.998f
            ),
            ResolverNode(
                id = "node_studio_master",
                name = "Studio Master CDN (JioSaavn / YTM)",
                url = "https://www.jiosaavn.com",
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

    private val _isOfflineMode = MutableStateFlow(loadPersistedOfflineMode())
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _circuitBreakerState = MutableStateFlow(CircuitBreakerState.CLOSED)
    val circuitBreakerState: StateFlow<CircuitBreakerState> = _circuitBreakerState.asStateFlow()

    private val _currentPackVersion = MutableStateFlow("v2026.10-standalone-vr")
    val currentPackVersion: StateFlow<String> = _currentPackVersion.asStateFlow()

    private fun loadPersistedOfflineMode(): Boolean {
        return runCatching {
            val prefs = CascadeApplication.instance.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)
            prefs.getBoolean("airplane_offline_mode", false)
        }.getOrDefault(false)
    }

    fun setOfflineMode(enabled: Boolean) {
        _isOfflineMode.value = enabled
        runCatching {
            val prefs = CascadeApplication.instance.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("airplane_offline_mode", enabled).apply()
        }
    }

    fun selectNode(nodeId: String) {
        _activeNodes.value = _activeNodes.value.map {
            it.copy(isSelected = it.id == nodeId)
        }
    }

    fun invalidateTrack(trackId: String) {
        val clean = com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId.trim()
        com.seitharol.data.resolver.AudioQuality.entries.forEach { q ->
            streamCache.remove("${clean}_${q.name}")
            streamCache.remove("${trackId.trim()}_${q.name}")
        }
        com.seitharol.player.OuterTuneResolvingDataSource.invalidate(trackId)
    }

    fun clearCache() {
        streamCache.clear()
        com.seitharol.player.OuterTuneResolvingDataSource.clearAll()
    }

    suspend fun resolveTrack(
        track: Track,
        preferredQuality: com.seitharol.data.resolver.AudioQuality = com.seitharol.data.resolver.AudioQuality.NORMAL,
        forceRefresh: Boolean = false,
        preferNonYoutubeFallback: Boolean = false
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rawId = track.id.trim()
        val cacheKey = "${rawId}_${preferredQuality.name}"

        if (forceRefresh && rawId.isNotBlank()) {
            invalidateTrack(rawId)
        }

        // 1. Verified Local File Priority (Always works in Airplane Mode Offline & Online)
        if (!track.localPath.isNullOrBlank()) {
            if (track.localPath.startsWith("content://")) {
                return@withContext ResolvedStream(
                    videoId = rawId.ifBlank { "local_track" },
                    streamUrl = track.localPath,
                    itag = track.itag,
                    codec = track.codec,
                    bitrate = "320 kbps",
                    expiresAtMs = now + 86400000L,
                    isLocal = true,
                    resolvedBy = "Public Device Music (MediaStore)",
                    latencyMs = 1
                )
            }
            val rawFilePath = if (track.localPath.startsWith("file://")) {
                Uri.parse(track.localPath).path ?: track.localPath.removePrefix("file://")
            } else {
                track.localPath
            }
            val localFile = File(rawFilePath)
            if (localFile.exists() && localFile.length() > 0L) {
                return@withContext ResolvedStream(
                    videoId = rawId.ifBlank { "local_track" },
                    streamUrl = if (track.localPath.startsWith("http")) track.localPath else Uri.fromFile(localFile).toString(),
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

        if (_isOfflineMode.value) {
            throw IllegalStateException("Offline Mode active. Stream not cached locally.")
        }

        // 2. Lookup in In-Memory Stream Cache (quality-aware)
        if (!forceRefresh && rawId.isNotBlank()) {
            val cached = streamCache[cacheKey]
            if (cached != null && now < cached.expiresAtMs - 120000L) {
                return@withContext cached
            }
        }

        // 3. Direct verified stream if provided and non-placeholder
        if (!forceRefresh && track.audioUrl.isNotBlank() && !track.audioUrl.contains("SoundHelix")) {
            val resolved = ResolvedStream(
                videoId = rawId.ifBlank { "direct_stream" },
                streamUrl = track.audioUrl,
                itag = track.itag,
                codec = track.codec,
                bitrate = "256 kbps",
                expiresAtMs = now + (24 * 3600 * 1000L),
                isLocal = false,
                resolvedBy = "Direct Verified Stream",
                latencyMs = 6
            )
            if (rawId.isNotBlank()) streamCache[cacheKey] = resolved
            return@withContext resolved
        }

        val cleanYtId = com.seitharol.data.resolver.InnerTubeStreamExtractor.extractVideoId(rawId)
        val isYouTubeId = !cleanYtId.isNullOrBlank()
        val targetVideoId = cleanYtId ?: rawId
        val targetKbps = preferredQuality.maxBitrateKbps
        val failures = mutableListOf<String>()

        // 4. STRICT ORIGINAL YOUTUBE VIDEO_ID RESOLUTION
        if (isYouTubeId) {
            val directResult = com.seitharol.data.resolver.InnerTubeStreamExtractor
                .resolveExactYoutubeStreamById(targetVideoId, preferredQuality, forceRefresh = forceRefresh)
            if (directResult.isSuccess) {
                val url = directResult.getOrThrow()
                val resolved = ResolvedStream(
                    videoId = targetVideoId,
                    streamUrl = url,
                    itag = if (url.contains("opus") || url.contains("webm")) 251 else 140,
                    codec = if (url.contains("opus") || url.contains("webm")) "Opus" else "AAC",
                    bitrate = "${targetKbps} kbps",
                    expiresAtMs = now + (6 * 3600 * 1000L),
                    isLocal = false,
                    resolvedBy = "YouTube Music InnerTube (Original)",
                    latencyMs = 10
                )
                streamCache[cacheKey] = resolved
                return@withContext resolved
            } else {
                failures.add("InnerTubeDirect: ${directResult.exceptionOrNull()?.message}")
            }

            val req = ResolveRequest(videoId = targetVideoId, title = track.title, artist = track.artist)
            for (directNode in listOf(deviceInnerTubeResolver, pipedResolver, invidiousResolver)) {
                try {
                    val res = withTimeoutOrNull(4500L) { directNode.resolve(req) } ?: continue
                    val candidateFormats = res.formats.filter { it.url.isNotBlank() }
                    val fmt = if (preferredQuality == com.seitharol.data.resolver.AudioQuality.LOW) {
                        candidateFormats.minByOrNull { it.bitrate } ?: candidateFormats.firstOrNull()
                    } else {
                        candidateFormats.firstOrNull()
                    }
                    if (fmt != null) {
                        val resolved = ResolvedStream(
                            videoId = targetVideoId,
                            streamUrl = fmt.url,
                            itag = fmt.id.toIntOrNull() ?: 140,
                            codec = fmt.codec.uppercase(),
                            bitrate = "${fmt.bitrate / 1000} kbps",
                            expiresAtMs = res.expiresAt,
                            isLocal = false,
                            resolvedBy = res.resolverId,
                            latencyMs = 20
                        )
                        streamCache[cacheKey] = resolved
                        return@withContext resolved
                    }
                } catch (e: Exception) {
                    failures.add("${directNode.id}: ${e.message}")
                }
            }

            // Fallback for audio songs if the specific videoId is restricted or unavailable
            if (!track.isVideoTrack && track.title.isNotBlank()) {
                try {
                    val fallbackUrl = com.seitharol.data.resolver.StreamResolver.resolveExactStreamUrl(
                        track = com.seitharol.data.resolver.TrackMetadata(
                            id = targetVideoId,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationSeconds = (track.durationMs / 1000L).toInt(),
                            thumbnailUrl = track.artUrl
                        ),
                        preferredQuality = preferredQuality
                    )
                    if (!fallbackUrl.isNullOrBlank()) {
                        val resolved = ResolvedStream(
                            videoId = targetVideoId,
                            streamUrl = fallbackUrl,
                            itag = if (fallbackUrl.contains("opus") || fallbackUrl.contains("webm")) 251 else 140,
                            codec = if (fallbackUrl.contains("opus") || fallbackUrl.contains("webm")) "Opus" else "AAC",
                            bitrate = "${targetKbps} kbps",
                            expiresAtMs = now + (6 * 3600 * 1000L),
                            isLocal = false,
                            resolvedBy = "Canonical Stream Fallback",
                            latencyMs = 25
                        )
                        streamCache[cacheKey] = resolved
                        return@withContext resolved
                    }
                } catch (e: Exception) {
                    failures.add("StreamResolverFallback: ${e.message}")
                }
            }

            val failureSummary = failures.joinToString(" | ")
            Log.w(TAG, "Original YouTube videoId resolution failed for '${track.title}' ($targetVideoId): [$failureSummary]")
            throw IllegalStateException("Unable to resolve original stream for '${track.title}' ($targetVideoId)")
        }

        // 5. Non-YouTube Track IDs (Direct JioSaavn ID or Spotify 22-char ID or Title/Artist Fallback)
        val isSpotify = rawId.length == 22 && !rawId.contains("_") && !rawId.contains("-")
        val cleanSaavnId = rawId.removePrefix("saavn_").removePrefix("saavn_clip:").removePrefix("ytm_clip:")
        val isSaavn = rawId.startsWith("saavn_") ||
            rawId.startsWith("saavn_clip:") ||
            track.audioUrl.contains("saavncdn.com") ||
            (!isSpotify && (cleanSaavnId.all { it.isDigit() } || (cleanSaavnId.length in 6..16 && !cleanSaavnId.contains(" "))))

        if (isSaavn && cleanSaavnId.isNotBlank()) {
            try {
                val resolved = jioSaavnProvider.resolveStream(cleanSaavnId, preferredKbps = targetKbps)
                streamCache[cacheKey] = resolved
                return@withContext resolved
            } catch (e: Exception) {
                failures.add("JioSaavnDirect: ${e.message}")
            }
        }

        if (isSpotify) {
            try {
                var effectiveTitle = track.title.trim()
                var effectiveArtist = track.artist.trim()
                if (effectiveTitle.isBlank()) {
                    fetchSpotifyOEmbedMetadata(rawId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
                val normalized = NormalizedTrack(
                    source = SourceType.SPOTIFY,
                    sourceId = rawId,
                    title = effectiveTitle,
                    artists = listOf(effectiveArtist.ifBlank { "Various Artists" }),
                    album = track.album,
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isPlayableNatively = false
                )
                val resolved = trackMatcher.matchAndResolve(normalized, preferredKbps = targetKbps)
                streamCache[cacheKey] = resolved
                return@withContext resolved
            } catch (e: Exception) {
                failures.add("SpotifyMatcher: ${e.message}")
            }
        }

        if (track.title.isNotBlank()) {
            try {
                val fallbackUrl = com.seitharol.data.resolver.StreamResolver.resolveExactStreamUrl(
                    track = com.seitharol.data.resolver.TrackMetadata(
                        id = cleanSaavnId.ifBlank { rawId },
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationSeconds = (track.durationMs / 1000L).toInt(),
                        thumbnailUrl = track.artUrl
                    ),
                    preferredQuality = preferredQuality
                )
                if (!fallbackUrl.isNullOrBlank()) {
                    val resolved = ResolvedStream(
                        videoId = rawId.ifBlank { "resolved_track" },
                        streamUrl = fallbackUrl,
                        itag = 140,
                        codec = "AAC",
                        bitrate = "${targetKbps} kbps",
                        expiresAtMs = now + (6 * 3600 * 1000L),
                        isLocal = false,
                        resolvedBy = "Multi-Source StreamResolver",
                        latencyMs = 25
                    )
                    if (rawId.isNotBlank()) streamCache[cacheKey] = resolved
                    return@withContext resolved
                }
            } catch (e: Exception) {
                failures.add("StreamResolverFallback: ${e.message}")
            }
        }

        throw IllegalStateException("Unable to resolve audio stream for '${track.title}' ($rawId)")
    }

    private suspend fun fetchSpotifyOEmbedMetadata(spotifyId: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://open.spotify.com/oembed?url=https://open.spotify.com/track/$spotifyId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                NetworkClients.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val title = json.optString("title", "").trim()
                    if (title.isNotBlank()) return@withContext title to ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify oEmbed lookup failed for $spotifyId: ${e.message}")
            }
            null
        }
}
