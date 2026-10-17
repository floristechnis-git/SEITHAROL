package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadTier
import com.example.data.model.Playlist
import com.example.data.model.ResolverNode
import com.example.data.model.Track
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MusicRepository
import com.example.network.NetworkClients
import com.example.player.AudioPlayerManager
import com.example.player.EqualizerPreset
import com.example.player.PlayerState
import com.example.resolver.CircuitBreakerState
import com.example.resolver.ResolverEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SearchFilter(val label: String) {
    ALL("All"),
    OFFLINE_ONLY("Offline Only"),
    LIKED("Liked"),
    FOLK("Folk / Heritage"),
    SYNTHWAVE("Synthwave"),
    LOFI("Lo-Fi"),
    ROCK("Rock"),
    ACOUSTIC("Acoustic")
}

class CascadeViewModel(
    private val musicRepository: MusicRepository,
    private val downloadRepository: DownloadRepository,
    private val resolverEngine: ResolverEngine,
    val playerManager: AudioPlayerManager
) : ViewModel() {

    val allTracks: StateFlow<List<Track>> = musicRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedTracks: StateFlow<List<Track>> = musicRepository.getLikedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<Track>> = musicRepository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = musicRepository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeDownloads: StateFlow<List<DownloadItem>> = downloadRepository.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalDownloadedBytes: StateFlow<Long> = downloadRepository.getTotalDownloadedBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val playerState: StateFlow<PlayerState> = playerManager.state

    val resolverNodes: StateFlow<List<ResolverNode>> = resolverEngine.activeNodes
    val isOfflineMode: StateFlow<Boolean> = resolverEngine.isOfflineMode
    val circuitBreakerState: StateFlow<CircuitBreakerState> = resolverEngine.circuitBreakerState
    val currentPackVersion: StateFlow<String> = resolverEngine.currentPackVersion

    val skipNotice: SharedFlow<String> = playerManager.skipEvent

    // Live Home Screen & Personalization State
    val trendingHits = MutableStateFlow<List<Track>>(emptyList())
    val freshReleases = MutableStateFlow<List<Track>>(emptyList())
    val personalizedTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedMood = MutableStateFlow("For You")
    val isLoadingHome = MutableStateFlow(false)

    // Search state
    val searchQuery = MutableStateFlow("")
    val searchFilter = MutableStateFlow(SearchFilter.ALL)
    private val onlineSearchResults = MutableStateFlow<List<Track>>(emptyList())
    val isSearchingOnline = MutableStateFlow(false)
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())

    init {
        // 1. Initial load of real YouTube Music for Home Screen & Personalization
        loadHomeScreenMusic()

        // 2. Trigger live search with debounce when online
        viewModelScope.launch {
            searchQuery
                .debounce(300L)
                .collectLatest { query ->
                    val clean = query.trim()
                    if (clean.length >= 2 && !isOfflineMode.value) {
                        isSearchingOnline.value = true
                        try {
                            val liveResults = NetworkClients.searchInnerTube(clean)
                            onlineSearchResults.value = liveResults
                            // Also fetch search suggestions
                            val suggestions = NetworkClients.getSearchSuggestions(clean)
                            searchSuggestions.value = suggestions
                        } catch (e: Exception) {
                            onlineSearchResults.value = emptyList()
                            searchSuggestions.value = emptyList()
                        } finally {
                            isSearchingOnline.value = false
                        }
                    } else {
                        onlineSearchResults.value = emptyList()
                        searchSuggestions.value = emptyList()
                        isSearchingOnline.value = false
                    }
                }
        }
    }

    fun loadHomeScreenMusic() {
        viewModelScope.launch {
            if (isOfflineMode.value) return@launch
            isLoadingHome.value = true
            try {
                // Fetch real trending hits
                val hits = NetworkClients.fetchCategoryTracks("trending")
                if (hits.isNotEmpty()) {
                    trendingHits.value = hits
                    // Pre-cache in local DB so user can play immediately and track offline status
                    hits.take(10).forEach { musicRepository.insertTrack(it) }
                }

                // Fetch fresh releases
                val fresh = NetworkClients.fetchCategoryTracks("rock")
                if (fresh.isNotEmpty()) {
                    freshReleases.value = fresh
                    fresh.take(6).forEach { musicRepository.insertTrack(it) }
                }

                // Fetch personalized tracks for the initial selected mood
                updatePersonalizedTracks(selectedMood.value)
            } catch (e: Exception) {
                // Keep local tracks as graceful offline fallback
            } finally {
                isLoadingHome.value = false
            }
        }
    }

    fun selectMood(mood: String) {
        selectedMood.value = mood
        // Zero-latency instant personalization from local library based on 2-D mood coordinates (§E.5)
        val local = allTracks.value
        val localVibe = when (mood) {
            "Energize" -> local.filter { it.moodX >= 0.75f }
            "Relax" -> local.filter { it.moodX < 0.6f && it.moodY >= 0.7f }
            "Workout" -> local.filter { it.moodX >= 0.8f }
            "Focus" -> local.filter { it.genre.contains("Lofi", true) || it.genre.contains("Acoustic", true) || it.moodX < 0.6f }
            "Party" -> local.filter { it.moodX >= 0.85f && it.moodY >= 0.75f }
            "Romance" -> local.filter { it.moodY >= 0.8f }
            "Rock" -> local.filter { it.genre.contains("Rock", true) }
            "Folk" -> local.filter { it.genre.contains("Folk", true) }
            else -> local
        }
        if (localVibe.isNotEmpty()) {
            personalizedTracks.value = localVibe
        }
        viewModelScope.launch {
            updatePersonalizedTracks(mood)
        }
    }

    fun setQueryImmediately(newQuery: String) {
        searchQuery.value = newQuery
        val clean = newQuery.trim()
        if (clean.length >= 2 && !isOfflineMode.value) {
            viewModelScope.launch {
                isSearchingOnline.value = true
                try {
                    val live = NetworkClients.searchInnerTube(clean)
                    if (live.isNotEmpty()) {
                        onlineSearchResults.value = live
                    }
                    val suggestions = NetworkClients.getSearchSuggestions(clean)
                    searchSuggestions.value = suggestions
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    isSearchingOnline.value = false
                }
            }
        }
    }

    private suspend fun updatePersonalizedTracks(mood: String) {
        val cat = when (mood) {
            "Energize" -> "energize"
            "Relax" -> "relax"
            "Workout" -> "workout"
            "Focus" -> "focus"
            "Party" -> "party"
            "Romance" -> "romance"
            "Rock" -> "rock"
            "Folk" -> "folk"
            else -> "trending"
        }
        val tracks = NetworkClients.fetchCategoryTracks(cat)
        if (tracks.isNotEmpty()) {
            personalizedTracks.value = tracks
            tracks.take(8).forEach { musicRepository.insertTrack(it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Track>> = combine(
        searchQuery,
        searchFilter,
        allTracks,
        onlineSearchResults
    ) { query, filter, localTracks, onlineTracks ->
        val localFiltered = if (query.isBlank()) {
            localTracks
        } else {
            localTracks.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true) ||
                        it.album.contains(query, ignoreCase = true) ||
                        it.genre.contains(query, ignoreCase = true)
            }
        }

        // Merge online and local matches, prioritizing online YouTube Music results when searching
        val combined = if (query.isNotBlank() && onlineTracks.isNotEmpty()) {
            val localIds = localFiltered.map { it.id }.toSet()
            onlineTracks + localFiltered.filter { it.id !in onlineTracks.map { t -> t.id }.toSet() }
        } else if (onlineTracks.isNotEmpty()) {
            onlineTracks
        } else {
            localFiltered
        }

        when (filter) {
            SearchFilter.ALL -> combined
            SearchFilter.OFFLINE_ONLY -> combined.filter { it.isDownloaded }
            SearchFilter.LIKED -> combined.filter { it.isLiked }
            SearchFilter.FOLK -> combined.filter { it.genre.contains("Folk", ignoreCase = true) }
            SearchFilter.SYNTHWAVE -> combined.filter { it.genre.equals("Synthwave", ignoreCase = true) }
            SearchFilter.LOFI -> combined.filter { it.genre.equals("Lo-Fi", ignoreCase = true) }
            SearchFilter.ROCK -> combined.filter { it.genre.contains("Rock", ignoreCase = true) }
            SearchFilter.ACOUSTIC -> combined.filter { it.genre.equals("Acoustic", ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playTrack(track: Track) {
        viewModelScope.launch {
            // Invariant I3: ensure track is cached in local database
            musicRepository.insertTrack(track)
        }
        playerManager.playTrack(track)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        viewModelScope.launch {
            tracks.forEach { musicRepository.insertTrack(it) }
        }
        playerManager.playQueue(tracks, startIndex)
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(posMs: Long) = playerManager.seekTo(posMs)
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun cycleRepeatMode() = playerManager.cycleRepeatMode()

    fun toggleLike(track: Track) {
        viewModelScope.launch {
            musicRepository.toggleLike(track.id, track.isLiked)
        }
    }

    fun startDownload(track: Track, tier: DownloadTier = DownloadTier.HIGH) {
        viewModelScope.launch {
            downloadRepository.startDownload(track, tier)
        }
    }

    fun deleteDownload(trackId: String) {
        viewModelScope.launch {
            downloadRepository.deleteDownload(trackId)
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        resolverEngine.setOfflineMode(enabled)
    }

    fun selectResolverNode(nodeId: String) {
        resolverEngine.selectNode(nodeId)
    }

    fun playMoodRadio(energy: Float, valence: Float) {
        viewModelScope.launch {
            val matching = musicRepository.getTracksByMood(energy, valence, delta = 0.4f)
            if (matching.isNotEmpty()) {
                playQueue(matching, 0)
            } else {
                val all = allTracks.value
                if (all.isNotEmpty()) {
                    playQueue(all.shuffled(), 0)
                }
            }
        }
    }

    fun createPlaylist(title: String, desc: String) {
        viewModelScope.launch {
            musicRepository.createPlaylist(title, desc)
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun togglePlaylistLike(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.togglePlaylistLike(playlist.id, !playlist.isLiked)
        }
    }

    fun clearStreamCacheAndReset() {
        viewModelScope.launch {
            resolverEngine.clearCache()
            searchQuery.value = ""
            onlineSearchResults.value = emptyList()
            searchSuggestions.value = emptyList()
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) = playerManager.setEqualizerPreset(preset)
    fun setBassBoost(percent: Float) = playerManager.setBassBoost(percent)
    fun setLoudnessGain(gainDb: Float) = playerManager.setLoudnessGain(gainDb)
    fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)
    fun adjustLyricOffset(deltaMs: Long) = playerManager.adjustLyricOffset(deltaMs)

    val isDarkMode = MutableStateFlow(false)
    fun toggleDarkMode() {
        isDarkMode.value = !isDarkMode.value
    }
    fun setDarkMode(enabled: Boolean) {
        isDarkMode.value = enabled
    }

    fun playQueueIndex(index: Int) = playerManager.playQueueIndex(index)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
    fun clearQueue() = playerManager.clearQueue()
    fun addToQueue(track: Track) = playerManager.addToQueue(track)
    fun playNext(track: Track) = playerManager.playNext(track)
}

class CascadeViewModelFactory(
    private val musicRepository: MusicRepository,
    private val downloadRepository: DownloadRepository,
    private val resolverEngine: ResolverEngine,
    private val playerManager: AudioPlayerManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CascadeViewModel(
            musicRepository,
            downloadRepository,
            resolverEngine,
            playerManager
        ) as T
    }
}
