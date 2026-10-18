package com.example.player

import com.example.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SuggestedPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val tracks: List<Track>
)

interface RecommendationRepository {
    suspend fun getRelatedTracks(seedTrack: Track): List<Track>
    suspend fun getRelatedPlaylists(seedTrack: Track): List<SuggestedPlaylist>
}

class AutoSuggestQueueManager(
    private val repository: RecommendationRepository
) {
    private val _isAutoSuggestEnabled = MutableStateFlow(true)
    val isAutoSuggestEnabled: StateFlow<Boolean> = _isAutoSuggestEnabled.asStateFlow()

    private val _suggestedPlaylists = MutableStateFlow<List<SuggestedPlaylist>>(emptyList())
    val suggestedPlaylists: StateFlow<List<SuggestedPlaylist>> = _suggestedPlaylists.asStateFlow()

    // Tracks both IDs and normalized "title|artist" keys to prevent cross-source duplicates
    private val playedSignatures = LinkedHashSet<String>()
    private val playedIds = LinkedHashSet<String>()

    private fun Track.signature(): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    fun markTrackPlayed(track: Track) {
        playedIds.add(track.id)
        playedSignatures.add(track.signature())
    }

    fun toggleAutoSuggest() {
        _isAutoSuggestEnabled.value = !_isAutoSuggestEnabled.value
    }

    fun setAutoSuggestEnabled(enabled: Boolean) {
        _isAutoSuggestEnabled.value = enabled
    }

    /**
     * Fetches non-repeating related tracks and playlists like YouTube Music's Up Next radio.
     */
    suspend fun fetchNextSuggestions(
        currentTrack: Track,
        currentQueue: List<Track>,
        maxTracksToAppend: Int = 10
    ): List<Track> {
        markTrackPlayed(currentTrack)

        val queuedIds = currentQueue.mapTo(HashSet()) { it.id }
        val queuedSignatures = currentQueue.mapTo(HashSet()) { it.signature() }

        // Update suggested playlists based on current vibe
        val playlists = repository.getRelatedPlaylists(currentTrack)
        _suggestedPlaylists.value = playlists

        if (!_isAutoSuggestEnabled.value) return emptyList()

        val candidates = repository.getRelatedTracks(currentTrack) +
            playlists.flatMap { it.tracks }

        val freshTracks = mutableListOf<Track>()
        for (candidate in candidates) {
            val sig = candidate.signature()
            val isAlreadyPlayed = candidate.id in playedIds || sig in playedSignatures
            val isAlreadyQueued = candidate.id in queuedIds || sig in queuedSignatures

            if (!isAlreadyPlayed && !isAlreadyQueued && candidate.title.isNotBlank()) {
                freshTracks.add(candidate)
                queuedIds.add(candidate.id)
                queuedSignatures.add(sig)
                if (freshTracks.size >= maxTracksToAppend) break
            }
        }

        return freshTracks
    }

    fun resetSessionHistory() {
        playedIds.clear()
        playedSignatures.clear()
    }
}
