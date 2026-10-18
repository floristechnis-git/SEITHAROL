package com.example.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel

/**
 * Album & Playlist Unified Screen:
 * Displays track lists, cover art, and playback controls for Albums (MPREb_... / OLAK5uy_...)
 * and Playlists / YouTube Mixes (VL... / PL... / RD...).
 */
@Composable
fun AlbumPlaylistScreen(
    id: String,
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    PlaylistScreen(
        playlistId = id,
        viewModel = viewModel,
        playerState = playerState,
        onBack = onBack,
        onAddToPlaylist = onAddToPlaylist,
        modifier = modifier
    )
}
