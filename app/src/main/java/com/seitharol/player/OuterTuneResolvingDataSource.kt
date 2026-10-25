package com.seitharol.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    const val ANDROID_APP_USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"

    const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)"

    const val TV_EMBED_USER_AGENT =
        "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/11.0 Safari/605.1.15"

    const val WEB_REMIX_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val MEDIA_HTTP_USER_AGENT = VR_161_USER_AGENT

    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours
    private const val GOOGLE_VIDEO_CHUNK_BYTES = 512L * 1024L // 512 KB per fast unthrottled chunk
    private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L // 256 MB LRU active disk cache

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    private val httpFactoryByUserAgent = ConcurrentHashMap<String, DefaultHttpDataSource.Factory>()
    private val activeCacheJobs = ConcurrentHashMap<String, Boolean>()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        runCatching {
            val dir = File(com.example.CascadeApplication.instance.cacheDir, "seitharol_active_stream_cache")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Active On-Disk Media Stream Cache (YouTube / YouTube Music style)
    // Caches played audio & video streams to disk so replays & scrubbing are
    // 100% instantaneous with zero re-buffering even on poor networks.
    // ─────────────────────────────────────────────────────────────────────────
    private fun getStreamCacheDir(context: Context): File {
        return File(context.applicationContext.cacheDir, "seitharol_active_stream_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun sanitizeCacheKey(trackId: String, isVideo: Boolean): String {
        val clean = (InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId)
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(48)
        val ext = if (isVideo) "mp4" else "audio"
        return "${clean}.$ext"
    }

    fun getCompletedCacheFile(context: Context, trackId: String, isVideo: Boolean): File? {
        if (trackId.isBlank()) return null
        return runCatching {
            val dir = getStreamCacheDir(context)
            val file = File(dir, sanitizeCacheKey(trackId, isVideo))
            if (file.exists() && file.length() > 24_576L) {
                file.setLastModified(System.currentTimeMillis())
                file
            } else null
        }.getOrNull()
    }

    fun warmStreamCacheAsync(context: Context, trackId: String, streamUrl: String, isVideo: Boolean) {
        if (trackId.isBlank() || !streamUrl.startsWith("http") || streamUrl.contains(".m3u8", true)) return
        val cacheKey = sanitizeCacheKey(trackId, isVideo)
        if (activeCacheJobs.putIfAbsent(cacheKey, true) != null) return

        cacheScope.launch {
            try {
                val dir = getStreamCacheDir(context)
                val destFile = File(dir, cacheKey)
                if (destFile.exists() && destFile.length() > 24_576L) return@launch

                val tmpFile = File(dir, "$cacheKey.tmp")
                val ok = InnerTubeStreamExtractor.downloadUrlToFileReliable(
                    streamUrl = streamUrl,
                    tempFile = tmpFile,
                    onProgress = { _, _ -> }
                )
                if (ok && tmpFile.exists() && tmpFile.length() > 24_576L) {
                    if (destFile.exists()) destFile.delete()
                    tmpFile.renameTo(destFile)
                    pruneDiskCacheIfNeeded(dir)
                } else {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            } catch (_: Throwable) {
            } finally {
                activeCacheJobs.remove(cacheKey)
            }
        }
    }

    private fun pruneDiskCacheIfNeeded(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= MAX_DISK_CACHE_BYTES) return
            val sortedOldestFirst = files.sortedBy { it.lastModified() }
            for (f in sortedOldestFirst) {
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                    if (totalBytes <= MAX_DISK_CACHE_BYTES * 0.80) break
                }
            }
        }
    }

    /**
     * Inspects both query parameters (`?c=...&cver=...`) and HLS path segments (`/c/.../cver/...`)
     * on googlevideo.com stream URLs and returns the exact matching headers required by GoogleVideo CDN.
     */
    fun resolveClientHeadersForUrl(streamUrl: String): Map<String, String> {
        val lower = streamUrl.lowercase()
        if (!lower.contains("googlevideo.com") && !lower.contains("youtube.com")) {
            return mapOf(
                "User-Agent" to WEB_REMIX_USER_AGENT
            )
        }

        val clientParam = (
            Regex("[?&]c=([^&]+)").find(streamUrl)?.groupValues?.get(1)
                ?: Regex("/c/([^/?&]+)").find(streamUrl)?.groupValues?.get(1)
            )?.uppercase().orEmpty()

        val clientVer = (
            Regex("[?&]cver=([^&]+)").find(streamUrl)?.groupValues?.get(1)
                ?: Regex("/cver/([^/?&]+)").find(streamUrl)?.groupValues?.get(1)
            ).orEmpty()

        return when {
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
            clientParam.contains("ANDROID_MUSIC") -> mapOf(
                "User-Agent" to ANDROID_MUSIC_USER_AGENT
            )
            clientParam == "ANDROID" -> mapOf(
                "User-Agent" to ANDROID_APP_USER_AGENT
            )
            clientParam.contains("IOS") -> mapOf(
                "User-Agent" to IOS_USER_AGENT
            )
            clientParam.contains("VISIONOS") -> mapOf(
                "User-Agent" to VISIONOS_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
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
                "User-Agent" to VR_161_USER_AGENT
            )
        }
    }

    private fun getHttpFactoryForUserAgent(userAgent: String): DefaultHttpDataSource.Factory {
        return httpFactoryByUserAgent.getOrPut(userAgent) {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(10_000)
                .setAllowCrossProtocolRedirects(true)
        }
    }

    private fun resolveDataSpec(
        dataSpec: DataSpec,
        qualityProvider: () -> AudioQuality
    ): DataSpec {
        val rawUri = dataSpec.uri.toString()

        if (rawUri.startsWith("file://") || rawUri.startsWith("content://") || rawUri.startsWith("/")) {
            return dataSpec
        }

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
            return dataSpec.withRequestHeaders(mergedHeaders)
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

            return dataSpec
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

            return dataSpec
                .withUri(Uri.parse(resolvedUrl))
                .withRequestHeaders(mergedHeaders)
        }

        return dataSpec
    }

    /**
     * Creates a DataSource.Factory that:
     * 1. Dispatches each HTTP request to a DefaultHttpDataSource configured with the exact client-matched
     *    User-Agent (preventing Media3 from overwriting ANDROID_VR / IOS / TESTSUITE User-Agents).
     * 2. Uses direct native HTTP Range requests for progressive muxed MP4 video streams (`itag=18`, `itag=22`)
     *    so ExoPlayer's `Mp4Extractor` reads the `moov` atom and renders video frames with zero black screen.
     * 3. Streams adaptive audio/video URLs (`gir=yes` + `clen=`) in fast 512 KB `&range=start-end` chunks.
     */
    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.NORMAL }
    ): DataSource.Factory {
        return DataSource.Factory {
            object : DataSource {
                private var activeDelegate: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                private var isChunkedGoogleVideo = false
                private var baseResolvedSpec: DataSpec? = null
                private var baseUriWithoutRange: String = ""
                private var targetUserAgent: String = VR_161_USER_AGENT
                private var nextReadPosition: Long = 0L
                private var totalBytesRemaining: Long = 0L
                private var currentChunkBytesRemaining: Long = 0L
                private var currentChunkBytesRead: Long = 0L

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                    activeDelegate?.addTransferListener(transferListener)
                }

                private fun createDelegate(): DataSource {
                    val d = getHttpFactoryForUserAgent(targetUserAgent).createDataSource()
                    transferListeners.forEach { d.addTransferListener(it) }
                    return d
                }

                private fun openNextGoogleVideoChunk(): Boolean {
                    try {
                        activeDelegate?.close()
                    } catch (_: Throwable) {}
                    activeDelegate = null

                    if (totalBytesRemaining <= 0L) return false
                    val baseSpec = baseResolvedSpec ?: return false

                    val chunkLen = min(GOOGLE_VIDEO_CHUNK_BYTES, totalBytesRemaining)
                    val endByte = nextReadPosition + chunkLen - 1L
                    val sep = if (baseUriWithoutRange.contains("?")) "&" else "?"
                    val chunkUri = Uri.parse("${baseUriWithoutRange}${sep}range=${nextReadPosition}-${endByte}")
                    val chunkSpec = baseSpec.buildUpon()
                        .setUri(chunkUri)
                        .setPosition(0L)
                        .setLength(C.LENGTH_UNSET.toLong())
                        .build()

                    val delegate = createDelegate()
                    activeDelegate = delegate
                    delegate.open(chunkSpec)
                    currentChunkBytesRemaining = chunkLen
                    currentChunkBytesRead = 0L
                    return true
                }

                override fun open(dataSpec: DataSpec): Long {
                    val resolvedSpec = resolveDataSpec(dataSpec, qualityProvider)
                    val uriStr = resolvedSpec.uri.toString()
                    targetUserAgent = resolvedSpec.httpRequestHeaders["User-Agent"]
                        ?: resolveClientHeadersForUrl(uriStr)["User-Agent"]
                        ?: VR_161_USER_AGENT

                    val isGoogleVideo = uriStr.contains("googlevideo.com", ignoreCase = true) &&
                        !uriStr.contains(".m3u8", ignoreCase = true) &&
                        !uriStr.contains("manifest.googlevideo.com", ignoreCase = true)

                    // Progressive muxed MP4 streams (itag=18 / itag=22) MUST use native HTTP Range requests
                    // so ExoPlayer's Mp4Extractor can seek to the moov box and decode video + audio frames cleanly!
                    val isProgressiveMuxedVideo = uriStr.contains("itag=18&", ignoreCase = true) ||
                        uriStr.endsWith("itag=18", ignoreCase = true) ||
                        uriStr.contains("itag=22&", ignoreCase = true) ||
                        uriStr.endsWith("itag=22", ignoreCase = true) ||
                        !uriStr.contains("gir=yes", ignoreCase = true)

                    val clen = if (isGoogleVideo && !isProgressiveMuxedVideo) {
                        Regex("[?&]clen=(\\d+)").find(uriStr)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                    } else -1L

                    if (isGoogleVideo && !isProgressiveMuxedVideo && clen > 0L) {
                        baseResolvedSpec = resolvedSpec
                        baseUriWithoutRange = uriStr.replace(Regex("([?&])range=[^&]+(&?)")) { m ->
                            if (m.groupValues[1] == "?" && m.groupValues[2] == "&") "?"
                            else if (m.groupValues[2] == "&") "&"
                            else ""
                        }
                        nextReadPosition = resolvedSpec.position.coerceAtLeast(0L)
                        totalBytesRemaining = if (resolvedSpec.length != C.LENGTH_UNSET.toLong()) {
                            resolvedSpec.length
                        } else {
                            (clen - nextReadPosition).coerceAtLeast(0L)
                        }

                        if (totalBytesRemaining > 0L) {
                            try {
                                isChunkedGoogleVideo = true
                                openNextGoogleVideoChunk()
                                return totalBytesRemaining
                            } catch (e: Exception) {
                                isChunkedGoogleVideo = false
                                try {
                                    activeDelegate?.close()
                                } catch (_: Throwable) {}
                                activeDelegate = null
                            }
                        }
                    }

                    isChunkedGoogleVideo = false
                    val delegate = createDelegate()
                    activeDelegate = delegate
                    return delegate.open(resolvedSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0

                    if (!isChunkedGoogleVideo) {
                        return activeDelegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                    }

                    if (totalBytesRemaining <= 0L) {
                        return C.RESULT_END_OF_INPUT
                    }

                    if (currentChunkBytesRemaining <= 0L || activeDelegate == null) {
                        if (!openNextGoogleVideoChunk()) {
                            return C.RESULT_END_OF_INPUT
                        }
                    }

                    val maxToRead = min(length.toLong(), currentChunkBytesRemaining).toInt()
                    var bytesRead = activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT

                    if (bytesRead == C.RESULT_END_OF_INPUT) {
                        if (currentChunkBytesRead == 0L) {
                            return C.RESULT_END_OF_INPUT
                        }
                        if (totalBytesRemaining <= 0L || !openNextGoogleVideoChunk()) {
                            return C.RESULT_END_OF_INPUT
                        }
                        val nextMax = min(length.toLong(), currentChunkBytesRemaining).toInt()
                        bytesRead = activeDelegate?.read(buffer, offset, nextMax) ?: C.RESULT_END_OF_INPUT
                        if (bytesRead == C.RESULT_END_OF_INPUT) {
                            return C.RESULT_END_OF_INPUT
                        }
                    }

                    nextReadPosition += bytesRead
                    currentChunkBytesRemaining -= bytesRead
                    currentChunkBytesRead += bytesRead
                    totalBytesRemaining -= bytesRead
                    return bytesRead
                }

                override fun getUri(): Uri? = baseResolvedSpec?.uri ?: activeDelegate?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    activeDelegate?.responseHeaders ?: emptyMap()

                override fun close() {
                    isChunkedGoogleVideo = false
                    baseResolvedSpec = null
                    totalBytesRemaining = 0L
                    currentChunkBytesRemaining = 0L
                    try {
                        activeDelegate?.close()
                    } finally {
                        activeDelegate = null
                    }
                }
            }
        }
    }
}
