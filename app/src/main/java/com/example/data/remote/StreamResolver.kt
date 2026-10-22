package com.example.data.remote

import com.example.data.model.Track
import com.example.resolver.ResolvedStream

object StreamResolver {
    suspend fun resolveStreamUrl(videoId: String?, title: String, artist: String): String? {
        return InnerTubeResolver.resolveStreamUrl(videoId, title, artist)
    }

    suspend fun resolveTrack(track: Track): ResolvedStream? {
        val streamUrl = InnerTubeResolver.resolveStreamUrl(track.id, track.title, track.artist) ?: return null
        val now = System.currentTimeMillis()
        return ResolvedStream(
            videoId = track.id,
            streamUrl = streamUrl,
            itag = track.itag,
            codec = if (streamUrl.contains("opus") || streamUrl.contains("webm")) "Opus" else "AAC",
            bitrate = "160 kbps",
            expiresAtMs = now + (6 * 3600 * 1000L),
            isLocal = false,
            resolvedBy = "YouTube Music InnerTube Direct (Android VR)",
            latencyMs = 20
        )
    }
}
