package com.seitharol.player

import android.content.Context
import android.net.Uri
import android.util.LruCache
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
import java.io.FileOutputStream
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

    const val MEDIA_HTTP_USER_AGENT = VR_161_USER_AGENT

    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours
    private const val GOOGLE_VIDEO_CHUNK_BYTES = 512L * 1024L // 512 KB block size for memory caching
    private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L // 256 MB LRU active disk cache

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

    /**
     * Real in-memory 512 KB block cache (up to 32 MB) indexed by `"${streamKey}#${chunkStartOffset}"`.
     * Serves header/index boxes (`sidx`, `moov`, `Cues`) and already-streamed audio/video blocks
     * in 0ms on seek without opening duplicate network connections.
     */
    private val memoryChunkCache = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

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

    fun notifyActivePlaybackTrack(trackId: String?) {
        currentlyPlayingTrackId = trackId
        if (!trackId.isNullOrBlank()) {
            // Cancel any background prefetch on the actively playing track so it NEVER competes
            // with ExoPlayer's live stream or seek requests on googlevideo.com!
            val audioKey = sanitizeCacheKey(trackId, false)
            val videoKey = sanitizeCacheKey(trackId, true)
            activeCacheJobs.remove(audioKey)?.cancel()
            activeCacheJobs.remove(videoKey)?.cancel()
        }
    }

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
        val cleanBase = url.substringBefore("&range=").substringBefore("?range=")
        urlToVideoIdMap[cleanBase] = videoId
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
        urlToVideoIdMap.clear()
        knownStreamTotalLengths.clear()
        synchronized(memoryChunkCache) {
            memoryChunkCache.evictAll()
        }
        activeCacheJobs.values.forEach { it.cancel() }
        activeCacheJobs.clear()
        runCatching {
            val dir = File(com.example.CascadeApplication.instance.cacheDir, "seitharol_active_stream_cache")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Active On-Disk + In-Memory Media Stream Cache
    // Inline-caches streamed bytes and prefetches upcoming queue tracks without
    // competing with the currently playing ExoPlayer stream.
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
            val memBytes = synchronized(memoryChunkCache) { memoryChunkCache.size().toLong() }
            validFiles.size to (diskBytes + memBytes)
        }.getOrDefault(0 to 0L)
    }

    /**
     * Prefetches a stream to disk ONLY when it is NOT the currently active track in ExoPlayer.
     * For the currently playing track, bytes are cached inline by the DataSource as ExoPlayer reads them,
     * preventing concurrent connections to the same signed `googlevideo.com` URL.
     */
    fun warmStreamCacheAsync(context: Context, trackId: String, streamUrl: String, isVideo: Boolean) {
        if (trackId.isBlank() || !streamUrl.startsWith("http") || streamUrl.contains(".m3u8", true)) return
        // Only cache single-file muxed video streams when isVideo == true (never video-only gir=yes streams)
        if (isVideo && streamUrl.contains("gir=yes", ignoreCase = true) && !streamUrl.contains("itag=18") && !streamUrl.contains("itag=22")) return
        // Never open a competing parallel download on the track that ExoPlayer is actively streaming!
        if (trackId == currentlyPlayingTrackId) return

        val cacheKey = sanitizeCacheKey(trackId, isVideo)
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

    private fun tryPersistCompletedStreamFromMemory(
        streamKey: String,
        videoId: String?,
        totalLength: Long,
        chunkSize: Long,
        isVideo: Boolean
    ) {
        if (videoId.isNullOrBlank() || totalLength <= 24_576L || totalLength > 40L * 1024L * 1024L) return
        val numChunks = ((totalLength + chunkSize - 1L) / chunkSize).toInt()
        if (numChunks <= 0 || numChunks > 80) return

        val chunks = ArrayList<ByteArray>(numChunks)
        synchronized(memoryChunkCache) {
            for (i in 0 until numChunks) {
                val startOffset = i.toLong() * chunkSize
                val block = memoryChunkCache.get("$streamKey#$startOffset") ?: return
                chunks.add(block)
            }
        }

        cacheScope.launch {
            runCatching {
                val ctx = com.example.CascadeApplication.instance
                val dir = getStreamCacheDir(ctx)
                val cacheKey = sanitizeCacheKey(videoId, isVideo)
                val destFile = File(dir, cacheKey)
                if (destFile.exists() && destFile.length() >= totalLength) return@runCatching
                val tmpFile = File(dir, "$cacheKey.tmp")
                FileOutputStream(tmpFile).use { out ->
                    var written = 0L
                    for (block in chunks) {
                        val toWrite = min(block.size.toLong(), totalLength - written).toInt()
                        if (toWrite > 0) {
                            out.write(block, 0, toWrite)
                            written += toWrite
                        }
                    }
                    out.flush()
                }
                if (tmpFile.exists() && tmpFile.length() == totalLength) {
                    if (destFile.exists()) destFile.delete()
                    tmpFile.renameTo(destFile)
                    pruneDiskCacheIfNeeded(dir)
                } else {
                    tmpFile.delete()
                }
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
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
        }
    }

    private fun stripRangeQueryParam(uriStr: String): String {
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
     * Creates a resilient DataSource.Factory that:
     * 1. Dispatches each HTTP request to a DefaultHttpDataSource configured with the exact client-matched
     *    User-Agent (preventing Media3 from overwriting ANDROID_VR / IOS / VISIONOS User-Agents).
     * 2. Streams all audio and video URLs continuously via native HTTP `Range: bytes=position-` (`206 Partial Content`)
     *    without artificially disconnecting every 512 KB, eliminating the mid-song (60-90s) connection drop!
     * 3. Automatically reconnects and resumes at the exact byte offset (`nextReadPosition`) if a mobile carrier NAT,
     *    background doze, or CDN socket drop interrupts `read()`, refreshing the stream URL if the signed token expired.
     */
    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.NORMAL }
    ): DataSource.Factory {
        return DataSource.Factory {
            object : DataSource {
                private var activeDelegate: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                private var baseResolvedSpec: DataSpec? = null
                private var baseUriWithoutRange: String = ""
                private var streamIdentityKey: String = ""
                private var mappedVideoId: String? = null
                private var isVideoStream: Boolean = false
                private var isGoogleVideo: Boolean = false
                private var targetUserAgent: String = VR_161_USER_AGENT

                private var nextReadPosition: Long = 0L
                private var totalStreamLength: Long = -1L
                private var totalBytesRemaining: Long = C.LENGTH_UNSET.toLong()

                // Inline 512 KB memory block cache recorder for sequential reads from 0
                private var activeWriteBlockStart: Long = -1L
                private var activeWriteBuffer: ByteArray? = null
                private var activeWriteOffset: Int = 0

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                    activeDelegate?.addTransferListener(transferListener)
                }

                private fun createDelegate(): DataSource {
                    val d = getHttpFactoryForUserAgent(targetUserAgent).createDataSource()
                    transferListeners.forEach { d.addTransferListener(it) }
                    return d
                }

                private fun flushActiveWriteBlock(forcePartialAtEof: Boolean = false) {
                    val buf = activeWriteBuffer
                    val blockStart = activeWriteBlockStart
                    if (buf != null && blockStart >= 0L && activeWriteOffset > 0 && streamIdentityKey.isNotBlank()) {
                        if (activeWriteOffset == buf.size || forcePartialAtEof) {
                            val slice = if (activeWriteOffset == buf.size) buf else buf.copyOf(activeWriteOffset)
                            synchronized(memoryChunkCache) {
                                memoryChunkCache.put("$streamIdentityKey#$blockStart", slice)
                            }
                            if (totalStreamLength > 0L &&
                                blockStart + activeWriteOffset >= totalStreamLength &&
                                !isVideoStream
                            ) {
                                tryPersistCompletedStreamFromMemory(
                                    streamKey = streamIdentityKey,
                                    videoId = mappedVideoId ?: currentlyPlayingTrackId,
                                    totalLength = totalStreamLength,
                                    chunkSize = GOOGLE_VIDEO_CHUNK_BYTES,
                                    isVideo = false
                                )
                            }
                        }
                    }
                    activeWriteBuffer = null
                    activeWriteOffset = 0
                    activeWriteBlockStart = -1L
                }

                private fun recordSequentialBytesToMemoryCache(buffer: ByteArray, offset: Int, bytesRead: Int, readStartPos: Long) {
                    if (bytesRead <= 0 || streamIdentityKey.isBlank() || isVideoStream) return
                    var srcOffset = offset
                    var remaining = bytesRead
                    var curPos = readStartPos

                    while (remaining > 0) {
                        val alignedBlockStart = (curPos / GOOGLE_VIDEO_CHUNK_BYTES) * GOOGLE_VIDEO_CHUNK_BYTES
                        val offsetInBlock = (curPos - alignedBlockStart).toInt()

                        if (activeWriteBlockStart != alignedBlockStart || activeWriteBuffer == null || activeWriteOffset != offsetInBlock) {
                            flushActiveWriteBlock(forcePartialAtEof = false)
                            if (offsetInBlock == 0) {
                                activeWriteBlockStart = alignedBlockStart
                                activeWriteBuffer = ByteArray(GOOGLE_VIDEO_CHUNK_BYTES.toInt())
                                activeWriteOffset = 0
                            } else {
                                // Not aligned to start of block; skip caching until next block boundary
                                val skipToNextBoundary = (GOOGLE_VIDEO_CHUNK_BYTES.toInt() - offsetInBlock).coerceAtMost(remaining)
                                srcOffset += skipToNextBoundary
                                remaining -= skipToNextBoundary
                                curPos += skipToNextBoundary
                                continue
                            }
                        }

                        val writeBuf = activeWriteBuffer ?: break
                        val spaceInBlock = writeBuf.size - activeWriteOffset
                        val toCopy = min(remaining, spaceInBlock)
                        System.arraycopy(buffer, srcOffset, writeBuf, activeWriteOffset, toCopy)
                        activeWriteOffset += toCopy
                        srcOffset += toCopy
                        remaining -= toCopy
                        curPos += toCopy

                        if (activeWriteOffset == writeBuf.size) {
                            flushActiveWriteBlock(forcePartialAtEof = false)
                        } else if (totalStreamLength > 0L && curPos >= totalStreamLength) {
                            flushActiveWriteBlock(forcePartialAtEof = true)
                        }
                    }
                }

                private fun openDirectAtOffset(spec: DataSpec, startOffset: Long, requestedLength: Long): Long {
                    try {
                        activeDelegate?.close()
                    } catch (_: Throwable) {}
                    activeDelegate = null

                    val cleanBase = stripRangeQueryParam(spec.uri.toString())
                    val cleanHeaders = HashMap<String, String>(spec.httpRequestHeaders)
                    cleanHeaders.remove("Range")
                    cleanHeaders.remove("range")
                    cleanHeaders.remove("If-Range")
                    cleanHeaders.putAll(resolveClientHeadersForUrl(cleanBase))
                    targetUserAgent = cleanHeaders["User-Agent"] ?: VR_161_USER_AGENT

                    val openSpec = spec.buildUpon()
                        .setUri(Uri.parse(cleanBase))
                        .setHttpRequestHeaders(cleanHeaders)
                        .setPosition(startOffset)
                        .setLength(requestedLength)
                        .build()

                    val delegate = createDelegate()
                    activeDelegate = delegate
                    val openedLen = delegate.open(openSpec)

                    val discoveredTotal = parseTotalLengthFromContentRange(delegate.responseHeaders)
                        .takeIf { it > 0L }
                        ?: parseContentLengthHeader(delegate.responseHeaders).takeIf { it > 0L && startOffset == 0L }
                        ?: openedLen.takeIf { it > 0L && startOffset == 0L }
                        ?: totalStreamLength

                    if (discoveredTotal > 0L) {
                        totalStreamLength = discoveredTotal
                        if (streamIdentityKey.isNotBlank()) {
                            knownStreamTotalLengths[streamIdentityKey] = discoveredTotal
                        }
                        knownStreamTotalLengths[cleanBase] = discoveredTotal
                        val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                        if (!trackKey.isNullOrBlank()) {
                            inlineTotalLengthByTrackId[trackKey] = discoveredTotal
                        }
                    }
                    return openedLen
                }

                private fun refreshStreamUrlForCurrentTrack(): String? {
                    val vid = mappedVideoId ?: currentlyPlayingTrackId
                    if (vid.isNullOrBlank() || !InnerTubeStreamExtractor.isValidYoutubeVideoId(vid)) return null
                    invalidate(vid)
                    InnerTubeStreamExtractor.invalidateVideoStreamCache(vid)
                    val refreshedRaw = runCatching {
                        runBlocking(Dispatchers.IO) {
                            if (isVideoStream) {
                                InnerTubeStreamExtractor.resolveRawVideoStreamById(vid, forceRefresh = true).getOrNull()
                            } else {
                                InnerTubeStreamExtractor.resolveExactYoutubeStreamById(vid, qualityProvider(), forceRefresh = true).getOrNull()
                            }
                        }
                    }.getOrNull()
                    return when {
                        refreshedRaw.isNullOrBlank() -> null
                        refreshedRaw.startsWith("merged_av::") -> {
                            val parts = refreshedRaw.removePrefix("merged_av::").split("|||")
                            if (isVideoStream) parts.firstOrNull() else parts.getOrNull(1) ?: parts.firstOrNull()
                        }
                        else -> refreshedRaw
                    }
                }

                override fun open(dataSpec: DataSpec): Long {
                    flushActiveWriteBlock(forcePartialAtEof = false)
                    val resolvedSpec = resolveDataSpec(dataSpec, qualityProvider)
                    val uriStr = resolvedSpec.uri.toString()
                    targetUserAgent = resolvedSpec.httpRequestHeaders["User-Agent"]
                        ?: resolveClientHeadersForUrl(uriStr)["User-Agent"]
                        ?: VR_161_USER_AGENT

                    isGoogleVideo = uriStr.contains("googlevideo.com", ignoreCase = true) &&
                        !uriStr.contains(".m3u8", ignoreCase = true) &&
                        !uriStr.contains("manifest.googlevideo.com", ignoreCase = true)

                    baseUriWithoutRange = stripRangeQueryParam(uriStr)
                    streamIdentityKey = if (isGoogleVideo) extractStreamIdentityKey(baseUriWithoutRange) else ""
                    mappedVideoId = urlToVideoIdMap[baseUriWithoutRange]
                        ?: InnerTubeStreamExtractor.extractVideoId(dataSpec.uri.toString())
                        ?: currentlyPlayingTrackId
                    isVideoStream = uriStr.contains("mime=video", ignoreCase = true) ||
                        uriStr.contains("itag=18", ignoreCase = true) ||
                        uriStr.contains("itag=22", ignoreCase = true)

                    val clenFromUrl = Regex("[?&]clen=(\\d+)").find(uriStr)?.groupValues?.get(1)?.toLongOrNull()
                        ?: knownStreamTotalLengths[streamIdentityKey]
                        ?: knownStreamTotalLengths[baseUriWithoutRange]
                        ?: -1L
                    totalStreamLength = if (clenFromUrl > 0L) clenFromUrl else -1L
                    if (totalStreamLength > 0L && streamIdentityKey.isNotBlank()) {
                        knownStreamTotalLengths[streamIdentityKey] = totalStreamLength
                    }

                    baseResolvedSpec = resolvedSpec
                    nextReadPosition = resolvedSpec.position.coerceAtLeast(0L)
                    totalBytesRemaining = resolvedSpec.length

                    try {
                        val opened = openDirectAtOffset(resolvedSpec, nextReadPosition, resolvedSpec.length)
                        totalBytesRemaining = when {
                            resolvedSpec.length != C.LENGTH_UNSET.toLong() -> resolvedSpec.length
                            opened != C.LENGTH_UNSET.toLong() && opened > 0L -> opened
                            totalStreamLength > 0L -> (totalStreamLength - nextReadPosition).coerceAtLeast(0L)
                            else -> C.LENGTH_UNSET.toLong()
                        }
                        return opened
                    } catch (err: Exception) {
                        val refreshedUrl = refreshStreamUrlForCurrentTrack()
                        if (!refreshedUrl.isNullOrBlank()) {
                            baseUriWithoutRange = stripRangeQueryParam(refreshedUrl)
                            streamIdentityKey = extractStreamIdentityKey(baseUriWithoutRange)
                            val refreshedSpec = resolvedSpec.withUri(Uri.parse(baseUriWithoutRange))
                                .withRequestHeaders(resolveClientHeadersForUrl(baseUriWithoutRange))
                            baseResolvedSpec = refreshedSpec
                            val opened = openDirectAtOffset(refreshedSpec, nextReadPosition, resolvedSpec.length)
                            totalBytesRemaining = when {
                                resolvedSpec.length != C.LENGTH_UNSET.toLong() -> resolvedSpec.length
                                opened != C.LENGTH_UNSET.toLong() && opened > 0L -> opened
                                totalStreamLength > 0L -> (totalStreamLength - nextReadPosition).coerceAtLeast(0L)
                                else -> C.LENGTH_UNSET.toLong()
                            }
                            return opened
                        }
                        throw err
                    }
                }

                private fun tryReconnectMidStream(): Boolean {
                    val baseSpec = baseResolvedSpec ?: return false
                    val remainingToRequest = if (totalBytesRemaining != C.LENGTH_UNSET.toLong() && totalBytesRemaining > 0L) {
                        totalBytesRemaining
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }

                    // Attempt 1 & 2: Reopen current URL at exact nextReadPosition via HTTP Range header
                    for (attempt in 1..2) {
                        try {
                            openDirectAtOffset(baseSpec, nextReadPosition, remainingToRequest)
                            return true
                        } catch (_: Throwable) {
                            try {
                                Thread.sleep(120L * attempt)
                            } catch (_: Throwable) {}
                        }
                    }

                    // Attempt 3: Refresh stream URL if signed googlevideo URL expired or throttled
                    val refreshedUrl = refreshStreamUrlForCurrentTrack()
                    if (!refreshedUrl.isNullOrBlank()) {
                        return try {
                            baseUriWithoutRange = stripRangeQueryParam(refreshedUrl)
                            streamIdentityKey = extractStreamIdentityKey(baseUriWithoutRange)
                            val refreshedSpec = baseSpec.withUri(Uri.parse(baseUriWithoutRange))
                                .withRequestHeaders(resolveClientHeadersForUrl(baseUriWithoutRange))
                            baseResolvedSpec = refreshedSpec
                            openDirectAtOffset(refreshedSpec, nextReadPosition, remainingToRequest)
                            true
                        } catch (_: Throwable) {
                            false
                        }
                    }
                    return false
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (totalBytesRemaining == 0L) return C.RESULT_END_OF_INPUT

                    val maxToRead = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                        min(length.toLong(), totalBytesRemaining).toInt()
                    } else {
                        length
                    }

                    var bytesRead: Int
                    try {
                        bytesRead = activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT
                    } catch (ioe: IOException) {
                        // Transparent mid-song socket drop recovery: reconnect at exact nextReadPosition!
                        if (tryReconnectMidStream()) {
                            bytesRead = activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT
                        } else {
                            throw ioe
                        }
                    }

                    // Detect premature EOF (when CDN closes connection mid-stream before full Content-Length)
                    val isPrematureEof = bytesRead == C.RESULT_END_OF_INPUT && (
                        (totalBytesRemaining != C.LENGTH_UNSET.toLong() && totalBytesRemaining > 2048L) ||
                            (totalStreamLength > 0L && nextReadPosition < totalStreamLength - 2048L)
                        )
                    if (isPrematureEof && tryReconnectMidStream()) {
                        bytesRead = activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT
                    }

                    if (bytesRead == C.RESULT_END_OF_INPUT) {
                        flushActiveWriteBlock(forcePartialAtEof = true)
                        return C.RESULT_END_OF_INPUT
                    }

                    if (bytesRead > 0) {
                        val readStartPos = nextReadPosition
                        nextReadPosition += bytesRead
                        if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                            totalBytesRemaining = (totalBytesRemaining - bytesRead).coerceAtLeast(0L)
                        }
                        recordSequentialBytesToMemoryCache(buffer, offset, bytesRead, readStartPos)

                        val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                        if (!trackKey.isNullOrBlank() && totalStreamLength > 0L) {
                            inlineTotalLengthByTrackId[trackKey] = totalStreamLength
                            val prevMax = inlineMaxPositionByTrackId[trackKey] ?: 0L
                            if (nextReadPosition > prevMax) {
                                inlineMaxPositionByTrackId[trackKey] = nextReadPosition.coerceAtMost(totalStreamLength)
                            }
                        }
                    }
                    return bytesRead
                }

                override fun getUri(): Uri? = activeDelegate?.uri ?: baseResolvedSpec?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    activeDelegate?.responseHeaders ?: emptyMap()

                override fun close() {
                    flushActiveWriteBlock(forcePartialAtEof = false)
                    baseResolvedSpec = null
                    totalBytesRemaining = 0L
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
