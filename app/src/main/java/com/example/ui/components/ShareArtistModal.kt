package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.theme.*
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Universal HTTPS Share & Deep Link Helper for SEITHAROL:
 * - Replaces raw `seitharol://` text in shared messages (which messaging platforms like WhatsApp,
 *   Telegram, Instagram, and SMS treat as non-clickable plain text) with clean RFC-3986 `https://`
 *   URLs (`https://seitharol.app/share/...` and `https://music.youtube.com/watch?v=...`) that are
 *   100% clickable across every messaging platform.
 * - Supports opening shared tracks/artists via `Intent.ACTION_VIEW` (`https://`, `http://`, `seitharol://`),
 *   `Intent.ACTION_SEND` ("Share to Seitharol" from WhatsApp/Telegram), and pasting into Search.
 */
object SeitharolDeepLinkHelper {
    const val WEB_SHARE_HOST = "https://seitharol.app"

    fun buildTrackShareUrl(track: Track): String {
        val cleanId = InnerTubeStreamExtractor.extractVideoId(track.id) ?: track.id.trim()
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

    fun buildPlaylistShareMessage(playlistTitle: String, playlistId: String): String {
        val encTitle = encodeParam(playlistTitle)
        val encId = encodeParam(playlistId)
        val launcherUrl = "$WEB_SHARE_HOST/share/playlist/$encId?title=$encTitle"
        return "Listen to \"$playlistTitle\" on SEITHAROL:\n$launcherUrl"
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

        data class AudioFileTarget(
            val uri: Uri,
            val title: String,
            val artist: String,
            val durationMs: Long = 0L,
            val album: String = "Local Audio",
            val isVideo: Boolean = false
        ) : DeepLinkTarget
    }

    fun parseSharedTextOrUrl(rawText: String?): DeepLinkTarget? {
        if (rawText.isNullOrBlank()) return null
        val urlRegex = Regex("""(https?://[^\s"<>]+|seitharol://[^\s"<>]+)""", RegexOption.IGNORE_CASE)
        val matches = urlRegex.findAll(rawText).map { it.value }.toList()
        for (candidate in matches) {
            val parsedUri = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
            parseUriTarget(parsedUri)?.let { return it }
        }
        val directUri = runCatching { Uri.parse(rawText.trim()) }.getOrNull()
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

        val uri: Uri = intent.data ?: if (action == Intent.ACTION_SEND) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        } else null ?: return null

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
                album = if (isVideo) "YouTube Video" else resolved.album,
                isVideo = isVideo
            )
        }

        return parseUriTarget(uri)
    }

    private fun parseUriTarget(uri: Uri): DeepLinkTarget? {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val segments = uri.pathSegments.orEmpty()

        // Custom scheme: seitharol://track/[id] or seitharol://share/track/[id] or seitharol://artist/[name]
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
                host == "share" && segments.size >= 2 -> {
                    val type = segments[0].lowercase()
                    val id = decodeParam(segments[1])
                    if (type == "track") {
                        val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                        val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                        val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                        DeepLinkTarget.TrackTarget(id, title, artist, art)
                    } else if (type == "artist") {
                        val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { id } ?: id
                        DeepLinkTarget.ArtistTarget(
                            artistName = name,
                            browseId = id.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                        )
                    } else null
                }
                else -> null
            }
        }

        // HTTPS / HTTP routes: /share/track/[id], /share/artist/[id], and YouTube / YouTube Music watch links
        if (scheme == "https" || scheme == "http") {
            val shareIdx = segments.indexOfFirst { it.equals("share", ignoreCase = true) }
            if (shareIdx >= 0 && segments.size > shareIdx + 2) {
                val entityType = segments[shareIdx + 1].lowercase()
                val rawId = decodeParam(segments[shareIdx + 2])
                if (entityType == "track") {
                    val title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty()
                    val artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
                    val art = uri.getQueryParameter("art")?.let(::decodeParam).orEmpty()
                    return DeepLinkTarget.TrackTarget(rawId, title, artist, art)
                } else if (entityType == "artist") {
                    val name = uri.getQueryParameter("name")?.let(::decodeParam)?.ifBlank { rawId } ?: rawId
                    return DeepLinkTarget.ArtistTarget(
                        artistName = name,
                        browseId = rawId.takeIf { it.startsWith("UC") || it.startsWith("MPLA") }
                    )
                }
            }

            if (host.contains("youtube.com") || host.contains("youtu.be")) {
                val ytId = InnerTubeStreamExtractor.extractVideoId(uri.toString())
                if (!ytId.isNullOrBlank()) {
                    return DeepLinkTarget.TrackTarget(
                        trackId = ytId,
                        title = uri.getQueryParameter("title")?.let(::decodeParam).orEmpty(),
                        artist = uri.getQueryParameter("artist")?.let(::decodeParam).orEmpty(),
                        artUrl = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareArtistModal(
    artistName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modalSheetState = rememberModalBottomSheetState()

    val shareUrl = remember(artistName) {
        SeitharolDeepLinkHelper.buildArtistShareUrl(artistName)
    }

    val shareText = remember(artistName) {
        SeitharolDeepLinkHelper.buildArtistShareMessage(artistName)
    }

    val cachedAvatar = remember(artistName) {
        ArtistAvatarCache.get(artistName).orEmpty()
    }

    fun triggerChooserShare() {
        runCatching {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_SUBJECT, "$artistName on Seitharol")
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share $artistName via")
            context.startActivity(shareIntent)
        }
        onDismiss()
    }

    fun shareToWhatsApp() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(intent)
            onDismiss()
        } catch (_: Exception) {
            triggerChooserShare()
        }
    }

    fun shareToTelegram() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("org.telegram.messenger")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(intent)
            onDismiss()
        } catch (_: Exception) {
            triggerChooserShare()
        }
    }

    fun copyToClipboard() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Artist Link", shareUrl)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Shareable HTTPS link copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy link", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalSheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("share_artist_modal"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Share Artist",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Artist Preview Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cachedAvatar.isNotBlank()) {
                        AsyncImage(
                            model = cachedAvatar,
                            contentDescription = artistName,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val initial = artistName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
                            Text(
                                text = initial,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artistName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = SyneFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = shareUrl,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Row of share targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Telegram
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { shareToTelegram() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = Color(0xFF29B6F6)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Telegram",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Telegram",
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // WhatsApp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { shareToWhatsApp() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = Color(0xFF25D366)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "WhatsApp",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "WhatsApp",
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Copy Link
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { copyToClipboard() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Copy Link",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Copy Link",
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // More
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { triggerChooserShare() }
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "More",
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
