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
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

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
        val clean = InnerTubeStreamExtractor.extractVideoId(videoId) ?: videoId
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${clean}_${q.name}")
        }
    }

    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.LOW }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VR_USER_AGENT)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(12_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            // Pass through local files, content:// URIs, and already-resolved CDN URLs untouched
            if (rawUri.startsWith("file://") ||
                rawUri.startsWith("content://") ||
                (rawUri.startsWith("http") && !rawUri.contains("youtube.com/watch") && !rawUri.contains("youtu.be/"))
            ) {
                return@Factory dataSpec
            }

            val targetQuality = qualityProvider()

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
                } ?: throw IllegalStateException("Unable to resolve YouTube stream for $cleanYtId")

                return@Factory dataSpec.withUri(Uri.parse(resolvedUrl))
            }

            dataSpec
        }
    }
}
