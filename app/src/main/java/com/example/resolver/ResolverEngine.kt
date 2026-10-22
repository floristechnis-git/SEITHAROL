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

    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _circuitBreakerState = MutableStateFlow(CircuitBreakerState.CLOSED)
    val circuitBreakerState: StateFlow<CircuitBreakerState> = _circuitBreakerState.asStateFlow()

    private val _currentPackVersion = MutableStateFlow("v2026.10-standalone-vr")
    val currentPackVersion: StateFlow<String> = _currentPackVersion.asStateFlow()

    fun setOfflineMode(enabled: Boolean) {
        _isOfflineMode.value = enabled
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
        forceRefresh: Boolean = false
    ): ResolvedStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rawId = track.id.trim()
        val cacheKey = "${rawId}_${preferredQuality.name}"

        if (forceRefresh && rawId.isNotBlank()) {
            invalidateTrack(rawId)
        }

        // 1. Verified Local File Priority
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
            val localFile = File(track.localPath.removePrefix("file://"))
            if (localFile.exists() && localFile.length() > 0L) {
                return@withContext ResolvedStream(
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

        // 4. Primary Fast Path: Single non-redundant multi-client YouTube InnerTube resolution
        if (isYouTubeId) {
            val directResult = com.seitharol.data.resolver.InnerTubeStreamExtractor
                .resolveExactYoutubeStreamById(targetVideoId, preferredQuality, forceRefresh = forceRefresh)
            if (directResult.isSuccess) {
                val url = directResult.getOrThrow()
                val resolved = ResolvedStream(
                    videoId = targetVideoId,
                    streamUrl = url,
                    itag = if (preferredQuality == com.seitharol.data.resolver.AudioQuality.LOW) 250 else 251,
                    codec = if (url.contains("opus") || url.contains("webm")) "Opus" else "AAC",
                    bitrate = "${targetKbps} kbps",
                    expiresAtMs = now + (6 * 3600 * 1000L),
                    isLocal = false,
                    resolvedBy = "YouTube Music InnerTube (ANDROID_VR)",
                    latencyMs = 10
                )
                streamCache[cacheKey] = resolved
                return@withContext resolved
            } else {
                Log.w(TAG, "Direct YouTube extraction for $targetVideoId failed: ${directResult.exceptionOrNull()?.message}, trying fallback resolvers")
            }
        }

        var effectiveTitle = track.title.trim()
        var effectiveArtist = track.artist.trim()
        val isSpotify = rawId.length == 22 && !rawId.contains("_") && !rawId.contains("-")
        val cleanSaavnId = rawId.removePrefix("saavn_")
        val isSaavn = rawId.startsWith("saavn_") ||
            track.audioUrl.contains("saavncdn.com") ||
            (!isYouTubeId && !isSpotify && (cleanSaavnId.all { it.isDigit() } || (cleanSaavnId.length in 6..16 && !cleanSaavnId.contains(" ") && !cleanSaavnId.contains("-")))) ||
            track.genre.contains("Indian", ignoreCase = true) ||
            track.genre.contains("Bollywood", ignoreCase = true)

        // 5. Auto-hydrate blank title/artist from ID so search fallbacks are never skipped
        if (effectiveTitle.isBlank() && effectiveArtist.isBlank() && rawId.isNotBlank()) {
            when {
                isSpotify -> {
                    fetchSpotifyOEmbedMetadata(rawId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
                isSaavn -> {
                    jioSaavnProvider.getTrackInfo(cleanSaavnId)?.let { info ->
                        effectiveTitle = info.title
                        effectiveArtist = info.artist
                    }
                }
                isYouTubeId -> {
                    youtubeInnerTubeProvider.fetchMetadataById(targetVideoId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
                else -> {
                    youtubeInnerTubeProvider.fetchMetadataById(rawId)?.let { (t, a) ->
                        effectiveTitle = t
                        effectiveArtist = a
                    }
                }
            }
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

        // 6. Spotify TrackMatcher
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
            }
        }

        // 7. Direct JioSaavn ID resolution
        if (resolvedStream == null && isSaavn && cleanSaavnId.isNotBlank() && !isSpotify && !isYouTubeId) {
            try {
                resolvedStream = jioSaavnProvider.resolveStream(cleanSaavnId, preferredKbps = targetKbps)
            } catch (e: Exception) {
                failures.add("JioSaavnDirect: ${e.message}")
            }
        }

        // 8. Secondary YouTube Fallback: Federated Piped & Invidious nodes (fast 2.5s cap)
        if (resolvedStream == null && isYouTubeId) {
            val req = ResolveRequest(videoId = targetVideoId, title = effectiveTitle, artist = effectiveArtist)
            withTimeoutOrNull(2500L) {
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
                                videoId = targetVideoId,
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
        }

        // Build search query from hydrated metadata for canonical alternate-upload / cross-provider recovery
        val baseQuery = when {
            effectiveTitle.isNotBlank() && effectiveArtist.isNotBlank() &&
                !effectiveArtist.equals("Unknown Artist", ignoreCase = true) -> "$effectiveTitle $effectiveArtist"
            effectiveTitle.isNotBlank() -> effectiveTitle
            effectiveArtist.isNotBlank() -> effectiveArtist
            else -> ""
        }
        val searchQuery = if (baseQuery.isNotBlank() && !baseQuery.contains("official", ignoreCase = true) && !baseQuery.contains("remix", ignoreCase = true)) {
            "$baseQuery official audio"
        } else {
            baseQuery
        }

        // 9. Canonical Alternate YouTube Art-Track Fallback (also rescues age-restricted / region-blocked YouTube videoIds!)
        if (resolvedStream == null && searchQuery.isNotBlank()) {
            try {
                val ytResults = youtubeInnerTubeProvider.searchTracks(searchQuery, limit = 8)
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
                    if (candidate.sourceId.isBlank() || candidate.sourceId == targetVideoId) continue
                    val altResult = com.seitharol.data.resolver.InnerTubeStreamExtractor
                        .resolveExactYoutubeStreamById(candidate.sourceId, preferredQuality)
                    if (altResult.isSuccess) {
                        val altUrl = altResult.getOrThrow()
                        resolvedStream = ResolvedStream(
                            videoId = candidate.sourceId,
                            streamUrl = altUrl,
                            itag = 251,
                            codec = if (altUrl.contains("opus")) "Opus" else "AAC",
                            bitrate = "${targetKbps} kbps",
                            expiresAtMs = now + (6 * 3600 * 1000L),
                            isLocal = false,
                            resolvedBy = "YouTube Canonical ATV Match",
                            latencyMs = 18
                        )
                        break
                    }
                }
            } catch (e: Exception) {
                failures.add("YouTubeCanonicalSearch: ${e.message}")
            }
        }

        // 10. Verified JioSaavn Ranked Match Fallback (plays verified matching song when YT video is blocked)
        if (resolvedStream == null && searchQuery.isNotBlank()) {
            try {
                val results = jioSaavnProvider.searchTracks(baseQuery.ifBlank { searchQuery }, limit = 6)
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
                for (candidate in rankedSaavn) {
                    if (candidate.sourceId.isBlank()) continue
                    try {
                        resolvedStream = jioSaavnProvider.resolveStream(candidate.sourceId, preferredKbps = targetKbps)
                        if (resolvedStream != null) break
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                failures.add("JioSaavnSearch: ${e.message}")
            }
        }

        // 11. StudioMasterResolver (only if title is non-blank so it never searches generic "Top Hits")
        if (resolvedStream == null && effectiveTitle.isNotBlank()) {
            try {
                val req = ResolveRequest(
                    videoId = rawId.ifBlank { "studio_master" },
                    title = effectiveTitle,
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
            }
        }

        val finalStream = resolvedStream ?: run {
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

        _circuitBreakerState.value = CircuitBreakerState.CLOSED
        if (rawId.isNotBlank()) {
            streamCache[cacheKey] = finalStream
        }
        return@withContext finalStream
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
