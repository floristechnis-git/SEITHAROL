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
 * Single Raw Movie Streamer (VidLink Direct Raw) ported cleanly from Mami/SEITHAROL+tv.
 * - Keyless movie search via Stremio Cinemeta + IMDb Suggestion CDN.
 * - IMDb -> TMDB ID resolution via local fast cache, Cinemeta meta, and Wikidata SPARQL.
 * - Extracts ONLY VidLink Direct Raw MP4 / Master HLS stream for direct playback inside SEITHAROL's ExoPlayer.
 */
object VidLinkRawMovieStreamer {
    private const val TAG = "VidLinkRawMovie"
    const val MOVIE_ID_PREFIX = "movie_vidlink_"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    data class RawMovieStream(
        val streamUrl: String,
        val referer: String?,
        val origin: String?,
        val isHls: Boolean,
        val qualityLabel: String,
        val captionVttUrl: String? = null
    )

    private val imdbToTmdbCache = ConcurrentHashMap<String, String>(
        mapOf(
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
            "tt1649418" to "725201"    // The Gray Man
        )
    )

    private val streamCache = ConcurrentHashMap<String, Pair<Long, RawMovieStream>>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    fun isMovieTrack(track: Track?): Boolean {
        if (track == null) return false
        return track.id.startsWith(MOVIE_ID_PREFIX) ||
            track.genre.equals("Raw Movie", ignoreCase = true)
    }

    /**
     * Searches movies using keyless Cinemeta catalog + IMDb suggestion API.
     * When query is blank, returns trending popular movies from Cinemeta.
     */
    suspend fun searchMovies(query: String, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        val results = mutableListOf<Track>()
        val seenImdb = HashSet<String>()

        // 1. Cinemeta Movie Catalog / Search
        runCatching {
            val url = if (clean.isBlank()) {
                "https://v3-cinemeta.strem.io/catalog/movie/top.json"
            } else {
                val enc = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
                "https://v3-cinemeta.strem.io/catalog/movie/top/search=$enc.json"
            }
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
                    val moviedbId = item.optString("moviedb_id").takeIf { it.isNotBlank() && it != "null" }
                    if (!moviedbId.isNullOrBlank()) {
                        imdbToTmdbCache[imdb] = moviedbId
                    }
                    val poster = item.optString("poster").ifBlank {
                        "https://images.metahub.space/poster/medium/$imdb/img"
                    }
                    val runtimeStr = item.optString("runtime")
                    val durationMs = parseRuntimeMs(runtimeStr)
                    val rating = item.optString("imdbRating")
                    val subtitle = buildString {
                        append("Movie")
                        if (year.isNotBlank()) append(" • $year")
                        if (rating.isNotBlank()) append(" • IMDb $rating")
                    }
                    results.add(
                        Track(
                            id = encodeMovieTrackId(imdb, moviedbId),
                            title = title,
                            artist = subtitle,
                            album = "Raw Movie",
                            durationMs = durationMs,
                            artUrl = poster,
                            genre = "Raw Movie",
                            itag = 18,
                            codec = "MP4",
                            isOfficial = true
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Cinemeta search failed: ${it.message}") }

        // 2. Supplement with IMDb Suggestion CDN if query is present and results are sparse
        if (clean.length >= 2 && results.size < 8) {
            runCatching {
                val first = clean.lowercase().firstOrNull { it.isLetterOrDigit() } ?: 'a'
                val enc = URLEncoder.encode(clean.lowercase(), "UTF-8").replace("+", "%20")
                val body = httpGet("https://v3.sg.media-imdb.com/suggestion/$first/$enc.json", timeoutMs = 4000)
                if (!body.isNullOrBlank()) {
                    val arr = JSONObject(body).optJSONArray("d") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        if (results.size >= limit) break
                        val item = arr.optJSONObject(i) ?: continue
                        val imdb = item.optString("id")
                        val qid = item.optString("qid").lowercase()
                        if (!imdb.startsWith("tt") || (qid.isNotBlank() && qid != "movie" && qid != "tvmovie")) continue
                        if (!seenImdb.add(imdb)) continue
                        val title = item.optString("l").trim()
                        if (title.isBlank()) continue
                        val year = item.optInt("y", 0).takeIf { it > 1900 }?.toString().orEmpty()
                        val poster = item.optJSONObject("i")?.optString("imageUrl").orEmpty().ifBlank {
                            "https://images.metahub.space/poster/medium/$imdb/img"
                        }
                        results.add(
                            Track(
                                id = encodeMovieTrackId(imdb, imdbToTmdbCache[imdb]),
                                title = title,
                                artist = if (year.isNotBlank()) "Movie • $year" else "Movie",
                                album = "Raw Movie",
                                durationMs = 7200_000L,
                                artUrl = poster,
                                genre = "Raw Movie",
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
     * Resolves a direct raw playable video stream (MP4 or Master HLS) for a movie Track
     * using ONLY VidLink Direct Raw (enc-dec.app -> vidlink.pro/api/b/movie/{encId}).
     */
    suspend fun resolveRawMovieStream(track: Track, forceRefresh: Boolean = false): RawMovieStream? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                streamCache[track.id]?.let { (ts, cached) ->
                    if (now - ts < STREAM_CACHE_TTL_MS) return@withContext cached
                }
            }

            val (imdbId, cachedTmdb) = decodeMovieTrackId(track.id)
            val tmdbId = cachedTmdb?.takeIf { it.isNotBlank() }
                ?: resolveTmdbIdFromImdb(imdbId)
                ?: return@withContext null

            // Step 1: Encrypt TMDB ID via enc-dec.app
            val encBody = httpGet(
                url = "https://enc-dec.app/api/enc-vidlink?text=${URLEncoder.encode(tmdbId, "UTF-8")}",
                timeoutMs = 5000
            ) ?: return@withContext null
            val encId = runCatching { JSONObject(encBody).optString("result") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@withContext null

            // Step 2: Fetch VidLink raw stream manifest
            val apiUrl = "https://vidlink.pro/api/b/movie/$encId"
            val streamBody = httpGet(
                url = apiUrl,
                timeoutMs = 6000,
                extraHeaders = mapOf(
                    "Referer" to "https://vidlink.pro/",
                    "Origin" to "https://vidlink.pro",
                    "Accept" to "application/json"
                )
            ) ?: return@withContext null

            val root = runCatching { JSONObject(streamBody) }.getOrNull() ?: return@withContext null
            val streamObj = root.optJSONObject("stream") ?: root

            // Optional VTT caption URL (if VidLink provides English/default captions)
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

            // Pick best direct stream: prefer H.264 MP4 (1080p/720p) or adaptive Master HLS (.m3u8)
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
                    val score = when {
                        qKey.contains("1080") -> 1080
                        qKey.contains("720") -> 720
                        qKey.contains("2160") || qKey.contains("4k", ignoreCase = true) -> 2160
                        qKey.contains("480") -> 480
                        else -> 700
                    } - (if (isHevc) 600 else 0)
                    if (score > bestMp4Score) {
                        bestMp4Score = score
                        bestMp4Url = rawUrl
                        bestMp4Label = if (qKey.contains("p", ignoreCase = true)) "$qKey Raw" else "${qKey}p Raw"
                    }
                }
            }

            val playlistUrl = streamObj.optString("playlist").trim().takeIf { it.startsWith("http") }

            val chosenUrl = bestMp4Url ?: playlistUrl ?: return@withContext null
            val isHls = chosenUrl.contains(".m3u8", ignoreCase = true) || chosenUrl == playlistUrl
            val isNoRefererHost = chosenUrl.contains("hakunaymatata.com", ignoreCase = true) ||
                chosenUrl.contains("aoneroom.com", ignoreCase = true)

            val resolved = RawMovieStream(
                streamUrl = chosenUrl,
                referer = if (isNoRefererHost) null else "https://vidlink.pro/",
                origin = if (isNoRefererHost) null else "https://vidlink.pro",
                isHls = isHls,
                qualityLabel = if (isHls) "Adaptive HLS" else bestMp4Label,
                captionVttUrl = captionVtt
            )
            streamCache[track.id] = now to resolved
            resolved
        }

    private fun resolveTmdbIdFromImdb(imdbId: String): String? {
        if (imdbId.isBlank()) return null
        imdbToTmdbCache[imdbId]?.let { return it }

        // 1. Cinemeta meta endpoint (returns moviedb_id directly for most movies)
        runCatching {
            val body = httpGet("https://v3-cinemeta.strem.io/meta/movie/$imdbId.json", timeoutMs = 3500)
            if (!body.isNullOrBlank()) {
                val moviedbId = JSONObject(body).optJSONObject("meta")?.optString("moviedb_id")
                    ?.takeIf { it.isNotBlank() && it != "null" && it.all(Char::isDigit) }
                if (!moviedbId.isNullOrBlank()) {
                    imdbToTmdbCache[imdbId] = moviedbId
                    return moviedbId
                }
            }
        }

        // 2. Wikidata SPARQL fallback (P345 IMDb -> P4947 TMDB movie ID)
        runCatching {
            val sparql = "SELECT ?t WHERE { ?item wdt:P345 \"$imdbId\" . ?item wdt:P4947 ?t . } LIMIT 1"
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

    private fun encodeMovieTrackId(imdbId: String, tmdbId: String?): String {
        return if (!tmdbId.isNullOrBlank()) {
            "$MOVIE_ID_PREFIX${imdbId}_$tmdbId"
        } else {
            "$MOVIE_ID_PREFIX$imdbId"
        }
    }

    private fun decodeMovieTrackId(trackId: String): Pair<String, String?> {
        val raw = trackId.removePrefix(MOVIE_ID_PREFIX)
        val parts = raw.split("_")
        val imdb = parts.getOrNull(0).orEmpty()
        val tmdb = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        return imdb to tmdb
    }

    private fun parseRuntimeMs(runtime: String): Long {
        val mins = Regex("(\\d+)").find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 120L
        return (mins.coerceIn(15L, 360L)) * 60_000L
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
