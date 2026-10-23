package com.example.resolver

import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Studio Master Audio Resolver (Zone 1 Direct Resolver).
 * Strictly resolves the original requested videoId without performing background search fallbacks.
 */
class StudioMasterResolver : Resolver {
    override val id: String = "resolver_studio_master"
    override val priority: Int = 1
    override val capabilities: Set<Capability> = setOf(Capability.SIGNATURE)
    override val trust: TrustZone = TrustZone.ZONE_1_DEVICE

    override suspend fun resolve(req: ResolveRequest): ResolveResult = withContext(Dispatchers.IO) {
        val ytId = InnerTubeStreamExtractor.extractVideoId(req.videoId)
            ?: throw IllegalStateException("StudioMasterResolver requires a valid YouTube videoId: ${req.videoId}")

        val ytmStreamUrl = InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
            ytId,
            AudioQuality.HIGH,
            forceRefresh = true
        ).getOrNull() ?: throw IllegalStateException("Studio master resolution failed for videoId=$ytId")

        val format = FormatItem(
            id = "140",
            codec = if (ytmStreamUrl.contains("opus")) "opus" else "aac",
            container = if (ytmStreamUrl.contains("opus")) "webm" else "m4a",
            bitrate = 160000,
            url = ytmStreamUrl
        )
        ResolveResult(
            videoId = ytId,
            resolveId = "ytm_$ytId",
            expiresAt = System.currentTimeMillis() + (6 * 3600 * 1000L),
            durationMs = 210000L,
            title = req.title ?: "Song",
            artist = req.artist ?: "Artist",
            artUrl = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg",
            formats = listOf(format),
            resolverId = id,
            packVersion = "v2026.10-studio-master"
        )
    }
}
