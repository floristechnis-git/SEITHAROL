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
import kotlinx.coroutines.Job
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

    const val MEDIA_HTTP_USER_AGENT = VISIONOS_USER_AGENT

    private const val STREAM_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
    private const val GOOGLE_VIDEO_CHUNK_BYTES = 512L * 1024L // 512 KB OuterTune standard range chunk
    private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    private val urlToVideoIdMap = ConcurrentHashMap<String, String>()
    private val knownStreamTotalLengths = ConcurrentHashMap<String, Long>()
    private val inlineMaxPositionByTrackId = ConcurrentHashMap<String, Long>()
    private val inlineTotalLengthByTrackId = ConcurrentHashMap<String, Long>()
    private val httpFactoryByUserAgent = ConcurrentHashMap<String, DefaultHttpDataSource.Factory>()
    private val activeCacheJobs = ConcurrentHashMap<String, Job>()
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentlyPlayingTrackId: String? = null

    private fun extractStreamIdentityKey(uriStr: String): String {
        val idParam = Regex("[?&]id=([^&]+)").find(uriStr)?.groupValues?.get(1)
        val itagParam = Regex("[?&]itag=(\\d+)").find(uriStr)?.groupValues?.get(1) ?: "0"
        if (!idParam.isNullOrBlank()) {
            return "${idParam}_$itagParam"
        }
        val mappedId = urlToVideoIdMap[uriStr.substringBefore("&range=").substringBefore("?range=")]
        if (!mappedId.isNullOrBlank()) {
            return "${mappedId}_$itagParam"
        }
        return uriStr.substringBefore("?").takeLast(48) + "_$itagParam"
    }

    fun recordKnownStreamLength(url: String, lengthBytes: Long) {
        if (url.isBlank() || lengthBytes <= 0L) return
        val cleanBase = stripRangeQueryParam(url)
        val key = extractStreamIdentityKey(cleanBase)
        knownStreamTotalLengths[key] = lengthBytes
        knownStreamTotalLengths[cleanBase] = lengthBytes
    }

    fun getKnownStreamLength(url: String): Long {
        if (url.isBlank()) return -1L
        val cleanBase = stripRangeQueryParam(url)
        val key = extractStreamIdentityKey(cleanBase)
        return Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: knownStreamTotalLengths[cleanBase]
            ?: knownStreamTotalLengths[key]
            ?: -1L
    }

    fun notifyActivePlaybackTrack(trackId: String?) {
        currentlyPlayingTrackId = trackId
        if (!trackId.isNullOrBlank()) {
            val audioKey = sanitizeCacheKey(trackId, false)
            val videoKey = sanitizeCacheKey(trackId, true)
            activeCacheJobs.remove(audioKey)?.cancel()
            activeCacheJobs.remove(videoKey)?.cancel()
        }
    }

    fun getCachedUrl(videoId: String, quality: AudioQuality): String? {
        val now = System.currentTimeMillis()
        val exactKey = "${videoId}_${quality.name}"
        urlCache[exactKey]?.let { cached ->
            if (now - cached.timestampMs <= STREAM_TTL_MS) {
                return cached.streamUrl
            } else {
                urlCache.remove(exactKey)
            }
        }
        for (q in AudioQuality.entries) {
            if (q == quality) continue
            val altKey = "${videoId}_${q.name}"
            val alt = urlCache[altKey] ?: continue
            if (now - alt.timestampMs <= STREAM_TTL_MS) {
                return alt.streamUrl
            } else {
                urlCache.remove(altKey)
            }
        }
        return null
    }

    fun putCachedUrl(videoId: String, quality: AudioQuality, url: String) {
        if (url.isBlank()) return
        urlCache["${videoId}_${quality.name}"] = CachedStream(
            streamUrl = url,
            timestampMs = System.currentTimeMillis(),
            quality = quality
        )
        val cleanBase = stripRangeQueryParam(url)
        urlToVideoIdMap[cleanBase] = videoId
        val clen = getKnownStreamLength(cleanBase)
        if (clen > 0L) {
            recordKnownStreamLength(cleanBase, clen)
            inlineTotalLengthByTrackId[videoId] = clen
        }
    }

    fun invalidate(videoId: String) {
        val clean = InnerTubeStreamExtractor.extractVideoId(videoId) ?: videoId
        AudioQuality.entries.forEach { q ->
            urlCache.remove("${clean}_${q.name}")?.let { cached ->
                InnerTubeStreamExtractor.markAudioStreamFailed(clean, cached.streamUrl)
            }
            urlCache.remove("${videoId}_${q.name}")?.let { cached ->
                InnerTubeStreamExtractor.markAudioStreamFailed(clean, cached.streamUrl)
            }
        }
    }

    fun clearAll() {
        urlCache.clear()
        urlToVideoIdMap.clear()
        knownStreamTotalLengths.clear()
        inlineMaxPositionByTrackId.clear()
        inlineTotalLengthByTrackId.clear()
        activeCacheJobs.values.forEach { it.cancel() }
        activeCacheJobs.clear()
        runCatching {
            val dir = File(com.example.CascadeApplication.instance.cacheDir, "seitharol_active_stream_cache")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun getStreamCacheDir(context: Context): File {
        return File(context.applicationContext.cacheDir, "seitharol_active_stream_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun sanitizeCacheKey(trackId: String, isVideo: Boolean): String {
        val clean = (InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId)
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(48)
        return if (isVideo) "${clean}_muxed_v2.mp4" else "${clean}.audio"
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

    fun isStreamFullyCached(context: Context, trackId: String, isVideo: Boolean): Boolean {
        return getCompletedCacheFile(context, trackId, isVideo) != null
    }

    fun getInlineBufferedFraction(trackId: String, isVideo: Boolean = false): Float {
        if (trackId.isBlank()) return 0f
        val clean = InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId
        val total = inlineTotalLengthByTrackId[clean] ?: inlineTotalLengthByTrackId[trackId] ?: return 0f
        if (total <= 0L) return 0f
        val maxRead = inlineMaxPositionByTrackId[clean] ?: inlineMaxPositionByTrackId[trackId] ?: 0L
        return (maxRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun getCacheStatsSummary(context: Context): Pair<Int, Long> {
        return runCatching {
            val dir = getStreamCacheDir(context)
            val validFiles = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && it.length() > 24_576L }.orEmpty()
            val diskBytes = validFiles.sumOf { it.length() }
            validFiles.size to diskBytes
        }.getOrDefault(0 to 0L)
    }

    fun warmStreamCacheAsync(context: Context, trackId: String, streamUrl: String, isVideo: Boolean) {
        if (trackId.isBlank() || !streamUrl.startsWith("http") || streamUrl.contains(".m3u8", true)) return
        if (isVideo) return
        if (trackId == currentlyPlayingTrackId) return

        val cacheKey = sanitizeCacheKey(trackId, false)
        if (activeCacheJobs[cacheKey]?.isActive == true) return

        val job = cacheScope.launch {
            try {
                val dir = getStreamCacheDir(context)
                val destFile = File(dir, cacheKey)
                if (destFile.exists() && destFile.length() > 24_576L) return@launch
                if (trackId == currentlyPlayingTrackId) return@launch

                val tmpFile = File(dir, "$cacheKey.tmp")
                val ok = InnerTubeStreamExtractor.downloadUrlToFileReliable(
                    streamUrl = streamUrl,
                    tempFile = tmpFile,
                    onProgress = { _, _ ->
                        if (trackId == currentlyPlayingTrackId) {
                            throw kotlinx.coroutines.CancellationException("Track became active in player; yielding connection")
                        }
                    }
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
        activeCacheJobs[cacheKey] = job
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
     * Matches the exact client User-Agent, Origin, and Referer required by googlevideo.com
     * for the client that issued the signed URL (OuterTune #1279 VISIONOS fix).
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
            clientParam.contains("ANDROID_MUSIC") -> mapOf(
                "User-Agent" to ANDROID_MUSIC_USER_AGENT
            )
            clientParam == "ANDROID" -> mapOf(
                "User-Agent" to ANDROID_APP_USER_AGENT
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

    private fun getHttpFactoryForUserAgent(userAgent: String): DefaultHttpDataSource.Factory {
        return httpFactoryByUserAgent.getOrPut(userAgent) {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(6_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(true)
        }
    }

    fun stripRangeQueryParam(uriStr: String): String {
        return uriStr.replace(Regex("([?&])range=[^&]+(&?)")) { m ->
            if (m.groupValues[1] == "?" && m.groupValues[2] == "&") "?"
            else if (m.groupValues[2] == "&") "&"
            else ""
        }
    }

    private fun parseTotalLengthFromContentRange(headers: Map<String, List<String>>): Long {
        val values = headers["Content-Range"] ?: headers["content-range"] ?: return -1L
        val first = values.firstOrNull() ?: return -1L
        val slashIdx = first.lastIndexOf('/')
        if (slashIdx <= 0 || slashIdx >= first.length - 1) return -1L
        return first.substring(slashIdx + 1).trim().toLongOrNull() ?: -1L
    }

    private fun parseContentLengthHeader(headers: Map<String, List<String>>): Long {
        val values = headers["Content-Length"] ?: headers["content-length"] ?: return -1L
        return values.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
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
                runBlocking(Dispatchers.IO) {
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
                runBlocking(Dispatchers.IO) {
                    InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                        cleanYtId,
                        targetQuality,
                        forceRefresh = forceRefresh
                    ).getOrNull()
                }
            } catch (e: Exception) {
                throw IOException("Unable to resolve YouTube stream for $cleanYtId", e)
            } ?: throw IOException("Unable to resolve YouTube stream for $cleanYtId")

            putCachedUrl(cleanYtId, targetQuality, resolvedUrl)

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
     * OuterTune ResolvingDataSource with VISIONOS header attachment and standard HTTP Range chunking.
     * - Attaches client-matched User-Agent, Origin, and Referer on both fresh-resolve and cached paths.
     * - Uses standard HTTP Range (`bytes=start-end`) first so VISIONOS returns HTTP 206 Partial Content
     *   across the entire track (0% to 100%) and seeks to any uncached offset immediately.
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
                private var streamIdentityKey: String = ""
                private var mappedVideoId: String? = null
                private var targetUserAgent: String = VISIONOS_USER_AGENT
                private var preferQueryRangeMode: Boolean = false

                private var nextReadPosition: Long = 0L
                private var totalStreamLength: Long = -1L
                private var totalBytesRemaining: Long = C.LENGTH_UNSET.toLong()
                private var currentChunkBytesRemaining: Long = 0L

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                    activeDelegate?.addTransferListener(transferListener)
                }

                private fun createDelegate(): DataSource {
                    val d = getHttpFactoryForUserAgent(targetUserAgent).createDataSource()
                    transferListeners.forEach { d.addTransferListener(it) }
                    return d
                }

                private fun openNextChunk(): Boolean {
                    try {
                        activeDelegate?.close()
                    } catch (_: Throwable) {}
                    activeDelegate = null

                    if (totalBytesRemaining == 0L) return false
                    if (totalStreamLength > 0L && nextReadPosition >= totalStreamLength) return false
                    val baseSpec = baseResolvedSpec ?: return false

                    val chunkLen = when {
                        totalBytesRemaining != C.LENGTH_UNSET.toLong() -> min(GOOGLE_VIDEO_CHUNK_BYTES, totalBytesRemaining)
                        totalStreamLength > 0L -> min(GOOGLE_VIDEO_CHUNK_BYTES, (totalStreamLength - nextReadPosition).coerceAtLeast(1L))
                        else -> GOOGLE_VIDEO_CHUNK_BYTES
                    }
                    if (chunkLen <= 0L) return false

                    val endByte = nextReadPosition + chunkLen - 1L
                    val cleanHeaders = HashMap<String, String>(baseSpec.httpRequestHeaders)
                    cleanHeaders.remove("Range")
                    cleanHeaders.remove("range")
                    cleanHeaders.remove("If-Range")

                    fun buildHeaderRangeSpec(): DataSpec {
                        return baseSpec.buildUpon()
                            .setUri(Uri.parse(baseUriWithoutRange))
                            .setHttpRequestHeaders(cleanHeaders)
                            .setPosition(nextReadPosition)
                            .setLength(chunkLen)
                            .build()
                    }

                    fun buildQueryRangeSpec(): DataSpec {
                        val sep = if (baseUriWithoutRange.contains("?")) "&" else "?"
                        val chunkUri = Uri.parse("${baseUriWithoutRange}${sep}range=${nextReadPosition}-${endByte}")
                        return baseSpec.buildUpon()
                            .setUri(chunkUri)
                            .setHttpRequestHeaders(cleanHeaders)
                            .setPosition(0L)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build()
                    }

                    // Always try standard HTTP Range header first (OuterTune / VISIONOS native 206 Partial Content behavior)
                    val firstIsHeader = !preferQueryRangeMode
                    val firstSpec = if (firstIsHeader) buildHeaderRangeSpec() else buildQueryRangeSpec()
                    val secondSpec = if (firstIsHeader) buildQueryRangeSpec() else buildHeaderRangeSpec()

                    var delegate = createDelegate()
                    activeDelegate = delegate
                    var openedLen = try {
                        delegate.open(firstSpec)
                    } catch (_: Exception) {
                        -100L
                    }

                    val firstContentLen = if (openedLen != -100L) parseContentLengthHeader(delegate.responseHeaders) else -1L
                    if (openedLen == -100L || openedLen == 0L || firstContentLen == 0L) {
                        try {
                            delegate.close()
                        } catch (_: Throwable) {}
                        delegate = createDelegate()
                        activeDelegate = delegate
                        openedLen = delegate.open(secondSpec)
                        val secondContentLen = parseContentLengthHeader(delegate.responseHeaders)
                        if (openedLen == 0L || secondContentLen == 0L) {
                            try {
                                delegate.close()
                            } catch (_: Throwable) {}
                            activeDelegate = null
                            return false
                        }
                        preferQueryRangeMode = firstIsHeader
                    }

                    if (totalStreamLength <= 0L) {
                        val crTotal = parseTotalLengthFromContentRange(delegate.responseHeaders)
                        if (crTotal > 0L) {
                            totalStreamLength = crTotal
                            knownStreamTotalLengths[streamIdentityKey] = crTotal
                            knownStreamTotalLengths[baseUriWithoutRange] = crTotal
                            val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                            if (!trackKey.isNullOrBlank()) {
                                inlineTotalLengthByTrackId[trackKey] = crTotal
                            }
                            if (totalBytesRemaining == C.LENGTH_UNSET.toLong()) {
                                totalBytesRemaining = (crTotal - nextReadPosition).coerceAtLeast(0L)
                            }
                        } else if (openedLen > 0L && openedLen < chunkLen) {
                            val discoveredEnd = nextReadPosition + openedLen
                            totalStreamLength = discoveredEnd
                            knownStreamTotalLengths[streamIdentityKey] = discoveredEnd
                            knownStreamTotalLengths[baseUriWithoutRange] = discoveredEnd
                            if (totalBytesRemaining == C.LENGTH_UNSET.toLong()) {
                                totalBytesRemaining = openedLen
                            }
                        }
                    }

                    val effectiveChunkLen = when {
                        openedLen != C.LENGTH_UNSET.toLong() && openedLen > 0L -> min(chunkLen, openedLen)
                        totalBytesRemaining != C.LENGTH_UNSET.toLong() -> min(chunkLen, totalBytesRemaining)
                        else -> chunkLen
                    }
                    if (effectiveChunkLen <= 0L) {
                        try {
                            delegate.close()
                        } catch (_: Throwable) {}
                        activeDelegate = null
                        return false
                    }

                    currentChunkBytesRemaining = effectiveChunkLen
                    return true
                }

                override fun open(dataSpec: DataSpec): Long {
                    val resolvedSpec = resolveDataSpec(dataSpec, qualityProvider)
                    val uriStr = resolvedSpec.uri.toString()
                    targetUserAgent = resolvedSpec.httpRequestHeaders["User-Agent"]
                        ?: resolveClientHeadersForUrl(uriStr)["User-Agent"]
                        ?: VISIONOS_USER_AGENT

                    val isGoogleVideo = uriStr.contains("googlevideo.com", ignoreCase = true) &&
                        !uriStr.contains(".m3u8", ignoreCase = true) &&
                        !uriStr.contains("manifest.googlevideo.com", ignoreCase = true)

                    val isProgressiveStream = !isGoogleVideo ||
                        uriStr.contains("itag=18&", ignoreCase = true) ||
                        uriStr.endsWith("itag=18", ignoreCase = true) ||
                        uriStr.contains("itag=22&", ignoreCase = true) ||
                        uriStr.endsWith("itag=22", ignoreCase = true) ||
                        !uriStr.contains("gir=yes", ignoreCase = true)

                    baseUriWithoutRange = stripRangeQueryParam(uriStr)
                    streamIdentityKey = extractStreamIdentityKey(baseUriWithoutRange)
                    mappedVideoId = urlToVideoIdMap[baseUriWithoutRange] ?: currentlyPlayingTrackId
                    baseResolvedSpec = resolvedSpec
                    nextReadPosition = resolvedSpec.position.coerceAtLeast(0L)

                    val clenFromUrl = getKnownStreamLength(uriStr)
                    if (clenFromUrl > 0L) {
                        totalStreamLength = clenFromUrl
                        knownStreamTotalLengths[streamIdentityKey] = clenFromUrl
                        val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                        if (!trackKey.isNullOrBlank()) {
                            inlineTotalLengthByTrackId[trackKey] = clenFromUrl
                        }
                    } else {
                        totalStreamLength = -1L
                    }

                    if (!isProgressiveStream) {
                        isChunkedGoogleVideo = true
                        preferQueryRangeMode = false
                        totalBytesRemaining = when {
                            resolvedSpec.length != C.LENGTH_UNSET.toLong() -> resolvedSpec.length
                            totalStreamLength > 0L -> (totalStreamLength - nextReadPosition).coerceAtLeast(0L)
                            else -> C.LENGTH_UNSET.toLong()
                        }
                        if (totalBytesRemaining == 0L) return 0L

                        if (openNextChunk()) {
                            if (resolvedSpec.length == C.LENGTH_UNSET.toLong() && totalStreamLength > 0L) {
                                totalBytesRemaining = (totalStreamLength - nextReadPosition).coerceAtLeast(0L)
                            }
                            return totalBytesRemaining
                        }
                        (mappedVideoId ?: currentlyPlayingTrackId)?.let { invalidate(it) }
                        throw IOException("Failed to open adaptive GoogleVideo stream at byte $nextReadPosition")
                    }

                    isChunkedGoogleVideo = false
                    val delegate = createDelegate()
                    activeDelegate = delegate
                    val openedLen = delegate.open(resolvedSpec)
                    val discoveredTotal = parseTotalLengthFromContentRange(delegate.responseHeaders)
                        .takeIf { it > 0L }
                        ?: parseContentLengthHeader(delegate.responseHeaders).takeIf { it > 0L && nextReadPosition == 0L }
                        ?: openedLen.takeIf { it > 0L && nextReadPosition == 0L }
                        ?: totalStreamLength
                    if (discoveredTotal > 0L) {
                        totalStreamLength = discoveredTotal
                        knownStreamTotalLengths[streamIdentityKey] = discoveredTotal
                        val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                        if (!trackKey.isNullOrBlank()) {
                            inlineTotalLengthByTrackId[trackKey] = discoveredTotal
                        }
                    }
                    return openedLen
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0

                    if (!isChunkedGoogleVideo) {
                        val r = try {
                            activeDelegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                        } catch (ioe: IOException) {
                            val baseSpec = baseResolvedSpec
                            if (baseSpec != null && nextReadPosition > 0L) {
                                try {
                                    activeDelegate?.close()
                                } catch (_: Throwable) {}
                                val retryDelegate = createDelegate()
                                activeDelegate = retryDelegate
                                val remLen = if (baseSpec.length != C.LENGTH_UNSET.toLong()) {
                                    (baseSpec.length - (nextReadPosition - baseSpec.position)).coerceAtLeast(1L)
                                } else {
                                    C.LENGTH_UNSET.toLong()
                                }
                                retryDelegate.open(baseSpec.buildUpon().setPosition(nextReadPosition).setLength(remLen).build())
                                retryDelegate.read(buffer, offset, length)
                            } else {
                                throw ioe
                            }
                        }
                        if (r > 0) {
                            nextReadPosition += r
                            val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                            if (!trackKey.isNullOrBlank() && totalStreamLength > 0L) {
                                inlineTotalLengthByTrackId[trackKey] = totalStreamLength
                                val prevMax = inlineMaxPositionByTrackId[trackKey] ?: 0L
                                if (nextReadPosition > prevMax) {
                                    inlineMaxPositionByTrackId[trackKey] = nextReadPosition.coerceAtMost(totalStreamLength)
                                }
                            }
                        }
                        return r
                    }

                    if (totalBytesRemaining == 0L) return C.RESULT_END_OF_INPUT

                    if (currentChunkBytesRemaining <= 0L || activeDelegate == null) {
                        if (totalStreamLength > 0L && nextReadPosition >= totalStreamLength) {
                            return C.RESULT_END_OF_INPUT
                        }
                        if (!openNextChunk()) {
                            if (nextReadPosition == 0L || (totalStreamLength > 0L && nextReadPosition < totalStreamLength - 4096L)) {
                                (mappedVideoId ?: currentlyPlayingTrackId)?.let { invalidate(it) }
                                throw IOException("Stream chunk open failed at byte $nextReadPosition / $totalStreamLength")
                            }
                            return C.RESULT_END_OF_INPUT
                        }
                    }

                    val maxToRead = min(length.toLong(), currentChunkBytesRemaining).toInt()
                    val bytesRead = try {
                        activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT
                    } catch (ioe: IOException) {
                        if (openNextChunk()) {
                            val retryMax = min(length.toLong(), currentChunkBytesRemaining).toInt()
                            activeDelegate?.read(buffer, offset, retryMax) ?: C.RESULT_END_OF_INPUT
                        } else {
                            throw ioe
                        }
                    }

                    if (bytesRead == C.RESULT_END_OF_INPUT) {
                        currentChunkBytesRemaining = 0L
                        val hasMore = (totalBytesRemaining == C.LENGTH_UNSET.toLong() || totalBytesRemaining > 0L) &&
                            (totalStreamLength <= 0L || nextReadPosition < totalStreamLength)
                        if (!hasMore) {
                            return C.RESULT_END_OF_INPUT
                        }
                        if (!openNextChunk()) {
                            if (totalStreamLength > 0L && nextReadPosition < totalStreamLength - 4096L) {
                                (mappedVideoId ?: currentlyPlayingTrackId)?.let { invalidate(it) }
                                throw IOException("Premature EOF on chunk at byte $nextReadPosition / $totalStreamLength")
                            }
                            return C.RESULT_END_OF_INPUT
                        }
                        return read(buffer, offset, length)
                    }

                    nextReadPosition += bytesRead
                    currentChunkBytesRemaining -= bytesRead
                    if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                        totalBytesRemaining = (totalBytesRemaining - bytesRead).coerceAtLeast(0L)
                    }
                    val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                    if (!trackKey.isNullOrBlank() && totalStreamLength > 0L) {
                        inlineTotalLengthByTrackId[trackKey] = totalStreamLength
                        val prevMax = inlineMaxPositionByTrackId[trackKey] ?: 0L
                        if (nextReadPosition > prevMax) {
                            inlineMaxPositionByTrackId[trackKey] = nextReadPosition.coerceAtMost(totalStreamLength)
                        }
                    }
                    return bytesRead
                }

                override fun getUri(): Uri? = activeDelegate?.uri ?: baseResolvedSpec?.uri

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
