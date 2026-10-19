package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Artist
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ProviderFilter(val label: String) {
    ALL("All"),
    YOUTUBE("YouTube"),
    JIOSAAVN("JioSaavn"),
    LOCAL("Downloaded")
}

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

    val likedTrackIds: StateFlow<Set<String>> = musicRepository.getLikedTracks()
        .map { tracks -> tracks.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val downloadedTracks: StateFlow<List<Track>> = musicRepository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeHistory: StateFlow<List<Track>> = musicRepository.getActiveHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedHistory: StateFlow<List<Track>> = musicRepository.getArchivedHistory()
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

    // Artist navigation event — emitted when user clicks an artist name in PlayerScreen
    private val _artistNavigationEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val artistNavigationEvent: SharedFlow<String> = _artistNavigationEvent.asSharedFlow()

    fun navigateToArtist(artistName: String) {
        viewModelScope.launch {
            _artistNavigationEvent.emit(artistName)
        }
    }

    // Live Home Screen & Personalization State
    val trendingHits = MutableStateFlow<List<Track>>(emptyList())
    val freshReleases = MutableStateFlow<List<Track>>(emptyList())
    val personalizedTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedMood = MutableStateFlow("All")
    val isLoadingHome = MutableStateFlow(false)
    val isHomeLoading: StateFlow<Boolean> = isLoadingHome.asStateFlow()

    // ONLY user-liked/followed artists — NO pre-populated mock/JioSaavn/Spotify artists
    private val _followedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val followedArtists: StateFlow<List<Artist>> = _followedArtists.asStateFlow()

    // User's custom playlists start with 0 songs when created (no 18-song fake list)
    private val _userPlaylists = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val userPlaylists: StateFlow<Map<String, List<Track>>> = _userPlaylists.asStateFlow()

    private var moodFetchJob: Job? = null

    // Discover Shorts Feed & Seeder State
    val spotifyProviderInstance = com.seitharol.data.provider.DefaultSpotifyMetadataProvider()
    val discoverFeedSeeder = com.seitharol.domain.discover.DiscoverFeedSeeder(spotifyProviderInstance)
    private val _discoverTracks = MutableStateFlow<List<com.seitharol.domain.model.NormalizedTrack>>(emptyList())
    val discoverTracks: StateFlow<List<com.seitharol.domain.model.NormalizedTrack>> = _discoverTracks.asStateFlow()

    // Search state
    val searchQuery = MutableStateFlow("")
    val searchFilter = MutableStateFlow(SearchFilter.ALL)
    val selectedProviderFilter = MutableStateFlow(ProviderFilter.ALL)
    private val onlineSearchResults = MutableStateFlow<List<Track>>(emptyList())
    val isSearchingOnline = MutableStateFlow(false)
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())

    fun selectProviderFilter(filter: ProviderFilter) {
        selectedProviderFilter.value = filter
        searchWithProvider(searchQuery.value, filter)
    }

    private fun searchWithProvider(query: String, filter: ProviderFilter) {
        val clean = query.trim()
        if (clean.length < 2 || isOfflineMode.value) return
        viewModelScope.launch {
            isSearchingOnline.value = true
            try {
                when (filter) {
                    ProviderFilter.ALL -> {
                        val yt = resolverEngine.youtubeInnerTubeProvider.searchTracks(clean, limit = 15).map { it.toDomainTrack() }
                        val saavn = resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 10).map { it.toDomainTrack() }
                        onlineSearchResults.value = yt + saavn
                    }
                    ProviderFilter.YOUTUBE -> {
                        val yt = resolverEngine.youtubeInnerTubeProvider.searchTracks(clean, limit = 25).map { it.toDomainTrack() }
                        onlineSearchResults.value = yt
                    }
                    ProviderFilter.JIOSAAVN -> {
                        val saavn = resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 25).map { it.toDomainTrack() }
                        onlineSearchResults.value = saavn
                    }
                    ProviderFilter.LOCAL -> {
                        onlineSearchResults.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                // Keep previous results on failure
            } finally {
                isSearchingOnline.value = false
            }
        }
    }

    init {
        // 1. Initial load of real YouTube Music for Home Screen & Personalization
        loadHomeScreenMusic()
        loadDiscoverFeed()

        // 2. Trigger live search with debounce when online
        viewModelScope.launch {
            searchQuery
                .debounce(300L)
                .collectLatest { query ->
                    val clean = query.trim()
                    if (clean.length >= 2 && !isOfflineMode.value) {
                        searchWithProvider(clean, selectedProviderFilter.value)
                        try {
                            val suggestions = NetworkClients.getSearchSuggestions(clean)
                            searchSuggestions.value = suggestions
                        } catch (e: Exception) {
                            searchSuggestions.value = emptyList()
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

    fun loadDiscoverFeed() {
        viewModelScope.launch {
            try {
                val hist = activeHistory.value.map { it.toNormalizedTrack() }
                val liked = likedTracks.value.map { it.toNormalizedTrack() }
                val feed = discoverFeedSeeder.generatePersonalizedFeed(hist, liked)
                _discoverTracks.value = feed
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Failed to load discover feed: ${e.message}")
            }
        }
    }

    suspend fun searchDiscoverPreviews(query: String): List<com.seitharol.domain.model.NormalizedTrack> {
        return runCatching {
            spotifyProviderInstance.searchTracksWithPreviews(query, limit = 20)
        }.getOrDefault(emptyList())
    }

    fun playNormalizedTrackFull(track: com.seitharol.domain.model.NormalizedTrack) {
        val domainTrack = Track(
            id = track.sourceId,
            title = track.title,
            artist = track.artists.firstOrNull() ?: "Unknown",
            album = track.album ?: "",
            durationMs = track.durationMs,
            artUrl = track.artUrl,
            isLiked = track.isLiked,
            localPath = track.localPath
        )
        playTrack(domainTrack)
    }

    private fun Track.toNormalizedTrack(): com.seitharol.domain.model.NormalizedTrack {
        return com.seitharol.domain.model.NormalizedTrack(
            source = com.seitharol.domain.model.TrackSource.YOUTUBE,
            sourceId = this.id,
            title = this.title,
            artists = listOf(this.artist),
            album = this.album,
            durationMs = this.durationMs,
            artUrl = this.artUrl,
            isPlayableNatively = true,
            localPath = this.localPath,
            isLiked = this.isLiked
        )
    }

    /**
     * Job for Home Mood Chips: "All", "For You", "Energize", "Relax", "Workout", "Focus", "Mixes", "New Releases"
     */
    fun selectHomeMood(mood: String) {
        if (selectedMood.value == mood && trendingHits.value.isNotEmpty()) return
        selectedMood.value = mood

        moodFetchJob?.cancel()
        moodFetchJob = viewModelScope.launch {
            isLoadingHome.value = true
            try {
                val (trendingQuery, freshQuery, mixQuery) = when (mood) {
                    "For You" -> Triple(
                        "recommended trending songs",
                        "new music for you",
                        "My Supermix YouTube Music"
                    )
                    "Energize" -> Triple(
                        "upbeat energetic hits",
                        "new dance pop workout songs",
                        "Energize Mix"
                    )
                    "Relax" -> Triple(
                        "chill relaxing acoustic lofi songs",
                        "new chill acoustic songs",
                        "Chill Mix"
                    )
                    "Workout" -> Triple(
                        "workout gym phonk hype music",
                        "new workout EDM hip hop",
                        "Workout Mix"
                    )
                    "Focus" -> Triple(
                        "deep focus instrumental study music",
                        "ambient piano electronic focus",
                        "Focus Mix"
                    )
                    "Mixes" -> Triple(
                        "top radio hits",
                        "popular mashups mixes",
                        "YouTube Music Mixes"
                    )
                    "New Releases" -> Triple(
                        "latest hit singles",
                        "brand new music releases",
                        "New Release Mix"
                    )
                    else -> Triple(
                        "trending songs Manipuri Bollywood global hits",
                        "fresh new music releases",
                        "My Mix radio"
                    )
                }

                // Fetch in parallel from YouTube Music InnerTube (never JioSaavn/Spotify)
                val trendingDeferred = async { NetworkClients.searchInnerTube(trendingQuery) }
                val freshDeferred = async { NetworkClients.searchInnerTube(freshQuery) }
                val mixesDeferred = async { NetworkClients.searchInnerTube(mixQuery) }

                val trending = trendingDeferred.await()
                val fresh = freshDeferred.await()
                val mixes = mixesDeferred.await()

                if (trending.isNotEmpty()) trendingHits.value = trending
                if (fresh.isNotEmpty()) freshReleases.value = fresh
                if (mixes.isNotEmpty()) personalizedTracks.value = mixes
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingHome.value = false
            }
        }
    }

    fun selectMood(mood: String) = selectHomeMood(mood)

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
        combine(searchQuery, searchFilter, selectedProviderFilter) { q, filter, provider ->
            Triple(q, filter, provider)
        },
        combine(allTracks, downloadedTracks, onlineSearchResults) { localTracks, downloaded, onlineTracks ->
            Triple(localTracks, downloaded, onlineTracks)
        }
    ) { (query, filter, provider), (localTracks, downloaded, onlineTracks) ->
        val cleanQuery = query.trim()
        val allDownloaded: List<Track> = (downloaded + localTracks.filter { it.isDownloaded })
            .distinctBy { it.id }

        val combined: List<Track> = if (provider == ProviderFilter.LOCAL) {
            val baseList = if (allDownloaded.isNotEmpty()) allDownloaded else localTracks.filter { it.isDownloaded }
            if (cleanQuery.isBlank()) {
                baseList
            } else {
                baseList.filter {
                    it.title.contains(cleanQuery, ignoreCase = true) ||
                            it.artist.contains(cleanQuery, ignoreCase = true) ||
                            it.album.contains(cleanQuery, ignoreCase = true)
                }
            }
        } else {
            val localFiltered = if (cleanQuery.isBlank()) {
                localTracks
            } else {
                localTracks.filter {
                    it.title.contains(cleanQuery, ignoreCase = true) ||
                            it.artist.contains(cleanQuery, ignoreCase = true) ||
                            it.album.contains(cleanQuery, ignoreCase = true) ||
                            it.genre.contains(cleanQuery, ignoreCase = true)
                }
            }

            if (cleanQuery.isNotBlank() && onlineTracks.isNotEmpty()) {
                val onlineIds = onlineTracks.map { it.id }.toSet()
                onlineTracks + localFiltered.filter { it.id !in onlineIds }
            } else if (onlineTracks.isNotEmpty()) {
                onlineTracks
            } else {
                localFiltered
            }
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
            else -> combined
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playTrack(track: Track) {
        if (track.id.startsWith("RD") || track.id.startsWith("VL") || track.id.startsWith("PL") || track.id.startsWith("MPREb_") || track.id.startsWith("OLAK5uy_")) {
            playPlaylistOrMix(track.id)
            return
        }
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

    fun playPlaylistOrMix(playlistId: String) {
        viewModelScope.launch {
            try {
                val tracks = com.example.data.remote.InnerTubeResolver.fetchAndParsePlaylistOrMixTracks(playlistId)
                if (tracks.isNotEmpty()) {
                    playQueue(tracks, 0)
                }
            } catch (e: Exception) {
                android.util.Log.w("CascadeViewModel", "Failed to play playlist/mix $playlistId: ${e.message}")
            }
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(posMs: Long) = playerManager.seekTo(posMs)
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun cycleRepeatMode() = playerManager.cycleRepeatMode()

    fun toggleLike(track: Track) {
        viewModelScope.launch {
            musicRepository.toggleLike(track)
        }
    }

    fun startDownload(track: Track, tier: DownloadTier = DownloadTier.HIGH) {
        viewModelScope.launch {
            downloadRepository.startDownload(track, tier)
        }
    }

    fun retryDownload(trackId: String) {
        viewModelScope.launch {
            downloadRepository.retryDownload(trackId)
        }
    }

    fun deleteDownload(trackId: String) {
        viewModelScope.launch {
            downloadRepository.deleteDownload(trackId)
        }
    }

    fun removeDownload(trackId: String) = deleteDownload(trackId)

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

    /**
     * Follow / Unfollow Artist (Only YT Music artists liked by the user appear in Library)
     */
    fun toggleFollowArtist(artist: Artist) {
        val current = _followedArtists.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == artist.id || it.name.equals(artist.name, ignoreCase = true) }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(
                artist.copy(
                    thumbnailUrl = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(artist.thumbnailUrl)
                )
            )
        }
        _followedArtists.value = current
    }

    /**
     * Loads exact tracks for a Playlist or Album without any 18-song fake fallback.
     */
    fun loadPlaylistTracks(playlistId: String, onResult: (List<Track>) -> Unit) {
        viewModelScope.launch {
            // 1. Check if user-created local playlist first
            val localTracks = _userPlaylists.value[playlistId]
            if (localTracks != null) {
                onResult(localTracks)
                return@launch
            }
            val dbTracks = musicRepository.getTracksForPlaylist(playlistId).firstOrNull()
            if (!dbTracks.isNullOrEmpty()) {
                onResult(dbTracks)
                return@launch
            }
            // 2. Otherwise fetch from YouTube Music InnerTube
            val remoteTracks = com.example.data.remote.InnerTubeResolver.fetchAndParsePlaylistOrMixTracks(playlistId)
            onResult(remoteTracks)
        }
    }

    fun createPlaylist(title: String, desc: String, initialTrack: Track? = null) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(title, desc, initialTrack?.artUrl)
            if (initialTrack != null) {
                musicRepository.insertTracks(listOf(initialTrack))
                musicRepository.addTrackToPlaylist(id, initialTrack.id)
                _userPlaylists.value = _userPlaylists.value + (id to listOf(initialTrack))
            } else {
                _userPlaylists.value = _userPlaylists.value + (id to emptyList())
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            musicRepository.deletePlaylist(playlistId)
            _userPlaylists.value = _userPlaylists.value - playlistId
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            musicRepository.removeTrackFromPlaylist(playlistId, trackId)
            val currentList = _userPlaylists.value[playlistId].orEmpty()
            _userPlaylists.value = _userPlaylists.value + (playlistId to currentList.filterNot { it.id == trackId })
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(playlistId, trackId)
            val track = allTracks.value.firstOrNull { it.id == trackId }
                ?: trendingHits.value.firstOrNull { it.id == trackId }
                ?: freshReleases.value.firstOrNull { it.id == trackId }
                ?: personalizedTracks.value.firstOrNull { it.id == trackId }
                ?: musicRepository.getTrackById(trackId)
            if (track != null) {
                val currentList = _userPlaylists.value[playlistId].orEmpty()
                _userPlaylists.value = _userPlaylists.value + (playlistId to (currentList + track))
            }
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

    val isDarkMode = MutableStateFlow<Boolean?>(null)
    fun toggleDarkMode(currentIsDark: Boolean = false) {
        val current = isDarkMode.value ?: currentIsDark
        isDarkMode.value = !current
    }
    fun setDarkMode(enabled: Boolean) {
        isDarkMode.value = enabled
    }

    fun playQueueIndex(index: Int) = playerManager.playQueueIndex(index)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
    fun moveTrack(fromIndex: Int, toIndex: Int) = playerManager.moveTrack(fromIndex, toIndex)
    fun clearQueue() = playerManager.clearQueue()
    fun stopPlayback() = playerManager.stopPlayback()
    fun addToQueue(track: Track) = playerManager.addToQueue(track)
    fun playNext(track: Track) = playerManager.playNext(track)

    fun playDownloadedTrack(trackId: String) {
        viewModelScope.launch {
            val track = musicRepository.getTrackById(trackId) ?: activeDownloads.value.firstOrNull { it.trackId == trackId }?.let { dl ->
                Track(
                    id = dl.trackId,
                    title = dl.title,
                    artist = dl.artist,
                    album = dl.title,
                    durationMs = 0L,
                    artUrl = dl.artUrl,
                    audioUrl = dl.localFilePath ?: "",
                    localPath = dl.localFilePath,
                    isDownloaded = true
                )
            }
            if (track != null) {
                playerManager.playTrack(track)
            }
        }
    }

    fun deleteTrackFromHistory(trackId: String) {
        viewModelScope.launch {
            musicRepository.deleteTrackFromHistory(trackId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            musicRepository.clearAllHistory()
        }
    }

    // ────────────────────────────────────────────────────────────
    // Auto-Suggest & Non-Repeating Radio
    // ────────────────────────────────────────────────────────────

    private val playedTrackKeys = mutableSetOf<String>()

    private fun Track.sessionKey(): String =
        "${id}_${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private val recommendationRepository = object : com.example.player.RecommendationRepository {
        override suspend fun getRelatedTracks(seedTrack: Track): List<Track> {
            return try {
                val automixTracks = resolverEngine.youtubeInnerTubeProvider
                    .getRadioTracks(seedTrack.id)
                    .map { it.toDomainTrack() }
                if (automixTracks.isNotEmpty()) {
                    automixTracks
                } else {
                    val query = "${seedTrack.title} ${seedTrack.artist}".trim()
                    resolverEngine.youtubeInnerTubeProvider
                        .searchTracks(query, limit = 10)
                        .map { it.toDomainTrack() }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        override suspend fun getRelatedPlaylists(seedTrack: Track): List<com.example.player.SuggestedPlaylist> {
            return emptyList()
        }
    }

    val autoSuggestManager = com.example.player.AutoSuggestQueueManager(recommendationRepository)
    val isAutoSuggestEnabled: StateFlow<Boolean> = autoSuggestManager.isAutoSuggestEnabled
    val suggestedPlaylists: StateFlow<List<com.example.player.SuggestedPlaylist>> = autoSuggestManager.suggestedPlaylists

    fun toggleAutoSuggest() {
        autoSuggestManager.toggleAutoSuggest()
        if (autoSuggestManager.isAutoSuggestEnabled.value) {
            fetchAndAppendSuggestions()
        }
    }

    /**
     * Called after each track finishes (or when Auto-Suggest is enabled).
     * Fetches related tracks and appends de-duplicated suggestions to the queue.
     */
    fun fetchAndAppendSuggestions() {
        if (!isAutoSuggestEnabled.value) return
        val seed = playerState.value.currentTrack ?: return
        viewModelScope.launch {
            try {
                val currentQueue = playerState.value.queue
                val freshTracks = autoSuggestManager.fetchNextSuggestions(seed, currentQueue, maxTracksToAppend = 3)
                freshTracks.forEach { track ->
                    musicRepository.insertTrack(track)
                    playerManager.addToQueue(track)
                }
            } catch (e: Exception) {
                // Non-fatal: Auto-Suggest silently fails
            }
        }
    }

    /** Mark the given track as played so Auto-Suggest won't repeat it. */
    fun markTrackPlayed(track: Track) {
        playedTrackKeys.add(track.sessionKey())
        autoSuggestManager.markTrackPlayed(track)
    }

    /** Clear played history (e.g. on new session or manual refresh). */
    fun clearPlayedHistory() {
        playedTrackKeys.clear()
        autoSuggestManager.resetSessionHistory()
    }
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
