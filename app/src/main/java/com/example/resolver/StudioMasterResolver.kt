package com.example.resolver

import android.util.Log
import com.example.resolver.providers.JioSaavnProvider
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Studio Master Audio Resolver (Zone 1 Direct Resolver).
 * Strictly verifies title & artist match before resolving JioSaavn or InnerTube streams,
 * never returning an unverified random track or remix.
 */
class StudioMasterResolver : Resolver {
    override val id: String = "resolver_studio_master"
    override val priority: Int = 1
    override val capabilities: Set<Capability> = setOf(Capability.SIGNATURE)
    override val trust: TrustZone = TrustZone.ZONE_1_DEVICE

    private val jioSaavnProvider = JioSaavnProvider()

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val cleanTitle = req.title?.replace(Regex("\\(.*?\\)|\\[.*?]"), "")?.trim().orEmpty()
        val cleanArtist = req.artist
            ?.removeSuffix(" - Topic")
            ?.removeSuffix("- Topic")
            ?.replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            ?.trim()
            .orEmpty()
        val query = if (cleanTitle.isNotBlank()) {
            "$cleanTitle $cleanArtist".trim()
        } else {
            req.videoId
        }

        // 1. Try Verified JioSaavn Full Track Match & Decryption (320kbps / 160kbps FULL SONG)
        // Pass source = "JIOSAAVN_ONLY" so StreamResolver never loops back into YouTube extraction when req.videoId is an 11-char YT ID!
        if (cleanTitle.isNotBlank()) {
            try {
                val exactSaavnUrl = StreamResolver.resolveExactStreamUrl(
                    TrackMetadata(
                        id = req.videoId,
                        title = cleanTitle,
                        artist = cleanArtist,
                        source = "JIOSAAVN_ONLY"
                    ),
                    AudioQuality.HIGH
                )
                if (!exactSaavnUrl.isNullOrBlank()) {
                    val format = FormatItem(
                        id = "140",
                        codec = "aac",
                        container = "m4a",
                        bitrate = 320000,
                        url = exactSaavnUrl
                    )
                    return@withContext ResolveResult(
                        videoId = req.videoId,
                        resolveId = "master_${req.videoId}",
                        expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                        durationMs = 210000L,
                        title = req.title ?: cleanTitle,
                        artist = req.artist ?: cleanArtist,
                        artUrl = "",
                        formats = listOf(format),
                        resolverId = id,
                        packVersion = "v2026.10-studio-master"
                    )
                }
            } catch (e: Exception) {
                Log.w("StudioMasterResolver", "JioSaavn studio master fallback failed: ${e.message}")
            }
        }

        // 2. Try InnerTube official song search & strict canonical match stream resolution (NEVER fall back to ytmTracks.firstOrNull()!)
        try {
            val ytmTracks = InnerTubeStreamExtractor.searchOfficialYtmSongs(query, limit = 6)
            val bestYt = if (cleanTitle.isNotBlank()) {
                CanonicalAudioResolver.selectBestCanonicalMatch(
                    queryTitle = cleanTitle,
                    queryArtist = cleanArtist,
                    targetDurationSeconds = null,
                    candidates = ytmTracks
                )
            } else {
                null
            }

            if (bestYt != null && bestYt.videoId.isNotBlank()) {
                val ytmStreamUrl = InnerTubeStreamExtractor.resolveWorkingStreamUrl(
                    bestYt.videoId,
                    AudioQuality.HIGH,
                    forceRefresh = true
                ).getOrNull()
                if (!ytmStreamUrl.isNullOrBlank()) {
                    val format = FormatItem(
                        id = "140",
                        codec = if (ytmStreamUrl.contains("opus")) "opus" else "aac",
                        container = if (ytmStreamUrl.contains("opus")) "webm" else "m4a",
                        bitrate = 160000,
                        url = ytmStreamUrl
                    )
                    return@withContext ResolveResult(
                        videoId = req.videoId,
                        resolveId = "ytm_${bestYt.videoId}",
                        expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
                        durationMs = if (bestYt.durationSeconds > 0) bestYt.durationSeconds * 1000L else 210000L,
                        title = bestYt.title.ifBlank { req.title ?: "Song" },
                        artist = bestYt.artist.ifBlank { req.artist ?: "Artist" },
                        artUrl = bestYt.thumbnailUrl,
                        formats = listOf(format),
                        resolverId = id,
                        packVersion = "v2026.10-studio-master"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("StudioMasterResolver", "InnerTube studio master fallback failed: ${e.message}")
        }

        throw IllegalStateException("Studio master resolution failed for '$query'")
    }
}
