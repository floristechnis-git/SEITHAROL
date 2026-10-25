package com.seitharol.player

import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.data.resolver.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object QueueRecommendationEngine {

    private val GENERIC_BOLLYWOOD_LABELS = listOf(
        "t-series", "zee music", "yrf", "tips official", "saregama",
        "sony music india", "desi melodies", "venus", "speed records",
        "shemaroo", "eros now", "gaana", "bollywood", "hindi"
    )

    /**
     * Generates 12–15 high-affinity PlayQueue recommendations that stay 100% relevant
     * to [currentTrack] and [queueTracks] across the entire suggestion list:
     * 1. For YouTube Video tracks (`YOUTUBE_VIDEO` / video mode): queries related YouTube Videos
     *    from YouTube Watch-Next (`compactVideoRenderer` / `videoRenderer`) and creator/topic video
     *    search, returning strictly videos (`source = "YOUTUBE_VIDEO"`) and zero audio-only songs.
     * 2. For Audio songs: combines real YouTube Music `RDAMVM{videoId}` Automix Watch-Next radio,
     *    primary artist catalog, contextual song/scene search, queue artist affinity, and JioSaavn
     *    station similarity fallback, returning strictly songs (`source = "YOUTUBE"` / `"JIOSAAVN"`)
     *    and zero videos.
     * 3. Strictly excludes all tracks in [queueTracks], [additionalExcludedIds], and
     *    [additionalExcludedSignatures] so queued or recently played items NEVER repeat.
     */
    suspend fun fetchRelevantRecommendations(
        currentTrack: TrackMetadata,
        queueTracks: List<TrackMetadata>,
        historyArtists: List<String> = emptyList(),
        limit: Int = 14,
        additionalExcludedIds: Set<String> = emptySet(),
        additionalExcludedSignatures: Set<String> = emptySet()
    ): List<TrackMetadata> = withContext(Dispatchers.IO) {
        val idLower = currentTrack.id.lowercase().trim()
        val isLocalSingle = (
            idLower.startsWith("local_") ||
                idLower.startsWith("file_") ||
                idLower.startsWith("vidfile_") ||
                idLower.startsWith("content://") ||
                idLower.startsWith("file://") ||
                currentTrack.source.equals("LOCAL", ignoreCase = true)
            ) && queueTracks.all {
            val qId = it.id.lowercase().trim()
            qId.startsWith("local_") ||
                qId.startsWith("file_") ||
                qId.startsWith("vidfile_") ||
                qId.startsWith("content://") ||
                qId.startsWith("file://") ||
                it.source.equals("LOCAL", ignoreCase = true)
        }
        if (isLocalSingle) return@withContext emptyList()

        val excludedIds = (queueTracks.map { it.id } + currentTrack.id + additionalExcludedIds).toHashSet()
        val excludedTitles = (
            queueTracks.map { normalizeTitle(it.title) } +
                normalizeTitle(currentTrack.title) +
                additionalExcludedSignatures.map { normalizeTitle(it.substringBefore("|")) }
            ).filter { it.isNotBlank() }.toHashSet()
        val excludedSigs = (
            queueTracks.map { signature(it.title, it.artist) } +
                signature(currentTrack.title, currentTrack.artist) +
                additionalExcludedSignatures
            ).toHashSet()

        val primaryArtist = currentTrack.artist
            .removeSuffix(" - Topic")
            .removeSuffix("- Topic")
            .split(",", "&", "feat.", "ft.")
            .firstOrNull()
            ?.trim()
            .orEmpty()

        val cleanTitleWords = normalizeTitle(currentTrack.title)
            .split(" ")
            .filter { it.length >= 3 && it !in setOf("official", "video", "audio", "song", "lyrics", "full", "new", "hd", "4k") }
            .take(4)
            .joinToString(" ")

        val isVideoSeed = currentTrack.album.equals("YouTube Video", ignoreCase = true) ||
            currentTrack.album.equals("YouTube Videos", ignoreCase = true) ||
            currentTrack.album.equals("MP4 Video", ignoreCase = true) ||
            currentTrack.source.equals("YOUTUBE_VIDEO", ignoreCase = true) ||
            (queueTracks.isNotEmpty() && queueTracks.all {
                it.source.equals("YOUTUBE_VIDEO", ignoreCase = true) ||
                    it.album.equals("YouTube Video", ignoreCase = true)
            })

        val results = mutableListOf<TrackMetadata>()

        // ─────────────────────────────────────────────────────────────────────────────
        // BRANCH 1: YOUTUBE VIDEO SUGGESTIONS (ONLY VIDEOS, NO AUDIO-ONLY SONGS)
        // ─────────────────────────────────────────────────────────────────────────────
        if (isVideoSeed) {
            coroutineScope {
                val queueVideoCreators = (
                    queueTracks.map {
                        it.artist.removeSuffix(" - Topic").removeSuffix("- Topic").split(",", "&").first().trim()
                    } + historyArtists
                    )
                    .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) && !it.equals("YouTube", true) && !it.equals(primaryArtist, true) }
                    .distinct()
                    .take(2)

                val q1 = if (primaryArtist.isNotBlank() && cleanTitleWords.isNotBlank()) {
                    "$primaryArtist $cleanTitleWords video"
                } else {
                    "${currentTrack.title} ${currentTrack.artist} video".trim()
                }
                val q2 = if (primaryArtist.isNotBlank()) "$primaryArtist official video" else "${currentTrack.title} video"
                val q3 = when {
                    queueVideoCreators.isNotEmpty() -> "${queueVideoCreators.first()} official video"
                    cleanTitleWords.isNotBlank() -> "$cleanTitleWords official video"
                    else -> currentTrack.title
                }

                // 1a. Real YouTube Watch-Next Related Videos for 11-char videoId
                val watchNextVideosDeferred = async {
                    val cleanVid = InnerTubeStreamExtractor.extractVideoId(currentTrack.id) ?: currentTrack.id.trim()
                    if (cleanVid.length == 11) {
                        fetchYoutubeWatchNextRelatedVideos(cleanVid, limit = 12)
                    } else {
                        emptyList()
                    }
                }
                val job1 = async { InnerTubeStreamExtractor.searchAllYoutubeVideos(q1, limit = 10) }
                val job2 = async { InnerTubeStreamExtractor.searchAllYoutubeVideos(q2, limit = 10) }
                val job3 = async {
                    if (!q3.equals(q1, ignoreCase = true) && !q3.equals(q2, ignoreCase = true)) {
                        InnerTubeStreamExtractor.searchAllYoutubeVideos(q3, limit = 8)
                    } else emptyList()
                }

                val combinedVideos = interleaveBatches(
                    listOf(watchNextVideosDeferred.await(), job1.await(), job2.await(), job3.await())
                )
                for (cand in combinedVideos) {
                    if (results.size >= limit) break
                    // Strictly exclude Topic-channel audio-only tracks so only real videos are suggested
                    if (cand.videoType == "MUSIC_VIDEO_TYPE_ATV" ||
                        cand.artist.endsWith("- Topic", ignoreCase = true) ||
                        cand.channelName.endsWith("- Topic", ignoreCase = true)
                    ) {
                        continue
                    }
                    val normTitle = normalizeTitle(cand.title)
                    val sig = signature(cand.title, cand.artist)
                    if (cand.videoId in excludedIds || normTitle in excludedTitles || sig in excludedSigs) continue
                    excludedIds.add(cand.videoId)
                    excludedTitles.add(normTitle)
                    excludedSigs.add(sig)
                    results.add(
                        TrackMetadata(
                            id = cand.videoId,
                            title = cand.title,
                            artist = cand.artist.removeSuffix(" - Topic").removeSuffix("- Topic").trim(),
                            album = "YouTube Video",
                            durationSeconds = cand.durationSeconds,
                            thumbnailUrl = cand.thumbnailUrl,
                            source = "YOUTUBE_VIDEO"
                        )
                    )
                }
            }
            return@withContext results.take(limit)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // BRANCH 2: AUDIO SONG RECOMMENDATIONS (ONLY SONGS, NO VIDEOS)
        // ─────────────────────────────────────────────────────────────────────────────
        val seedCombinedLower = "${currentTrack.title} ${currentTrack.artist} ${currentTrack.album}".lowercase()
        val isSeedHindiOrBollywood = GENERIC_BOLLYWOOD_LABELS.any { seedCombinedLower.contains(it) }
        val isSeedManipuri = seedCombinedLower.contains("manipuri") ||
            seedCombinedLower.contains("meitei") ||
            seedCombinedLower.contains("imphal") ||
            queueTracks.any {
                val ql = "${it.title} ${it.artist}".lowercase()
                ql.contains("manipuri") || ql.contains("meitei")
            }

        // Dominant artists from the active queue + listening history personalization
        val dominantQueueArtists = (
            queueTracks.map {
                it.artist.removeSuffix(" - Topic").removeSuffix("- Topic").split(",", "&", "feat.", "ft.").first().trim()
            } + historyArtists
            )
            .filter {
                it.isNotBlank() &&
                    !it.equals("Unknown Artist", true) &&
                    !it.equals("Local Audio", true) &&
                    !it.equals("Local Artist", true) &&
                    !it.equals(primaryArtist, true)
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)

        coroutineScope {
            // 1. Primary: Real YouTube Music RDAMVM Automix Queue for current seed + secondary queue seed
            val automixDeferred = async {
                val rawAutomix = if (currentTrack.id.length == 11) {
                    InnerTubeStreamExtractor.fetchAutomixQueue(currentTrack.id, limit = limit + 10)
                } else {
                    val resolvedSeed = InnerTubeStreamExtractor.searchOfficialYtmSongs(
                        "${currentTrack.title} $primaryArtist".trim(),
                        limit = 2
                    ).firstOrNull()
                    if (resolvedSeed != null) {
                        InnerTubeStreamExtractor.fetchAutomixQueue(resolvedSeed.videoId, limit = limit + 10)
                    } else emptyList()
                }
                rawAutomix.filter { cand ->
                    val candLower = "${cand.title} ${cand.artist} ${cand.channelName}".lowercase()
                    val isVideoItem = cand.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                        cand.videoType == "MUSIC_VIDEO_TYPE_UGC" ||
                        cand.albumName.equals("YouTube Video", ignoreCase = true)
                    if (isVideoItem) {
                        false
                    } else if (!isSeedHindiOrBollywood && GENERIC_BOLLYWOOD_LABELS.any { candLower.contains(it) }) {
                        false
                    } else {
                        true
                    }
                }
            }

            // 2. Same-Artist Official Songs (high relevance to currently playing track)
            val primaryArtistDeferred = async {
                if (primaryArtist.isNotBlank() && !primaryArtist.equals("Unknown Artist", true)) {
                    InnerTubeStreamExtractor.searchOfficialYtmSongs("$primaryArtist official songs", limit = 12)
                        .filterNot {
                            it.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                                it.videoType == "MUSIC_VIDEO_TYPE_UGC" ||
                                it.albumName.equals("YouTube Video", ignoreCase = true)
                        }
                } else emptyList()
            }

            // 3. Contextual Song / Scene Affinity Search
            val contextualDeferred = async {
                val contextQuery = when {
                    isSeedManipuri && primaryArtist.isNotBlank() -> "$primaryArtist Manipuri song"
                    isSeedManipuri -> "$cleanTitleWords Manipuri song"
                    primaryArtist.isNotBlank() && cleanTitleWords.isNotBlank() -> "$cleanTitleWords $primaryArtist"
                    cleanTitleWords.isNotBlank() -> "$cleanTitleWords official audio"
                    else -> "${currentTrack.title} official audio"
                }
                InnerTubeStreamExtractor.searchOfficialYtmSongs(contextQuery, limit = 12)
                    .filterNot {
                        it.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                            it.videoType == "MUSIC_VIDEO_TYPE_UGC" ||
                            it.albumName.equals("YouTube Video", ignoreCase = true)
                    }
            }

            // 4. Queue & Personalization Artist Affinity Search (based on what's in the list or history)
            val secondaryDeferred = async {
                val queries = buildList {
                    if (isSeedManipuri) add("Manipuri official hit songs")
                    dominantQueueArtists.forEach { add("$it official songs") }
                }
                val jobs = queries.map { q ->
                    async {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(q, limit = 8)
                            .filterNot {
                                it.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                                    it.videoType == "MUSIC_VIDEO_TYPE_UGC" ||
                                    it.albumName.equals("YouTube Video", ignoreCase = true)
                            }
                    }
                }
                jobs.awaitAll().flatten()
            }

            // 5. JioSaavn Station Similarity Fallback for numeric/Saavn IDs or regional tracks
            val saavnDeferred = async {
                if (currentTrack.id.length != 11 || currentTrack.source.equals("JIOSAAVN", ignoreCase = true)) {
                    fetchJioSaavnSimilarSongs("${currentTrack.title} $primaryArtist".trim(), limit = 8)
                } else {
                    emptyList()
                }
            }

            val automixTracks = automixDeferred.await()
            val artistTracks = primaryArtistDeferred.await()
            val contextualTracks = contextualDeferred.await()
            val secondaryTracks = secondaryDeferred.await()
            val saavnTracks = saavnDeferred.await()

            // Interleave Automix, Same-Artist, Contextual, Queue-Affinity, and JioSaavn tracks
            val orderedCandidates = interleaveBatches(
                listOf(automixTracks, artistTracks, contextualTracks, secondaryTracks, saavnTracks)
            )

            for (cand in orderedCandidates) {
                if (results.size >= limit) break
                // Never include video tracks when suggesting songs
                if (cand.videoType == "MUSIC_VIDEO_TYPE_OMV" ||
                    cand.videoType == "MUSIC_VIDEO_TYPE_UGC" ||
                    cand.albumName.equals("YouTube Video", ignoreCase = true) ||
                    cand.albumName.equals("YouTube Videos", ignoreCase = true)
                ) {
                    continue
                }
                val normTitle = normalizeTitle(cand.title)
                val sig = signature(cand.title, cand.artist)
                if (cand.videoId in excludedIds || normTitle in excludedTitles || sig in excludedSigs) continue
                if (StreamResolver.containsUnwantedVariant(currentTrack.title, cand.title)) continue

                excludedIds.add(cand.videoId)
                excludedTitles.add(normTitle)
                excludedSigs.add(sig)
                results.add(
                    TrackMetadata(
                        id = cand.videoId,
                        title = cand.title,
                        artist = cand.artist.removeSuffix(" - Topic").removeSuffix("- Topic").trim(),
                        album = cand.albumName?.takeUnless {
                            it.equals("YouTube Video", ignoreCase = true) ||
                                it.equals("YouTube Videos", ignoreCase = true)
                        }.orEmpty(),
                        durationSeconds = cand.durationSeconds,
                        thumbnailUrl = cand.thumbnailUrl,
                        source = "YOUTUBE"
                    )
                )
            }
        }

        results.take(limit)
    }

    /**
     * Queries YouTube's main WEB `/youtubei/v1/next` endpoint for a videoId to extract
     * authentic related videos (`compactVideoRenderer` / `videoRenderer`) for video mode.
     */
    private fun fetchYoutubeWatchNextRelatedVideos(videoId: String, limit: Int = 12): List<CandidateStream> {
        return runCatching {
            val conn = (URL("https://www.youtube.com/youtubei/v1/next?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4500
                readTimeout = 4500
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                setRequestProperty("X-YouTube-Client-Name", "1")
                setRequestProperty("X-YouTube-Client-Version", "2.20250310.01.00")
                setRequestProperty("Origin", "https://www.youtube.com")
                setRequestProperty("Referer", "https://www.youtube.com/watch?v=$videoId")
            }
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250310.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val renderers = mutableListOf<JSONObject>()
            collectWatchNextVideoRenderers(root, renderers)

            val out = mutableListOf<CandidateStream>()
            for (vr in renderers) {
                val vid = InnerTubeStreamExtractor.extractVideoId(vr.optString("videoId")) ?: continue
                if (vid == videoId) continue
                val title = vr.optJSONObject("title")?.optString("simpleText")
                    ?: vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: continue
                val channel = vr.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: vr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: "YouTube"
                if (channel.endsWith("- Topic", ignoreCase = true)) continue
                val lenText = vr.optJSONObject("lengthText")?.optString("simpleText")
                    ?: vr.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: ""
                if (lenText.isBlank()) continue
                val durParts = lenText.split(":").mapNotNull { it.trim().toIntOrNull() }
                val durSec = when (durParts.size) {
                    2 -> durParts[0] * 60 + durParts[1]
                    3 -> durParts[0] * 3600 + durParts[1] * 60 + durParts[2]
                    else -> 210
                }
                val thumbs = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                } else {
                    "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                }
                out.add(
                    CandidateStream(
                        videoId = vid,
                        title = title,
                        artist = channel,
                        channelName = channel,
                        albumName = "YouTube Video",
                        durationSeconds = durSec,
                        videoType = "MUSIC_VIDEO_TYPE_OMV",
                        thumbnailUrl = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                        isVerifiedArtist = true
                    )
                )
                if (out.size >= limit) break
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun collectWatchNextVideoRenderers(node: Any?, out: MutableList<JSONObject>) {
        if (out.size >= 30) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("compactVideoRenderer")?.let { out.add(it); return }
                node.optJSONObject("videoRenderer")?.let { out.add(it); return }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectWatchNextVideoRenderers(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectWatchNextVideoRenderers(node.opt(i), out)
                }
            }
        }
    }

    private fun fetchJioSaavnSimilarSongs(query: String, limit: Int = 8): List<CandidateStream> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=android&q=$encoded&n=$limit"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            if (conn.responseCode != 200) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val results = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<CandidateStream>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = item.optString("title")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#039;", "'")
                    .trim()
                if (title.isBlank()) continue
                val moreInfo = item.optJSONObject("more_info")
                val artistMap = moreInfo?.optJSONObject("artistMap")
                val primaryArtists = artistMap?.optJSONArray("primary_artists")
                val artist = if (primaryArtists != null && primaryArtists.length() > 0) {
                    primaryArtists.optJSONObject(0)?.optString("name").orEmpty()
                } else {
                    item.optString("subtitle").substringBefore("-").trim()
                }.ifBlank { "Artist" }
                val album = moreInfo?.optString("album").orEmpty()
                val dur = moreInfo?.optString("duration")?.toIntOrNull() ?: 210
                val image = item.optString("image").replace("150x150", "500x500")
                out.add(
                    CandidateStream(
                        videoId = id,
                        title = title,
                        artist = artist,
                        channelName = "$artist - Topic",
                        albumName = album.takeUnless { it.equals("YouTube Video", ignoreCase = true) },
                        durationSeconds = dur,
                        videoType = "MUSIC_VIDEO_TYPE_ATV",
                        thumbnailUrl = image,
                        isVerifiedArtist = true
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun interleaveBatches(batches: List<List<CandidateStream>>): List<CandidateStream> {
        val out = mutableListOf<CandidateStream>()
        val maxLen = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (batch in batches) {
                if (i < batch.size) {
                    out.add(batch[i])
                }
            }
        }
        return out
    }

    private fun normalizeTitle(raw: String): String =
        raw.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim()

    private fun signature(title: String, artist: String): String =
        "${normalizeTitle(title)}|${artist.lowercase().trim()}"
}
