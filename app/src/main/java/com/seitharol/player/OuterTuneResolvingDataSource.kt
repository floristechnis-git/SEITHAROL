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
        val cleanBase = stripRangeQueryParam(url).substringBefore("&range=").substringBefore("?range=")
        urlToVideoIdMap[cleanBase] = videoId
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
        if (isVideo && streamUrl.contains("gir=yes", ignoreCase = true) && !streamUrl.contains("itag=18") && !streamUrl.contains("itag=22")) return
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
     * Standard Media3 DataSource.Factory powered directly by DefaultHttpDataSource:
     * 1. Uses the exact same native DefaultHttpDataSource pipeline for YouTube Music audio,
     *    YouTube Videos, and JioSaavn streams so Mp4Extractor and MatroskaExtractor receive
     *    authentic Content-Length / 206 Partial Content headers and post-redirect CDN URIs.
     * 2. Automatically refreshes the stream URL from the next InnerTube client on HTTP 403 / error.
     * 3. Automatically resumes at exact `nextReadPosition` via HTTP Range if a mobile socket drops mid-song.
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
                private var targetUserAgent: String = VR_161_USER_AGENT

                private var nextReadPosition: Long = 0L
                private var totalStreamLength: Long = -1L
                private var consecutiveEmptyChunkRetries: Int = 0

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                    activeDelegate?.addTransferListener(transferListener)
                }

                private fun createDelegate(): DataSource {
                    val d = getHttpFactoryForUserAgent(targetUserAgent).createDataSource()
                    transferListeners.forEach { d.addTransferListener(it) }
                    return d
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

                    baseUriWithoutRange = stripRangeQueryParam(uriStr)
                    streamIdentityKey = if (isGoogleVideo) extractStreamIdentityKey(baseUriWithoutRange) else ""
                    mappedVideoId = urlToVideoIdMap[baseUriWithoutRange] ?: currentlyPlayingTrackId
                    isVideoStream = uriStr.contains("mime=video", ignoreCase = true)

                    val clenFromUrl = Regex("[?&]clen=(\\d+)").find(uriStr)?.groupValues?.get(1)?.toLongOrNull()
                        ?: (if (streamIdentityKey.isNotBlank()) knownStreamTotalLengths[streamIdentityKey] else null)
                        ?: knownStreamTotalLengths[baseUriWithoutRange]
                        ?: -1L
                    if (clenFromUrl > 0L) {
                        totalStreamLength = clenFromUrl
                        if (streamIdentityKey.isNotBlank()) {
                            knownStreamTotalLengths[streamIdentityKey] = clenFromUrl
                        }
                    } else {
                        totalStreamLength = -1L
                    }

                    baseResolvedSpec = resolvedSpec
                    nextReadPosition = resolvedSpec.position.coerceAtLeast(0L)

                    val delegate = createDelegate()
                    activeDelegate = delegate
                    return try {
                        val openedLen = delegate.open(resolvedSpec)
                        val contentLenHeader = parseContentLengthHeader(delegate.responseHeaders)
                        if (isGoogleVideo && (openedLen == 0L || contentLenHeader == 0L)) {
                            throw IOException("Empty 0-byte response from GoogleVideo")
                        }
                        consecutiveEmptyChunkRetries = 0
                        val discoveredTotal = parseTotalLengthFromContentRange(delegate.responseHeaders)
                            .takeIf { it > 0L }
                            ?: contentLenHeader.takeIf { it > 0L && nextReadPosition == 0L }
                            ?: openedLen.takeIf { it > 0L && nextReadPosition == 0L }
                            ?: totalStreamLength
                        if (discoveredTotal > 0L) {
                            totalStreamLength = discoveredTotal
                            if (streamIdentityKey.isNotBlank()) {
                                knownStreamTotalLengths[streamIdentityKey] = discoveredTotal
                            }
                            val trackKey = mappedVideoId ?: currentlyPlayingTrackId
                            if (!trackKey.isNullOrBlank()) {
                                inlineTotalLengthByTrackId[trackKey] = discoveredTotal
                            }
                        }
                        openedLen
                    } catch (err: Exception) {
                        try {
                            delegate.close()
                        } catch (_: Throwable) {}
                        activeDelegate = null

                        val vid = mappedVideoId ?: currentlyPlayingTrackId
                        if (isGoogleVideo && !vid.isNullOrBlank() && InnerTubeStreamExtractor.isValidYoutubeVideoId(vid)) {
                            val targetQuality = qualityProvider()
                            val currentItag = Regex("[?&]itag=(\\d+)").find(baseUriWithoutRange)?.groupValues?.get(1)?.toIntOrNull()
                            InnerTubeStreamExtractor.markAudioStreamFailed(vid, uriStr)
                            invalidate(vid)
                            InnerTubeStreamExtractor.invalidateVideoStreamCache(vid)

                            val refreshedRaw = runCatching {
                                runBlocking(Dispatchers.IO) {
                                    if (isVideoStream) {
                                        InnerTubeStreamExtractor.resolveRawVideoStreamById(vid, forceRefresh = true).getOrNull()
                                    } else {
                                        InnerTubeStreamExtractor.resolveExactYoutubeStreamById(
                                            videoId = vid,
                                            preferredQuality = targetQuality,
                                            forceRefresh = true,
                                            requiredItag = if (nextReadPosition > 0L) currentItag else null
                                        ).getOrNull()
                                    }
                                }
                            }.getOrNull()

                            val refreshedSingle = when {
                                refreshedRaw.isNullOrBlank() -> null
                                refreshedRaw.startsWith("merged_av::") -> {
                                    val parts = refreshedRaw.removePrefix("merged_av::").split("|||")
                                    if (isVideoStream) parts.firstOrNull() else parts.getOrNull(1) ?: parts.firstOrNull()
                                }
                                else -> refreshedRaw
                            }

                            if (!refreshedSingle.isNullOrBlank()) {
                                if (!isVideoStream) {
                                    putCachedUrl(vid, targetQuality, refreshedSingle)
                                }
                                baseUriWithoutRange = stripRangeQueryParam(refreshedSingle)
                                streamIdentityKey = extractStreamIdentityKey(baseUriWithoutRange)
                                val refreshedHeaders = HashMap<String, String>(resolvedSpec.httpRequestHeaders)
                                refreshedHeaders.remove("Origin")
                                refreshedHeaders.remove("Referer")
                                refreshedHeaders.putAll(resolveClientHeadersForUrl(refreshedSingle))
                                targetUserAgent = refreshedHeaders["User-Agent"] ?: VR_161_USER_AGENT
                                val retrySpec = resolvedSpec
                                    .withUri(Uri.parse(refreshedSingle))
                                    .withRequestHeaders(refreshedHeaders)
                                baseResolvedSpec = retrySpec
                                val retryDelegate = createDelegate()
                                activeDelegate = retryDelegate
                                val len = retryDelegate.open(retrySpec)
                                consecutiveEmptyChunkRetries = 0
                                return len
                            }
                        }
                        throw err
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0

                    var r = try {
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

                    // Transparently recover if GVS or carrier NAT closed a socket before the true end of the stream
                    if (r == C.RESULT_END_OF_INPUT &&
                        totalStreamLength > 0L &&
                        nextReadPosition in 1L until (totalStreamLength - 2048L) &&
                        consecutiveEmptyChunkRetries < 3
                    ) {
                        val baseSpec = baseResolvedSpec
                        if (baseSpec != null) {
                            consecutiveEmptyChunkRetries++
                            runCatching {
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
                                r = retryDelegate.read(buffer, offset, length)
                            }
                        }
                    }

                    if (r > 0) {
                        consecutiveEmptyChunkRetries = 0
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

                override fun getUri(): Uri? = activeDelegate?.uri ?: baseResolvedSpec?.uri

                override fun getResponseHeaders(): Map<String, List<String>> =
                    activeDelegate?.responseHeaders ?: emptyMap()

                override fun close() {
                    baseResolvedSpec = null
                    consecutiveEmptyChunkRetries = 0
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
