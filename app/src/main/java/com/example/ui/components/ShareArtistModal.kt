package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.Track
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Universal HTTPS Share & Serverless Device-to-Device Deep Link Helper for SEITHAROL:
 * - Uses `https://seitharol.vercel.app` as the canonical web share & deep-link bridge host.
 * - Personal device-created playlists share ONLY online songs (YouTube / JioSaavn IDs),
 *   strictly excluding offline on-device local audio/video files.
 * - Encodes both the raw YouTube/JioSaavn ID list (`ids=...`) AND a compact GZIP+Base64URL
 *   track metadata bundle (`data=...`) directly in the URL so device-to-device playlist sharing
 *   works 100% serverlessly with instant reconstruction on the receiving device.
 * - Also parses external playlist links from YouTube, YouTube Music, Spotify, and Apple Music.
 */
object SeitharolDeepLinkHelper {
    const val WEB_SHARE_HOST = "https://seitharol.vercel.app"

    fun isOnlineShareableTrack(track: Track): Boolean {
        if (track.isLocalOnlyTrack) return false
        val id = track.id.trim()
        if (id.isBlank()) return false
        val lower = id.lowercase()
        if (lower.startsWith("local_") ||
            lower.startsWith("file_") ||
            lower.startsWith("vidfile_") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://") ||
            lower.startsWith("/")
        ) {
            return false
        }
        return true
    }

    fun normalizeOnlineTrackId(rawId: String): String {
        val trimmed = rawId.trim()
        if (trimmed.startsWith("saavn_", ignoreCase = true)) {
            return "saavn_${trimmed.removePrefix("saavn_").removePrefix("SAAVN_")}"
        }
        return InnerTubeStreamExtractor.extractVideoId(trimmed) ?: trimmed
    }

    fun buildTrackShareUrl(track: Track): String {
        val cleanId = normalizeOnlineTrackId(track.id)
        val encTitle = encodeParam(track.title)
        val encArtist = encodeParam(track.artist)
        return "$WEB_SHARE_HOST/share/track/${encodeParam(cleanId)}?title=$encTitle&artist=$encArtist"
    }

    fun buildTrackShareMessage(track: Track): String {
        val cleanId = InnerTubeStreamExtractor.extractVideoId(track.id)
        val shareUrl = buildTrackShareUrl(track)
        val ytStreamLink = if (!cleanId.isNullOrBlank()) {
            "\nStream link: https://music.youtube.com/watch?v=$cleanId"
        } else ""
        return "Listen to \"${track.title}\" by ${track.artist} on SEITHAROL:\n$shareUrl$ytStreamLink"
    }

    fun buildArtistShareUrl(artistName: String, browseId: String? = null): String {
        val idOrName = encodeParam(browseId?.takeIf { it.isNotBlank() } ?: artistName)
        val encName = encodeParam(artistName)
        return "$WEB_SHARE_HOST/share/artist/$idOrName?name=$encName"
    }

    fun buildArtistShareMessage(artistName: String, browseId: String? = null): String {
        val launcherUrl = buildArtistShareUrl(artistName, browseId)
        val ytChannelLink = if (!browseId.isNullOrBlank() && browseId.startsWith("UC")) {
            "\nChannel: https://music.youtube.com/channel/$browseId"
        } else ""
        return "Check out $artistName on SEITHAROL:\n$launcherUrl$ytChannelLink"
    }

    /**
     * Encodes online tracks into a compact GZIP + URL-safe Base64 string for serverless device-to-device sharing.
     */
    fun encodeServerlessPlaylistPayload(playlistTitle: String, onlineTracks: List<Track>): String {
        return runCatching {
            val root = JSONObject()
            root.put("t", playlistTitle.trim().ifBlank { "Shared Playlist" })
            val items = JSONArray()
            onlineTracks.take(80).forEach { track ->
                val arr = JSONArray()
                arr.put(normalizeOnlineTrackId(track.id))
                arr.put(track.title.take(90))
                arr.put(track.artist.take(70))
                arr.put((track.durationMs / 1000L).toInt().coerceAtLeast(0))
                items.put(arr)
            }
            root.put("i", items)
            val rawBytes = root.toString().toByteArray(Charsets.UTF_8)
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(rawBytes)
            }
            Base64.encodeToString(
                baos.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        }.getOrDefault("")
    }

    fun decodeServerlessPlaylistPayload(encoded: String?): Pair<String?, List<Track>> {
        if (encoded.isNullOrBlank()) return null to emptyList()
        return runCatching {
            val compressed = Base64.decode(encoded.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonStr)
            val title = root.optString("t").takeIf { it.isNotBlank() }
            val items = root.optJSONArray("i") ?: JSONArray()
            val tracks = mutableListOf<Track>()
            for (idx in 0 until items.length()) {
                val arr = items.optJSONArray(idx) ?: continue
                val id = arr.optString(0).trim()
                if (id.isBlank()) continue
                val trackTitle = arr.optString(1).ifBlank { "Track ${idx + 1}" }
                val trackArtist = arr.optString(2).ifBlank { "Artist" }
                val durSec = arr.optInt(3, 210).coerceAtLeast(0)
                val ytId = InnerTubeStreamExtractor.extractVideoId(id)
                val artUrl = if (!ytId.isNullOrBlank()) "https://i.ytimg.com/vi/$ytId/hqdefault.jpg" else ""
                tracks.add(
                    Track(
                        id = id,
                        title = trackTitle,
                        artist = trackArtist,
                        album = "",
                        durationMs = durSec * 1000L,
                        artUrl = artUrl
                    )
                )
            }
            title to tracks
        }.getOrDefault(null to emptyList())
    }

    /**
     * Builds a serverless device-to-device share message for personal playlists.
     * Strictly includes ONLY online songs (YouTube & JioSaavn) and excludes offline on-device local files.
     */
    fun buildPersonalPlaylistShareUrl(playlistTitle: String, tracks: List<Track>): String {
        val onlineTracks = tracks.filter { isOnlineShareableTrack(it) }.distinctBy { normalizeOnlineTrackId(it.id) }
        val cleanTitle = playlistTitle.trim().ifBlank { "Shared Playlist" }
        val encTitle = encodeParam(cleanTitle)
        val idsParam = onlineTracks.take(100).joinToString(",") { normalizeOnlineTrackId(it.id) }
        val payload = encodeServerlessPlaylistPayload(cleanTitle, onlineTracks)
        return buildString {
            append("$WEB_SHARE_HOST/share/playlist/shared?title=$encTitle")
            if (idsParam.isNotBlank()) {
                append("&ids=${encodeParam(idsParam)}")
            }
            if (payload.isNotBlank()) {
                append("&data=$payload")
            }
        }
    }

    fun buildPlaylistShareMessage(
        playlistTitle: String,
        playlistId: String,
        tracks: List<Track> = emptyList()
    ): String {
        val isPersonalPlaylist = playlistId.startsWith("pl_", ignoreCase = true) ||
            playlistId.equals("shared", ignoreCase = true) ||
            tracks.isNotEmpty()
        val onlineTracks = tracks.filter { isOnlineShareableTrack(it) }.distinctBy { normalizeOnlineTrackId(it.id) }

        if (isPersonalPlaylist && onlineTracks.isNotEmpty()) {
            val shareUrl = buildPersonalPlaylistShareUrl(playlistTitle, onlineTracks)
            val ytIds = onlineTracks.mapNotNull { InnerTubeStreamExtractor.extractVideoId(it.id) }
            val ytQuickWatchLink = if (ytIds.isNotEmpty()) {
                "\nYouTube Queue: https://www.youtube.com/watch_videos?video_ids=${ytIds.take(40).joinToString(",")}"
            } else ""
            val trackPreviewLines = onlineTracks.take(8).mapIndexed { i, t ->
                "${i + 1}. ${t.title} — ${t.artist} [${normalizeOnlineTrackId(t.id)}]"
            }.joinToString("\n")
            val moreLine = if (onlineTracks.size > 8) "\n...and ${onlineTracks.size - 8} more online tracks" else ""
            return "Listen to \"$playlistTitle\" (${onlineTracks.size} online songs) on SEITHAROL:\n$shareUrl$ytQuickWatchLink\n\nTracks:\n$trackPreviewLines$moreLine"
        }

        val encTitle = encodeParam(playlistTitle)
        val encId = encodeParam(playlistId)
        val launcherUrl = "$WEB_SHARE_HOST/share/playlist/$encId?title=$encTitle"
        return "Listen to \"$playlistTitle\" on SEITHAROL:\n$launcherUrl"
    }

    fun sharePersonalPlaylistSystemChooser(
        context: Context,
        playlistTitle: String,
        playlistId: String,
        tracks: List<Track> = emptyList(),
        excludedOfflineCount: Int = 0
    ) {
        sharePlaylistSystemChooser(
            context = context,
            playlistTitle = playlistTitle,
            playlistId = playlistId,
            tracks = tracks.filter { isOnlineShareableTrack(it) }
        )
    }

    fun sharePlaylistSystemChooser(
        context: Context,
        playlistTitle: String,
        playlistId: String,
        tracks: List<Track> = emptyList()
    ) {
        runCatching {
            val shareText = buildPlaylistShareMessage(playlistTitle, playlistId, tracks)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_SUBJECT, "$playlistTitle on Seitharol")
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val chooserIntent = Intent.createChooser(sendIntent, "Share Playlist").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        }
    }

    fun shareArtistSystemChooser(context: Context, artistName: String, browseId: String? = null) {
        runCatching {
            val shareText = buildArtistShareMessage(artistName, browseId)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_SUBJECT, "$artistName on Seitharol")
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val chooserIntent = Intent.createChooser(sendIntent, "Share Artist").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        }
    }

    sealed interface DeepLinkTarget {
        data class TrackTarget(
            val trackId: String,
            val title: String,
            val artist: String,
            val artUrl: String
        ) : DeepLinkTarget

        data class ArtistTarget(
            val artistName: String,
            val browseId: String?
        ) : DeepLinkTarget

        data class PlaylistTarget(
            val playlistId: String,
            val title: String,
            val trackIds: List<String> = emptyList(),
            val sharedTracks: List<Track> = emptyList(),
            val externalUrl: String? = null,
            val sourcePlatform: String = when {
                playlistId.startsWith("spotify:", ignoreCase = true) || externalUrl?.contains("spotify", ignoreCase = true) == true -> "SPOTIFY"
                playlistId.startsWith("apple:", ignoreCase = true) || externalUrl?.contains("apple.com", ignoreCase = true) == true -> "APPLE_MUSIC"
                sharedTracks.isNotEmpty() || trackIds.isNotEmpty() || playlistId.equals("shared", ignoreCase = true) -> "SEITHAROL_PERSONAL"
                else -> "YOUTUBE"
            },
            val onlineTracks: List<Track> = if (sharedTracks.isNotEmpty()) {
                sharedTracks
            } else {
                trackIds.map { rawId ->
                    Track(
                        id = rawId,
                        title = "Shared Track",
                        artist = "",
                        album = "",
                        durationMs = 210_000L,
                        artUrl = ""
                    )
                }
            }
        ) : DeepLinkTarget

        data class AudioFileTarget(
            val uri: Uri,
            val title: String,
            val artist: String,
            val durationMs: Long = 0L,
            val album: String = "Local Audio",
            val isVideo: Boolean = false
        ) : DeepLinkTarget

        data class NotFoundTarget(
            val message: String,
            val rawUrl: String = message
        ) : DeepLinkTarget
    }

    fun parseSharedTextOrUrl(rawText: String?): DeepLinkTarget? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()
        if (trimmed.startsWith("spotify:playlist:", ignoreCase = true)) {
            val spotId = trimmed.substringAfter("spotify:playlist:").substringBefore("?").trim()
            if (spotId.isNotBlank()) {
                return DeepLinkTarget.PlaylistTarget(
                    playlistId = "spotify:$spotId",
                    title = "Spotify Playlist",
                    externalUrl = "https://open.spotify.com/playlist/$spotId"
                )
            }
        }
        val urlRegex = Regex("""(https?://[^\s"<>]+|seitharol://[^\s"<>]+)""", RegexOption.IGNORE_CASE)
        val matches = urlRegex.findAll(rawText).map { it.value }.toList()
        for (candidate in matches) {
            val parsedUri = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
            parseUriTarget(parsedUri)?.let { return it }
        }
        val directUri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (directUri != null) {
            parseUriTarget(directUri)?.let { return it }
        }
        return null
    }

    fun parseIncomingIntent(intent: Intent?, context: Context? = null): DeepLinkTarget? {
        if (intent == null) return null
        val action = intent.action
        val mimeType = intent.type?.lowercase().orEmpty()

        if (action == Intent.ACTION_SEND && mimeType.startsWith("text/")) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            parseSharedTextOrUrl(sharedText)?.let { return it }
        }

        val streamUri = if (action == Intent.ACTION_SEND) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        } else null
        val uri: Uri = intent.data ?: streamUri ?: return null

        val scheme = uri.scheme?.lowercase().orEmpty()
        val lowerPath = uri.toString().lowercase()

        val isVideoMime = mimeType.startsWith("video/") || mimeType == "application/mp4"
        val isVideoExtension = lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v") ||
            lowerPath.endsWith(".mkv") || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mov")

        val isAudioMime = mimeType.startsWith("audio/") ||
            mimeType == "application/ogg" ||
            mimeType == "application/x-ogg" ||
            mimeType == "application/flac" ||
            mimeType == "application/x-flac" ||
            mimeType == "application/itunes"
        val isAudioExtension = lowerPath.endsWith(".mp3") || lowerPath.endsWith(".flac") ||
            lowerPath.endsWith(".m4a") || lowerPath.endsWith(".wav") || lowerPath.endsWith(".ogg") ||
            lowerPath.endsWith(".opus") || lowerPath.endsWith(".aac") || lowerPath.endsWith(".wma") ||
            lowerPath.endsWith(".m4b")

        if ((action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND) &&
            (isVideoMime || isVideoExtension || isAudioMime || isAudioExtension ||
                (scheme == "content" && (isVideoMime || isAudioMime)))
        ) {
            val isVideo = isVideoMime || isVideoExtension
            val resolved = extractAudioMetadata(uri, context)
            return DeepLinkTarget.AudioFileTarget(
                uri = uri,
                title = resolved.title,
                artist = resolved.artist,
                durationMs = resolved.durationMs,
                album = if (isVideo) "Local Video" else resolved.album.takeUnless {
                    it.equals("YouTube Video", ignoreCase = true) || it.equals("Local Audio", ignoreCase = true)
                }.orEmpty(),
                isVideo = isVideo
            )
        }

        return parseUriTarget(uri)
    }

    private fun parsePlaylistQueryTarget(uri: Uri, rawIdSegment: String): DeepLinkTarget.PlaylistTarget {
        val rawTitle = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
        val dataParam = uri.getQueryParameter("data")
        val idsParam = uri.getQueryParameter("ids")?.let(::decodeParam).orEmpty()
        val (decodedTitle, decodedTracks) = decodeServerlessPlaylistPayload(dataParam)
        val parsedIds = idsParam
            .split(",", ";", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val finalTitle = rawTitle.ifBlank { decodedTitle.orEmpty() }.ifBlank { "Shared Playlist" }
        return DeepLinkTarget.PlaylistTarget(
            playlistId = rawIdSegment.ifBlank { "shared" },
            title = finalTitle,
            trackIds = parsedIds,
            sharedTracks = decodedTracks
        )
    }

    private fun parseUriTarget(uri: Uri): DeepLinkTarget? {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val segments = uri.pathSegments.orEmpty()

        // Custom scheme: seitharol://track/[id], seitharol://artist/[name], seitharol://playlist/[id]
        if (scheme == "seitharol") {
            return when {
                host == "track" && segments.isNotEmpty() -> {
                    val id = decodeParam(segments.first())
                    val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                    val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                    val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                    DeepLinkTarget.TrackTarget(id, title, artist, art)
                }
                host == "artist" && segments.isNotEmpty() -> {
                    val rawId = decodeParam(segments.first())
                    val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { rawId } ?: rawId
                    DeepLinkTarget.ArtistTarget(
                        artistName = name,
                        browseId = rawId.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                    )
                }
                host == "playlist" -> {
                    val rawId = segments.firstOrNull()?.let(::decodeParam).orEmpty()
                    parsePlaylistQueryTarget(uri, rawId)
                }
                host == "share" && segments.size >= 2 -> {
                    val type = segments[0].lowercase()
                    val id = decodeParam(segments[1])
                    when (type) {
                        "track" -> {
                            val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                            val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                            val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                            DeepLinkTarget.TrackTarget(id, title, artist, art)
                        }
                        "artist" -> {
                            val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { id } ?: id
                            DeepLinkTarget.ArtistTarget(
                                artistName = name,
                                browseId = id.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                            )
                        }
                        "playlist" -> parsePlaylistQueryTarget(uri, id)
                        else -> DeepLinkTarget.NotFoundTarget("404: Unknown share link type '$type'")
                    }
                }
                else -> {
                    val typeParam = uri.getQueryParameter("type")?.lowercase().orEmpty()
                    val idParam = (uri.getQueryParameter("id") ?: uri.getQueryParameter("v"))?.let(::decodeParam).orEmpty()
                    val nameParam = uri.getQueryParameter("name")?.let(::decodeParam).orEmpty()
                    when {
                        typeParam == "playlist" || !uri.getQueryParameter("ids").isNullOrBlank() || !uri.getQueryParameter("data").isNullOrBlank() -> {
                            parsePlaylistQueryTarget(uri, idParam)
                        }
                        (typeParam == "track" || idParam.isNotBlank()) && idParam.isNotBlank() -> {
                            val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                            val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                            val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                            DeepLinkTarget.TrackTarget(idParam, title, artist, art)
                        }
                        typeParam == "artist" || nameParam.isNotBlank() -> {
                            val resolvedName = nameParam.ifBlank { idParam }
                            if (resolvedName.isNotBlank()) {
                                DeepLinkTarget.ArtistTarget(
                                    artistName = resolvedName,
                                    browseId = idParam.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                                )
                            } else null
                        }
                        else -> null
                    }
                }
            }
        }

        if (scheme == "https" || scheme == "http") {
            val shareIdx = segments.indexOfFirst { it.equals("share", ignoreCase = true) }
            if (shareIdx >= 0 && segments.size > shareIdx + 1) {
                val entityType = segments[shareIdx + 1].lowercase()
                val rawId = if (segments.size > shareIdx + 2) decodeParam(segments[shareIdx + 2]) else ""
                when (entityType) {
                    "track" -> {
                        val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                        val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                        val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                        return if (rawId.isNotBlank()) DeepLinkTarget.TrackTarget(rawId, title, artist, art)
                        else DeepLinkTarget.NotFoundTarget("404: Shared track ID is missing.")
                    }
                    "artist" -> {
                        val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { rawId } ?: rawId
                        return if (name.isNotBlank()) {
                            DeepLinkTarget.ArtistTarget(
                                artistName = name,
                                browseId = rawId.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                            )
                        } else DeepLinkTarget.NotFoundTarget("404: Shared artist could not be found.")
                    }
                    "playlist" -> {
                        return parsePlaylistQueryTarget(uri, rawId)
                    }
                    else -> {
                        return DeepLinkTarget.NotFoundTarget("404: Page '/share/$entityType' was not found.")
                    }
                }
            }

            if (host.contains("seitharol.vercel.app") || host.contains("seitharol.app")) {
                if (segments.size >= 2 && segments[0].equals("track", ignoreCase = true)) {
                    val rawId = decodeParam(segments[1])
                    val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                    val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                    val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                    return DeepLinkTarget.TrackTarget(rawId, title, artist, art)
                }
                if (segments.size >= 2 && segments[0].equals("artist", ignoreCase = true)) {
                    val rawId = decodeParam(segments[1])
                    val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { rawId } ?: rawId
                    return DeepLinkTarget.ArtistTarget(
                        artistName = name,
                        browseId = rawId.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                    )
                }
                if (segments.isNotEmpty() && segments[0].equals("playlist", ignoreCase = true)) {
                    val rawId = segments.getOrNull(1)?.let(::decodeParam).orEmpty()
                    return parsePlaylistQueryTarget(uri, rawId)
                }
                val typeParam = uri.getQueryParameter("type")?.lowercase().orEmpty()
                val idParam = (uri.getQueryParameter("id") ?: uri.getQueryParameter("v"))?.let(::decodeParam).orEmpty()
                if (typeParam == "playlist" || !uri.getQueryParameter("ids").isNullOrBlank() || !uri.getQueryParameter("data").isNullOrBlank()) {
                    return parsePlaylistQueryTarget(uri, idParam)
                } else if (typeParam == "track" && idParam.isNotBlank()) {
                    val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                    val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                    val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                    return DeepLinkTarget.TrackTarget(idParam, title, artist, art)
                } else if (typeParam == "artist" && (idParam.isNotBlank() || !uri.getQueryParameter("name").isNullOrBlank())) {
                    val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { idParam } ?: idParam
                    return DeepLinkTarget.ArtistTarget(
                        artistName = name,
                        browseId = idParam.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                    )
                } else if (segments.isNotEmpty()) {
                    return DeepLinkTarget.NotFoundTarget("404: Page '${uri.path}' not found on Seitharol.")
                }
            }

            // YouTube / YouTube Music Playlist & watch_videos links
            if (host.contains("youtube.com") || host.contains("youtu.be")) {
                val videoIdsParam = uri.getQueryParameter("video_ids")
                if (!videoIdsParam.isNullOrBlank()) {
                    val ids = videoIdsParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (ids.isNotEmpty()) {
                        return DeepLinkTarget.PlaylistTarget(
                            playlistId = "yt_queue",
                            title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty().ifBlank { "Imported YouTube Queue" },
                            trackIds = ids,
                            externalUrl = uri.toString()
                        )
                    }
                }
                val listId = uri.getQueryParameter("list")?.trim().orEmpty()
                val ytId = InnerTubeStreamExtractor.extractVideoId(uri.toString())
                if (listId.isNotBlank() && (uri.path?.contains("playlist", ignoreCase = true) == true || ytId.isNullOrBlank())) {
                    return DeepLinkTarget.PlaylistTarget(
                        playlistId = listId,
                        title = "YouTube Playlist",
                        externalUrl = uri.toString()
                    )
                }
                if (!ytId.isNullOrBlank()) {
                    return DeepLinkTarget.TrackTarget(
                        trackId = ytId,
                        title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty(),
                        artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty(),
                        artUrl = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
                    )
                }
            }

            // Spotify Playlist / Album links
            if (host.contains("spotify.com") || host.contains("spotify.link")) {
                val plIdx = segments.indexOfFirst { it.equals("playlist", ignoreCase = true) || it.equals("album", ignoreCase = true) }
                if (plIdx >= 0 && segments.size > plIdx + 1) {
                    val spotId = segments[plIdx + 1]
                    return DeepLinkTarget.PlaylistTarget(
                        playlistId = "spotify:$spotId",
                        title = "Spotify Playlist",
                        externalUrl = uri.toString()
                    )
                }
            }

            // Apple Music Playlist / Album links
            if (host.contains("music.apple.com") || host.contains("itunes.apple.com")) {
                if (segments.any { it.equals("playlist", ignoreCase = true) || it.equals("album", ignoreCase = true) }) {
                    val appleId = segments.lastOrNull().orEmpty()
                    val nameHint = segments.getOrNull(segments.size - 2)
                        ?.replace("-", " ")
                        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        ?: "Apple Music Playlist"
                    return DeepLinkTarget.PlaylistTarget(
                        playlistId = "apple:$appleId",
                        title = nameHint,
                        externalUrl = uri.toString()
                    )
                }
            }
        }
        return null
    }

    private data class ExtractedAudio(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private fun extractAudioMetadata(uri: Uri, context: Context?): ExtractedAudio {
        var title = ""
        var artist = ""
        var album = "Local Audio"
        var durationMs = 0L

        if (context != null) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                    title = it
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                    artist = it
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let {
                    album = it
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    durationMs = it
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (title.isBlank() && context != null && uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (colIdx >= 0) {
                            title = cursor.getString(colIdx).orEmpty()
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (title.isBlank()) {
            title = uri.lastPathSegment.orEmpty()
        }

        val cleanName = title.substringBeforeLast('.')
        if (title.contains('.')) {
            title = cleanName
        }

        if (artist.isBlank() && title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            artist = parts[0].trim()
            title = parts[1].trim()
        }

        if (title.isBlank()) title = "Audio File"
        if (artist.isBlank()) artist = "Local Audio"

        return ExtractedAudio(title, artist, album, durationMs)
    }

    // Encode using %20 instead of '+' so messaging app URL parsers never break the link mid-query
    private fun encodeParam(value: String): String =
        runCatching {
            URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")
        }.getOrDefault(value.trim())

    private fun decodeParam(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * Launches the native Android system share sheet (`Intent.createChooser`) immediately
 * with the `https://seitharol.vercel.app/` artist link, eliminating the fake slide-up sheet.
 */
@Composable
fun ShareArtistModal(
    artistName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(artistName) {
        if (artistName.isNotBlank()) {
            SeitharolDeepLinkHelper.shareArtistSystemChooser(context, artistName)
        }
        onDismiss()
    }
}
