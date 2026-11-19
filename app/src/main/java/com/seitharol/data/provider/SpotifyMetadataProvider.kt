package com.seitharol.data.provider

import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.data.resolver.StreamResolver
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface SpotifyMetadataProvider {
    suspend fun searchTracksWithPreviews(query: String, limit: Int = 10): List<NormalizedTrack>
    suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack?
}

class DefaultSpotifyMetadataProvider : SpotifyMetadataProvider {

    override suspend fun searchTracksWithPreviews(query: String, limit: Int): List<NormalizedTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()

            // Check if the user pasted a direct Spotify track URL or URI
            val spotifyTrackId = extractSpotifyTrackId(clean)
            if (!spotifyTrackId.isNullOrBlank()) {
                resolveSpotifyEmbedTrack(spotifyTrackId)?.let { return@withContext listOf(it) }
            }

            coroutineScope {
                // 1. Real 30-Second Preview Clips (Apple iTunes / Spotify 30s M4A/MP3 preview CDN)
                val previewClipsDeferred = async {
                    runCatching { search30sPreviewClips(clean, limit) }.getOrDefault(emptyList())
                }

                // 2. JioSaavn Direct CDN Streams
                val saavnDeferred = async {
                    runCatching { searchJioSaavnTracks(clean, limit) }.getOrDefault(emptyList())
                }

                // 3. Official YouTube Music Songs (Canonical full-track sourceId)
                val ytmDeferred = async {
                    runCatching {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = limit).map { yt ->
                            NormalizedTrack(
                                source = TrackSource.YOUTUBE,
                                sourceId = yt.videoId,
                                title = yt.title,
                                artists = listOf(yt.artist.removeSuffix(" - Topic").removeSuffix("- Topic").trim()),
                                primaryArtistBrowseId = yt.artistBrowseId,
                                artistAvatarUrl = yt.thumbnailUrl,
                                album = yt.albumName ?: "Single",
                                durationMs = yt.durationSeconds.coerceAtLeast(120) * 1000L,
                                artUrl = yt.thumbnailUrl,
                                isPlayableNatively = true,
                                previewUrl = "ytm_clip:${yt.videoId}"
                            )
                        }
                    }.getOrDefault(emptyList())
                }

                val previewClips = previewClipsDeferred.await()
                val saavnTracks = saavnDeferred.await()
                val ytmTracks = ytmDeferred.await()

                // Pair official YTM / JioSaavn tracks with authentic 30s preview URLs whenever a title+artist match exists
                val enrichedYtm = ytmTracks.map { ytm ->
                    val matching30s = findMatching30sClip(ytm.title, ytm.artists.firstOrNull().orEmpty(), previewClips)
                    if (matching30s != null && !matching30s.previewUrl.isNullOrBlank()) {
                        ytm.copy(
                            previewUrl = matching30s.previewUrl,
                            artUrl = ytm.artUrl.ifBlank { matching30s.artUrl }
                        )
                    } else {
                        ytm
                    }
                }

                val enrichedSaavn = saavnTracks.map { saavn ->
                    val matching30s = findMatching30sClip(saavn.title, saavn.artists.firstOrNull().orEmpty(), previewClips)
                    if (matching30s != null && !matching30s.previewUrl.isNullOrBlank()) {
                        saavn.copy(previewUrl = matching30s.previewUrl)
                    } else {
                        saavn
                    }
                }

                val merged = mutableListOf<NormalizedTrack>()
                for (track in (enrichedYtm + enrichedSaavn + previewClips)) {
                    if (CanonicalAudioResolver.containsBannedModifier(clean, "${track.title} ${track.artists.joinToString(" ")}")) {
                        continue
                    }
                    val normT = CanonicalAudioResolver.normalizeText(track.title)
                    val normA = CanonicalAudioResolver.normalizeText(track.artists.firstOrNull().orEmpty())
                    val exists = merged.any {
                        CanonicalAudioResolver.normalizeText(it.title) == normT &&
                            CanonicalAudioResolver.normalizeText(it.artists.firstOrNull().orEmpty()) == normA
                    }
                    if (!exists && merged.size < limit) {
                        merged.add(track)
                    }
                }
                merged
            }
        }

    private fun findMatching30sClip(
        targetTitle: String,
        targetArtist: String,
        clips: List<NormalizedTrack>
    ): NormalizedTrack? {
        val normTargetTitle = CanonicalAudioResolver.normalizeText(targetTitle)
        val normTargetArtist = CanonicalAudioResolver.normalizeText(targetArtist)
        if (normTargetTitle.isBlank()) return null
        return clips.firstOrNull { clip ->
            val clipTitle = CanonicalAudioResolver.normalizeText(clip.title)
            val clipArtist = CanonicalAudioResolver.normalizeText(clip.artists.firstOrNull().orEmpty())
            val titleMatches = clipTitle == normTargetTitle ||
                (clipTitle.length >= 4 && normTargetTitle.length >= 4 && (clipTitle.contains(normTargetTitle) || normTargetTitle.contains(clipTitle)))
            val artistMatches = normTargetArtist.isBlank() ||
                clipArtist.contains(normTargetArtist) ||
                normTargetArtist.contains(clipArtist)
            titleMatches && artistMatches && !clip.previewUrl.isNullOrBlank()
        }
    }

    /**
     * Queries 30-second official audio preview clips (30s M4A/MP3 preview URLs) with full track duration metadata.
     */
    private fun search30sPreviewClips(query: String, limit: Int): List<NormalizedTrack> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=${limit.coerceIn(5, 20)}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            }
            if (conn.responseCode !in 200..299) return emptyList()
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val results = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<NormalizedTrack>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val previewUrl = obj.optString("previewUrl").trim()
                val title = obj.optString("trackName").trim()
                val artist = obj.optString("artistName").trim()
                if (previewUrl.isBlank() || title.isBlank() || artist.isBlank()) continue
                val trackId = obj.optLong("trackId", 0L).takeIf { it > 0L }?.toString() ?: "spot30_${title.hashCode()}"
                val album = obj.optString("collectionName", "Single")
                val durationMs = obj.optLong("trackTimeMillis", 210_000L).coerceAtLeast(60_000L)
                val artUrl = obj.optString("artworkUrl100")
                    .replace("100x100bb", "600x600bb")
                    .replace("http://", "https://")
                out.add(
                    NormalizedTrack(
                        source = TrackSource.SPOTIFY,
                        sourceId = "spot30_$trackId",
                        title = title,
                        artists = listOf(artist),
                        artistAvatarUrl = artUrl,
                        album = album,
                        durationMs = durationMs,
                        artUrl = artUrl,
                        isPlayableNatively = false,
                        previewUrl = previewUrl
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun searchJioSaavnTracks(query: String, limit: Int): List<NormalizedTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=android&q=$encoded&n=$limit"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 4500
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<NormalizedTrack>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title", obj.optString("song"))
                .replace("&quot;", "\"").replace("&amp;", "&")
            val moreInfo = obj.optJSONObject("more_info")
            val durationSec = moreInfo?.optString("duration")?.toLongOrNull() ?: 210L
            val artist = moreInfo?.optJSONObject("artistMap")
                ?.optJSONArray("primary_artists")
                ?.optJSONObject(0)
                ?.optString("name")
                ?: obj.optString("subtitle").ifBlank { "Unknown Artist" }
            val image = obj.optString("image").replace("150x150", "500x500")

            val encryptedUrl = obj.optString("encrypted_media_url").ifBlank {
                moreInfo?.optString("encrypted_media_url").orEmpty()
            }
            val directCdnUrl = if (encryptedUrl.isNotBlank()) {
                StreamResolver.decryptJioSaavnUrl(encryptedUrl, AudioQuality.LOW)
            } else null

            out.add(
                NormalizedTrack(
                    source = TrackSource.JIOSAAVN,
                    sourceId = id,
                    title = title,
                    artists = listOf(artist),
                    artistAvatarUrl = image,
                    album = moreInfo?.optString("album") ?: "Single",
                    durationMs = durationSec * 1000L,
                    artUrl = image,
                    isPlayableNatively = true,
                    previewUrl = directCdnUrl ?: "saavn_clip:$id"
                )
            )
        }
        return out
    }

    private fun extractSpotifyTrackId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("spotify:track:")) {
            return trimmed.removePrefix("spotify:track:").substringBefore("?").trim().takeIf { it.isNotBlank() }
        }
        val match = Regex("open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?track/([a-zA-Z0-9]{15,25})").find(trimmed)
        return match?.groupValues?.getOrNull(1)
    }

    override suspend fun resolveSpotifyEmbedTrack(spotifyTrackId: String): NormalizedTrack? =
        withContext(Dispatchers.IO) {
            try {
                val cleanId = spotifyTrackId.substringBefore("?").trim()
                if (cleanId.isBlank()) return@withContext null
                val url = "https://open.spotify.com/embed/track/$cleanId"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    setRequestProperty("Referer", "https://open.spotify.com/")
                }
                if (conn.responseCode !in 200..299) return@withContext null
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                val start = html.indexOf(marker)
                if (start == -1) return@withContext null
                val jsonStart = start + marker.length
                val jsonEnd = html.indexOf("</script>", jsonStart)
                if (jsonEnd == -1) return@withContext null
                val entity = JSONObject(html.substring(jsonStart, jsonEnd).trim())
                    .optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity") ?: return@withContext null

                val title = entity.optString("title", entity.optString("name", "")).trim()
                if (title.isBlank()) return@withContext null
                val artists = mutableListOf<String>()
                val artistsArr = entity.optJSONArray("artists")
                if (artistsArr != null) {
                    for (i in 0 until artistsArr.length()) {
                        val name = artistsArr.optJSONObject(i)?.optString("name").orEmpty().trim()
                        if (name.isNotBlank()) artists.add(name)
                    }
                }
                if (artists.isEmpty()) {
                    val sub = entity.optString("subtitle", "").trim()
                    if (sub.isNotBlank()) artists.add(sub) else artists.add("Spotify Artist")
                }
                val durationMs = entity.optLong("duration", 210_000L).coerceAtLeast(60_000L)
                val previewUrl = entity.optJSONObject("audioPreview")?.optString("url").orEmpty()
                val artUrl = entity.optJSONObject("visualIdentity")
                    ?.optJSONObject("image")
                    ?.optJSONArray("sources")
                    ?.optJSONObject(0)
                    ?.optString("url")
                    ?: entity.optJSONObject("coverArt")
                        ?.optJSONArray("sources")
                        ?.optJSONObject(0)
                        ?.optString("url")
                    ?: ""

                NormalizedTrack(
                    source = TrackSource.SPOTIFY,
                    sourceId = cleanId,
                    title = title,
                    artists = artists,
                    artistAvatarUrl = artUrl,
                    album = "Spotify Single",
                    durationMs = durationMs,
                    artUrl = artUrl,
                    isPlayableNatively = false,
                    previewUrl = previewUrl.ifBlank { null }
                )
            } catch (_: Exception) {
                null
            }
        }
}
