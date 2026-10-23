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
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

    private fun isLocalUriOrFileReadable(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return if (path.startsWith("content://")) {
            runCatching {
                com.example.CascadeApplication.instance.contentResolver
                    .openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            val f = java.io.File(path.removePrefix("file://"))
            f.exists() && f.length() > 0L
        }
    }

    val downloadedTracks: StateFlow<List<Track>> = combine(
        musicRepository.getDownloadedTracks(),
        com.seitharol.data.download.DownloadPreferencesAndService.getInstance(com.example.CascadeApplication.instance).completedDownloads,
        likedTrackIds
    ) { dbTracks, discoverDownloads, likedIds ->
        val dlService = com.seitharol.data.download.DownloadPreferencesAndService.getInstance(com.example.CascadeApplication.instance)
        val trackMap = LinkedHashMap<String, Track>()
        dbTracks.forEach { track ->
            // Skip any corrupted "Artist - Title" filename-scanned entries or entries whose local file was deleted
            val isFakeScannedId = track.id.contains(" - ") && track.artist == "Downloaded Music"
            if (!isFakeScannedId && isLocalUriOrFileReadable(track.localPath)) {
                val canonId = dlService.canonicalTrackId(track.id)
                trackMap[canonId] = track.copy(
                    id = canonId,
                    isLiked = track.isLiked || likedIds.contains(canonId) || likedIds.contains(track.id)
                )
            }
        }
        discoverDownloads.forEach { norm ->
            val canonId = dlService.canonicalTrackId(norm.sourceId)
            if (isLocalUriOrFileReadable(norm.localPath)) {
                val existing = trackMap[canonId] ?: trackMap.values.firstOrNull {
                    it.title.equals(norm.title, ignoreCase = true)
                }
                if (existing == null) {
                    trackMap[canonId] = Track(
                        id = canonId,
                        title = norm.title,
                        artist = norm.artists.firstOrNull() ?: "Unknown Artist",
                        album = norm.album ?: "Discover Downloads",
                        durationMs = norm.durationMs,
                        artUrl = norm.artUrl,
                        genre = "Downloaded",
                        isLiked = likedIds.contains(canonId) || norm.isLiked,
                        isDownloaded = true,
                        localPath = norm.localPath
                    )
                } else if (!existing.isDownloaded || existing.localPath.isNullOrBlank()) {
                    trackMap[existing.id] = existing.copy(
                        isDownloaded = true,
                        localPath = norm.localPath,
                        isLiked = existing.isLiked || likedIds.contains(canonId)
                    )
                }
            }
        }
        trackMap.values.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val downloadedTracksById: StateFlow<Map<String, Track>> = downloadedTracks
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun enrichTrackStatus(track: Track, likedIds: Set<String>, downloadedMap: Map<String, Track>): Track {
        val dl = downloadedMap[track.id]
        return track.copy(
            isLiked = likedIds.contains(track.id),
            isDownloaded = dl != null || track.isDownloaded,
            localPath = dl?.localPath ?: track.localPath
        )
    }

    val activeHistory: StateFlow<List<Track>> = combine(
        musicRepository.getActiveHistory(),
        likedTrackIds,
        downloadedTracksById
    ) { history, likedIds, downloadedMap ->
        history.map { enrichTrackStatus(it, likedIds, downloadedMap) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val archivedHistory: StateFlow<List<Track>> = combine(
        musicRepository.getArchivedHistory(),
        likedTrackIds,
        downloadedTracksById
    ) { history, likedIds, downloadedMap ->
        history.map { enrichTrackStatus(it, likedIds, downloadedMap) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    private val _playerExpandEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playerExpandEvent: SharedFlow<Unit> = _playerExpandEvent.asSharedFlow()

    fun navigateToArtist(artistName: String) {
        viewModelScope.launch { _artistNavigationEvent.emit(artistName) }
    }

    fun openSharedTrackById(
        trackId: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackArtUrl: String = ""
    ) {
        viewModelScope.launch {
            val cleanId = InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId.trim()
            val existing = musicRepository.getTrackById(cleanId)
            val trackToPlay = when {
                existing != null -> existing
                fallbackTitle.isNotBlank() -> Track(
                    id = cleanId,
                    title = fallbackTitle,
                    artist = fallbackArtist.ifBlank { "Artist" },
                    album = "Shared Track",
                    durationMs = 210_000L,
                    artUrl = fallbackArtUrl.ifBlank {
                        if (cleanId.length == 11) "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" else ""
                    }
                )
                else -> {
                    val oembed = resolverEngine.youtubeInnerTubeProvider.fetchMetadataById(cleanId)
                    Track(
                        id = cleanId,
                        title = oembed?.first ?: "Shared Track",
                        artist = oembed?.second ?: "Artist",
                        album = "Single",
                        durationMs = 210_000L,
                        artUrl = if (cleanId.length == 11) "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" else ""
                    )
                }
            }
            playTrack(trackToPlay)
            _playerExpandEvent.emit(Unit)
        }
    }

    fun playExternalAudioUri(
        uri: android.net.Uri,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        album: String = "Local Audio"
    ) {
        viewModelScope.launch {
            val trackId = "file_${uri.hashCode()}"
            val track = Track(
                id = trackId,
                title = title.ifBlank { "Audio File" },
                artist = artist.ifBlank { "Local Audio" },
                album = album.ifBlank { "Local Audio" },
                durationMs = durationMs,
                artUrl = "",
                genre = "Local Audio",
                isDownloaded = true,
                localPath = uri.toString()
            )
            playTrack(track)
            _playerExpandEvent.emit(Unit)
        }
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
    private var automixFetchJob: Job? = null

    val spotifyProviderInstance = com.seitharol.data.provider.DefaultSpotifyMetadataProvider()
    val discoverFeedSeeder = com.seitharol.domain.discover.DiscoverFeedSeeder(spotifyProviderInstance)
    private val _discoverTracks = MutableStateFlow<List<com.seitharol.domain.model.NormalizedTrack>>(emptyList())
    val discoverTracks: StateFlow<List<com.seitharol.domain.model.NormalizedTrack>> = _discoverTracks.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val searchFilter = MutableStateFlow(SearchFilter.ALL)
    val selectedProviderFilter = MutableStateFlow(ProviderFilter.ALL)
    private val onlineSearchResults = MutableStateFlow<List<Track>>(emptyList())
    val isSearchingOnline = MutableStateFlow(false)
    val hasCompletedOnlineSearch = MutableStateFlow(false)
    private var searchRequestToken = 0
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())

    fun selectProviderFilter(filter: ProviderFilter) {
        selectedProviderFilter.value = filter
        executeRankedSearch(searchQuery.value, filter)
    }

    private fun executeRankedSearch(query: String, filter: ProviderFilter) {
        val clean = query.trim()
        activeSearchJob?.cancel()
        if (clean.length < 2 || isOfflineMode.value) {
            searchRequestToken++
            onlineSearchResults.value = emptyList()
            isSearchingOnline.value = false
            hasCompletedOnlineSearch.value = false
            return
        }

        val token = ++searchRequestToken
        isSearchingOnline.value = true
        hasCompletedOnlineSearch.value = false

        activeSearchJob = viewModelScope.launch {
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
                if (token == searchRequestToken) {
                    isSearchingOnline.value = false
                    hasCompletedOnlineSearch.value = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Search failed: ${e.message}")
                if (token == searchRequestToken) {
                    isSearchingOnline.value = false
                    hasCompletedOnlineSearch.value = true
                }
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
                .collectLatest { query ->
                    val clean = query.trim()
                    if (clean.length >= 2 && !isOfflineMode.value) {
                        // Immediately set loading state before debounce so "No songs found" never flashes while typing
                        isSearchingOnline.value = true
                        hasCompletedOnlineSearch.value = false
                        delay(320L)
                        executeRankedSearch(clean, selectedProviderFilter.value)
                        runCatching { NetworkClients.getSearchSuggestions(clean) }
                            .onSuccess { searchSuggestions.value = it }
                            .onFailure { searchSuggestions.value = emptyList() }
                    } else {
                        activeSearchJob?.cancel()
                        searchRequestToken++
                        onlineSearchResults.value = emptyList()
                        searchSuggestions.value = emptyList()
                        isSearchingOnline.value = false
                        hasCompletedOnlineSearch.value = false
                    }
                }
        }

        viewModelScope.launch {
            autoSuggestManager.isAutoSuggestEnabled.collect { enabled ->
                playerManager.isAutoplayEnabled = enabled
            }
        }

        // Keep Home & Discover shelves reactively synced with liked/downloaded state
        viewModelScope.launch {
            combine(likedTrackIds, downloadedTracksById) { likedIds, downloadedMap ->
                likedIds to downloadedMap
            }.collect { (likedIds, downloadedMap) ->
                if (trendingHits.value.isNotEmpty()) {
                    trendingHits.value = trendingHits.value.map { enrichTrackStatus(it, likedIds, downloadedMap) }
                }
                if (freshReleases.value.isNotEmpty()) {
                    freshReleases.value = freshReleases.value.map { enrichTrackStatus(it, likedIds, downloadedMap) }
                }
                if (personalizedTracks.value.isNotEmpty()) {
                    personalizedTracks.value = personalizedTracks.value.map { enrichTrackStatus(it, likedIds, downloadedMap) }
                }
                if (_discoverTracks.value.isNotEmpty()) {
                    _discoverTracks.value = _discoverTracks.value.map { norm ->
                        norm.copy(
                            isLiked = likedIds.contains(norm.sourceId),
                            localPath = downloadedMap[norm.sourceId]?.localPath ?: norm.localPath
                        )
                    }
                }
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
                val likedIds = realLiked.map { it.id }.toSet()
                val dlMap = downloadedTracksById.value

                val recentSearches = try {
                    com.example.data.AppPreferences(com.example.CascadeApplication.instance).recentSearches.value
                } catch (e: Exception) {
                    emptyList()
                }

                val bannedKeywords = setOf(
                    "unknown artist", "unknown", "local artist", "device storage",
                    "downloaded in seitharol", "<unknown>", "various artists", "various", "download", "seitharol"
                )

                val seedIds = (realHistory.take(5).map { it.id } + realLiked.take(3).map { it.id })
                    .filter { it.length == 11 }
                    .distinct()

                val historyArtists = (realHistory + realLiked)
                    .map { it.artist.trim() }
                    .filter { it.isNotBlank() && !bannedKeywords.contains(it.lowercase()) && !bannedKeywords.any { b -> it.lowercase().contains(b) } }

                val searchArtists = recentSearches
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !bannedKeywords.contains(it.lowercase()) && !bannedKeywords.any { b -> it.lowercase().contains(b) } }

                val topArtists = (searchArtists + historyArtists)
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(5)

                // 1. Personalized Quick Picks / For You feed via YouTube Music RDAMVM Automix + Top Artists / Searches
                val personalizedCandidates = InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                    seedVideoIds = seedIds,
                    favoriteArtists = topArtists,
                    moodCategory = selectedMood.value
                )
                if (personalizedCandidates.isNotEmpty()) {
                    val mapped = personalizedCandidates
                        .filterNot { candidate ->
                            val a = candidate.artist.lowercase().trim()
                            val t = candidate.title.lowercase().trim()
                            bannedKeywords.any { b -> a.contains(b) || t.contains(b) } ||
                                t.contains("supermix") || t.contains("radio")
                        }
                        .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                    personalizedTracks.value = mapped
                    trendingHits.value = mapped.take(12)
                }

                // 2. Regional / Manipuri & Fresh Releases shelf
                val freshCandidates = InnerTubeStreamExtractor.searchOfficialYtmSongs(
                    query = if (topArtists.isNotEmpty()) "${topArtists.first()} new songs" else "Manipuri latest official songs",
                    limit = 12
                )
                if (freshCandidates.isNotEmpty()) {
                    freshReleases.value = freshCandidates
                        .filterNot { candidate ->
                            val a = candidate.artist.lowercase().trim()
                            val t = candidate.title.lowercase().trim()
                            bannedKeywords.any { b -> a.contains(b) || t.contains(b) }
                        }
                        .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
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
            val dlService = com.seitharol.data.download.DownloadPreferencesAndService.getInstance(com.example.CascadeApplication.instance)
            val canonId = dlService.canonicalTrackId(normalized.sourceId)
            val localTrack = Track(
                id = canonId,
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
        val likedIds = likedTrackIds.value

        // 1. YouTube Music official songs FIRST
        runCatching {
            val ytmTracks = com.seitharol.data.resolver.InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 25)
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
                    previewUrl = "ytm_clip:${yt.videoId}",
                    isLiked = likedIds.contains(yt.videoId)
                )
            }
            results.addAll(mapped)
        }

        // 2. Supplementary Spotify preview tracks as fallback
        runCatching {
            val previewTracks = spotifyProviderInstance.searchTracksWithPreviews(clean, limit = 15)
            results.addAll(previewTracks.map { it.copy(isLiked = likedIds.contains(it.sourceId)) })
        }

        val cleanLower = clean.lowercase()
        // Rank results so the actual original track matching query and title is strictly at position 0
        return results.distinctBy { it.sourceId }.sortedWith(
            compareByDescending<com.seitharol.domain.model.NormalizedTrack> { track ->
                val titleLower = track.title.lowercase().trim()
                val artistLower = track.artists.firstOrNull().orEmpty().lowercase().trim()
                when {
                    titleLower == cleanLower -> 100
                    titleLower.startsWith(cleanLower) -> 80
                    "$titleLower $artistLower".contains(cleanLower) -> 60
                    titleLower.contains(cleanLower) -> 50
                    artistLower.contains(cleanLower) -> 30
                    else -> 10
                }
            }.thenByDescending {
                if (it.previewUrl?.startsWith("ytm_clip:") == true) 10 else 0
            }
        )
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
                val likedIds = likedTrackIds.value
                val dlMap = downloadedTracksById.value

                val feed = InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                    seedVideoIds = seedIds,
                    favoriteArtists = topArtists,
                    moodCategory = targetMood
                ).map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }

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
        combine(downloadedTracks, onlineSearchResults, likedTrackIds) { downloaded, onlineTracks, likedIds ->
            Triple(downloaded, onlineTracks, likedIds)
        }
    ) { (query, filter, provider), (downloaded, onlineTracks, likedIds) ->
        val cleanQuery = query.trim()
        val dlMap = downloaded.associateBy { it.id }

        val baseList: List<Track> = if (provider == ProviderFilter.LOCAL) {
            if (cleanQuery.isBlank()) downloaded
            else downloaded.filter {
                it.title.contains(cleanQuery, ignoreCase = true) ||
                    it.artist.contains(cleanQuery, ignoreCase = true)
            }
        } else {
            // Never dump fake/demo database tracks when searchQuery is blank
            if (cleanQuery.isBlank()) emptyList()
            else onlineTracks.map { enrichTrackStatus(it, likedIds, dlMap) }
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
        val isOffline = track.isDownloaded || !track.localPath.isNullOrBlank()
        if (isOffline) {
            autoSuggestManager.setAutoSuggestEnabled(false)
            playerManager.isAutoplayEnabled = false
        }
        automixFetchJob?.cancel()
        playerManager.playTrack(track)
        automixFetchJob = viewModelScope.launch {
            musicRepository.insertTrack(track)
            musicRepository.recordPlayHistory(track)
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, isPlaylist: Boolean = true) {
        val hasOffline = tracks.any { it.isDownloaded || !it.localPath.isNullOrBlank() }
        if (hasOffline) {
            autoSuggestManager.setAutoSuggestEnabled(false)
            playerManager.isAutoplayEnabled = false
        }
        viewModelScope.launch { tracks.forEach { musicRepository.insertTrack(it) } }
        playerManager.playQueue(tracks, startIndex, isPlaylist = isPlaylist)
    }

    fun playPlaylistOrMix(playlistId: String) {
        viewModelScope.launch {
            if (playlistId == "liked_songs" || playlistId == "pl_liked_songs") {
                val liked = likedTracks.value
                if (liked.isNotEmpty()) {
                    playQueue(liked, 0, isPlaylist = true)
                }
                return@launch
            }
            val localTracks = _userPlaylists.value[playlistId] ?: musicRepository.getTracksForPlaylist(playlistId).firstOrNull()
            if (!localTracks.isNullOrEmpty()) {
                playQueue(localTracks, 0, isPlaylist = true)
                return@launch
            }
            runCatching {
                val details = InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(playlistId)
                val likedIds = likedTrackIds.value
                val dlMap = downloadedTracksById.value
                val tracks = details.tracks.map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                if (tracks.isNotEmpty()) {
                    playQueue(tracks, 0, isPlaylist = true)
                } else {
                    val seed = playlistId.removePrefix("VL").removePrefix("RDAMVM")
                    if (seed.length == 11) {
                        val automix = InnerTubeStreamExtractor.fetchAutomixQueue(seed, limit = 35)
                        if (automix.isNotEmpty()) {
                            playQueue(automix.map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }, 0, isPlaylist = true)
                        }
                    }
                }
            }
        }
    }

    fun playSingleRelease(collection: com.seitharol.data.resolver.YtmCollectionSummary, artistName: String) {
        viewModelScope.launch {
            val directVid = InnerTubeStreamExtractor.extractVideoId(collection.playlistId)
                ?: InnerTubeStreamExtractor.extractVideoId(collection.browseId)
            if (directVid != null) {
                val directTrack = Track(
                    id = directVid,
                    title = collection.title,
                    artist = artistName,
                    album = "Single",
                    durationMs = 210_000L,
                    artUrl = collection.thumbnailUrl
                )
                playTrack(enrichTrackStatus(directTrack, likedTrackIds.value, downloadedTracksById.value))
                return@launch
            }

            val candidate = runCatching {
                InnerTubeStreamExtractor.searchOfficialYtmSongs("${collection.title} $artistName", limit = 1).firstOrNull()
            }.getOrNull() ?: runCatching {
                InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(collection.browseId).tracks.firstOrNull()
            }.getOrNull()

            if (candidate != null) {
                playTrack(enrichTrackStatus(candidate.toTrack(), likedTrackIds.value, downloadedTracksById.value))
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
                val likedIds = likedTrackIds.value
                val dlMap = downloadedTracksById.value
                val radioTracks = listOf(seedTrack) + automix.filter { it.videoId != seedTrack.id }
                    .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                if (radioTracks.size > 1) {
                    playerManager.updateQueuePreservingPlayback(radioTracks)
                }
            }
        }
    }

    fun loadPlaylistTracks(playlistId: String, onResult: (List<Track>) -> Unit) {
        viewModelScope.launch {
            val likedIds = likedTrackIds.value
            val dlMap = downloadedTracksById.value
            val dbTracks = musicRepository.getTracksForPlaylist(playlistId).firstOrNull()
            if (!dbTracks.isNullOrEmpty()) {
                onResult(dbTracks.map { enrichTrackStatus(it, likedIds, dlMap) })
                return@launch
            }
            val localTracks = _userPlaylists.value[playlistId]
            if (!localTracks.isNullOrEmpty()) {
                onResult(localTracks.map { enrichTrackStatus(it, likedIds, dlMap) })
                return@launch
            }
            val remoteDetails = InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(playlistId)
            val tracks = remoteDetails.tracks.map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
            if (tracks.isNotEmpty()) {
                onResult(tracks)
            } else {
                val cleanId = playlistId.removePrefix("VL").removePrefix("RDAMVM")
                if (cleanId.length == 11) {
                    val automix = InnerTubeStreamExtractor.fetchAutomixQueue(cleanId, limit = 35)
                    onResult(automix.map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) })
                } else {
                    onResult(emptyList())
                }
            }
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun pause() = playerManager.pause()
    fun stopPlayback() = playerManager.stopPlayback()
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
            val track = allTracks.value.firstOrNull { it.id == trackId }
                ?: onlineSearchResults.value.firstOrNull { it.id == trackId }
                ?: trendingHits.value.firstOrNull { it.id == trackId }
                ?: freshReleases.value.firstOrNull { it.id == trackId }
                ?: personalizedTracks.value.firstOrNull { it.id == trackId }
                ?: downloadedTracks.value.firstOrNull { it.id == trackId }
                ?: playerState.value.currentTrack?.takeIf { it.id == trackId }
                ?: musicRepository.getTrackById(trackId)
            if (track != null) {
                musicRepository.insertTrack(track)
            }
            musicRepository.addTrackToPlaylist(playlistId, trackId)
            if (track != null) {
                val currentList = _userPlaylists.value[playlistId].orEmpty()
                _userPlaylists.value = _userPlaylists.value + (playlistId to (currentList.filterNot { it.id == track.id } + track))
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            musicRepository.insertTrack(track)
            musicRepository.addTrackToPlaylist(playlistId, track.id)
            val currentList = _userPlaylists.value[playlistId].orEmpty()
            _userPlaylists.value = _userPlaylists.value + (playlistId to (currentList.filterNot { it.id == track.id } + track))
        }
    }

    fun togglePlaylistLike(playlist: Playlist) {
        viewModelScope.launch { musicRepository.togglePlaylistLike(playlist.id, playlist.isLiked) }
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
    fun setTemporaryTrackQuality(quality: AudioQuality) = playerManager.setTemporaryTrackQuality(quality)
    fun getTemporaryTrackQuality(): AudioQuality? = playerManager.getTemporaryTrackQuality()

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
