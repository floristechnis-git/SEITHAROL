package com.example.resolver

import android.util.Log
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
    val studioResolver = StudioMasterResolver()
    private val pipedResolver = PipedResolver()
    private val invidiousResolver = InvidiousResolver()

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
                name = "On-Device JioSaavn (160kbps Good Quality)",
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

    suspend fun resolveTrack(
        track: Track,
        preferredQuality: com.seitharol.data.resolver.AudioQuality = com.seitharol.data.resolver.AudioQuality.NORMAL
    ): ResolvedStream {
        val now = System.currentTimeMillis()
        val rawId = track.id.trim()
        val cacheKey = "${rawId}_${preferredQuality.name}"

        // 1. Verified Local File Priority
        if (track.isDownloaded && !track.localPath.isNullOrBlank()) {
            if (track.localPath.startsWith("content://")) {
                return ResolvedStream(
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
            val localFile = File(track.localPath.removePrefix("file://"))
            if (localFile.exists() && localFile.length() > 0L) {
                return ResolvedStream(
                    videoId = rawId.ifBlank { "local_track" },
                    streamUrl = if (track.localPath.startsWith("http")) track.localPath else "file://${localFile.absolutePath}",
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
            val isContent = track.localPath?.startsWith("content://") == true
            val isLocalExisting = !track.localPath.isNullOrBlank() && (isContent || File(track.localPath.removePrefix("file://")).exists())
            if (!isLocalExisting) {
                throw IllegalStateException("Offline Mode active. Stream not cached locally.")
            }
        }

        // 2. Lookup in In-Memory Stream Cache (quality-aware)
        if (rawId.isNotBlank()) {
            val cached = streamCache[cacheKey]
            if (cached != null && now < cached.expiresAtMs - 120000L) {
                return cached
            }
        }

        // 3. Direct verified stream if provided and non-placeholder
        if (track.audioUrl.isNotBlank() && !track.audioUrl.contains("SoundHelix")) {
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
            if (rawId.isNotBlank()) streamCache[rawId] = resolved
            return resolved
        }

        var effectiveTitle = track.title.trim()
        var effectiveArtist = track.artist.trim()
        val isSpotify = rawId.length == 22 && !rawId.contains("_") && !rawId.contains("-")
        val isSaavn = (rawId.isNotBlank() && rawId.all { it.isDigit() }) ||
            track.genre.contains("Indian", ignoreCase = true) ||
            track.genre.contains("Bollywood", ignoreCase = true)
        val isYouTubeId = rawId.length == 11 && !rawId.contains(" ") && !rawId.startsWith("pl_")

        // 4. Auto-hydrate blank title/artist from ID so search fallbacks are never skipped
        if (effectiveTitle.isBlank() && effectiveArtist.isBlank() && rawId.isNotBlank()) {
            Log.w(TAG, "Blank title & artist for id='$rawId'; attempting metadata hydration.")
            when {
                isSpotify -> {
                    fetchSpotifyOEmbedMetadata(rawId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
                isSaavn -> {
                    jioSaavnProvider.getTrackInfo(rawId)?.let { info ->
                        effectiveTitle = info.title
                        effectiveArtist = info.artist
                    }
                }
                else -> {
                    youtubeInnerTubeProvider.fetchMetadataById(rawId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
            }
            // If ID itself is a readable slug (e.g. "artist-song_name"), extract tokens
            if (effectiveTitle.isBlank() && !isSpotify && !isSaavn && !isYouTubeId) {
                val cleanedSlug = rawId
                    .removePrefix("pl_")
                    .replace(Regex("[_\\-]+"), " ")
                    .trim()
                if (cleanedSlug.any { it.isLetter() }) {
                    effectiveTitle = cleanedSlug
                }
            }
        }

        val failures = mutableListOf<String>()
        var resolvedStream: ResolvedStream? = null

        val targetKbps = preferredQuality.maxBitrateKbps

        // 5. Spotify TrackMatcher
        if (isSpotify && (effectiveTitle.isNotBlank() || effectiveArtist.isNotBlank())) {
            try {
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
                resolvedStream = trackMatcher.matchAndResolve(normalized, preferredKbps = targetKbps)
            } catch (e: Exception) {
                failures.add("SpotifyMatcher: ${e.message}")
                Log.w(TAG, "Spotify TrackMatcher failed for $rawId: ${e.message}")
            }
        }

        // 6. Direct JioSaavn ID resolution
        if (resolvedStream == null && isSaavn && rawId.isNotBlank() && !isSpotify && !isYouTubeId) {
            try {
                resolvedStream = jioSaavnProvider.resolveStream(rawId, preferredKbps = targetKbps)
            } catch (e: Exception) {
                failures.add("JioSaavnDirect: ${e.message}")
                Log.w(TAG, "JioSaavn resolution failed for $rawId: ${e.message}")
            }
        }

        // 7. Primary: Direct YouTube InnerTube resolution (skip Spotify 22-char IDs and numeric Saavn IDs)
        if (resolvedStream == null && isYouTubeId) {
            try {
                val directResult = com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveWorkingStreamUrl(rawId, preferredQuality)
                if (directResult.isSuccess && directResult.getOrNull()?.isNotBlank() == true) {
                    val streamUrl = directResult.getOrNull()!!
                    resolvedStream = ResolvedStream(
                        videoId = rawId,
                        streamUrl = streamUrl,
                        itag = track.itag,
                        codec = if (streamUrl.contains("opus") || streamUrl.contains("webm")) "Opus" else "AAC",
                        bitrate = "${targetKbps} kbps",
                        expiresAtMs = now + (6 * 3600 * 1000L),
                        isLocal = false,
                        resolvedBy = "YouTube Music InnerTube Direct",
                        latencyMs = 12
                    )
                }
            } catch (e: Exception) {
                failures.add("InnerTubeDirectExtractor: ${e.message}")
            }

            if (resolvedStream == null) {
                try {
                    resolvedStream = youtubeInnerTubeProvider.resolveStream(rawId, preferredKbps = targetKbps)
                } catch (e: Exception) {
                    failures.add("InnerTubeDirect: ${e.message}")
                    Log.w(TAG, "On-device InnerTube direct resolve failed for $rawId: ${e.message}")
                }
            }
        }

        // Build search query from hydrated metadata
        val baseQuery = when {
            effectiveTitle.isNotBlank() && effectiveArtist.isNotBlank() &&
                !effectiveArtist.equals("Unknown Artist", ignoreCase = true) -> "$effectiveTitle $effectiveArtist"
            effectiveTitle.isNotBlank() -> effectiveTitle
            effectiveArtist.isNotBlank() -> effectiveArtist
            else -> ""
        }
        val searchQuery = if (baseQuery.isNotBlank() && !baseQuery.contains("official", ignoreCase = true)) {
            "$baseQuery official audio"
        } else {
            baseQuery
        }

        // 8. Fallback 1: Direct InnerTubeResolver helper (fast on-device resolution)
        if (resolvedStream == null && (isYouTubeId || effectiveTitle.isNotBlank())) {
            try {
                val directUrl = com.example.data.remote.InnerTubeResolver.resolveStreamUrl(
                    rawId,
                    effectiveTitle,
                    effectiveArtist
                )
                if (!directUrl.isNullOrBlank()) {
                    resolvedStream = ResolvedStream(
                        videoId = rawId,
                        streamUrl = directUrl,
                        itag = track.itag,
                        codec = if (directUrl.contains("opus") || directUrl.contains("webm")) "Opus" else "AAC",
                        bitrate = "${targetKbps} kbps",
                        expiresAtMs = now + (6 * 3600 * 1000L),
                        isLocal = false,
                        resolvedBy = "YouTube Music InnerTube Direct",
                        latencyMs = 15
                    )
                }
            } catch (e: Exception) {
                failures.add("InnerTubeDirectHelper: ${e.message}")
                Log.w(TAG, "Direct InnerTubeResolver helper failed: ${e.message}")
            }
        }

        // 9. Fallback 2: Federated Piped & Invidious resolvers (for exact YouTube videoId before third-party searches)
        if (resolvedStream == null && isYouTubeId) {
            val req = ResolveRequest(videoId = rawId, title = effectiveTitle, artist = effectiveArtist)
            for (federated in listOf(pipedResolver, invidiousResolver)) {
                try {
                    val res = federated.resolve(req)
                    val candidateFormats = res.formats.filter { it.url.isNotBlank() }
                    val fmt = if (preferredQuality == com.seitharol.data.resolver.AudioQuality.LOW) {
                        candidateFormats.minByOrNull { it.bitrate } ?: candidateFormats.firstOrNull()
                    } else {
                        candidateFormats.firstOrNull()
                    }
                    if (fmt != null) {
                        resolvedStream = ResolvedStream(
                            videoId = rawId,
                            streamUrl = fmt.url,
                            itag = fmt.id.toIntOrNull() ?: 140,
                            codec = fmt.codec.uppercase(),
                            bitrate = "${fmt.bitrate / 1000} kbps",
                            expiresAtMs = res.expiresAt,
                            isLocal = false,
                            resolvedBy = res.resolverId,
                            latencyMs = 25
                        )
                        break
                    }
                } catch (e: Exception) {
                    failures.add("${federated.id}: ${e.message}")
                }
            }
        }

        // 10. Fallback 3: On-Device YouTube Music text search + ranked stream extraction
        if (resolvedStream == null && !isSaavn && searchQuery.isNotBlank()) {
            try {
                val ytResults = youtubeInnerTubeProvider.searchTracks(searchQuery, limit = 10)
                val targetTrack = NormalizedTrack(
                    source = SourceType.YOUTUBE,
                    sourceId = rawId,
                    title = effectiveTitle,
                    artists = listOf(effectiveArtist.ifBlank { "Various Artists" }),
                    album = track.album,
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isPlayableNatively = true
                )
                val ranked = trackMatcher.rankCandidates(targetTrack, ytResults)
                for (candidate in ranked) {
                    if (candidate.sourceId.isBlank()) continue
                    try {
                        resolvedStream = youtubeInnerTubeProvider.resolveStream(candidate.sourceId, preferredKbps = targetKbps)
                        if (resolvedStream != null) break
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                failures.add("YouTubeSearch: ${e.message}")
                Log.w(TAG, "YouTube search fallback failed: ${e.message}")
            }
        }

        // 11. Fallback 4: On-Device JioSaavn search + 160kbps AAC DES decryption for verified match only
        if (resolvedStream == null && searchQuery.isNotBlank()) {
            try {
                val results = jioSaavnProvider.searchTracks(searchQuery, limit = 5)
                val targetTrack = NormalizedTrack(
                    source = SourceType.JIOSAAVN,
                    sourceId = rawId,
                    title = effectiveTitle,
                    artists = listOf(effectiveArtist.ifBlank { "Various Artists" }),
                    album = track.album,
                    durationMs = track.durationMs,
                    artUrl = track.artUrl,
                    isPlayableNatively = true
                )
                val rankedSaavn = trackMatcher.rankCandidates(targetTrack, results)
                // Strictly require a high-confidence match; NEVER play an unranked random song
                for (candidate in rankedSaavn) {
                    if (candidate.sourceId.isBlank()) continue
                    try {
                        resolvedStream = jioSaavnProvider.resolveStream(candidate.sourceId, preferredKbps = targetKbps)
                        if (resolvedStream != null) break
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                failures.add("JioSaavnSearch: ${e.message}")
                Log.w(TAG, "JioSaavn search fallback failed: ${e.message}")
            }
        }

        // 12. Fallback 5: StudioMasterResolver (Guaranteed BotGuard-free AAC stream)
        if (resolvedStream == null) {
            try {
                val fallbackQueryTitle = effectiveTitle.ifBlank { "Top Hits" }
                val req = ResolveRequest(
                    videoId = rawId.ifBlank { "studio_master" },
                    title = fallbackQueryTitle,
                    artist = effectiveArtist
                )
                val masterResult = studioResolver.resolve(req)
                val fmt = masterResult.formats.firstOrNull { it.url.isNotBlank() }
                if (fmt != null) {
                    resolvedStream = ResolvedStream(
                        videoId = rawId.ifBlank { masterResult.videoId },
                        streamUrl = fmt.url,
                        itag = 140,
                        codec = "AAC 256k",
                        bitrate = "256 kbps",
                        expiresAtMs = masterResult.expiresAt,
                        isLocal = false,
                        resolvedBy = "Studio Master CDN",
                        latencyMs = 14
                    )
                }
            } catch (e: Exception) {
                failures.add("StudioMaster: ${e.message}")
                Log.w(TAG, "StudioMaster fallback failed: ${e.message}")
            }
        }

        if (resolvedStream == null) {
            val failureSummary = failures.joinToString(" | ")
            Log.w(TAG, "Resolution failed for '$effectiveTitle' ($rawId): [$failureSummary]")
            val isNetworkIssue = failures.any { f ->
                f.contains("UnknownHost", ignoreCase = true) ||
                f.contains("No address associated", ignoreCase = true) ||
                f.contains("unreachable", ignoreCase = true) ||
                f.contains("failed to connect", ignoreCase = true)
            }
            if (isNetworkIssue) {
                throw java.net.UnknownHostException("No internet connection")
            }
            throw IllegalStateException("Unable to resolve audio stream for '$effectiveTitle'")
        }
        val finalStream = resolvedStream

        _circuitBreakerState.value = CircuitBreakerState.CLOSED
        if (rawId.isNotBlank()) {
            streamCache[cacheKey] = finalStream
        }
        return finalStream
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
