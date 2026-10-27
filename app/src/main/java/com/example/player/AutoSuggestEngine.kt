package com.example.player

import com.example.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SuggestedPlaylist(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artUrl: String = "",
    val thumbnailUrl: String = artUrl,
    val trackCount: Int = 0,
    val tracks: List<Track> = emptyList()
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

    fun getPlayedIds(): Set<String> = playedIds.toSet()

    fun getPlayedSignatures(): Set<String> = playedSignatures.toSet()

    fun toggleAutoSuggest() {
        _isAutoSuggestEnabled.value = !_isAutoSuggestEnabled.value
    }

    fun setAutoSuggestEnabled(enabled: Boolean) {
        _isAutoSuggestEnabled.value = enabled
    }

    /**
     * Fetches non-repeating related tracks (or videos if seed is a video) and playlists
     * like YouTube Music's Up Next radio.
     * Note: When [requireAutoSuggestEnabled] is true (e.g. when the last track ends and
     * auto-appending into the queue is requested), returns emptyList() if the AutoSuggest
     * toggle is off. When false (for the UI suggestion display div), always returns
     * recommendations so the user can view and manually add them.
     */
    suspend fun fetchNextSuggestions(
        currentTrack: Track,
        currentQueue: List<Track>,
        maxTracksToAppend: Int = 10,
        requireAutoSuggestEnabled: Boolean = true
    ): List<Track> {
        // Never suggest for a local single file
        val isLocalSingle = currentTrack.isLocalOnlyTrack && currentQueue.all { it.isLocalOnlyTrack }
        if (isLocalSingle) return emptyList()

        markTrackPlayed(currentTrack)

        val queuedIds = currentQueue.mapTo(HashSet()) { it.id }
        val queuedSignatures = currentQueue.mapTo(HashSet()) { it.signature() }

        // Update suggested playlists based on current vibe
        val playlists = repository.getRelatedPlaylists(currentTrack)
        _suggestedPlaylists.value = playlists

        if (requireAutoSuggestEnabled && !_isAutoSuggestEnabled.value) return emptyList()

        val isCurrentVideo = currentTrack.isVideoTrack
        val candidates = (repository.getRelatedTracks(currentTrack) + playlists.flatMap { it.tracks })
            .filter { cand ->
                !cand.isLocalOnlyTrack && if (isCurrentVideo) cand.isVideoTrack else !cand.isVideoTrack
            }

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
