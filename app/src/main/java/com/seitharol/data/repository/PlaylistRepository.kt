package com.seitharol.data.repository

import com.seitharol.data.resolver.CandidateStream
import com.seitharol.ui.home.PlaylistSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class UserPlaylistEntity(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val tracks: List<CandidateStream> = emptyList()
) {
    fun toSummary(): PlaylistSummary = PlaylistSummary(
        id = id,
        title = title,
        trackCount = tracks.size,
        firstVideoThumbnailUrl = tracks.firstOrNull()?.thumbnailUrl,
        isLikedSongsCard = id == "liked"
    )
}

class PlaylistRepository {
    private val _playlists = MutableStateFlow<List<UserPlaylistEntity>>(
        listOf(
            UserPlaylistEntity(id = "liked", title = "Liked Songs"),
            UserPlaylistEntity(id = "downloads", title = "Downloads")
        )
    )
    val playlists: StateFlow<List<UserPlaylistEntity>> = _playlists.asStateFlow()

    fun createPlaylistAndAddTrack(title: String, initialTrack: CandidateStream? = null): UserPlaylistEntity {
        val cleanTitle = title.trim().ifEmpty { "New Playlist" }
        val newPlaylist = UserPlaylistEntity(
            title = cleanTitle,
            tracks = if (initialTrack != null) listOf(initialTrack) else emptyList()
        )
        _playlists.update { current -> current + newPlaylist }
        return newPlaylist
    }

    fun addTrackToPlaylist(playlistId: String, track: CandidateStream) {
        _playlists.update { list ->
            list.map { playlist ->
                if (playlist.id == playlistId && playlist.tracks.none { it.videoId == track.videoId }) {
                    playlist.copy(tracks = playlist.tracks + track)
                } else {
                    playlist
                }
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, videoId: String) {
        _playlists.update { list ->
            list.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(tracks = playlist.tracks.filterNot { it.videoId == videoId })
                } else {
                    playlist
                }
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId == "liked" || playlistId == "downloads") return
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
    }
}
