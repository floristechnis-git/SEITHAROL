package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.example.data.model.Track

fun Track.toMediaItem(streamUri: Uri? = null): MediaItem {
    val safeTitle = title.ifBlank { "Unknown Track" }
    val safeArtist = artist.ifBlank { "Unknown Artist" }

    val extras = Bundle().apply {
        putString("track_id", id)
        putString("raw_title", safeTitle)
        putString("raw_artist", safeArtist)
        putString("genre", genre)
        putString("codec", codec)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(safeTitle)
        .setArtist(safeArtist)
        .setAlbumTitle(album)
        .setArtworkUri(if (artUrl.isNotBlank()) Uri.parse(artUrl) else null)
        .setExtras(extras)
        .build()

    val effectiveUri = streamUri ?: if (audioUrl.isNotBlank()) Uri.parse(audioUrl) else Uri.parse("resolver://track/$id")
    val uriString = effectiveUri.toString().lowercase()

    val builder = MediaItem.Builder()
        .setMediaId(id)
        .setUri(effectiveUri)
        .setMediaMetadata(metadata)
        .setTag(this)

    // Only set explicit APPLICATION_M3U8 for HLS manifests; let Media3's DefaultExtractorsFactory
    // auto-sniff MP4, Fragmented M4A, WebM, Opus, and FLAC container headers natively.
    if ((uriString.contains(".m3u8") ||
            uriString.contains("manifest/hls") ||
            uriString.contains("manifest.googlevideo.com") ||
            uriString.contains("hls_variant") ||
            uriString.contains("hls_playlist")) &&
        com.seitharol.data.resolver.InnerTubeStreamExtractor.isHlsSupported
    ) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }

    return builder.build()
}
