package com.seitharol.data.resolver

import android.util.Log
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Single Raw Movie & TV Series Streamer (VidLink Direct Raw) ported from Mami/SEITHAROL+tv.
 * - Keyless Movie & Series search via Stremio Cinemeta + IMDb Suggestion CDN.
 * - Full Netflix/Prime preview metadata (backdrop, overview, rating, genres, cast, seasons & episodes).
 * - Extracts ONLY VidLink Direct Raw MP4 / Master HLS stream for direct ExoPlayer playback.
 */
object VidLinkRawMovieStreamer {
    private const val TAG = "VidLinkRawMovie"
    const val MOVIE_ID_PREFIX = "movie_vidlink_"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    data class RawMovieStream(
        val streamUrl: String,
        val fallbackUrl: String? = null,
        val referer: String?,
        val origin: String?,
        val isHls: Boolean,
        val qualityLabel: String,
        val captionVttUrl: String? = null
    )

    data class EpisodeItem(
        val season: Int,
        val number: Int,
        val title: String,
        val overview: String,
        val thumbnail: String,
        val runtime: String,
        val released: String
    )

    data class SeasonItem(
        val number: Int,
        val name: String,
        val episodes: List<EpisodeItem>
    )

    data class TitlePreview(
        val imdbId: String,
        val tmdbId: String,
        val isSeries: Boolean,
        val title: String,
        val year: String,
        val rating: String,
        val runtime: String,
        val poster: String,
        val backdrop: String,
        val description: String,
        val genres: List<String>,
        val cast: List<String>,
        val seasons: List<SeasonItem>
    )

    data class ParsedTrackMeta(
        val imdbId: String,
        val tmdbId: String?,
        val isSeries: Boolean,
        val season: Int,
        val episode: Int
    )

    private val imdbToTmdbCache = ConcurrentHashMap<String, String>(
        mapOf(
            "tt1196946" to "5920",     // The Mentalist
            "tt4574334" to "66732",    // Stranger Things
            "tt13443470" to "119051",  // Wednesday
            "tt5753856" to "70523",    // Dark
            "tt10919420" to "93405",   // Squid Game
            "tt0903747" to "1396",     // Breaking Bad
            "tt0944947" to "1399",     // Game of Thrones
            "tt6468322" to "71446",    // Money Heist
            "tt0111161" to "278",      // The Shawshank Redemption
            "tt1375666" to "27205",    // Inception
            "tt0468569" to "155",      // The Dark Knight
            "tt0816692" to "157336",   // Interstellar
            "tt4633694" to "324857",   // Spider-Man: Into the Spider-Verse
            "tt9362722" to "569094",   // Spider-Man: Across the Spider-Verse
            "tt10872600" to "634649",  // Spider-Man: No Way Home
            "tt15398776" to "872585",  // Oppenheimer
            "tt15239678" to "693134",  // Dune: Part Two
            "tt1160419" to "438631",   // Dune
            "tt1877830" to "414906",   // The Batman
            "tt6751668" to "496243",   // Parasite
            "tt8178634" to "579974",   // RRR
            "tt12735488" to "804150",  // Kalki 2898 AD
            "tt15354916" to "781732",  // Jawan
            "tt1649418" to "725201"    // The Gray Man
        )
    )

    private val streamCache = ConcurrentHashMap<String, Pair<Long, RawMovieStream>>()
    private val previewCache = ConcurrentHashMap<String, TitlePreview>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    fun isMovieTrack(track: Track?): Boolean {
        if (track == null) return false
        return track.id.startsWith(MOVIE_ID_PREFIX) ||
            track.genre.equals("Raw Movie", ignoreCase = true) ||
            track.genre.equals("Raw Series", ignoreCase = true)
    }

    fun isSeriesTrack(track: Track?): Boolean {
        if (track == null) return false
        return track.genre.equals("Raw Series", ignoreCase = true) ||
            track.id.startsWith("${MOVIE_ID_PREFIX}s_") ||
            track.id.startsWith("${MOVIE_ID_PREFIX}e_")
    }

    fun buildMoviePlayableTrack(preview: TitlePreview): Track {
        return Track(
            id = encodeTrackId(preview.imdbId, preview.tmdbId.ifBlank { null }, isSeries = false),
            title = preview.title,
            artist = buildString {
                append("Movie")
                if (preview.year.isNotBlank()) append(" • ${preview.year}")
                if (preview.rating.isNotBlank()) append(" • IMDb ${preview.rating}")
            },
            album = "Raw Movie",
            durationMs = parseRuntimeMs(preview.runtime, defaultMins = 120L),
            artUrl = preview.backdrop.ifBlank { preview.poster },
            genre = "Raw Movie",
            itag = 18,
            codec = "MP4",
            isOfficial = true
        )
    }

    fun buildEpisodePlayableTrack(preview: TitlePreview, episode: EpisodeItem): Track {
        return Track(
            id = encodeTrackId(
                imdbId = preview.imdbId,
                tmdbId = preview.tmdbId.ifBlank { null },
                isSeries = true,
                season = episode.season,
                episode = episode.number
            ),
            title = "${preview.title} • S${episode.season} E${episode.number}: ${episode.title}",
            artist = "${preview.title} • Season ${episode.season}",
            album = "Raw Series",
            durationMs = parseRuntimeMs(episode.runtime, defaultMins = 45L),
            artUrl = episode.thumbnail.ifBlank { preview.backdrop.ifBlank { preview.poster } },
            genre = "Raw Series",
            itag = 18,
            codec = "MP4",
            isOfficial = true
        )
    }

    /**
     * Searches BOTH Movies and TV Series via keyless Cinemeta + IMDb Suggestion CDN.
     * When query is blank, returns an empty list (never pollutes before search unless user searches).
     */
    suspend fun searchMovies(query: String, limit: Int = 24): List<Track> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext emptyList()

        val results = mutableListOf<Track>()
        val seenImdb = HashSet<String>()
        val enc = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")

        // 1. Cinemeta Movies + Series Search
        for (kind in listOf("movie", "series")) {
            val isSeries = kind == "series"
            runCatching {
                val url = "https://v3-cinemeta.strem.io/catalog/$kind/top/search=$enc.json"
                val body = httpGet(url, timeoutMs = 4500)
                if (!body.isNullOrBlank()) {
                    val metas = JSONObject(body).optJSONArray("metas") ?: JSONArray()
                    for (i in 0 until metas.length()) {
                        if (results.size >= limit) break
                        val item = metas.optJSONObject(i) ?: continue
                        val imdb = item.optString("imdb_id").ifBlank { item.optString("id") }
                        if (!imdb.startsWith("tt") || !seenImdb.add(imdb)) continue
                        val title = item.optString("name").trim()
                        if (title.isBlank()) continue
                        val year = item.optString("releaseInfo").ifBlank { item.optString("year") }.take(4)
                        val moviedbId = item.optString("moviedb_id").takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
                        if (!moviedbId.isNullOrBlank()) imdbToTmdbCache[imdb] = moviedbId
                        val poster = item.optString("poster").ifBlank {
                            "https://images.metahub.space/poster/medium/$imdb/img"
                        }
                        val runtimeStr = item.optString("runtime")
                        val rating = item.optString("imdbRating")
                        val typeLabel = if (isSeries) "TV Series" else "Movie"
                        val subtitle = buildString {
                            append(typeLabel)
                            if (year.isNotBlank()) append(" • $year")
                            if (rating.isNotBlank()) append(" • IMDb $rating")
                        }
                        results.add(
                            Track(
                                id = encodeTrackId(imdb, moviedbId ?: imdbToTmdbCache[imdb], isSeries = isSeries),
                                title = title,
                                artist = subtitle,
                                album = if (isSeries) "Raw Series" else "Raw Movie",
                                durationMs = parseRuntimeMs(runtimeStr, defaultMins = if (isSeries) 45L else 120L),
                                artUrl = poster,
                                genre = if (isSeries) "Raw Series" else "Raw Movie",
                                itag = 18,
                                codec = "MP4",
                                isOfficial = true
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Cinemeta $kind search failed: ${it.message}") }
        }

        // 2. IMDb Suggestion CDN supplement (fast, handles both movies and TV series like "The Mentalist")
        if (results.size < limit) {
            runCatching {
                val first = clean.lowercase().firstOrNull { it.isLetterOrDigit() } ?: 'a'
                val encLow = URLEncoder.encode(clean.lowercase(), "UTF-8").replace("+", "%20")
                val body = httpGet("https://v3.sg.media-imdb.com/suggestion/$first/$encLow.json", timeoutMs = 4000)
                if (!body.isNullOrBlank()) {
                    val arr = JSONObject(body).optJSONArray("d") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        if (results.size >= limit) break
                        val item = arr.optJSONObject(i) ?: continue
                        val imdb = item.optString("id")
                        val qid = item.optString("qid").lowercase()
                        if (!imdb.startsWith("tt") || !seenImdb.add(imdb)) continue
                        val isSeries = qid.contains("series") || qid == "tv" || qid == "tvminiseries"
                        val isMovie = qid == "movie" || qid == "tvmovie" || qid.isBlank()
                        if (!isSeries && !isMovie) continue
                        val title = item.optString("l").trim()
                        if (title.isBlank()) continue
                        val year = item.optInt("y", 0).takeIf { it > 1900 }?.toString().orEmpty()
                        val poster = item.optJSONObject("i")?.optString("imageUrl").orEmpty().ifBlank {
                            "https://images.metahub.space/poster/medium/$imdb/img"
                        }
                        val typeLabel = if (isSeries) "TV Series" else "Movie"
                        results.add(
                            Track(
                                id = encodeTrackId(imdb, imdbToTmdbCache[imdb], isSeries = isSeries),
                                title = title,
                                artist = if (year.isNotBlank()) "$typeLabel • $year" else typeLabel,
                                album = if (isSeries) "Raw Series" else "Raw Movie",
                                durationMs = if (isSeries) 45 * 60_000L else 120 * 60_000L,
                                artUrl = poster,
                                genre = if (isSeries) "Raw Series" else "Raw Movie",
                                itag = 18,
                                codec = "MP4",
                                isOfficial = true
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "IMDb suggestion fallback failed: ${it.message}") }
        }

        results
    }

    /**
     * Fetches full Netflix/Prime-style preview info (backdrop, synopsis, rating, genres, cast,
     * and full Seasons + Episodes for TV Series) and pre-warms TMDB ID for instant playback!
     */
    suspend fun fetchTitlePreview(track: Track): TitlePreview = withContext(Dispatchers.IO) {
        val parsed = parseTrackId(track.id)
        val cacheKey = "${parsed.imdbId}_${parsed.isSeries}"
        previewCache[cacheKey]?.let { return@withContext it }

        val kind = if (parsed.isSeries) "series" else "movie"
        var title = track.title
        var year = ""
        var rating = ""
        var runtime = if (parsed.isSeries) "45 min" else "120 min"
        var poster = track.artUrl.ifBlank { "https://images.metahub.space/poster/medium/${parsed.imdbId}/img" }
        var backdrop = "https://images.metahub.space/background/medium/${parsed.imdbId}/img"
        var description = ""
        var genres = emptyList<String>()
        var cast = emptyList<String>()
        var tmdbId = parsed.tmdbId ?: imdbToTmdbCache[parsed.imdbId].orEmpty()
        var seasons = emptyList<SeasonItem>()

        for (host in listOf("https://v3-cinemeta.strem.io", "https://cinemeta-live.strem.io")) {
            val body = httpGet("$host/meta/$kind/${parsed.imdbId}.json", timeoutMs = 4500)
            if (body.isNullOrBlank()) continue
            val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: continue
            title = meta.optString("name").ifBlank { title }
            year = meta.optString("releaseInfo").ifBlank { meta.optString("year") }.take(9)
            rating = meta.optString("imdbRating").ifBlank { rating }
            runtime = meta.optString("runtime").ifBlank { runtime }
            poster = meta.optString("poster").ifBlank { poster }
            backdrop = meta.optString("background").ifBlank { backdrop }
            description = meta.optString("description").ifBlank { description }
            val mId = meta.optString("moviedb_id").takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
            if (!mId.isNullOrBlank()) {
                tmdbId = mId
                imdbToTmdbCache[parsed.imdbId] = mId
            }

            val genresArr = meta.optJSONArray("genres") ?: meta.optJSONArray("genre")
            if (genresArr != null) {
                genres = (0 until genresArr.length()).mapNotNull { genresArr.optString(it).takeIf { g -> g.isNotBlank() } }.take(5)
            }
            val castArr = meta.optJSONArray("cast")
            if (castArr != null) {
                cast = (0 until castArr.length()).mapNotNull { castArr.optString(it).takeIf { c -> c.isNotBlank() } }.take(8)
            }

            if (parsed.isSeries) {
                val videos = meta.optJSONArray("videos")
                if (videos != null && videos.length() > 0) {
                    val seasonMap = LinkedHashMap<Int, MutableList<EpisodeItem>>()
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        val s = v.optInt("season", 0)
                        val e = v.optInt("episode", v.optInt("number", 0))
                        if (s <= 0 || e <= 0) continue
                        val epTitle = v.optString("name").ifBlank { v.optString("title") }.ifBlank { "Episode $e" }
                        val epOverview = v.optString("overview").ifBlank { v.optString("description") }
                        val epThumb = v.optString("thumbnail").ifBlank { backdrop }
                        val epReleased = v.optString("released").take(10)
                        seasonMap.getOrPut(s) { mutableListOf() }.add(
                            EpisodeItem(
                                season = s,
                                number = e,
                                title = epTitle,
                                overview = epOverview,
                                thumbnail = epThumb,
                                runtime = runtime,
                                released = epReleased
                            )
                        )
                    }
                    seasons = seasonMap.entries
                        .sortedBy { it.key }
                        .map { (sNum, eps) ->
                            SeasonItem(
                                number = sNum,
                                name = "Season $sNum",
                                episodes = eps.sortedBy { it.number }
                            )
                        }
                }
            }
            break
        }

        // Pre-resolve TMDB ID now so tapping Play starts instantaneously!
        if (tmdbId.isBlank() && parsed.imdbId.isNotBlank()) {
            tmdbId = resolveTmdbIdFromImdb(parsed.imdbId, parsed.isSeries).orEmpty()
        }

        val preview = TitlePreview(
            imdbId = parsed.imdbId,
            tmdbId = tmdbId,
            isSeries = parsed.isSeries,
            title = title,
            year = year,
            rating = rating,
            runtime = runtime,
            poster = poster,
            backdrop = backdrop,
            description = description,
            genres = genres,
            cast = cast,
            seasons = seasons
        )
        previewCache[cacheKey] = preview
        preview
    }

    /**
     * Resolves a direct raw playable video stream (MP4 or Master HLS) for a Movie or TV Episode
     * using ONLY VidLink Direct Raw (enc-dec.app -> vidlink.pro/api/b/{movie|tv}/{encId}).
     */
    suspend fun resolveRawMovieStream(track: Track, forceRefresh: Boolean = false): RawMovieStream? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                streamCache[track.id]?.let { (ts, cached) ->
                    if (now - ts < STREAM_CACHE_TTL_MS) return@withContext cached
                }
            }

            val parsed = parseTrackId(track.id)
            val tmdbId = parsed.tmdbId?.takeIf { it.isNotBlank() }
                ?: resolveTmdbIdFromImdb(parsed.imdbId, parsed.isSeries)
                ?: return@withContext null

            val hlsAllowed = InnerTubeStreamExtractor.isHlsSupported
            val vidlinkResult = resolveViaVidlink(tmdbId, parsed, hlsAllowed)
            if (vidlinkResult != null && (!vidlinkResult.isHls || hlsAllowed)) {
                streamCache[track.id] = now to vidlinkResult
                return@withContext vidlinkResult
            }

            // Direct progressive HTTP MP4 fallback from original.zip (a.111477.xyz) so playback works even without media3-exoplayer-hls
            val directMp4Fallback = resolveViaDahmerDirectMp4(track, parsed)
            if (directMp4Fallback != null) {
                streamCache[track.id] = now to directMp4Fallback
                return@withContext directMp4Fallback
            }

            if (vidlinkResult != null) {
                streamCache[track.id] = now to vidlinkResult
                return@withContext vidlinkResult
            }
            return@withContext null
        }

    private fun resolveViaVidlink(
        tmdbId: String,
        parsed: ParsedTrackMeta,
        hlsAllowed: Boolean
    ): RawMovieStream? {
        val encBody = httpGet(
            url = "https://enc-dec.app/api/enc-vidlink?text=${URLEncoder.encode(tmdbId, "UTF-8")}",
            timeoutMs = 5500
        ) ?: return null
        val encId = runCatching { JSONObject(encBody).optString("result") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null

        val apiUrl = if (parsed.isSeries) {
            "https://vidlink.pro/api/b/tv/$encId/${parsed.season}/${parsed.episode}"
        } else {
            "https://vidlink.pro/api/b/movie/$encId"
        }
        val streamBody = httpGet(
            url = apiUrl,
            timeoutMs = 6500,
            extraHeaders = mapOf(
                "Referer" to "https://vidlink.pro/",
                "Origin" to "https://vidlink.pro",
                "Accept" to "application/json"
            )
        ) ?: return null

            val root = runCatching { JSONObject(streamBody) }.getOrNull() ?: return null
            val streamObj = root.optJSONObject("stream") ?: root
            val declaredType = streamObj.optString("type").lowercase()

            // Optional VTT caption URL
            var captionVtt: String? = null
            val captionsArr = streamObj.optJSONArray("captions") ?: streamObj.optJSONArray("subtitles")
            if (captionsArr != null) {
                for (i in 0 until captionsArr.length()) {
                    val cap = captionsArr.optJSONObject(i) ?: continue
                    val capUrl = cap.optString("url").trim()
                    if (!capUrl.startsWith("http")) continue
                    val lang = cap.optString("language").ifBlank { cap.optString("label") }.lowercase()
                    if (captionVtt == null || lang.contains("eng") || lang == "en") {
                        captionVtt = capUrl
                        if (lang.contains("eng") || lang == "en") break
                    }
                }
            }

            // Collect direct progressive MP4 candidates first (most compatible with Android ExoPlayer)
            var bestMp4Url: String? = null
            var bestMp4Score = -1
            var bestMp4Label = "1080p MP4"

            val qualities = streamObj.optJSONObject("qualities")
            if (qualities != null) {
                val keys = qualities.keys()
                while (keys.hasNext()) {
                    val qKey = keys.next()
                    val qVal = qualities.opt(qKey)
                    val rawUrl = when (qVal) {
                        is String -> qVal
                        is JSONObject -> qVal.optString("url")
                        else -> ""
                    }.trim()
                    if (!rawUrl.startsWith("http")) continue
                    if (rawUrl.contains("b164fbfb4347792950bdfbfb563d39d9") || rawUrl.contains("/notice.mp4")) continue
                    val isHevc = rawUrl.contains("/h265/", ignoreCase = true) || rawUrl.contains("hevc", ignoreCase = true)
                    val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)
                    val score = when {
                        qKey.contains("1080") -> 1080
                        qKey.contains("720") -> 980
                        qKey.contains("480") -> 600
                        qKey.contains("2160") || qKey.contains("4k", ignoreCase = true) -> 900
                        else -> 700
                    } - (if (isHevc) 700 else 0) - (if (isM3u8) 300 else 0)
                    if (score > bestMp4Score) {
                        bestMp4Score = score
                        bestMp4Url = rawUrl
                        bestMp4Label = if (qKey.contains("p", ignoreCase = true)) "$qKey Raw" else "${qKey}p Raw"
                    }
                }
            }

            val playlistUrl = streamObj.optString("playlist").trim().takeIf { it.startsWith("http") }

            val usablePlaylist = playlistUrl?.takeIf { hlsAllowed || !it.contains(".m3u8", ignoreCase = true) }
            val chosenUrl = bestMp4Url ?: usablePlaylist ?: playlistUrl ?: return null
            val fallbackUrl = if (chosenUrl == bestMp4Url) usablePlaylist else bestMp4Url
            val isHls = chosenUrl.contains(".m3u8", ignoreCase = true) ||
                (chosenUrl == playlistUrl && (declaredType == "hls" || !chosenUrl.contains(".mp4", ignoreCase = true)))
            val isNoRefererHost = chosenUrl.contains("hakunaymatata.com", ignoreCase = true) ||
                chosenUrl.contains("aoneroom.com", ignoreCase = true)

            return RawMovieStream(
                streamUrl = chosenUrl,
                fallbackUrl = fallbackUrl,
                referer = if (isNoRefererHost) null else "https://vidlink.pro/",
                origin = if (isNoRefererHost) null else "https://vidlink.pro",
                isHls = isHls,
                qualityLabel = if (isHls) "Adaptive HLS" else bestMp4Label,
                captionVttUrl = captionVtt
            )
        }

    private fun resolveViaDahmerDirectMp4(track: Track, parsed: ParsedTrackMeta): RawMovieStream? {
        return runCatching {
            val baseTitle = track.title.substringBefore(" • S").replace(":", "").trim()
            if (baseTitle.isBlank()) return null
            val year = Regex("\b(19|20)\d{2}\b").find(track.artist)?.value.orEmpty()
            val folderUrl = if (parsed.isSeries) {
                val encTitle = URLEncoder.encode(baseTitle, "UTF-8").replace("+", "%20")
                "https://a.111477.xyz/tvs/$encTitle/Season%20${parsed.season}/"
            } else {
                val folderName = if (year.isNotBlank()) "$baseTitle ($year)" else baseTitle
                val encFolder = URLEncoder.encode(folderName, "UTF-8").replace("+", "%20")
                "https://a.111477.xyz/movies/$encFolder/"
            }
            val html = httpGet(folderUrl, timeoutMs = 4500) ?: return null
            val hrefRegex = Regex("href=\"([^\"]+\.(?:mp4|mkv|webm))\"", RegexOption.IGNORE_CASE)
            val epTag = if (parsed.isSeries) {
                Regex("s0*${parsed.season}e0*${parsed.episode}\b|${parsed.season}x0*${parsed.episode}\b", RegexOption.IGNORE_CASE)
            } else null

            var bestUrl: String? = null
            var bestScore = -1
            for (m in hrefRegex.findAll(html)) {
                val href = m.groupValues[1]
                val fileName = java.net.URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
                if (epTag != null && !epTag.containsMatchIn(fileName)) continue
                val absUrl = URL(URL(folderUrl), href).toString()
                val lower = fileName.lowercase()
                val isMkvOrHevc = lower.endsWith(".mkv") || lower.contains("x265") || lower.contains("h265") || lower.contains("hevc")
                val score = when {
                    lower.contains("1080") -> 1080
                    lower.contains("720") -> 980
                    lower.contains("2160") || lower.contains("4k") -> 900
                    else -> 650
                } - (if (isMkvOrHevc) 600 else 0)
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = absUrl
                }
            }
            bestUrl?.let { url ->
                RawMovieStream(
                    streamUrl = url,
                    fallbackUrl = null,
                    referer = "https://a.111477.xyz/",
                    origin = null,
                    isHls = false,
                    qualityLabel = "Direct MP4"
                )
            }
        }.getOrNull()
    }

    private fun resolveTmdbIdFromImdb(imdbId: String, isSeries: Boolean): String? {
        if (imdbId.isBlank()) return null
        imdbToTmdbCache[imdbId]?.let { return it }

        val kind = if (isSeries) "series" else "movie"
        runCatching {
            val body = httpGet("https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json", timeoutMs = 3500)
            if (!body.isNullOrBlank()) {
                val moviedbId = JSONObject(body).optJSONObject("meta")?.optString("moviedb_id")
                    ?.takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
                if (!moviedbId.isNullOrBlank()) {
                    imdbToTmdbCache[imdbId] = moviedbId
                    return moviedbId
                }
            }
        }

        // Wikidata SPARQL fallback: P4947 = TMDB Movie ID, P4983 = TMDB TV Series ID
        val tmdbProp = if (isSeries) "P4983" else "P4947"
        runCatching {
            val sparql = "SELECT ?t WHERE { ?item wdt:P345 \"$imdbId\" . ?item wdt:$tmdbProp ?t . } LIMIT 1"
            val enc = URLEncoder.encode(sparql, "UTF-8")
            val body = httpGet(
                url = "https://query.wikidata.org/sparql?format=json&query=$enc",
                timeoutMs = 4500,
                extraHeaders = mapOf("Accept" to "application/sparql-results+json")
            )
            if (!body.isNullOrBlank()) {
                val bindings = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings")
                val tmdb = bindings?.optJSONObject(0)?.optJSONObject("t")?.optString("value")
                    ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                if (!tmdb.isNullOrBlank()) {
                    imdbToTmdbCache[imdbId] = tmdb
                    return tmdb
                }
            }
        }
        return null
    }

    private fun encodeTrackId(
        imdbId: String,
        tmdbId: String?,
        isSeries: Boolean,
        season: Int = 1,
        episode: Int = 1
    ): String {
        val safeTmdb = tmdbId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }.orEmpty()
        return when {
            isSeries && season > 0 && episode > 0 -> "${MOVIE_ID_PREFIX}e_${imdbId}_${safeTmdb}_${season}_${episode}"
            isSeries -> "${MOVIE_ID_PREFIX}s_${imdbId}_${safeTmdb}"
            else -> "${MOVIE_ID_PREFIX}m_${imdbId}_${safeTmdb}"
        }
    }

    fun parseTrackId(trackId: String): ParsedTrackMeta {
        val raw = trackId.removePrefix(MOVIE_ID_PREFIX)
        val parts = raw.split("_")
        return when (parts.firstOrNull()) {
            "e" -> ParsedTrackMeta(
                imdbId = parts.getOrNull(1).orEmpty(),
                tmdbId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) },
                isSeries = true,
                season = parts.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                episode = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            )
            "s" -> ParsedTrackMeta(
                imdbId = parts.getOrNull(1).orEmpty(),
                tmdbId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) },
                isSeries = true,
                season = 1,
                episode = 1
            )
            "m" -> ParsedTrackMeta(
                imdbId = parts.getOrNull(1).orEmpty(),
                tmdbId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) },
                isSeries = false,
                season = 1,
                episode = 1
            )
            else -> ParsedTrackMeta(
                imdbId = parts.getOrNull(0).orEmpty(),
                tmdbId = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) },
                isSeries = false,
                season = 1,
                episode = 1
            )
        }
    }

    private fun parseRuntimeMs(runtime: String, defaultMins: Long = 120L): Long {
        val mins = Regex("(\\d+)").find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: defaultMins
        return (mins.coerceIn(10L, 360L)) * 60_000L
    }

    private fun httpGet(
        url: String,
        timeoutMs: Int = 5000,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "httpGet failed for $url: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
