package com.example.ui

import android.util.Log
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
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
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
    LIKED("Liked")
}

class CascadeViewModel(
    private val musicRepository: MusicRepository,
    private val downloadRepository: DownloadRepository,
    val resolverEngine: ResolverEngine,
    val playerManager: AudioPlayerManager
) : ViewModel() {

    val allTracks: StateFlow<List<Track>> = musicRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val likedTracks: StateFlow<List<Track>> = musicRepository.getLikedTracks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val likedTrackIds: StateFlow<Set<String>> = likedTracks
        .map { tracks -> tracks.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val downloadedTracks: StateFlow<List<Track>> = musicRepository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeHistory: StateFlow<List<Track>> = musicRepository.getActiveHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val archivedHistory: StateFlow<List<Track>> = musicRepository.getArchivedHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<Playlist>> = musicRepository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    private val _artistNavigationEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val artistNavigationEvent: SharedFlow<String> = _artistNavigationEvent.asSharedFlow()

    fun navigateToArtist(artistName: String) {
        viewModelScope.launch { _artistNavigationEvent.emit(artistName) }
    }

    val trendingHits = MutableStateFlow<List<Track>>(emptyList())
    val freshReleases = MutableStateFlow<List<Track>>(emptyList())
    val personalizedTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedMood = MutableStateFlow("All")
    val isLoadingHome = MutableStateFlow(false)
    val isHomeLoading: StateFlow<Boolean> = isLoadingHome.asStateFlow()

    private val _followedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val followedArtists: StateFlow<List<Artist>> = _followedArtists.asStateFlow()

    private val _userPlaylists = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val userPlaylists: StateFlow<Map<String, List<Track>>> = _userPlaylists.asStateFlow()

    private var moodFetchJob: Job? = null
    private var activeSearchJob: Job? = null

    val spotifyProviderInstance = com.seitharol.data.provider.DefaultSpotifyMetadataProvider()
    val discoverFeedSeeder = com.seitharol.domain.discover.DiscoverFeedSeeder(spotifyProviderInstance)
    private val _discoverTracks = MutableStateFlow<List<com.seitharol.domain.model.NormalizedTrack>>(emptyList())
    val discoverTracks: StateFlow<List<com.seitharol.domain.model.NormalizedTrack>> = _discoverTracks.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val searchFilter = MutableStateFlow(SearchFilter.ALL)
    val selectedProviderFilter = MutableStateFlow(ProviderFilter.ALL)
    private val onlineSearchResults = MutableStateFlow<List<Track>>(emptyList())
    val isSearchingOnline = MutableStateFlow(false)
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())

    fun selectProviderFilter(filter: ProviderFilter) {
        selectedProviderFilter.value = filter
        executeRankedSearch(searchQuery.value, filter)
    }

    private fun executeRankedSearch(query: String, filter: ProviderFilter) {
        val clean = query.trim()
        activeSearchJob?.cancel()
        if (clean.length < 2 || isOfflineMode.value) {
            onlineSearchResults.value = emptyList()
            isSearchingOnline.value = false
            return
        }

        activeSearchJob = viewModelScope.launch {
            isSearchingOnline.value = true
            try {
                val rawCandidates = when (filter) {
                    ProviderFilter.ALL -> {
                        val ytDeferred = async { InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 18) }
                        val saavnDeferred = async {
                            resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 8).map {
                                com.seitharol.data.resolver.CandidateStream(
                                    videoId = it.sourceId,
                                    title = it.title,
                                    artist = it.primaryArtist,
                                    channelName = "${it.primaryArtist} - Topic",
                                    albumName = it.album,
                                    durationSeconds = (it.durationMs / 1000L).toInt(),
                                    videoType = "MUSIC_VIDEO_TYPE_ATV",
                                    thumbnailUrl = it.artUrl,
                                    isVerifiedArtist = true
                                )
                            }
                        }
                        ytDeferred.await() + saavnDeferred.await()
                    }
                    ProviderFilter.YOUTUBE -> InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 25)
                    ProviderFilter.JIOSAAVN -> {
                        resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 25).map {
                            com.seitharol.data.resolver.CandidateStream(
                                videoId = it.sourceId,
                                title = it.title,
                                artist = it.primaryArtist,
                                channelName = "${it.primaryArtist} - Topic",
                                albumName = it.album,
                                durationSeconds = (it.durationMs / 1000L).toInt(),
                                videoType = "MUSIC_VIDEO_TYPE_ATV",
                                thumbnailUrl = it.artUrl,
                                isVerifiedArtist = true
                            )
                        }
                    }
                    ProviderFilter.LOCAL -> emptyList()
                }

                // Rank strictly by relevance to user's query
                val ranked = CanonicalAudioResolver.rankSearchResultsByRelevance(clean, rawCandidates)
                onlineSearchResults.value = ranked.map { c ->
                    Track(
                        id = c.videoId,
                        title = c.title,
                        artist = c.artist,
                        album = c.albumName.orEmpty(),
                        durationMs = c.durationSeconds * 1000L,
                        artUrl = c.thumbnailUrl
                    )
                }
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Search failed: ${e.message}")
            } finally {
                isSearchingOnline.value = false
            }
        }
    }

    private val playedTrackKeys = mutableSetOf<String>()
    private fun Track.sessionKey(): String = "${id}_${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private val recommendationRepository = object : com.example.player.RecommendationRepository {
        override suspend fun getRelatedTracks(seedTrack: Track): List<Track> {
            return try {
                val automix = InnerTubeStreamExtractor.fetchAutomixQueue(seedTrack.id, limit = 12)
                    .map { it.toTrack() }
                if (automix.isNotEmpty()) automix
                else InnerTubeStreamExtractor.searchOfficialYtmSongs("${seedTrack.title} ${seedTrack.artist}", limit = 10)
                    .map { it.toTrack() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        override suspend fun getRelatedPlaylists(seedTrack: Track): List<com.example.player.SuggestedPlaylist> = emptyList()
    }

    val autoSuggestManager = com.example.player.AutoSuggestQueueManager(recommendationRepository)
    val isAutoSuggestEnabled: StateFlow<Boolean> = autoSuggestManager.isAutoSuggestEnabled
    val suggestedPlaylists: StateFlow<List<com.example.player.SuggestedPlaylist>> = autoSuggestManager.suggestedPlaylists

    init {
        playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value
        loadHomeScreenMusic()
        loadDiscoverFeed()
        viewModelScope.launch { downloadRepository.rescanLocalDownloads() }

        viewModelScope.launch {
            searchQuery
                .debounce(280L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val clean = query.trim()
                    if (clean.length >= 2 && !isOfflineMode.value) {
                        executeRankedSearch(clean, selectedProviderFilter.value)
                        runCatching { NetworkClients.getSearchSuggestions(clean) }
                            .onSuccess { searchSuggestions.value = it }
                            .onFailure { searchSuggestions.value = emptyList() }
                    } else {
                        onlineSearchResults.value = emptyList()
                        searchSuggestions.value = emptyList()
                        isSearchingOnline.value = false
                    }
                }
        }

        viewModelScope.launch {
            autoSuggestManager.isAutoSuggestEnabled.collect { enabled ->
                playerManager.isAutoplayEnabled = enabled
            }
        }
    }

    /**
     * OuterTune-style Personalization Engine:
     * Reads persisted listening history & liked tracks directly from Room DB (avoiding cold StateFlow empty values),
     * then builds personalized Quick Picks (via RDAMVM Automix) and artist/mood shelves.
     */
    fun loadHomeScreenMusic() {
        viewModelScope.launch {
            if (isOfflineMode.value) return@launch
            isLoadingHome.value = true
            try {
                val realHistory = musicRepository.getActiveHistory().firstOrNull().orEmpty()
                val realLiked = musicRepository.getLikedTracks().firstOrNull().orEmpty()

                val seedIds = (realHistory.take(4).map { it.id } + realLiked.take(3).map { it.id })
                    .filter { it.length == 11 }
                    .distinct()

                val topArtists = (realHistory + realLiked)
                    .map { it.artist.trim() }
                    .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) && !it.equals("Unknown", true) }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(4)

                // 1. Personalized Quick Picks / For You feed via YouTube Music RDAMVM Automix + Top Artists
                val personalizedCandidates = InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                    seedVideoIds = seedIds,
                    favoriteArtists = topArtists,
                    moodCategory = selectedMood.value
                )
                if (personalizedCandidates.isNotEmpty()) {
                    val mapped = personalizedCandidates.map { it.toTrack() }
                    personalizedTracks.value = mapped
                    trendingHits.value = mapped.take(12)
                }

                // 2. Regional / Manipuri & Fresh Releases shelf
                val freshCandidates = InnerTubeStreamExtractor.searchOfficialYtmSongs(
                    query = if (topArtists.isNotEmpty()) "${topArtists.first()} new songs" else "Manipuri latest official songs",
                    limit = 12
                )
                if (freshCandidates.isNotEmpty()) {
                    freshReleases.value = freshCandidates.map { it.toTrack() }
                }
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Home load error: ${e.message}")
            } finally {
                isLoadingHome.value = false
            }
        }
    }

    fun loadDiscoverFeed() {
        viewModelScope.launch {
            try {
                val hist = musicRepository.getActiveHistory().firstOrNull().orEmpty().map { it.toNormalizedTrack() }
                val liked = musicRepository.getLikedTracks().firstOrNull().orEmpty().map { it.toNormalizedTrack() }
                val feed = discoverFeedSeeder.generatePersonalizedFeed(hist, liked)
                _discoverTracks.value = feed
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Failed to load discover feed: ${e.message}")
            }
        }
    }

    fun registerLocallyDownloadedTrack(normalized: com.seitharol.domain.model.NormalizedTrack) {
        viewModelScope.launch {
            val localTrack = Track(
                id = normalized.sourceId,
                title = normalized.title,
                artist = normalized.artists.firstOrNull() ?: "Unknown Artist",
                album = normalized.album ?: "Single",
                durationMs = normalized.durationMs,
                artUrl = normalized.artUrl,
                audioUrl = normalized.localPath.orEmpty(),
                localPath = normalized.localPath,
                isDownloaded = true,
                isLiked = normalized.isLiked
            )
            musicRepository.insertTrack(localTrack)
            downloadRepository.rescanLocalDownloads()
        }
    }

    val isRefreshingLibrary = MutableStateFlow(false)

    fun refreshLibrary() {
        viewModelScope.launch {
            isRefreshingLibrary.value = true
            try {
                downloadRepository.rescanLocalDownloads()
                loadHomeScreenMusic()
            } finally {
                isRefreshingLibrary.value = false
            }
        }
    }

    suspend fun searchDiscoverPreviews(query: String): List<com.seitharol.domain.model.NormalizedTrack> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val results = mutableListOf<com.seitharol.domain.model.NormalizedTrack>()
        runCatching {
            val previewTracks = spotifyProviderInstance.searchTracksWithPreviews(clean, limit = 20)
            results.addAll(previewTracks)
        }
        if (results.isEmpty()) {
            runCatching {
                val ytmTracks = com.seitharol.data.resolver.InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 20)
                val mapped = ytmTracks.map { yt ->
                    com.seitharol.domain.model.NormalizedTrack(
                        source = com.seitharol.domain.model.TrackSource.YOUTUBE,
                        sourceId = yt.videoId,
                        title = yt.title,
                        artists = listOf(yt.artist),
                        primaryArtistBrowseId = yt.artistBrowseId,
                        artistAvatarUrl = yt.thumbnailUrl,
                        album = yt.albumName ?: "Single",
                        durationMs = yt.durationSeconds * 1000L,
                        artUrl = yt.thumbnailUrl,
                        isPlayableNatively = true,
                        previewUrl = "ytm_clip:${yt.videoId}"
                    )
                }
                results.addAll(mapped)
            }
        }
        return results.distinctBy { it.sourceId }
    }

    fun playNormalizedTrackFull(track: com.seitharol.domain.model.NormalizedTrack) {
        val domainTrack = Track(
            id = track.sourceId,
            title = track.title,
            artist = track.artists.firstOrNull() ?: "Unknown Artist",
            album = track.album ?: "",
            durationMs = track.durationMs,
            artUrl = track.artUrl,
            isLiked = track.isLiked,
            localPath = track.localPath,
            isDownloaded = !track.localPath.isNullOrBlank()
        )
        playTrack(domainTrack)
    }

    private fun com.seitharol.data.resolver.CandidateStream.toTrack(): Track = Track(
        id = this.videoId,
        title = this.title,
        artist = this.artist,
        album = this.albumName.orEmpty(),
        durationMs = this.durationSeconds * 1000L,
        artUrl = this.thumbnailUrl
    )

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

    fun selectHomeMood(mood: String) {
        val targetMood = if (selectedMood.value == mood && mood != "All") "All" else mood
        selectedMood.value = targetMood
        moodFetchJob?.cancel()
        moodFetchJob = viewModelScope.launch {
            isLoadingHome.value = true
            try {
                val realHistory = musicRepository.getActiveHistory().firstOrNull().orEmpty()
                val seedIds = realHistory.take(3).map { it.id }.filter { it.length == 11 }
                val topArtists = realHistory.map { it.artist }.filter { it.isNotBlank() }.distinct().take(3)

                val feed = InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                    seedVideoIds = seedIds,
                    favoriteArtists = topArtists,
                    moodCategory = targetMood
                ).map { it.toTrack() }

                if (feed.isNotEmpty()) {
                    personalizedTracks.value = feed
                    trendingHits.value = feed.take(10)
                    freshReleases.value = feed.drop(10).ifEmpty { feed.take(6) }
                }
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Mood switch failed: ${e.message}")
            } finally {
                isLoadingHome.value = false
            }
        }
    }

    fun selectMood(mood: String) = selectHomeMood(mood)

    fun setQueryImmediately(newQuery: String) {
        // Only update searchQuery StateFlow; let the single debounced collector run executeRankedSearch
        // so competing coroutines never overwrite search results out of order.
        searchQuery.value = newQuery
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Track>> = combine(
        combine(searchQuery, searchFilter, selectedProviderFilter) { q, filter, provider ->
            Triple(q, filter, provider)
        },
        combine(downloadedTracks, onlineSearchResults) { downloaded, onlineTracks ->
            downloaded to onlineTracks
        }
    ) { (query, filter, provider), (downloaded, onlineTracks) ->
        val cleanQuery = query.trim()

        val baseList: List<Track> = if (provider == ProviderFilter.LOCAL) {
            if (cleanQuery.isBlank()) downloaded
            else downloaded.filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                    it.artist.contains(cleanQuery, ignoreCase = true)
            }
        } else {
            // Never dump fake/demo database tracks when searchQuery is blank
            if (cleanQuery.isBlank()) emptyList() else onlineTracks
        }

        when (filter) {
            SearchFilter.ALL -> baseList
            SearchFilter.OFFLINE_ONLY -> baseList.filter { it.isDownloaded }
            SearchFilter.LIKED -> baseList.filter { it.isLiked }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playTrack(track: Track) {
        if (track.id.startsWith("RD") || track.id.startsWith("VL") || track.id.startsWith("PL") ||
            track.id.startsWith("MPREb_") || track.id.startsWith("OLAK5uy_")
        ) {
            playPlaylistOrMix(track.id)
            return
        }
        playerManager.playTrack(track)
        viewModelScope.launch {
            musicRepository.insertTrack(track)
            musicRepository.recordPlayHistory(track)
            if (track.id.length == 11) {
                runCatching {
                    val automix = InnerTubeStreamExtractor.fetchAutomixQueue(track.id, limit = 30)
                    if (automix.isNotEmpty()) {
                        val mixTracks = listOf(track) + automix.filter { it.videoId != track.id }.map { it.toTrack() }
                        personalizedTracks.value = mixTracks
                        playerManager.updateQueuePreservingPlayback(mixTracks)
                    }
                }
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, isPlaylist: Boolean = true) {
        viewModelScope.launch { tracks.forEach { musicRepository.insertTrack(it) } }
        playerManager.playQueue(tracks, startIndex, isPlaylist = isPlaylist)
    }

    fun playPlaylistOrMix(playlistId: String) {
        viewModelScope.launch {
            runCatching {
                val details = InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(playlistId)
                val tracks = details.tracks.map { it.toTrack() }
                if (tracks.isNotEmpty()) {
                    playQueue(tracks, 0, isPlaylist = true)
                } else {
                    val seed = playlistId.removePrefix("VL").removePrefix("RDAMVM")
                    if (seed.length == 11) {
                        val automix = InnerTubeStreamExtractor.fetchAutomixQueue(seed, limit = 35)
                        if (automix.isNotEmpty()) playQueue(automix.map { it.toTrack() }, 0, isPlaylist = true)
                    }
                }
            }
        }
    }

    fun playSingleRelease(collection: com.seitharol.data.resolver.YtmCollectionSummary, artistName: String) {
        viewModelScope.launch {
            val candidate = runCatching {
                InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(collection.browseId).tracks.firstOrNull()
            }.getOrNull() ?: runCatching {
                InnerTubeStreamExtractor.searchOfficialYtmSongs("${collection.title} $artistName", limit = 1).firstOrNull()
            }.getOrNull()

            if (candidate != null) {
                playTrack(candidate.toTrack())
            }
        }
    }

    fun startRadioMix(seedTrack: Track) {
        viewModelScope.launch {
            musicRepository.insertTrack(seedTrack)
            musicRepository.recordPlayHistory(seedTrack)
            playerManager.playQueue(listOf(seedTrack), 0, isPlaylist = false)
            runCatching {
                val automix = InnerTubeStreamExtractor.fetchAutomixQueue(seedTrack.id, limit = 35)
                val radioTracks = listOf(seedTrack) + automix.filter { it.videoId != seedTrack.id }.map { it.toTrack() }
                if (radioTracks.size > 1) {
                    playerManager.updateQueuePreservingPlayback(radioTracks)
                }
            }
        }
    }

    fun loadPlaylistTracks(playlistId: String, onResult: (List<Track>) -> Unit) {
        viewModelScope.launch {
            val localTracks = _userPlaylists.value[playlistId]
            if (!localTracks.isNullOrEmpty()) {
                onResult(localTracks)
                return@launch
            }
            val dbTracks = musicRepository.getTracksForPlaylist(playlistId).firstOrNull()
            if (!dbTracks.isNullOrEmpty()) {
                onResult(dbTracks)
                return@launch
            }
            val remoteDetails = InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(playlistId)
            val tracks = remoteDetails.tracks.map { it.toTrack() }
            if (tracks.isNotEmpty()) {
                onResult(tracks)
            } else {
                val cleanId = playlistId.removePrefix("VL").removePrefix("RDAMVM")
                if (cleanId.length == 11) {
                    val automix = InnerTubeStreamExtractor.fetchAutomixQueue(cleanId, limit = 35)
                    onResult(automix.map { it.toTrack() })
                } else {
                    onResult(emptyList())
                }
            }
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun pause() = playerManager.pause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seekTo(posMs: Long) = playerManager.seekTo(posMs)
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun cycleRepeatMode() = playerManager.cycleRepeatMode()

    fun toggleLike(track: Track) {
        viewModelScope.launch { musicRepository.toggleLike(track) }
    }

    fun startDownload(track: Track, tier: DownloadTier = DownloadTier.STANDARD) {
        viewModelScope.launch { downloadRepository.startDownload(track, tier) }
    }

    fun retryDownload(trackId: String) {
        viewModelScope.launch { downloadRepository.retryDownload(trackId) }
    }

    fun deleteDownload(trackId: String) {
        viewModelScope.launch { downloadRepository.deleteDownload(trackId) }
    }

    fun removeDownload(trackId: String) = deleteDownload(trackId)
    fun setOfflineMode(enabled: Boolean) = resolverEngine.setOfflineMode(enabled)
    fun selectResolverNode(nodeId: String) = resolverEngine.selectNode(nodeId)

    fun toggleFollowArtist(artist: Artist) {
        val current = _followedArtists.value.toMutableList()
        val idx = current.indexOfFirst { it.id == artist.id || it.name.equals(artist.name, ignoreCase = true) }
        if (idx >= 0) current.removeAt(idx) else current.add(artist)
        _followedArtists.value = current
    }

    fun createPlaylist(title: String, desc: String, initialTrack: Track? = null) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(title, desc, initialTrack?.artUrl)
            if (initialTrack != null) {
                musicRepository.insertTrack(initialTrack)
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

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            musicRepository.insertTracks(listOf(track))
            musicRepository.addTrackToPlaylist(playlistId, track.id)
            val currentList = _userPlaylists.value[playlistId].orEmpty()
            _userPlaylists.value = _userPlaylists.value + (playlistId to (currentList.filterNot { it.id == track.id } + track))
        }
    }

    fun togglePlaylistLike(playlist: Playlist) {
        viewModelScope.launch { musicRepository.togglePlaylistLike(playlist.id, !playlist.isLiked) }
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
        isDarkMode.value = !(isDarkMode.value ?: currentIsDark)
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
            val track = musicRepository.getTrackById(trackId)
                ?: activeDownloads.value.firstOrNull { it.trackId == trackId }?.let { dl ->
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
            if (track != null) playerManager.playTrack(track)
        }
    }

    fun deleteTrackFromHistory(trackId: String) {
        viewModelScope.launch { musicRepository.deleteTrackFromHistory(trackId) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { musicRepository.clearAllHistory() }
    }

    fun toggleAutoSuggest() {
        autoSuggestManager.toggleAutoSuggest()
        if (autoSuggestManager.isAutoSuggestEnabled.value) fetchAndAppendSuggestions()
    }

    fun fetchAndAppendSuggestions() {
        if (!isAutoSuggestEnabled.value) return
        val seed = playerState.value.currentTrack ?: return
        viewModelScope.launch {
            runCatching {
                val currentQueue = playerState.value.queue
                val freshTracks = autoSuggestManager.fetchNextSuggestions(seed, currentQueue, maxTracksToAppend = 3)
                freshTracks.forEach { track ->
                    musicRepository.insertTrack(track)
                    playerManager.addToQueue(track)
                }
            }
        }
    }

    fun markTrackPlayed(track: Track) {
        playedTrackKeys.add(track.sessionKey())
        autoSuggestManager.markTrackPlayed(track)
    }

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
        return CascadeViewModel(musicRepository, downloadRepository, resolverEngine, playerManager) as T
    }
}
