package com.seitharol.data.resolver

import android.util.Log
import com.example.data.model.Track
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Single Trusted Raw Movie & TV Series Streamer (VidLink Direct Raw — Tier-R #1 in original.zip):
 * - Relies strictly on ONE trusted, high-speed raw provider: VidLink Direct Raw
 *   (`enc-dec.app/api/enc-vidlink` -> `vidlink.pro/api/b/{movie|tv}/{encId}`) for both 1080p/720p Progressive MP4
 *   and Adaptive Master HLS playback + direct MP4 downloads.
 * - Fixes the 4 root causes that previously broke movie playback:
 *   1. Reliable IMDb -> TMDB ID resolution (`FlixDB` + `Cinemeta` + `Wikidata` with compliant User-Agent) so IMDb search results always resolve their TMDB ID.
 *   2. Automatic `Referer`/`Origin` stripping for `NO_REFERER_HOSTS` (`hakunaymatata.com`, `aoneroom.com`, `inmoviebox.com`, `moviebox.ph`, `moviebox.ac`) + automatic no-Referer retry on HTTP 403/429 (from `original.zip/app/api/proxy/route.ts`).
 *   3. Accurate HLS (`#EXTM3U`) vs Progressive MP4 detection so ExoPlayer always mounts `HlsMediaSource` for Master HLS and native extractors for MP4.
 *   4. Non-blocking in-app captions (`VidLink` captions + `OpenSubtitles v3` fallback) without wrapping ExoPlayer in a fragile `MergingMediaSource`.
 */
object VidLinkRawMovieStreamer {
    private const val TAG = "VidLinkRawMovie"
    const val MOVIE_ID_PREFIX = "movie_vidlink_"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private const val WIKIDATA_UA =
        "MamiStreaming/2.0 (https://github.com/mami-stream; contact@mami.local) NodeFetch/1.0"

    // CDNs used by VidLink that throttle with HTTP 429 or 403 if Referer / Origin headers are attached (from original.zip/app/api/proxy/route.ts)
    private val NO_REFERER_HOSTS = listOf(
        "hakunaymatata.com",
        "aoneroom.com",
        "inmoviebox.com",
        "moviebox.ph",
        "moviebox.ac"
    )

    data class RawMovieStream(
        val streamUrl: String,
        val fallbackUrl: String? = null,
        val referer: String?,
        val origin: String?,
        val isHls: Boolean,
        val qualityLabel: String,
        val captionVttUrl: String? = null,
        val qualityScore: Int = 1080
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
            "tt4574334" to "66732",    // Stranger Things
            "tt13443470" to "119051",  // Wednesday
            "tt5753856" to "70523",    // Dark
            "tt4786824" to "65494",    // The Crown
            "tt2085059" to "42009",    // Black Mirror
            "tt10919420" to "93405",   // Squid Game
            "tt13918776" to "129552",  // The Night Agent
            "tt6468322" to "71446",    // Money Heist
            "tt17491088" to "202250",  // The Diplomat
            "tt10048342" to "87739",   // The Queen's Gambit
            "tt5071412" to "69740",    // Ozark
            "tt2531336" to "96677",    // Lupin
            "tt5180504" to "71912",    // The Witcher
            "tt1649418" to "725201",   // The Gray Man
            "tt9253866" to "83880",    // Our Planet
            "tt1196946" to "5920",     // The Mentalist
            "tt0903747" to "1396",     // Breaking Bad
            "tt0944947" to "1399",     // Game of Thrones
            "tt0111161" to "278",      // The Shawshank Redemption
            "tt1375666" to "27205",    // Inception
            "tt0468569" to "155",      // The Dark Knight
            "tt0816692" to "157336",   // Interstellar
            "tt4633694" to "324857",   // Spider-Man: Into the Spider-Verse
            "tt9362722" to "569094",   // Spider-Man: Across the Spider-Verse
            "tt10872600" to "634649",  // Spider-Man: No Way Home
            "tt6320628" to "429617",   // Spider-Man: Far From Home
            "tt2250912" to "315635",   // Spider-Man: Homecoming
            "tt15398776" to "872585",  // Oppenheimer
            "tt15239678" to "693134",  // Dune: Part Two
            "tt1160419" to "438631",   // Dune
            "tt1877830" to "414906",   // The Batman
            "tt6751668" to "496243",   // Parasite
            "tt8178634" to "579974",   // RRR
            "tt12735488" to "804150",  // Kalki 2898 AD
            "tt15354916" to "781732",  // Jawan
            "tt0388629" to "37854",    // One Piece
            "tt0988824" to "31910",    // Naruto Shippuden
            "tt2560140" to "1429",     // Attack on Titan
            "tt9335498" to "85937",    // Demon Slayer
            "tt12343534" to "95479"    // Jujutsu Kaisen
        )
    )

    private val streamCache = ConcurrentHashMap<String, Pair<Long, RawMovieStream>>()
    private val candidateListCache = ConcurrentHashMap<String, Pair<Long, List<RawMovieStream>>>()
    private val failedUrlsByTrackId = ConcurrentHashMap<String, MutableSet<String>>()
    private val streamHeadersByUrl = ConcurrentHashMap<String, Map<String, String>>()
    private val previewCache = ConcurrentHashMap<String, TitlePreview>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    fun clearCaches() {
        streamCache.clear()
        candidateListCache.clear()
        failedUrlsByTrackId.clear()
        streamHeadersByUrl.clear()
        previewCache.clear()
    }

    fun markStreamFailed(trackId: String, failedUrl: String?) {
        streamCache.remove(trackId)
        if (!failedUrl.isNullOrBlank()) {
            val set = failedUrlsByTrackId.getOrPut(trackId) {
                Collections.synchronizedSet(HashSet())
            }
            set.add(failedUrl.trim())
        }
    }

    fun getHeadersForStreamUrl(url: String): Map<String, String>? {
        val clean = url.trim()
        streamHeadersByUrl[clean]?.let { return it }
        val host = runCatching { URL(clean).host.lowercase() }.getOrDefault("")
        if (host.isBlank() || host.contains("googlevideo.com") || host.contains("saavncdn.com")) {
            return null
        }
        if (shouldStripReferer(host)) {
            return mapOf("User-Agent" to BROWSER_UA, "Accept" to "*/*")
        }
        return null
    }

    private fun recordStreamHeaders(stream: RawMovieStream) {
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = BROWSER_UA
        map["Accept"] = "*/*"
        if (!stream.referer.isNullOrBlank() && !shouldStripReferer(stream.streamUrl)) {
            map["Referer"] = stream.referer
        }
        if (!stream.origin.isNullOrBlank() && !shouldStripReferer(stream.streamUrl)) {
            map["Origin"] = stream.origin
        }
        streamHeadersByUrl[stream.streamUrl] = map
        stream.fallbackUrl?.takeIf { it.isNotBlank() }?.let { fb ->
            streamHeadersByUrl[fb] = map
        }
    }

    private fun shouldStripReferer(urlOrHost: String): Boolean {
        val lower = urlOrHost.lowercase()
        return NO_REFERER_HOSTS.any { d -> lower == d || lower.endsWith(".$d") || lower.contains(d) }
    }

    /**
     * Scales raw multi-megabyte IMDb/Amazon poster URLs (`._V1_.jpg`) down to fast 480px/720px JPEG thumbnails
     * so movie & series thumbnails render almost instantaneously instead of stalling on 15 MB studio posters.
     */
    fun optimizePosterUrl(rawUrl: String?, imdbId: String = "", isBackdrop: Boolean = false): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return if (imdbId.startsWith("tt")) {
                if (isBackdrop) "https://images.metahub.space/background/medium/$imdbId/img"
                else "https://images.metahub.space/poster/small/$imdbId/img"
            } else ""
        }
        if (trimmed.contains("m.media-amazon.com/images/", ignoreCase = true) ||
            trimmed.contains("ia.media-imdb.com/images/", ignoreCase = true)
        ) {
            val targetWidth = if (isBackdrop) "UX720" else "UX480"
            if (trimmed.contains("._V1_")) {
                val prefix = trimmed.substringBefore("._V1_")
                val ext = trimmed.substringAfterLast('.', "jpg")
                return "${prefix}._V1_QL75_${targetWidth}_.$ext"
            }
        }
        if (!isBackdrop && trimmed.contains("images.metahub.space/poster/medium/", ignoreCase = true)) {
            return trimmed.replace("/poster/medium/", "/poster/small/")
        }
        return trimmed
    }

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
     * Searches BOTH Movies and TV Series via keyless Cinemeta + IMDb Suggestion CDN in parallel.
     */
    suspend fun searchMovies(query: String, limit: Int = 24): List<Track> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext emptyList()

        val enc = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")

        coroutineScope {
            val imdbDeferred = async {
                runCatching {
                    val list = mutableListOf<Pair<String, Track>>()
                    val first = clean.lowercase().firstOrNull { it.isLetterOrDigit() } ?: 'a'
                    val encLow = URLEncoder.encode(clean.lowercase(), "UTF-8").replace("+", "%20")
                    val body = httpGet("https://v3.sg.media-imdb.com/suggestion/$first/$encLow.json", timeoutMs = 2500)
                    if (!body.isNullOrBlank()) {
                        val arr = JSONObject(body).optJSONArray("d") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val imdb = item.optString("id")
                            val qid = item.optString("qid").lowercase()
                            if (!imdb.startsWith("tt")) continue
                            val isSeries = qid.contains("series") || qid == "tv" || qid == "tvminiseries" || qid.contains("mini")
                            val isMovie = !isSeries && (qid == "movie" || qid == "tvmovie" || qid.contains("feature") || qid.contains("film") || qid.isBlank())
                            if (!isSeries && !isMovie) continue
                            val title = item.optString("l").trim()
                            if (title.isBlank()) continue
                            val year = item.optInt("y", 0).takeIf { it > 1900 }?.toString().orEmpty()
                            val rawPoster = item.optJSONObject("i")?.optString("imageUrl").orEmpty()
                            val poster = optimizePosterUrl(rawPoster, imdb, isBackdrop = false)
                            val typeLabel = if (isSeries) "TV Series" else "Movie"
                            list.add(
                                imdb to Track(
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
                    list
                }.getOrDefault(emptyList())
            }

            val cinemetaJobs = listOf("movie", "series").map { kind ->
                async {
                    val isSeries = kind == "series"
                    runCatching {
                        val list = mutableListOf<Pair<String, Track>>()
                        val url = "https://v3-cinemeta.strem.io/catalog/$kind/top/search=$enc.json"
                        val body = httpGet(url, timeoutMs = 2800)
                        if (!body.isNullOrBlank()) {
                            val metas = JSONObject(body).optJSONArray("metas") ?: JSONArray()
                            for (i in 0 until metas.length()) {
                                val item = metas.optJSONObject(i) ?: continue
                                val imdb = item.optString("imdb_id").ifBlank { item.optString("id") }
                                if (!imdb.startsWith("tt")) continue
                                val title = item.optString("name").trim()
                                if (title.isBlank()) continue
                                val year = item.optString("releaseInfo").ifBlank { item.optString("year") }.take(4)
                                val moviedbId = item.optString("moviedb_id").takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
                                if (!moviedbId.isNullOrBlank()) imdbToTmdbCache[imdb] = moviedbId
                                val poster = optimizePosterUrl(item.optString("poster"), imdb, isBackdrop = false)
                                val runtimeStr = item.optString("runtime")
                                val rating = item.optString("imdbRating")
                                val typeLabel = if (isSeries) "TV Series" else "Movie"
                                val subtitle = buildString {
                                    append(typeLabel)
                                    if (year.isNotBlank()) append(" • $year")
                                    if (rating.isNotBlank()) append(" • IMDb $rating")
                                }
                                list.add(
                                    imdb to Track(
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
                        list
                    }.getOrDefault(emptyList())
                }
            }

            val imdbList = imdbDeferred.await()
            val cinemetaLists = cinemetaJobs.map { it.await() }
            val merged = LinkedHashMap<String, Track>()
            for ((imdb, tr) in imdbList) {
                if (merged.size >= limit) break
                merged[imdb] = tr
            }
            for (cList in cinemetaLists) {
                for ((imdb, tr) in cList) {
                    val existing = merged[imdb]
                    if (existing == null) {
                        if (merged.size < limit) merged[imdb] = tr
                    } else {
                        val fastArt = if (existing.artUrl.contains("media-amazon.com")) existing.artUrl else tr.artUrl
                        merged[imdb] = tr.copy(artUrl = fastArt)
                    }
                }
            }
            merged.values.take(limit)
        }
    }

    /**
     * Fetches full Netflix/Prime-style preview info (backdrop, synopsis, rating, genres, cast,
     * and full Seasons + Episodes via Cinemeta + TVMaze fallback) and pre-warms TMDB ID for instant playback.
     */
    suspend fun fetchTitlePreview(track: Track): TitlePreview = withContext(Dispatchers.IO) {
        val parsed = parseTrackId(track.id)
        val cacheKey = "${parsed.imdbId}_${parsed.isSeries}"
        previewCache[cacheKey]?.let { return@withContext it }

        val kind = if (parsed.isSeries) "series" else "movie"
        var title = track.title
        var year = Regex("\\b(19|20)\\d{2}\\b").find(track.artist)?.value.orEmpty()
        var rating = ""
        var runtime = if (parsed.isSeries) "45 min" else "120 min"
        var poster = optimizePosterUrl(track.artUrl, parsed.imdbId, isBackdrop = false)
        var backdrop = optimizePosterUrl("", parsed.imdbId, isBackdrop = true)
        var description = ""
        var genres = emptyList<String>()
        var cast = emptyList<String>()
        var tmdbId = parsed.tmdbId ?: imdbToTmdbCache[parsed.imdbId].orEmpty()
        var seasons = emptyList<SeasonItem>()

        for (host in listOf("https://v3-cinemeta.strem.io", "https://cinemeta-live.strem.io")) {
            val body = httpGet("$host/meta/$kind/${parsed.imdbId}.json", timeoutMs = 4200)
            if (body.isNullOrBlank()) continue
            val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: continue
            title = meta.optString("name").ifBlank { title }
            year = meta.optString("releaseInfo").ifBlank { meta.optString("year") }.take(9).ifBlank { year }
            rating = meta.optString("imdbRating").ifBlank { rating }
            runtime = meta.optString("runtime").ifBlank { runtime }
            poster = optimizePosterUrl(meta.optString("poster").ifBlank { poster }, parsed.imdbId, isBackdrop = false)
            backdrop = optimizePosterUrl(meta.optString("background").ifBlank { backdrop }, parsed.imdbId, isBackdrop = true)
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
                        val epThumb = optimizePosterUrl(v.optString("thumbnail").ifBlank { backdrop }, parsed.imdbId, isBackdrop = true)
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

        if (parsed.isSeries && seasons.isEmpty() && parsed.imdbId.startsWith("tt")) {
            seasons = fetchTvMazeSeasons(parsed.imdbId, backdrop, runtime)
        }

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

    private fun fetchTvMazeSeasons(imdbId: String, defaultBackdrop: String, defaultRuntime: String): List<SeasonItem> {
        return runCatching {
            val lookupBody = httpGet(
                "https://api.tvmaze.com/lookup/shows?imdb=${URLEncoder.encode(imdbId, "UTF-8")}",
                timeoutMs = 3800
            ) ?: return emptyList()
            val showId = JSONObject(lookupBody).optInt("id", 0)
            if (showId <= 0) return emptyList()
            val epsBody = httpGet("https://api.tvmaze.com/shows/$showId/episodes", timeoutMs = 4200)
                ?: return emptyList()
            val epsArr = JSONArray(epsBody)
            val seasonMap = LinkedHashMap<Int, MutableList<EpisodeItem>>()
            for (i in 0 until epsArr.length()) {
                val ep = epsArr.optJSONObject(i) ?: continue
                val s = ep.optInt("season", 0)
                val e = ep.optInt("number", 0)
                if (s <= 0 || e <= 0) continue
                val epTitle = ep.optString("name").ifBlank { "Episode $e" }
                val rawSummary = ep.optString("summary").replace(Regex("<[^>]+>"), "").trim()
                val epThumb = ep.optJSONObject("image")?.optString("medium").orEmpty().ifBlank { defaultBackdrop }
                val epRuntime = ep.optInt("runtime", 0).takeIf { it > 0 }?.let { "$it min" } ?: defaultRuntime
                val airdate = ep.optString("airdate").take(10)
                seasonMap.getOrPut(s) { mutableListOf() }.add(
                    EpisodeItem(
                        season = s,
                        number = e,
                        title = epTitle,
                        overview = rawSummary,
                        thumbnail = epThumb,
                        runtime = epRuntime,
                        released = airdate
                    )
                )
            }
            seasonMap.entries.sortedBy { it.key }.map { (sNum, eps) ->
                SeasonItem(
                    number = sNum,
                    name = "Season $sNum",
                    episodes = eps.sortedBy { it.number }
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches the best OpenSubtitles v3 (.srt) subtitle URL for a Movie or TV Episode
     * when VidLink's stream response does not bundle an inline VTT file.
     */
    fun fetchOpenSubtitlesUrl(trackId: String): String? {
        val parsed = parseTrackId(trackId)
        val imdb = parsed.imdbId
        if (!imdb.startsWith("tt")) return null
        val type = if (parsed.isSeries) "series" else "movie"
        val subQueryId = if (parsed.isSeries) "$imdb:${parsed.season}:${parsed.episode}" else imdb
        return runCatching {
            val encId = URLEncoder.encode(subQueryId, "UTF-8")
            val body = httpGet(
                url = "https://opensubtitles-v3.strem.io/subtitles/$type/$encId.json",
                timeoutMs = 4000
            ) ?: return null
            val subs = JSONObject(body).optJSONArray("subtitles") ?: return null
            var bestEn: String? = null
            var bestAny: String? = null
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val url = s.optString("url").trim()
                if (!url.startsWith("http")) continue
                val lang = s.optString("lang").lowercase()
                if (lang == "eng" || lang == "en") {
                    bestEn = url
                    break
                }
                if (bestAny == null) bestAny = url
            }
            bestEn ?: bestAny
        }.getOrNull()
    }

    /**
     * Resolves a direct raw playable video stream (Progressive MP4 or Master HLS) for a Movie or TV Episode
     * using ONLY VidLink Direct Raw (`enc-dec.app/api/enc-vidlink` -> `vidlink.pro/api/b/{movie|tv}/{encId}`).
     */
    suspend fun resolveRawMovieStream(track: Track, forceRefresh: Boolean = false): RawMovieStream? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val failedSet = failedUrlsByTrackId[track.id].orEmpty()

            if (!forceRefresh) {
                streamCache[track.id]?.let { (ts, cached) ->
                    if (now - ts < STREAM_CACHE_TTL_MS && cached.streamUrl !in failedSet) {
                        return@withContext cached
                    }
                }
            }

            // If we already have VidLink's quality/HLS candidates cached for this track, advance to the next non-failed quality stream
            candidateListCache[track.id]?.let { (ts, list) ->
                if (now - ts < STREAM_CACHE_TTL_MS) {
                    val remaining = list.filter { it.streamUrl !in failedSet }
                    for (cand in remaining) {
                        val probed = probeAndNormalizeCandidate(cand) ?: cand
                        val nextFallback = remaining.firstOrNull { it.streamUrl != probed.streamUrl }?.streamUrl
                        val finalStream = probed.copy(fallbackUrl = nextFallback)
                        recordStreamHeaders(finalStream)
                        streamCache[track.id] = now to finalStream
                        return@withContext finalStream
                    }
                }
            }

            val parsed = parseTrackId(track.id)
            val tmdbId = parsed.tmdbId?.takeIf { it.isNotBlank() }
                ?: resolveTmdbIdFromImdb(parsed.imdbId, parsed.isSeries)
                ?: return@withContext null

            val hlsAllowed = InnerTubeStreamExtractor.isHlsSupported
            val vidlinkCandidates = extractVidlinkCandidates(tmdbId, parsed)
                .filter { it.streamUrl.startsWith("http") && (!it.isHls || hlsAllowed) && it.streamUrl !in failedSet }
                .sortedByDescending { it.qualityScore }

            if (vidlinkCandidates.isEmpty()) return@withContext null

            candidateListCache[track.id] = now to vidlinkCandidates

            var verifiedPrimary: RawMovieStream? = null
            for (cand in vidlinkCandidates) {
                val probed = probeAndNormalizeCandidate(cand)
                if (probed != null) {
                    verifiedPrimary = probed
                    break
                }
            }

            val chosen = verifiedPrimary ?: vidlinkCandidates.first()
            val fallback = vidlinkCandidates.firstOrNull { it.streamUrl != chosen.streamUrl }?.streamUrl
            val finalStream = chosen.copy(fallbackUrl = fallback)
            recordStreamHeaders(finalStream)
            streamCache[track.id] = now to finalStream
            finalStream
        }

    /**
     * Resolves direct downloadable progressive MP4 URLs (non-HLS) from VidLink Direct Raw.
     */
    suspend fun resolveDownloadableMovieMp4Candidates(track: Track): List<String> =
        withContext(Dispatchers.IO) {
            val parsed = parseTrackId(track.id)
            val tmdbId = parsed.tmdbId?.takeIf { it.isNotBlank() }
                ?: resolveTmdbIdFromImdb(parsed.imdbId, parsed.isSeries)
                ?: return@withContext emptyList()
            val mp4Streams = extractVidlinkCandidates(tmdbId, parsed)
                .filter { !it.isHls && !it.streamUrl.contains(".m3u8", ignoreCase = true) }
                .sortedByDescending { it.qualityScore }
            mp4Streams.forEach { recordStreamHeaders(it) }
            val verifiedUrls = mutableListOf<String>()
            for (cand in mp4Streams) {
                val probed = probeAndNormalizeCandidate(cand)
                if (probed != null && !probed.isHls) {
                    recordStreamHeaders(probed)
                    verifiedUrls.add(probed.streamUrl)
                }
            }
            if (verifiedUrls.isNotEmpty()) verifiedUrls else mp4Streams.map { it.streamUrl }.distinct()
        }

    /**
     * Extracts VidLink Direct Raw streams (1080p / 720p / 480p / 360p MP4 + Master HLS) via enc-dec.app -> vidlink.pro.
     */
    private fun extractVidlinkCandidates(
        tmdbId: String,
        parsed: ParsedTrackMeta
    ): List<RawMovieStream> {
        val encBody = httpGet(
            url = "https://enc-dec.app/api/enc-vidlink?text=${URLEncoder.encode(tmdbId, "UTF-8")}",
            timeoutMs = 4500
        ) ?: return emptyList()
        val encId = runCatching { JSONObject(encBody).optString("result") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val apiUrl = if (parsed.isSeries) {
            "https://vidlink.pro/api/b/tv/$encId/${parsed.season}/${parsed.episode}"
        } else {
            "https://vidlink.pro/api/b/movie/$encId"
        }
        val streamBody = httpGet(
            url = apiUrl,
            timeoutMs = 5500,
            extraHeaders = mapOf(
                "Referer" to "https://vidlink.pro/",
                "Origin" to "https://vidlink.pro",
                "Accept" to "application/json"
            )
        ) ?: return emptyList()

        val root = runCatching { JSONObject(streamBody) }.getOrNull() ?: return emptyList()
        val streamObj = root.optJSONObject("stream") ?: root
        val declaredType = streamObj.optString("type").lowercase()

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

        val out = mutableListOf<RawMovieStream>()

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
                val isHevc = rawUrl.contains("/h265/", ignoreCase = true) ||
                    rawUrl.contains("hevc", ignoreCase = true) ||
                    rawUrl.contains("x265", ignoreCase = true)
                val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)
                val baseScore = when {
                    qKey.contains("1080") -> 1180
                    qKey.contains("720") -> 1120
                    qKey.contains("2160") || qKey.contains("4k", ignoreCase = true) -> 1060
                    qKey.contains("480") -> 860
                    else -> 800
                } - (if (isHevc) 450 else 0) - (if (isM3u8) 80 else 0)
                val noRef = shouldStripReferer(rawUrl)
                val label = if (qKey.contains("p", ignoreCase = true)) "$qKey Raw" else "${qKey}p Raw"
                out.add(
                    RawMovieStream(
                        streamUrl = rawUrl,
                        referer = if (noRef) null else "https://vidlink.pro/",
                        origin = if (noRef) null else "https://vidlink.pro",
                        isHls = isM3u8,
                        qualityLabel = label,
                        captionVttUrl = captionVtt,
                        qualityScore = baseScore
                    )
                )
            }
        }

        val playlistUrl = streamObj.optString("playlist").trim().takeIf { it.startsWith("http") }
        if (!playlistUrl.isNullOrBlank()) {
            val noRef = shouldStripReferer(playlistUrl)
            val isHls = playlistUrl.contains(".m3u8", ignoreCase = true) ||
                declaredType == "hls" ||
                !playlistUrl.contains(".mp4", ignoreCase = true)
            out.add(
                RawMovieStream(
                    streamUrl = playlistUrl,
                    referer = if (noRef) null else "https://vidlink.pro/",
                    origin = if (noRef) null else "https://vidlink.pro",
                    isHls = isHls,
                    qualityLabel = "1080p Master HLS",
                    captionVttUrl = captionVtt,
                    qualityScore = 1140
                )
            )
        }

        return out
    }

    /**
     * Verifies candidate stream reachability (HTTP 200/206), automatically retries without Referer/Origin
     * on HTTP 403/429 (from original.zip/app/api/proxy/route.ts), and detects #EXTM3U HLS vs progressive MP4.
     */
    private fun probeAndNormalizeCandidate(candidate: RawMovieStream): RawMovieStream? {
        val stripRef = shouldStripReferer(candidate.streamUrl)
        val initialRef = if (stripRef) null else candidate.referer
        val initialOrig = if (stripRef) null else candidate.origin

        val firstTry = executeStreamProbeRequest(candidate.streamUrl, initialRef, initialOrig)
        if (firstTry != null && firstTry.first in 200..299) {
            return candidate.copy(
                referer = initialRef,
                origin = initialOrig,
                isHls = firstTry.second || candidate.isHls
            )
        }

        if (initialRef != null && (firstTry == null || firstTry.first == 403 || firstTry.first == 429)) {
            val secondTry = executeStreamProbeRequest(candidate.streamUrl, referer = null, origin = null)
            if (secondTry != null && secondTry.first in 200..299) {
                return candidate.copy(
                    referer = null,
                    origin = null,
                    isHls = secondTry.second || candidate.isHls
                )
            }
        }
        return null
    }

    private fun executeStreamProbeRequest(
        url: String,
        referer: String?,
        origin: String?
    ): Pair<Int, Boolean>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "*/*")
                if (!url.contains(".m3u8", ignoreCase = true) && !url.contains("playlist", ignoreCase = true)) {
                    setRequestProperty("Range", "bytes=0-1023")
                }
                if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
                if (!origin.isNullOrBlank()) setRequestProperty("Origin", origin)
            }
            val code = conn.responseCode
            if (code !in 200..299) return code to false
            val ct = conn.contentType?.lowercase().orEmpty()
            if (ct.contains("text/html")) return null

            val buf = ByteArray(256)
            val read = conn.inputStream.use { it.read(buf) }
            if (read <= 0) return null
            val prefix = String(buf, 0, read, Charsets.UTF_8).trimStart()
            if (prefix.startsWith("<!DOCTYPE", ignoreCase = true) ||
                prefix.startsWith("<html", ignoreCase = true) ||
                prefix.startsWith("<head", ignoreCase = true)
            ) {
                return null
            }
            val isHls = prefix.startsWith("#EXTM3U") ||
                ct.contains("mpegurl") ||
                url.contains(".m3u8", ignoreCase = true)
            code to isHls
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ── Fast Parallel IMDb -> TMDB ID Resolver (from original.zip/lib/tmdb.ts) ──

    private suspend fun resolveTmdbIdFromImdb(imdbId: String, isSeries: Boolean): String? = coroutineScope {
        if (imdbId.isBlank()) return@coroutineScope null
        imdbToTmdbCache[imdbId]?.let { return@coroutineScope it }

        val kind = if (isSeries) "series" else "movie"
        val flixType = if (isSeries) "tv" else "movie"

        val cinemetaJob = async(Dispatchers.IO) {
            runCatching {
                val body = httpGet("https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json", timeoutMs = 2800)
                if (!body.isNullOrBlank()) {
                    JSONObject(body).optJSONObject("meta")?.optString("moviedb_id")
                        ?.takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
                } else null
            }.getOrNull()
        }

        val flixDbJob = async(Dispatchers.IO) {
            runCatching {
                val body = httpGet(
                    "https://enc-dec.app/db/flix/find?imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&type=$flixType",
                    timeoutMs = 2600
                )
                if (!body.isNullOrBlank()) {
                    val d = JSONObject(body)
                    val item = d.optJSONArray("result")?.optJSONObject(0)
                        ?: d.optJSONObject("result")
                    val rawTmdb = item?.optString("tmdb_id")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: item?.optJSONObject("info")?.optString("tmdb_id")?.takeIf { it.isNotBlank() && it != "null" }
                    rawTmdb?.takeIf { it.all(Char::isDigit) }
                } else null
            }.getOrNull()
        }

        val wikidataJob = async(Dispatchers.IO) {
            runCatching {
                val sparql = "SELECT ?movie ?tv WHERE { ?item wdt:P345 \"$imdbId\". OPTIONAL { ?item wdt:P4947 ?movie. } OPTIONAL { ?item wdt:P4983 ?tv. } } LIMIT 1"
                val enc = URLEncoder.encode(sparql, "UTF-8")
                val body = httpGet(
                    url = "https://query.wikidata.org/sparql?format=json&query=$enc",
                    timeoutMs = 3200,
                    extraHeaders = mapOf(
                        "User-Agent" to WIKIDATA_UA,
                        "Accept" to "application/sparql-results+json"
                    )
                )
                if (!body.isNullOrBlank()) {
                    val row = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0)
                    val movieVal = row?.optJSONObject("movie")?.optString("value")?.takeIf { it.all(Char::isDigit) }
                    val tvVal = row?.optJSONObject("tv")?.optString("value")?.takeIf { it.all(Char::isDigit) }
                    if (isSeries) (tvVal ?: movieVal) else (movieVal ?: tvVal)
                } else null
            }.getOrNull()
        }

        val resolved = listOf(flixDbJob, cinemetaJob, wikidataJob).awaitAll().firstNotNullOfOrNull { it }
        if (!resolved.isNullOrBlank()) {
            imdbToTmdbCache[imdbId] = resolved
        }
        resolved
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
