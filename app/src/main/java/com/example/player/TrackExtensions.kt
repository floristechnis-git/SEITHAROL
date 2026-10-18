package com.example.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(streamUri ?: if (audioUrl.isNotBlank()) Uri.parse(audioUrl) else Uri.parse("resolver://track/$id"))
        .setMediaMetadata(metadata)
        .setTag(this) // Preserves full domain object
        .build()
}
