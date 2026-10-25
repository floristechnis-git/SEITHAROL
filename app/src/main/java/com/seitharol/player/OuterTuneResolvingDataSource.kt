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
    private const val GOOGLE_VIDEO_CHUNK_BYTES = 512L * 1024L // 512 KB per fast unthrottled chunk
    private const val PROGRESSIVE_VIDEO_CHUNK_BYTES = 1024L * 1024L // 1 MB per bounded Range chunk
    private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L // 256 MB LRU active disk cache

    private data class CachedStream(
        val streamUrl: String,
        val timestampMs: Long,
        val quality: AudioQuality
    )

    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    private val urlToVideoIdMap = ConcurrentHashMap<String, String>()
    private val knownStreamTotalLengths = ConcurrentHashMap<String, Long>()
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

    fun isStreamFullyCached(context: Context, trackId: String, isVideo: Boolean): Boolean {
        return getCompletedCacheFile(context, trackId, isVideo) != null
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
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(10_000)
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
     * 2. Uses bounded HTTP `Range: bytes=start-end` (for progressive muxed MP4 `itag=18`/`22`) or
     *    `&range=start-end` (for adaptive `gir=yes` audio/video streams) so YouTube CDN never throttles
     *    open-ended range requests and seeking to any timestamp completes in <150ms.
     * 3. Caches 512 KB chunks in `memoryChunkCache` inline as ExoPlayer reads them, serving header/index
     *    seeks (`sidx`, `moov`, `Cues`) and backward/forward scrubs within buffered regions in 0ms.
     */
    fun createFactory(
        qualityProvider: () -> AudioQuality = { AudioQuality.NORMAL }
    ): DataSource.Factory {
        return DataSource.Factory {
            object : DataSource {
                private var activeDelegate: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                private var isChunkedGoogleVideo = false
                private var useHttpRangeHeaderForChunks = false
                private var chunkStepBytes: Long = GOOGLE_VIDEO_CHUNK_BYTES
                private var baseResolvedSpec: DataSpec? = null
                private var baseUriWithoutRange: String = ""
                private var streamIdentityKey: String = ""
                private var mappedVideoId: String? = null
                private var isVideoStream: Boolean = false
                private var targetUserAgent: String = VR_161_USER_AGENT

                private var nextReadPosition: Long = 0L
                private var totalStreamLength: Long = -1L
                private var totalBytesRemaining: Long = C.LENGTH_UNSET.toLong()
                private var currentChunkStartOffset: Long = 0L
                private var currentChunkExpectedLen: Long = 0L
                private var currentChunkBytesRemaining: Long = 0L
                private var currentChunkBytesRead: Long = 0L

                // Inline memory-chunk state
                private var activeCachedBlock: ByteArray? = null
                private var activeCachedBlockReadOffset: Int = 0
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

                private fun commitActiveWriteBufferIfComplete() {
                    val buf = activeWriteBuffer
                    val expected = currentChunkExpectedLen.toInt()
                    if (buf != null && expected > 0 && activeWriteOffset == expected && streamIdentityKey.isNotBlank()) {
                        val copy = if (buf.size == expected) buf else buf.copyOf(expected)
                        synchronized(memoryChunkCache) {
                            memoryChunkCache.put("$streamIdentityKey#$currentChunkStartOffset", copy)
                        }
                        if (totalStreamLength > 0L && currentChunkStartOffset + expected >= totalStreamLength) {
                            tryPersistCompletedStreamFromMemory(
                                streamKey = streamIdentityKey,
                                videoId = mappedVideoId ?: currentlyPlayingTrackId,
                                totalLength = totalStreamLength,
                                chunkSize = chunkStepBytes,
                                isVideo = isVideoStream
                            )
                        }
                    }
                    activeWriteBuffer = null
                    activeWriteOffset = 0
                }

                private fun openNextGoogleVideoChunk(): Boolean {
                    commitActiveWriteBufferIfComplete()
                    activeCachedBlock = null
                    activeCachedBlockReadOffset = 0
                    try {
                        activeDelegate?.close()
                    } catch (_: Throwable) {}
                    activeDelegate = null

                    if (totalBytesRemaining == 0L) return false
                    if (totalStreamLength > 0L && nextReadPosition >= totalStreamLength) return false
                    val baseSpec = baseResolvedSpec ?: return false

                    val alignedChunkIndex = nextReadPosition / chunkStepBytes
                    val alignedStart = alignedChunkIndex * chunkStepBytes
                    val offsetInsideAligned = (nextReadPosition - alignedStart).toInt()

                    // 1. Check if this chunk is already in our in-memory block cache!
                    if (streamIdentityKey.isNotBlank()) {
                        val cachedBlock = synchronized(memoryChunkCache) {
                            memoryChunkCache.get("$streamIdentityKey#$alignedStart")
                        }
                        if (cachedBlock != null && offsetInsideAligned < cachedBlock.size) {
                            activeCachedBlock = cachedBlock
                            activeCachedBlockReadOffset = offsetInsideAligned
                            val availableFromBlock = (cachedBlock.size - offsetInsideAligned).toLong()
                            val chunkLen = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                                min(availableFromBlock, totalBytesRemaining)
                            } else {
                                availableFromBlock
                            }
                            currentChunkStartOffset = alignedStart
                            currentChunkExpectedLen = cachedBlock.size.toLong()
                            currentChunkBytesRemaining = chunkLen
                            currentChunkBytesRead = 0L
                            return chunkLen > 0L
                        }
                    }

                    // 2. Fetch chunk from network
                    val maxDesired = if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                        min(chunkStepBytes, totalBytesRemaining)
                    } else if (totalStreamLength > 0L) {
                        min(chunkStepBytes, (totalStreamLength - nextReadPosition).coerceAtLeast(1L))
                    } else {
                        chunkStepBytes
                    }
                    if (maxDesired <= 0L) return false

                    val endByte = nextReadPosition + maxDesired - 1L
                    val cleanHeaders = HashMap<String, String>(baseSpec.httpRequestHeaders)
                    cleanHeaders.remove("Range")
                    cleanHeaders.remove("range")
                    cleanHeaders.remove("If-Range")

                    val chunkUri: Uri
                    if (useHttpRangeHeaderForChunks) {
                        chunkUri = Uri.parse(baseUriWithoutRange)
                        cleanHeaders["Range"] = "bytes=${nextReadPosition}-${endByte}"
                    } else {
                        val sep = if (baseUriWithoutRange.contains("?")) "&" else "?"
                        chunkUri = Uri.parse("${baseUriWithoutRange}${sep}range=${nextReadPosition}-${endByte}")
                    }

                    val chunkSpec = baseSpec.buildUpon()
                        .setUri(chunkUri)
                        .setHttpRequestHeaders(cleanHeaders)
                        .setPosition(0L)
                        .setLength(C.LENGTH_UNSET.toLong())
                        .build()

                    val delegate = createDelegate()
                    activeDelegate = delegate
                    val openedLen = delegate.open(chunkSpec)

                    // Discover totalStreamLength from Content-Range if not known from clen=
                    if (totalStreamLength <= 0L) {
                        val crTotal = parseTotalLengthFromContentRange(delegate.responseHeaders)
                        if (crTotal > 0L) {
                            totalStreamLength = crTotal
                            knownStreamTotalLengths[streamIdentityKey] = crTotal
                            if (totalBytesRemaining == C.LENGTH_UNSET.toLong()) {
                                totalBytesRemaining = (crTotal - nextReadPosition).coerceAtLeast(0L)
                            }
                        } else if (openedLen > 0L && openedLen < maxDesired && nextReadPosition == 0L) {
                            totalStreamLength = openedLen
                            knownStreamTotalLengths[streamIdentityKey] = openedLen
                            if (totalBytesRemaining == C.LENGTH_UNSET.toLong()) {
                                totalBytesRemaining = openedLen
                            }
                        }
                    }

                    val effectiveChunkLen = when {
                        openedLen != C.LENGTH_UNSET.toLong() && openedLen > 0L -> min(maxDesired, openedLen)
                        totalBytesRemaining != C.LENGTH_UNSET.toLong() -> min(maxDesired, totalBytesRemaining)
                        else -> maxDesired
                    }

                    currentChunkStartOffset = nextReadPosition
                    currentChunkExpectedLen = effectiveChunkLen
                    currentChunkBytesRemaining = effectiveChunkLen
                    currentChunkBytesRead = 0L

                    // If this chunk starts on an aligned boundary, record into activeWriteBuffer for memory cache
                    if (nextReadPosition == alignedStart && effectiveChunkLen in 1L..chunkStepBytes) {
                        activeWriteBuffer = ByteArray(effectiveChunkLen.toInt())
                        activeWriteOffset = 0
                    } else {
                        activeWriteBuffer = null
                        activeWriteOffset = 0
                    }
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

                    if (isGoogleVideo) {
                        baseUriWithoutRange = stripRangeQueryParam(uriStr)
                        streamIdentityKey = extractStreamIdentityKey(baseUriWithoutRange)
                        mappedVideoId = urlToVideoIdMap[baseUriWithoutRange] ?: currentlyPlayingTrackId
                        isVideoStream = uriStr.contains("mime=video", ignoreCase = true) ||
                            uriStr.contains("itag=18", ignoreCase = true) ||
                            uriStr.contains("itag=22", ignoreCase = true)

                        val isProgressiveMuxedVideo = uriStr.contains("itag=18&", ignoreCase = true) ||
                            uriStr.endsWith("itag=18", ignoreCase = true) ||
                            uriStr.contains("itag=22&", ignoreCase = true) ||
                            uriStr.endsWith("itag=22", ignoreCase = true) ||
                            !uriStr.contains("gir=yes", ignoreCase = true)

                        useHttpRangeHeaderForChunks = isProgressiveMuxedVideo
                        chunkStepBytes = if (isProgressiveMuxedVideo) PROGRESSIVE_VIDEO_CHUNK_BYTES else GOOGLE_VIDEO_CHUNK_BYTES

                        val clenFromUrl = Regex("[?&]clen=(\\d+)").find(uriStr)?.groupValues?.get(1)?.toLongOrNull()
                            ?: knownStreamTotalLengths[streamIdentityKey]
                            ?: -1L
                        if (clenFromUrl > 0L) {
                            totalStreamLength = clenFromUrl
                            knownStreamTotalLengths[streamIdentityKey] = clenFromUrl
                        } else {
                            totalStreamLength = -1L
                        }

                        baseResolvedSpec = resolvedSpec
                        nextReadPosition = resolvedSpec.position.coerceAtLeast(0L)
                        totalBytesRemaining = when {
                            resolvedSpec.length != C.LENGTH_UNSET.toLong() -> resolvedSpec.length
                            totalStreamLength > 0L -> (totalStreamLength - nextReadPosition).coerceAtLeast(0L)
                            else -> C.LENGTH_UNSET.toLong()
                        }

                        if (totalBytesRemaining != 0L) {
                            try {
                                isChunkedGoogleVideo = true
                                if (openNextGoogleVideoChunk()) {
                                    return totalBytesRemaining
                                }
                            } catch (firstChunkErr: Exception) {
                                // If a seek or chunk open failed on an expired/throttled googlevideo URL,
                                // try refreshing the stream URL once if we know the videoId!
                                val vid = mappedVideoId
                                if (!vid.isNullOrBlank() && InnerTubeStreamExtractor.isValidYoutubeVideoId(vid)) {
                                    runCatching {
                                        val refreshedUrl = runBlocking(Dispatchers.IO) {
                                            if (isVideoStream) {
                                                InnerTubeStreamExtractor.resolveRawVideoStreamById(vid, forceRefresh = true).getOrNull()
                                            } else {
                                                InnerTubeStreamExtractor.resolveExactYoutubeStreamById(vid, qualityProvider(), forceRefresh = true).getOrNull()
                                            }
                                        }
                                        if (!refreshedUrl.isNullOrBlank() && !refreshedUrl.startsWith("merged_av::")) {
                                            baseUriWithoutRange = stripRangeQueryParam(refreshedUrl)
                                            val refreshedHeaders = HashMap<String, String>(resolvedSpec.httpRequestHeaders)
                                            refreshedHeaders.remove("Origin")
                                            refreshedHeaders.remove("Referer")
                                            refreshedHeaders.putAll(resolveClientHeadersForUrl(refreshedUrl))
                                            targetUserAgent = refreshedHeaders["User-Agent"] ?: VR_161_USER_AGENT
                                            baseResolvedSpec = resolvedSpec.withUri(Uri.parse(refreshedUrl)).withRequestHeaders(refreshedHeaders)
                                            val newClen = Regex("[?&]clen=(\\d+)").find(refreshedUrl)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                                            if (newClen > 0L) {
                                                totalStreamLength = newClen
                                                totalBytesRemaining = if (resolvedSpec.length != C.LENGTH_UNSET.toLong()) {
                                                    resolvedSpec.length
                                                } else {
                                                    (newClen - nextReadPosition).coerceAtLeast(0L)
                                                }
                                            }
                                            if (openNextGoogleVideoChunk()) {
                                                return totalBytesRemaining
                                            }
                                        }
                                    }
                                }

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

                    if (totalBytesRemaining == 0L) {
                        return C.RESULT_END_OF_INPUT
                    }

                    if (currentChunkBytesRemaining <= 0L || (activeDelegate == null && activeCachedBlock == null)) {
                        if (!openNextGoogleVideoChunk()) {
                            return C.RESULT_END_OF_INPUT
                        }
                    }

                    // 1. Serve from in-memory cached block if active
                    val cachedBlock = activeCachedBlock
                    if (cachedBlock != null) {
                        val maxToCopy = min(length.toLong(), currentChunkBytesRemaining).toInt()
                            .coerceAtMost(cachedBlock.size - activeCachedBlockReadOffset)
                        if (maxToCopy <= 0) {
                            activeCachedBlock = null
                            if (!openNextGoogleVideoChunk()) return C.RESULT_END_OF_INPUT
                        } else {
                            System.arraycopy(cachedBlock, activeCachedBlockReadOffset, buffer, offset, maxToCopy)
                            activeCachedBlockReadOffset += maxToCopy
                            nextReadPosition += maxToCopy
                            currentChunkBytesRemaining -= maxToCopy
                            currentChunkBytesRead += maxToCopy
                            if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                                totalBytesRemaining = (totalBytesRemaining - maxToCopy).coerceAtLeast(0L)
                            }
                            if (currentChunkBytesRemaining <= 0L) {
                                activeCachedBlock = null
                            }
                            return maxToCopy
                        }
                    }

                    // 2. Read from active HTTP chunk delegate
                    val maxToRead = min(length.toLong(), currentChunkBytesRemaining).toInt()
                    var bytesRead = activeDelegate?.read(buffer, offset, maxToRead) ?: C.RESULT_END_OF_INPUT

                    if (bytesRead == C.RESULT_END_OF_INPUT) {
                        commitActiveWriteBufferIfComplete()
                        if (currentChunkBytesRead == 0L) {
                            return C.RESULT_END_OF_INPUT
                        }
                        if (totalBytesRemaining == 0L || !openNextGoogleVideoChunk()) {
                            return C.RESULT_END_OF_INPUT
                        }
                        // Recursive read on the newly opened chunk (which may be a memory hit or network delegate)
                        return read(buffer, offset, length)
                    }

                    // Copy read bytes into activeWriteBuffer for inline caching
                    val writeBuf = activeWriteBuffer
                    if (writeBuf != null && activeWriteOffset + bytesRead <= writeBuf.size) {
                        System.arraycopy(buffer, offset, writeBuf, activeWriteOffset, bytesRead)
                        activeWriteOffset += bytesRead
                        if (activeWriteOffset == writeBuf.size) {
                            commitActiveWriteBufferIfComplete()
                        }
                    } else if (writeBuf != null) {
                        activeWriteBuffer = null
                        activeWriteOffset = 0
                    }

                    nextReadPosition += bytesRead
                    currentChunkBytesRemaining -= bytesRead
                    currentChunkBytesRead += bytesRead
                    if (totalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                        totalBytesRemaining = (totalBytesRemaining - bytesRead).coerceAtLeast(0L)
                    }
                    return bytesRead
                }

                override fun getUri(): Uri? = baseResolvedSpec?.uri ?: activeDelegate?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    activeDelegate?.responseHeaders ?: emptyMap()

                override fun close() {
                    commitActiveWriteBufferIfComplete()
                    activeCachedBlock = null
                    activeCachedBlockReadOffset = 0
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
