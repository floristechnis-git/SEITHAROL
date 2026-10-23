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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
object OuterTuneResolvingDataSource {

    const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    const val VR_161_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val VR_160_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val VR_QUEST2_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; Quest 2 Build/SQ3A.220605.009.A1) gzip"

    const val VR_143_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    const val ANDROID_TESTSUITE_USER_AGENT =
        "com.google.android.youtube/1.9 (Linux; U; Android 12L; US) gzip"

    const val ANDROID_MUSIC_USER_AGENT =
        "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 13; en_US) gzip"

    const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)"

    const val TV_EMBED_USER_AGENT =
        "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15"

    const val WEB_REMIX_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val MEDIA_HTTP_USER_AGENT = VISIONOS_USER_AGENT

    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()

    fun getCachedUrl(videoId: String, quality: AudioQuality): String? {
        val key = "${videoId}_${quality.name}"
        val cached = urlCache[key] ?: return null
        if (System.currentTimeMillis() - cached.timestampMs > STREAM_TTL_MS) {
            urlCache.remove(key)
            return null
        }
        return cached.streamUrl
    }

    fun putCachedUrl(videoId: String, quality: AudioQuality, url: String) {
        if (url.isBlank()) return
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
            urlCache.remove("${videoId}_${q.name}")
        }
    }

    fun clearAll() {
        urlCache.clear()
    }

    /**
     * Inspects the signed `c=` and `cver=` parameters on googlevideo.com stream URLs and returns
     * the exact matching headers required by GoogleVideo CDN.
     */
    fun resolveClientHeadersForUrl(streamUrl: String): Map<String, String> {
        val lower = streamUrl.lowercase()
        if (!lower.contains("googlevideo.com") && !lower.contains("youtube.com")) {
            return mapOf(
                "User-Agent" to WEB_REMIX_USER_AGENT
            )
        }

        val clientParam = Regex("[?&]c=([^&]+)").find(streamUrl)?.groupValues?.get(1)?.uppercase().orEmpty()
        val clientVer = Regex("[?&]cver=([^&]+)").find(streamUrl)?.groupValues?.get(1).orEmpty()

        return when {
            clientParam.contains("VISIONOS") -> mapOf(
                "User-Agent" to VISIONOS_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
            clientParam.contains("ANDROID_VR") -> {
                val vrUa = when {
                    clientVer.startsWith("1.61") -> VR_161_USER_AGENT
                    clientVer.startsWith("1.60") -> VR_160_USER_AGENT
                    clientVer.startsWith("1.56") -> VR_QUEST2_USER_AGENT
                    clientVer.startsWith("1.43") -> VR_143_USER_AGENT
                    else -> VR_161_USER_AGENT
                }
                mapOf("User-Agent" to vrUa)
            }
            clientParam.contains("ANDROID_TESTSUITE") -> mapOf(
                "User-Agent" to ANDROID_TESTSUITE_USER_AGENT
            )
            clientParam.contains("ANDROID_MUSIC") || clientParam == "ANDROID" -> mapOf(
                "User-Agent" to ANDROID_MUSIC_USER_AGENT
            )
            clientParam.contains("IOS") -> mapOf(
                "User-Agent" to IOS_USER_AGENT
            )
            clientParam.contains("TVHTML5") -> mapOf(
                "User-Agent" to TV_EMBED_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
            clientParam.contains("WEB_REMIX") -> mapOf(
                "User-Agent" to WEB_REMIX_USER_AGENT,
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/"
            )
            clientParam.contains("WEB") -> mapOf(
                "User-Agent" to WEB_REMIX_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
            else -> mapOf(
                "User-Agent" to VISIONOS_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
        }
    }

    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.NORMAL }
    ): DataSource.Factory {
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(VISIONOS_USER_AGENT)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        return ResolvingDataSource.Factory(baseHttpFactory) { dataSpec: DataSpec ->
            val rawUri = dataSpec.uri.toString()

            // Local files & content URIs pass straight through
            if (rawUri.startsWith("file://") || rawUri.startsWith("content://") || rawUri.startsWith("/")) {
                return@Factory dataSpec
            }

            // Already-resolved HTTP(S) CDN URLs (e.g. googlevideo.com or saavncdn.com):
            if (rawUri.startsWith("http") &&
                !rawUri.contains("youtube.com/watch") &&
                !rawUri.contains("music.youtube.com/watch") &&
                !rawUri.contains("youtu.be/")
            ) {
                val extraHeaders = resolveClientHeadersForUrl(rawUri)
                val mergedHeaders = HashMap<String, String>(dataSpec.httpRequestHeaders)
                mergedHeaders.remove("Origin")
                mergedHeaders.remove("Referer")
                mergedHeaders.putAll(extraHeaders)
                return@Factory dataSpec.withRequestHeaders(mergedHeaders)
            }

            val targetQuality = qualityProvider()
            val forceRefresh = rawUri.contains("refresh=1")

            if (rawUri.startsWith("saavn_clip:")) {
                val saavnId = rawUri.removePrefix("saavn_clip:").substringBefore("?").trim()
                val resolvedSaavn = try {
                    runBlocking {
                        StreamResolver.resolveExactStreamUrl(
                            TrackMetadata(id = saavnId, title = "", artist = ""),
                            targetQuality
                        )
                    }
                } catch (e: Exception) {
                    throw IOException("Unable to resolve Saavn stream for $saavnId", e)
                } ?: throw IOException("Unable to resolve Saavn stream for $saavnId")

                return@Factory dataSpec
                    .withUri(Uri.parse(resolvedSaavn))
                    .withRequestHeaders(resolveClientHeadersForUrl(resolvedSaavn))
            }

            val cleanYtId = when {
                rawUri.startsWith("ytm_clip:") -> rawUri.removePrefix("ytm_clip:").substringBefore("?").trim()
                rawUri.startsWith("ytm://") -> rawUri.removePrefix("ytm://").substringBefore("?").trim()
                rawUri.startsWith("youtube://") -> rawUri.removePrefix("youtube://").substringBefore("?").trim()
                rawUri.startsWith("resolver://track/") -> rawUri.removePrefix("resolver://track/").substringBefore("?").trim()
                else -> InnerTubeStreamExtractor.extractVideoId(rawUri)
            }

            if (!cleanYtId.isNullOrBlank()) {
                val cached = if (forceRefresh) null else getCachedUrl(cleanYtId, targetQuality)
                val resolvedUrl = cached ?: try {
                    runBlocking {
                        InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                            cleanYtId,
                            targetQuality,
                            forceRefresh = forceRefresh
                        ).getOrNull()
                    }
                } catch (e: Exception) {
                    throw IOException("Unable to resolve YouTube stream for $cleanYtId", e)
                } ?: throw IOException("Unable to resolve YouTube stream for $cleanYtId")

                val headers = resolveClientHeadersForUrl(resolvedUrl)
                val mergedHeaders = HashMap<String, String>(dataSpec.httpRequestHeaders)
                mergedHeaders.remove("Origin")
                mergedHeaders.remove("Referer")
                mergedHeaders.putAll(headers)

                return@Factory dataSpec
                    .withUri(Uri.parse(resolvedUrl))
                    .withRequestHeaders(mergedHeaders)
            }

            dataSpec
        }
    }
}
