package com.seitharol.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
object OuterTuneResolvingDataSource {

    const val VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
    private const val CHUNK_LENGTH_BYTES = 10L * 1024L * 1024L // 10 MB anti-throttle chunking

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()

    fun getCachedUrl(videoId: String, quality: AudioQuality): String? {
        val cached = urlCache["${videoId}_${quality.name}"] ?: return null
        if (System.currentTimeMillis() - cached.timestampMs > STREAM_TTL_MS) {
            urlCache.remove("${videoId}_${quality.name}")
            return null
        }
        return cached.streamUrl
    }

    fun putCachedUrl(videoId: String, quality: AudioQuality, url: String) {
        urlCache["${videoId}_${quality.name}"] = CachedStream(
            streamUrl = url,
            timestampMs = System.currentTimeMillis(),
            quality = quality
        )
    }

    fun invalidate(videoId: String) {
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${videoId}_${q.name}")
        }
    }

    /**
     * Creates an OuterTune-grade Media3 DataSource.Factory that:
     * 1. Sends the exact ANDROID_VR User-Agent required by googlevideo.com streams.
     * 2. Lazily resolves YouTube 11-char videoIds, "ytm_clip:{videoId}", or "saavn_clip:{id}" URIs.
     * 3. Never re-searches a known YouTube videoId by title/artist (0% chance of playing a remix).
     */
    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.LOW }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VR_USER_AGENT)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            // Pass through local files, content:// URIs, and already-resolved googlevideo/saavn HTTP URLs
            if (rawUri.startsWith("file://") ||
                rawUri.startsWith("content://") ||
                (rawUri.startsWith("http") && !rawUri.contains("youtube.com/watch"))
            ) {
                return@Factory applyChunkRangeIfNeeded(dataSpec, rawUri)
            }

            val targetQuality = qualityProvider()
            val cleanYtId = when {
                rawUri.startsWith("ytm_clip:") -> rawUri.removePrefix("ytm_clip:").trim()
                rawUri.startsWith("youtube://") -> rawUri.removePrefix("youtube://").trim()
                else -> InnerTubeStreamExtractor.extractVideoId(rawUri)
            }

            if (!cleanYtId.isNullOrBlank()) {
                val cached = getCachedUrl(cleanYtId, targetQuality)
                val resolvedUrl = cached ?: runBlocking {
                    InnerTubeStreamExtractor.resolveExactYoutubeStreamById(cleanYtId, targetQuality)
                        .getOrNull()
                }?.also { freshUrl ->
                    putCachedUrl(cleanYtId, targetQuality, freshUrl)
                } ?: throw IllegalStateException("Unable to resolve YouTube stream for $cleanYtId")

                return@Factory applyChunkRangeIfNeeded(
                    dataSpec.withUri(Uri.parse(resolvedUrl)),
                    resolvedUrl
                )
            }

            if (rawUri.startsWith("saavn_clip:")) {
                val saavnId = rawUri.removePrefix("saavn_clip:").trim()
                val resolvedSaavn = runBlocking {
                    StreamResolver.resolveExactStreamUrl(
                        TrackMetadata(id = saavnId, title = "", artist = ""),
                        targetQuality
                    )
                } ?: throw IllegalStateException("Unable to resolve Saavn stream for $saavnId")
                return@Factory dataSpec.withUri(Uri.parse(resolvedSaavn))
            }

            dataSpec
        }
    }

    private fun applyChunkRangeIfNeeded(dataSpec: DataSpec, url: String): DataSpec {
        if (!url.contains("googlevideo.com")) return dataSpec
        // If URL already has range query param or bounded DataSpec length, leave as-is
        if (url.contains("&range=") || dataSpec.length != -1L) return dataSpec
        val start = dataSpec.position
        val end = start + CHUNK_LENGTH_BYTES - 1L
        val rangedUrl = "$url&range=$start-$end"
        return dataSpec.withUri(Uri.parse(rangedUrl))
    }
}
