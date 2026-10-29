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
import com.example.ui.components.SeitharolDeepLinkHelper
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ProviderFilter(val label: String) {
    ALL("All"),
    MUSIC("Music"),
    JIOSAAVN("JioSaavn"),
    YOUTUBE("YouTube"),
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

    private val vmExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("CascadeViewModel", "Handled coroutine exception: ${throwable.message}", throwable)
    }

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
            val rawPath = if (path.startsWith("file://")) {
                android.net.Uri.parse(path).path ?: path.removePrefix("file://")
            } else path
            val f = java.io.File(rawPath)
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
            val isFakeScannedId = track.artist == "Downloaded Music" &&
                (track.id.contains(" - ") || track.id.endsWith("_video", true) || track.id.endsWith("_data", true) || track.album == "Offline Storage")
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
                    val isMp4 = norm.localPath?.lowercase()?.endsWith(".mp4") == true
                    trackMap[canonId] = Track(
                        id = canonId,
                        title = norm.title,
                        artist = norm.artists.firstOrNull() ?: "Unknown Artist",
                        album = norm.album?.takeUnless {
                            it.equals("Discover Downloads", ignoreCase = true) ||
                                it.equals("YouTube Video", ignoreCase = true) ||
                                it.equals("YouTube Videos", ignoreCase = true)
                        }.orEmpty(),
                        durationMs = norm.durationMs,
                        artUrl = norm.artUrl,
                        genre = if (isMp4) "YouTube Video" else "Downloaded",
                        codec = if (isMp4) "MP4" else "Opus",
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
        trackMap.values.distinctBy { it.title.lowercase().trim() }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val downloadedTracksById: StateFlow<Map<String, Track>> = downloadedTracks
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun enrichTrackStatus(track: Track, likedIds: Set<String>, downloadedMap: Map<String, Track>): Track {
        val dl = downloadedMap[track.id]
        return track.copy(
            isLiked = likedIds.contains(track.id),
            isDownloaded = dl != null || track.isDownloaded,
            localPath = dl?.localPath ?: track.localPath,
            genre = if (track.genre.equals("YouTube Video", ignoreCase = true) || track.album.equals("YouTube Video", ignoreCase = true)) "YouTube Video" else track.genre
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalDownloadedBytes: StateFlow<Long> = downloadRepository.getTotalDownloadedBytes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val playerState: StateFlow<PlayerState> = playerManager.state
    val resolverNodes: StateFlow<List<ResolverNode>> = resolverEngine.activeNodes
    val isOfflineMode: StateFlow<Boolean> = resolverEngine.isOfflineMode
    val circuitBreakerState: StateFlow<CircuitBreakerState> = resolverEngine.circuitBreakerState
    val currentPackVersion: StateFlow<String> = resolverEngine.currentPackVersion
    val skipNotice: SharedFlow<String> = playerManager.skipEvent

    // Hoisted fullscreen video state so landscape orientation changes never reset fullscreen mode
    val isFullscreenVideo = MutableStateFlow(false)
    fun setFullscreenVideo(fullscreen: Boolean) {
        isFullscreenVideo.value = fullscreen
    }

    private val _artistNavigationEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val artistNavigationEvent: SharedFlow<String> = _artistNavigationEvent.asSharedFlow()
    val isArtistNavigationChannel = MutableStateFlow(false)

    private val _playerExpandEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playerExpandEvent: SharedFlow<Unit> = _playerExpandEvent.asSharedFlow()

    private val nonNavigableArtists = setOf(
        "unknown artist",
        "<unknown>",
        "unknown",
        "local audio",
        "local video",
        "local artist",
        "device storage",
        "downloaded music",
        "local storage",
        "various artists"
    )

    fun navigateToArtist(artistName: String, isChannel: Boolean = false) {
        val cleanName = artistName.trim()
        if (cleanName.isBlank() || cleanName.lowercase() in nonNavigableArtists) return
        if (playerState.value.currentTrack?.isLocalOnlyTrack == true &&
            playerState.value.currentTrack?.artist?.equals(cleanName, ignoreCase = true) == true
        ) {
            return
        }
        isArtistNavigationChannel.value = isChannel
        viewModelScope.launch(vmExceptionHandler) { _artistNavigationEvent.emit(cleanName) }
    }

    fun openSharedTrackById(
        trackId: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackArtUrl: String = ""
    ) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                val cleanId = InnerTubeStreamExtractor.extractVideoId(trackId) ?: trackId.trim()
                val existing = musicRepository.getTrackById(cleanId)
                    ?: downloadedTracks.value.firstOrNull { it.id == cleanId }
                val trackToPlay = when {
                    existing != null -> existing
                    fallbackTitle.isNotBlank() -> Track(
                        id = cleanId,
                        title = fallbackTitle,
                        artist = fallbackArtist.ifBlank { "Artist" },
                        album = "",
                        durationMs = 210_000L,
                        artUrl = fallbackArtUrl.ifBlank {
                            if (cleanId.length == 11) "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" else ""
                        }
                    )
                    else -> {
                        val oembed = runCatching {
                            resolverEngine.youtubeInnerTubeProvider.fetchMetadataById(cleanId)
                        }.getOrNull()
                        Track(
                            id = cleanId,
                            title = oembed?.first ?: "Track",
                            artist = oembed?.second ?: "Artist",
                            album = "",
                            durationMs = 210_000L,
                            artUrl = if (cleanId.length == 11) "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" else ""
                        )
                    }
                }
                playTrack(trackToPlay)
                _playerExpandEvent.emit(Unit)
            }
        }
    }

    // Secondary Video Source: Dynamic Queue Suggestions (includes YouTube Video suggestions)
    private val _queueVideoSuggestions = MutableStateFlow<List<Track>>(emptyList())
    val queueVideoSuggestions: StateFlow<List<Track>> = _queueVideoSuggestions.asStateFlow()

    fun refreshQueueSuggestions(seedTrack: Track?) {
        val state = playerState.value
        val seed = seedTrack ?: state.currentTrack
        val isLocalSingle = seed != null && seed.isLocalOnlyTrack && state.queue.all { it.isLocalOnlyTrack }
        // Suppress suggestions ONLY when offline or when playing a local single file (no online tracks in queue)
        if (seed == null || isOfflineMode.value || isLocalSingle) {
            _queueVideoSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                val effectiveSeed = if (!seed.isLocalOnlyTrack) seed else state.queue.firstOrNull { !it.isLocalOnlyTrack } ?: return@launch
                val isVideo = state.isVideoMode || effectiveSeed.isVideoTrack || (state.queue.isNotEmpty() && state.queue.all { it.isVideoTrack })
                val historyArtistNames = activeHistory.value
                    .filterNot { it.isLocalOnlyTrack }
                    .map { it.artist.trim() }
                    .filter { it.isNotBlank() && !it.equals("Unknown Artist", true) }
                    .distinct()
                    .take(5)
                val queueMeta = state.queue.filterNot { it.isLocalOnlyTrack }.map {
                    com.seitharol.data.resolver.TrackMetadata(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        album = if (it.isVideoTrack) "YouTube Video" else it.album,
                        durationSeconds = (it.durationMs / 1000L).toInt(),
                        thumbnailUrl = it.artUrl,
                        source = if (it.isVideoTrack) "YOUTUBE_VIDEO" else "YOUTUBE"
                    )
                }
                val seedMeta = com.seitharol.data.resolver.TrackMetadata(
                    id = effectiveSeed.id,
                    title = effectiveSeed.title,
                    artist = effectiveSeed.artist,
                    album = if (isVideo) "YouTube Video" else effectiveSeed.album.takeUnless {
                        it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
                    }.orEmpty(),
                    durationSeconds = (effectiveSeed.durationMs / 1000L).toInt(),
                    thumbnailUrl = effectiveSeed.artUrl,
                    source = if (isVideo) "YOUTUBE_VIDEO" else "YOUTUBE"
                )
                val recs = com.seitharol.player.QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    historyArtists = historyArtistNames,
                    limit = 14,
                    additionalExcludedIds = autoSuggestManager.getPlayedIds(),
                    additionalExcludedSignatures = autoSuggestManager.getPlayedSignatures()
                )
                _queueVideoSuggestions.value = recs.map { r ->
                    val recIsVideo = isVideo || r.source.equals("YOUTUBE_VIDEO", ignoreCase = true)
                    Track(
                        id = r.id,
                        title = r.title,
                        artist = r.artist,
                        album = if (recIsVideo) "YouTube Video" else r.album.takeUnless {
                            it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
                        }.orEmpty(),
                        durationMs = r.durationSeconds * 1000L,
                        artUrl = r.thumbnailUrl,
                        genre = if (recIsVideo) "YouTube Video" else "Music",
                        itag = if (recIsVideo) 18 else 251,
                        codec = if (recIsVideo) "MP4" else "Opus"
                    )
                }
            }
        }
    }

    fun playExternalAudioUri(
        uri: android.net.Uri,
        title: String,
        artist: String,
        durationMs: Long = 0L,
        album: String = "Local Media",
        isVideo: Boolean = false
    ) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                val uriStr = uri.toString()
                val detectedVideo = isVideo ||
                    uriStr.lowercase().endsWith(".mp4") ||
                    uriStr.lowercase().endsWith(".mkv") ||
                    uriStr.lowercase().endsWith(".webm") ||
                    uriStr.lowercase().endsWith(".mov") ||
                    uriStr.lowercase().contains("/video/") ||
                    album.equals("YouTube Video", ignoreCase = true) ||
                    album.equals("Local Video", ignoreCase = true) ||
                    album.equals("MP4 Video", ignoreCase = true)

                val trackId = if (detectedVideo) "vidfile_${uri.hashCode()}" else "file_${uri.hashCode()}"
                val track = Track(
                    id = trackId,
                    title = title.ifBlank { if (detectedVideo) "Video File" else "Audio File" },
                    artist = artist.ifBlank { "Unknown Artist" },
                    album = if (detectedVideo) "MP4 Video" else album.takeUnless {
                        it.equals("YouTube Video", ignoreCase = true) || it.equals("Local Audio", ignoreCase = true)
                    }.orEmpty(),
                    durationMs = durationMs,
                    artUrl = "",
                    genre = if (detectedVideo) "Local Video" else "Local Audio",
                    isDownloaded = true,
                    localPath = uriStr,
                    itag = if (detectedVideo) 18 else 251,
                    codec = if (detectedVideo) "MP4" else "Opus"
                )
                playTrack(track)
                _playerExpandEvent.emit(Unit)
            }
        }
    }

    val trendingHits = MutableStateFlow<List<Track>>(emptyList())
    val freshReleases = MutableStateFlow<List<Track>>(emptyList())
    val personalizedTracks = MutableStateFlow<List<Track>>(emptyList())
    val suggestedYoutubeMixes = MutableStateFlow<List<com.seitharol.data.resolver.YtmCollectionSummary>>(emptyList())
    val suggestedYoutubePlaylists = MutableStateFlow<List<com.seitharol.data.resolver.YtmCollectionSummary>>(emptyList())
    val selectedMood = MutableStateFlow("All")
    val isLoadingHome = MutableStateFlow(true)
    val isHomeLoading: StateFlow<Boolean> = isLoadingHome.asStateFlow()
    val hasCompletedInitialHomeLoad = MutableStateFlow(false)
    val trendingSearches = MutableStateFlow<List<String>>(emptyList())

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
    val searchedVerifiedArtists = MutableStateFlow<List<com.seitharol.data.resolver.YtmArtistPreview>>(emptyList())
    val searchedOnlinePlaylists = MutableStateFlow<List<com.seitharol.data.resolver.YtmCollectionSummary>>(emptyList())
    val searchedOnlineAlbums = MutableStateFlow<List<com.seitharol.data.resolver.YtmCollectionSummary>>(emptyList())
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

        SeitharolDeepLinkHelper.parseSharedTextOrUrl(clean)?.let { target ->
            when (target) {
                is SeitharolDeepLinkHelper.DeepLinkTarget.TrackTarget -> {
                    searchQuery.value = ""
                    openSharedTrackById(target.trackId, target.title, target.artist, target.artUrl)
                    return
                }
                is SeitharolDeepLinkHelper.DeepLinkTarget.ArtistTarget -> {
                    searchQuery.value = ""
                    navigateToArtist(target.artistName)
                    return
                }
                else -> Unit
            }
        }

        if (clean.length < 2 || isOfflineMode.value || !com.example.util.ErrorFormatter.isOnline(com.example.CascadeApplication.instance)) {
            searchRequestToken++
            onlineSearchResults.value = emptyList()
            searchedVerifiedArtists.value = emptyList()
            searchedOnlinePlaylists.value = emptyList()
            searchedOnlineAlbums.value = emptyList()
            isSearchingOnline.value = false
            hasCompletedOnlineSearch.value = true
            return
        }

        val token = ++searchRequestToken
        isSearchingOnline.value = true
        hasCompletedOnlineSearch.value = false

        activeSearchJob = viewModelScope.launch(vmExceptionHandler) {
            try {
                val verifiedArtistsDeferred = async {
                    if (filter == ProviderFilter.ALL || filter == ProviderFilter.MUSIC) {
                        InnerTubeStreamExtractor.searchVerifiedArtists(clean, limit = 5)
                    } else {
                        emptyList()
                    }
                }
                val onlinePlaylistsDeferred = async {
                    if (filter != ProviderFilter.LOCAL && filter != ProviderFilter.JIOSAAVN) {
                        InnerTubeStreamExtractor.searchYtmCollections(
                            query = clean,
                            filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==",
                            type = "PLAYLIST",
                            limit = 10
                        )
                    } else {
                        emptyList()
                    }
                }
                val onlineAlbumsDeferred = async {
                    if (filter == ProviderFilter.ALL || filter == ProviderFilter.MUSIC) {
                        InnerTubeStreamExtractor.searchYtmCollections(
                            query = clean,
                            filterParams = "EgWKAQIYAWoKEAkQChADEAUQBA==",
                            type = "ALBUM",
                            limit = 8
                        )
                    } else {
                        emptyList()
                    }
                }
                val rawCandidates = when (filter) {
                    ProviderFilter.ALL -> {
                        val ytDeferred = async {
                            InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 18)
                                .filterNot { it.videoType == "MUSIC_VIDEO_TYPE_OMV" || it.albumName == "YouTube Video" }
                        }
                        val saavnDeferred = async {
                            resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 8).map {
                                val cleanSaavnId = it.sourceId.removePrefix("saavn_")
                                com.seitharol.data.resolver.CandidateStream(
                                    videoId = "saavn_$cleanSaavnId",
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
                    ProviderFilter.MUSIC -> {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(clean, limit = 25)
                            .filterNot { it.videoType == "MUSIC_VIDEO_TYPE_OMV" || it.albumName == "YouTube Video" }
                    }
                    ProviderFilter.JIOSAAVN -> {
                        resolverEngine.jioSaavnProvider.searchTracks(clean, limit = 25).map {
                            val cleanSaavnId = it.sourceId.removePrefix("saavn_")
                            com.seitharol.data.resolver.CandidateStream(
                                videoId = "saavn_$cleanSaavnId",
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
                    ProviderFilter.YOUTUBE -> InnerTubeStreamExtractor.searchAllYoutubeVideos(clean, limit = 25)
                    ProviderFilter.LOCAL -> emptyList()
                }

                val ranked = if (filter == ProviderFilter.YOUTUBE) {
                    rawCandidates.distinctBy { it.videoId }
                } else {
                    CanonicalAudioResolver.rankSearchResultsByRelevance(clean, rawCandidates)
                }

                onlineSearchResults.value = ranked.map { c ->
                    val isYtVideo = filter == ProviderFilter.YOUTUBE || c.videoType == "MUSIC_VIDEO_TYPE_OMV"
                    val cleanAlbum = c.albumName?.takeUnless {
                        it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
                    }.orEmpty()
                    Track(
                        id = c.videoId,
                        title = c.title,
                        artist = c.artist,
                        album = cleanAlbum,
                        durationMs = c.durationSeconds * 1000L,
                        artUrl = c.thumbnailUrl,
                        genre = if (isYtVideo) "YouTube Video" else "Music",
                        itag = if (isYtVideo) 18 else 251,
                        codec = if (isYtVideo) "MP4" else "Opus"
                    )
                }
                val verifiedArtists = runCatching { verifiedArtistsDeferred.await() }.getOrDefault(emptyList())
                val onlinePlaylists = runCatching { onlinePlaylistsDeferred.await() }.getOrDefault(emptyList())
                val onlineAlbums = runCatching { onlineAlbumsDeferred.await() }.getOrDefault(emptyList())
                if (token == searchRequestToken) {
                    searchedVerifiedArtists.value = verifiedArtists
                    searchedOnlinePlaylists.value = onlinePlaylists
                    searchedOnlineAlbums.value = onlineAlbums
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

    private val recommendationRepository: com.example.player.RecommendationRepository = object : com.example.player.RecommendationRepository {
        override suspend fun getRelatedTracks(seedTrack: Track): List<Track> {
            if (seedTrack.isLocalOnlyTrack) return emptyList()
            return try {
                val state = playerState.value
                val isVideo = state.isVideoMode || seedTrack.isVideoTrack || (state.queue.isNotEmpty() && state.queue.all { it.isVideoTrack })
                val queueMeta = state.queue.filterNot { it.isLocalOnlyTrack }.map {
                    com.seitharol.data.resolver.TrackMetadata(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        album = if (it.isVideoTrack) "YouTube Video" else it.album,
                        durationSeconds = (it.durationMs / 1000L).toInt(),
                        thumbnailUrl = it.artUrl,
                        source = if (it.isVideoTrack) "YOUTUBE_VIDEO" else "YOUTUBE"
                    )
                }
                val seedMeta = com.seitharol.data.resolver.TrackMetadata(
                    id = seedTrack.id,
                    title = seedTrack.title,
                    artist = seedTrack.artist,
                    album = if (isVideo) "YouTube Video" else seedTrack.album,
                    durationSeconds = (seedTrack.durationMs / 1000L).toInt(),
                    thumbnailUrl = seedTrack.artUrl,
                    source = if (isVideo) "YOUTUBE_VIDEO" else "YOUTUBE"
                )
                val recs = com.seitharol.player.QueueRecommendationEngine.fetchRelevantRecommendations(
                    currentTrack = seedMeta,
                    queueTracks = queueMeta,
                    limit = 12,
                    additionalExcludedIds = autoSuggestManager.getPlayedIds(),
                    additionalExcludedSignatures = autoSuggestManager.getPlayedSignatures()
                )
                recs.map { r ->
                    val recIsVideo = isVideo || r.source.equals("YOUTUBE_VIDEO", ignoreCase = true)
                    Track(
                        id = r.id,
                        title = r.title,
                        artist = r.artist,
                        album = if (recIsVideo) "YouTube Video" else r.album.takeUnless {
                            it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
                        }.orEmpty(),
                        durationMs = r.durationSeconds * 1000L,
                        artUrl = r.thumbnailUrl,
                        genre = if (recIsVideo) "YouTube Video" else "Music",
                        itag = if (recIsVideo) 18 else 251,
                        codec = if (recIsVideo) "MP4" else "Opus"
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        override suspend fun getRelatedPlaylists(seedTrack: Track): List<com.example.player.SuggestedPlaylist> {
            if (seedTrack.isLocalOnlyTrack || isOfflineMode.value) return emptyList()
            return try {
                val cleanArtist = seedTrack.artist.removeSuffix(" - Topic").removeSuffix("- Topic").trim()
                val seedVid = InnerTubeStreamExtractor.extractVideoId(seedTrack.id)
                val out = mutableListOf<com.example.player.SuggestedPlaylist>()
                if (!seedVid.isNullOrBlank()) {
                    out.add(
                        com.example.player.SuggestedPlaylist(
                            id = "RDAMVM$seedVid",
                            title = "${seedTrack.title.take(24)} Radio Mix",
                            thumbnailUrl = seedTrack.artUrl,
                            tracks = emptyList(),
                            subtitle = "YouTube Mix • Continuous radio",
                            artUrl = seedTrack.artUrl,
                            trackCount = 35
                        )
                    )
                }
                val collections = InnerTubeStreamExtractor.searchYtmCollections(
                    query = "$cleanArtist Mix Playlist",
                    filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==",
                    type = "PLAYLIST",
                    limit = 5
                )
                collections.forEach { col ->
                    val resolvedThumb = col.thumbnailUrl.ifBlank { seedTrack.artUrl }
                    out.add(
                        com.example.player.SuggestedPlaylist(
                            id = col.browseId,
                            title = col.title,
                            thumbnailUrl = resolvedThumb,
                            tracks = emptyList(),
                            subtitle = col.subtitle,
                            artUrl = resolvedThumb,
                            trackCount = 25
                        )
                    )
                }
                out.distinctBy { it.id }.take(6)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val autoSuggestManager: com.example.player.AutoSuggestQueueManager = com.example.player.AutoSuggestQueueManager(recommendationRepository)
    val isAutoSuggestEnabled: StateFlow<Boolean> = autoSuggestManager.isAutoSuggestEnabled
    val suggestedPlaylists: StateFlow<List<com.example.player.SuggestedPlaylist>> = autoSuggestManager.suggestedPlaylists

    init {
        playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value && !isOfflineMode.value
        loadHomeScreenMusic()
        viewModelScope.launch(vmExceptionHandler) {
            delay(1500L)
            downloadRepository.rescanLocalDownloads()
            delay(1500L)
            loadTrendingSearches()
        }

        viewModelScope.launch(vmExceptionHandler) {
            searchQuery
                .collectLatest { query ->
                    val clean = query.trim()
                    if (clean.length >= 2) {
                        if (!isOfflineMode.value && com.example.util.ErrorFormatter.isOnline(com.example.CascadeApplication.instance)) {
                            isSearchingOnline.value = true
                            hasCompletedOnlineSearch.value = false
                            delay(320L)
                            executeRankedSearch(clean, selectedProviderFilter.value)
                            runCatching { NetworkClients.getSearchSuggestions(clean) }
                                .onSuccess { searchSuggestions.value = it }
                                .onFailure { searchSuggestions.value = emptyList() }
                        } else {
                            executeRankedSearch(clean, selectedProviderFilter.value)
                        }
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

        viewModelScope.launch(vmExceptionHandler) {
            autoSuggestManager.isAutoSuggestEnabled.collect { enabled ->
                playerManager.isAutoplayEnabled = enabled && !isOfflineMode.value
            }
        }

        viewModelScope.launch(vmExceptionHandler) {
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

        // Reactive Homefeed Personalization Observer:
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        // Automatically updates Home recommendations whenever the user plays an online song,
        // likes/unlikes a track, or performs a new search!
        viewModelScope.launch(vmExceptionHandler) {
            val prefs = com.example.data.AppPreferences.getInstance(com.example.CascadeApplication.instance)
            combine(
                activeHistory.map { list -> list.take(6).joinToString(",") { it.id } }.distinctUntilChanged(),
                likedTracks.map { list -> list.take(6).joinToString(",") { it.id } }.distinctUntilChanged(),
                prefs.recentSearches.map { list -> list.take(4).joinToString(",") }.distinctUntilChanged()
            ) { histSig, likedSig, searchSig ->
                "$histSig|$likedSig|$searchSig"
            }
                .drop(1)
                .debounce(1200L)
                .collectLatest { sig ->
                    if (sig.isNotBlank() && sig != "||" && !isOfflineMode.value) {
                        loadHomeScreenMusic(isBackgroundUpdate = true)
                    }
                }
        }
    }

    private var homeLoadJob: Job? = null

    fun loadHomeScreenMusic(isBackgroundUpdate: Boolean = false) {
        homeLoadJob?.cancel()
        homeLoadJob = viewModelScope.launch(vmExceptionHandler) {
            if (isOfflineMode.value || !com.example.util.ErrorFormatter.isOnline(com.example.CascadeApplication.instance)) {
                val offlineTracks = downloadedTracks.value.filterNot { it.isLocalOnlyTrack }
                if (offlineTracks.isNotEmpty()) {
                    personalizedTracks.value = offlineTracks
                    trendingHits.value = offlineTracks
                    freshReleases.value = offlineTracks
                }
                isLoadingHome.value = false
                hasCompletedInitialHomeLoad.value = true
                return@launch
            }
            if (!isBackgroundUpdate) {
                isLoadingHome.value = true
            }
            try {
                // Strictly exclude local tracks (`isLocalOnlyTrack`) from history & liked seeds
                // so Home feed never generates suggestions from local song names or artists!
                val realHistory = musicRepository.getActiveHistory().firstOrNull().orEmpty()
                    .filterNot { it.isLocalOnlyTrack }
                val realLiked = musicRepository.getLikedTracks().firstOrNull().orEmpty()
                    .filterNot { it.isLocalOnlyTrack }
                val likedIds = realLiked.map { it.id }.toSet()
                val dlMap = downloadedTracksById.value

                val recentSearches = try {
                    com.example.data.AppPreferences(com.example.CascadeApplication.instance).recentSearches.value
                } catch (e: Exception) {
                    emptyList()
                }

                val bannedKeywords = setOf(
                    "unknown artist", "unknown", "local artist", "local audio", "local video",
                    "device storage", "downloaded in seitharol", "<unknown>", "various artists",
                    "various", "download", "seitharol"
                )

                val seedIds = (realHistory.take(5).map { it.id } + realLiked.take(4).map { it.id })
                    .mapNotNull { InnerTubeStreamExtractor.extractVideoId(it) }
                    .distinct()

                // Recency-weighted + frequency-weighted artist ranking so newly played/liked/searched
                // artists immediately shape the Home feed!
                val artistWeights = LinkedHashMap<String, Int>()
                fun addArtistWeight(rawArtist: String, weight: Int) {
                    val clean = rawArtist
                        .removeSuffix(" - Topic")
                        .removeSuffix("- Topic")
                        .split(",", "&", "feat.", "ft.", "/")
                        .firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (clean.length < 2) return
                    val lower = clean.lowercase()
                    if (bannedKeywords.contains(lower) || bannedKeywords.any { b -> lower.contains(b) }) return
                    val existingKey = artistWeights.keys.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
                    artistWeights[existingKey] = (artistWeights[existingKey] ?: 0) + weight
                }

                realHistory.take(12).forEachIndexed { idx, track ->
                    val recencyBonus = (12 - idx).coerceAtLeast(2)
                    addArtistWeight(track.artist, recencyBonus * 2)
                }
                realLiked.take(10).forEachIndexed { idx, track ->
                    val recencyBonus = (10 - idx).coerceAtLeast(2)
                    addArtistWeight(track.artist, recencyBonus * 2)
                }
                val cleanSearchQueries = recentSearches
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !bannedKeywords.contains(it.lowercase()) && !bannedKeywords.any { b -> it.lowercase().contains(b) } }

                cleanSearchQueries.take(4).forEachIndexed { idx, q ->
                    addArtistWeight(q, (6 - idx) * 3)
                }

                val topArtists = artistWeights.entries
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(6)

                val personalizedDeferred = async {
                    InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                        seedVideoIds = seedIds,
                        favoriteArtists = topArtists,
                        moodCategory = selectedMood.value,
                        recentSearchQueries = cleanSearchQueries
                    )
                }

                val trendingDeferred = async {
                    if (selectedMood.value.equals("All", ignoreCase = true)) {
                        InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                            seedVideoIds = seedIds.take(2),
                            favoriteArtists = topArtists.take(2),
                            moodCategory = "Trending",
                            recentSearchQueries = emptyList()
                        )
                    } else {
                        emptyList()
                    }
                }

                val freshDeferred = async {
                    InnerTubeStreamExtractor.buildFreshNewMusicFeed(
                        seedVideoIds = seedIds,
                        favoriteArtists = topArtists,
                        moodCategory = selectedMood.value
                    )
                }

                val shelvesDeferred = async {
                    InnerTubeStreamExtractor.fetchHomeMixesAndPlaylists(
                        favoriteArtists = topArtists,
                        seedVideoIds = seedIds,
                        moodCategory = selectedMood.value
                    )
                }

                val personalizedCandidates = personalizedDeferred.await()
                val trendingCandidates = trendingDeferred.await()
                val freshCandidates = freshDeferred.await()
                val homeShelves = runCatching { shelvesDeferred.await() }.getOrNull()
                if (homeShelves != null) {
                    if (homeShelves.mixes.isNotEmpty()) {
                        suggestedYoutubeMixes.value = homeShelves.mixes
                    }
                    val combinedCols = (homeShelves.playlists + homeShelves.albums).distinctBy { it.browseId }
                    if (combinedCols.isNotEmpty()) {
                        suggestedYoutubePlaylists.value = combinedCols
                    }
                }

                fun cleanAndMap(list: List<com.seitharol.data.resolver.CandidateStream>): List<Track> {
                    return list
                        .filterNot { candidate ->
                            val a = candidate.artist.lowercase().trim()
                            val t = candidate.title.lowercase().trim()
                            bannedKeywords.any { b -> a.contains(b) || t.contains(b) } ||
                                t.contains("supermix") || t.contains("radio")
                        }
                        .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                        .filterNot { it.isLocalOnlyTrack }
                        .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
                }

                val mappedPersonalized = cleanAndMap(personalizedCandidates)
                val mappedTrending = cleanAndMap(trendingCandidates)
                val mappedFresh = cleanAndMap(freshCandidates)

                if (mappedPersonalized.isNotEmpty()) {
                    personalizedTracks.value = mappedPersonalized
                    val quickDialIds = mappedPersonalized.take(6).map { it.id }.toSet()
                    val combinedTrending = (mappedTrending.filterNot { it.id in quickDialIds } +
                        mappedPersonalized.drop(6).filterNot { it.id in quickDialIds })
                        .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
                    trendingHits.value = if (combinedTrending.isNotEmpty()) combinedTrending.take(24) else mappedPersonalized.reversed().take(16)
                }

                if (mappedFresh.isNotEmpty()) {
                    val quickDialIds = personalizedTracks.value.take(6).map { it.id }.toSet()
                    val distinctFresh = mappedFresh.filterNot { it.id in quickDialIds }
                    freshReleases.value = if (distinctFresh.isNotEmpty()) distinctFresh.take(14) else mappedFresh.take(12)
                }

                if (personalizedTracks.value.isEmpty() && trendingHits.value.isEmpty()) {
                    delay(500L)
                    val fallbackSongs = cleanAndMap(
                        InnerTubeStreamExtractor.searchOfficialYtmSongs("Manipuri & Indian Top Songs", limit = 20).shuffled()
                    )
                    if (fallbackSongs.isNotEmpty()) {
                        personalizedTracks.value = fallbackSongs
                        trendingHits.value = fallbackSongs.drop(6).ifEmpty { fallbackSongs }.take(14)
                        if (freshReleases.value.isEmpty()) freshReleases.value = fallbackSongs.reversed().take(10)
                    }
                }
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Home load error: ${e.message}")
            } finally {
                isLoadingHome.value = false
                hasCompletedInitialHomeLoad.value = true
            }
        }
    }

    fun loadTrendingSearches() {
        viewModelScope.launch(vmExceptionHandler) {
            if (isOfflineMode.value) return@launch
            runCatching {
                val realTrending = InnerTubeStreamExtractor.fetchRealTrendingSearches()
                if (realTrending.isNotEmpty()) {
                    trendingSearches.value = realTrending
                }
            }
        }
    }

    fun refreshSearchAndTrending() {
        loadTrendingSearches()
        val currentQ = searchQuery.value.trim()
        if (currentQ.length >= 2) {
            executeRankedSearch(currentQ, selectedProviderFilter.value)
        } else {
            loadHomeScreenMusic()
        }
    }

    fun loadDiscoverFeed() {
        viewModelScope.launch(vmExceptionHandler) {
            if (isOfflineMode.value) return@launch
            try {
                val hist = musicRepository.getActiveHistory().firstOrNull().orEmpty()
                    .filterNot { it.isLocalOnlyTrack }
                    .map { it.toNormalizedTrack() }
                val liked = musicRepository.getLikedTracks().firstOrNull().orEmpty()
                    .filterNot { it.isLocalOnlyTrack }
                    .map { it.toNormalizedTrack() }
                val feed = discoverFeedSeeder.generatePersonalizedFeed(hist, liked)
                _discoverTracks.value = feed
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "Failed to load discover feed: ${e.message}")
            }
        }
    }

    fun registerLocallyDownloadedTrack(normalized: com.seitharol.domain.model.NormalizedTrack) {
        viewModelScope.launch(vmExceptionHandler) {
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
        viewModelScope.launch(vmExceptionHandler) {
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

        runCatching {
            val previewTracks = spotifyProviderInstance.searchTracksWithPreviews(clean, limit = 15)
            results.addAll(previewTracks.map { it.copy(isLiked = likedIds.contains(it.sourceId)) })
        }

        val cleanLower = clean.lowercase()
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
        val cleanId = InnerTubeStreamExtractor.extractVideoId(track.sourceId)
            ?: InnerTubeStreamExtractor.extractVideoId(track.previewUrl?.removePrefix("ytm_clip:"))
            ?: track.sourceId.removePrefix("ytm_clip:").removePrefix("saavn_clip:").trim()
        val resolvedHttpUrl = track.previewUrl?.takeIf {
            it.startsWith("http") &&
                !it.contains("p.scdn.co") &&
                !it.contains("audio-ssl.itunes.apple.com") &&
                !it.contains("youtube.com/watch", ignoreCase = true) &&
                !it.contains("music.youtube.com/watch", ignoreCase = true) &&
                !it.contains("youtu.be/", ignoreCase = true)
        }.orEmpty()
        val cleanAlbum = track.album?.takeUnless {
            it.equals("YouTube Video", ignoreCase = true) ||
                it.equals("YouTube Videos", ignoreCase = true) ||
                it.equals("Discover Downloads", ignoreCase = true)
        }.orEmpty()
        val domainTrack = Track(
            id = cleanId,
            title = track.title,
            artist = track.artists.firstOrNull() ?: "Unknown Artist",
            album = cleanAlbum,
            durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L,
            artUrl = track.artUrl,
            audioUrl = resolvedHttpUrl,
            isLiked = track.isLiked,
            localPath = track.localPath,
            isDownloaded = !track.localPath.isNullOrBlank()
        )
        playTrack(domainTrack)
    }

    private fun com.seitharol.data.resolver.CandidateStream.toTrack(): Track {
        val isVideo = this.videoType == "MUSIC_VIDEO_TYPE_UGC" || this.videoType == "MUSIC_VIDEO_TYPE_OMV"
        val cleanAlbum = this.albumName?.takeUnless {
            it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
        }.orEmpty()
        return Track(
            id = this.videoId,
            title = this.title,
            artist = this.artist,
            album = cleanAlbum,
            durationMs = this.durationSeconds * 1000L,
            artUrl = this.thumbnailUrl,
            genre = if (isVideo) "YouTube Video" else "Music",
            itag = if (isVideo) 18 else 251,
            codec = if (isVideo) "MP4" else "Opus"
        )
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

    fun selectHomeMood(mood: String) {
        val targetMood = if (selectedMood.value == mood && mood != "All") "All" else mood
        selectedMood.value = targetMood
        if (isOfflineMode.value) return
        moodFetchJob?.cancel()
        if (targetMood.equals("All", ignoreCase = true)) {
            loadHomeScreenMusic()
            return
        }
        moodFetchJob = viewModelScope.launch(vmExceptionHandler) {
            isLoadingHome.value = true
            try {
                val likedIds = likedTrackIds.value
                val dlMap = downloadedTracksById.value

                val feedDeferred = async {
                    InnerTubeStreamExtractor.buildPersonalizedHomeFeed(
                        seedVideoIds = emptyList(),
                        favoriteArtists = emptyList(),
                        moodCategory = targetMood,
                        recentSearchQueries = emptyList()
                    )
                }
                val freshCategoryDeferred = async {
                    InnerTubeStreamExtractor.buildFreshNewMusicFeed(
                        seedVideoIds = emptyList(),
                        favoriteArtists = emptyList(),
                        moodCategory = targetMood
                    )
                }
                val categoryShelvesDeferred = async {
                    InnerTubeStreamExtractor.fetchHomeMixesAndPlaylists(
                        favoriteArtists = emptyList(),
                        seedVideoIds = emptyList(),
                        moodCategory = targetMood
                    )
                }

                val feed = feedDeferred.await()
                    .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                    .filterNot { it.isLocalOnlyTrack }
                    .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
                val freshCat = freshCategoryDeferred.await()
                    .map { enrichTrackStatus(it.toTrack(), likedIds, dlMap) }
                    .filterNot { it.isLocalOnlyTrack }
                    .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
                val catShelves = runCatching { categoryShelvesDeferred.await() }.getOrNull()

                if (feed.isNotEmpty()) {
                    trendingHits.value = feed.take(24)
                    freshReleases.value = if (freshCat.isNotEmpty()) freshCat.take(14) else feed.drop(12).ifEmpty { feed.reversed().take(12) }
                }
                if (catShelves != null) {
                    if (catShelves.mixes.isNotEmpty()) suggestedYoutubeMixes.value = catShelves.mixes
                    val cols = (catShelves.playlists + catShelves.albums).distinctBy { it.browseId }
                    if (cols.isNotEmpty()) suggestedYoutubePlaylists.value = cols
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
        searchQuery.value = newQuery
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Track>> = combine(
        combine(searchQuery, searchFilter, selectedProviderFilter, isOfflineMode) { q, filter, provider, offline ->
            listOf(q, filter, provider, offline)
        },
        combine(downloadedTracks, onlineSearchResults, likedTrackIds) { downloaded, onlineTracks, likedIds ->
            Triple(downloaded, onlineTracks, likedIds)
        }
    ) { params, (downloaded, onlineTracks, likedIds) ->
        val query = params[0] as String
        val filter = params[1] as SearchFilter
        val provider = params[2] as ProviderFilter
        val offline = params[3] as Boolean

        val cleanQuery = query.trim()
        val dlMap = downloaded.associateBy { it.id }
        val deviceOffline = offline || !com.example.util.ErrorFormatter.isOnline(com.example.CascadeApplication.instance)

        val baseList: List<Track> = when {
            provider == ProviderFilter.LOCAL || deviceOffline -> {
                if (cleanQuery.isBlank()) downloaded
                else downloaded.filter {
                    it.title.contains(cleanQuery, ignoreCase = true) ||
                        it.artist.contains(cleanQuery, ignoreCase = true) ||
                        it.album.contains(cleanQuery, ignoreCase = true)
                }
            }
            provider == ProviderFilter.YOUTUBE -> {
                if (cleanQuery.isBlank()) {
                    downloaded.filter { it.isVideoTrack }
                } else {
                    val matchingLocalVideos = downloaded.filter {
                        it.isVideoTrack && (it.title.contains(cleanQuery, ignoreCase = true) || it.artist.contains(cleanQuery, ignoreCase = true))
                    }
                    val enrichedOnline = onlineTracks.map { enrichTrackStatus(it, likedIds, dlMap) }
                    (matchingLocalVideos + enrichedOnline).distinctBy { it.id }
                }
            }
            else -> {
                if (cleanQuery.isBlank()) emptyList()
                else {
                    val matchingLocalAudio = downloaded.filter {
                        !it.isVideoTrack && (it.title.contains(cleanQuery, ignoreCase = true) || it.artist.contains(cleanQuery, ignoreCase = true))
                    }
                    val enrichedOnline = onlineTracks
                        .filterNot { it.isVideoTrack }
                        .map { enrichTrackStatus(it, likedIds, dlMap) }
                    (matchingLocalAudio + enrichedOnline).distinctBy { it.id }
                }
            }
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
        val dlMatch = downloadedTracksById.value[track.id] ?: downloadedTracks.value.firstOrNull {
            it.title.equals(track.title, ignoreCase = true) &&
                (track.artist.isBlank() || it.artist.contains(track.artist, ignoreCase = true) || track.artist.contains(it.artist, ignoreCase = true))
        }
        val canUseLocalMatch = dlMatch != null && (
            !track.isVideoTrack ||
                isOfflineMode.value ||
                dlMatch.localPath?.endsWith(".mp4", ignoreCase = true) == true
            )
        val effectiveTrack = if (canUseLocalMatch && dlMatch != null) {
            track.copy(isDownloaded = true, localPath = dlMatch.localPath)
        } else track

        if (effectiveTrack.isLocalOnlyTrack || isOfflineMode.value) {
            _queueVideoSuggestions.value = emptyList()
            playerManager.isAutoplayEnabled = false
        } else {
            playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value
        }
        automixFetchJob?.cancel()
        playerManager.playTrack(effectiveTrack)
        if (!effectiveTrack.isLocalOnlyTrack && !isOfflineMode.value) {
            refreshQueueSuggestions(effectiveTrack)
        }
        automixFetchJob = viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                if (!effectiveTrack.isLocalOnlyTrack) {
                    musicRepository.insertTrack(effectiveTrack)
                }
                musicRepository.recordPlayHistory(effectiveTrack)
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, isPlaylist: Boolean = true) {
        val dlMap = downloadedTracksById.value
        val enrichedTracks = tracks.map { enrichTrackStatus(it, likedTrackIds.value, dlMap) }
        val isAllLocal = enrichedTracks.all { it.isLocalOnlyTrack } || isOfflineMode.value
        if (isAllLocal) {
            _queueVideoSuggestions.value = emptyList()
            playerManager.isAutoplayEnabled = false
        } else {
            playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value
        }
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                enrichedTracks.filterNot { it.isLocalOnlyTrack }.forEach { musicRepository.insertTrack(it) }
            }
        }
        playerManager.playQueue(enrichedTracks, startIndex, isPlaylist = isPlaylist && enrichedTracks.size > 1)
        val validIdx = startIndex.coerceIn(0, (enrichedTracks.size - 1).coerceAtLeast(0))
        val seed = enrichedTracks.getOrNull(validIdx)
        if (!isAllLocal && seed != null) {
            refreshQueueSuggestions(seed)
        }
    }

    fun playPlaylistOrMix(playlistId: String) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                if (playlistId == "liked_songs" || playlistId == "pl_liked_songs") {
                    val liked = likedTracks.value
                    if (liked.isNotEmpty()) {
                        playQueue(liked, 0, isPlaylist = true)
                    }
                    return@launch
                }
                val localTracks = _userPlaylists.value[playlistId] ?: musicRepository.getTracksForPlaylist(playlistId).firstOrNull()
                if (!localTracks.isNullOrEmpty()) {
                    val filtered = if (isOfflineMode.value) {
                        val dlMap = downloadedTracksById.value
                        localTracks.map { enrichTrackStatus(it, likedTrackIds.value, dlMap) }.filter { it.isDownloaded }
                            .ifEmpty { localTracks }
                    } else localTracks
                    playQueue(filtered, 0, isPlaylist = true)
                    return@launch
                }
                if (isOfflineMode.value) return@launch
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
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
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
    }

    fun startRadioMix(seedTrack: Track) {
        if (seedTrack.isLocalOnlyTrack) {
            playTrack(seedTrack)
            return
        }
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                musicRepository.insertTrack(seedTrack)
                musicRepository.recordPlayHistory(seedTrack)
                playerManager.playQueue(listOf(seedTrack), 0, isPlaylist = true)
                if (isOfflineMode.value) return@launch
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

    fun loadPlaylistTracks(
        playlistId: String,
        onMetadata: ((com.seitharol.data.resolver.YtmCollectionPageData) -> Unit)? = null,
        onResult: (List<Track>) -> Unit
    ) {
        viewModelScope.launch(vmExceptionHandler) {
            try {
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
                if (isOfflineMode.value) {
                    onResult(emptyList())
                    return@launch
                }
                val remoteDetails = InnerTubeStreamExtractor.fetchPlaylistAlbumOrMixDetails(playlistId)
                onMetadata?.invoke(remoteDetails)
                val cleanAlbumTitle = remoteDetails.title.takeUnless {
                    it.equals("Music Collection", ignoreCase = true) ||
                        it.equals("YouTube Personalized Mix", ignoreCase = true) ||
                        it.contains("YouTube Video", ignoreCase = true)
                }.orEmpty()
                val tracks = remoteDetails.tracks.map { cand ->
                    val base = cand.toTrack()
                    val withAlbum = if (base.album.isBlank() && (playlistId.startsWith("MPREb_") || playlistId.startsWith("OLAK5uy_")) && cleanAlbumTitle.isNotBlank()) {
                        base.copy(album = cleanAlbumTitle)
                    } else base
                    enrichTrackStatus(withAlbum, likedIds, dlMap)
                }
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
            } catch (e: Exception) {
                Log.w("CascadeViewModel", "loadPlaylistTracks failed for $playlistId: ${e.message}")
                onResult(emptyList())
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
        viewModelScope.launch(vmExceptionHandler) { runCatching { musicRepository.toggleLike(track) } }
    }

    fun startDownload(track: Track, tier: DownloadTier = DownloadTier.STANDARD) {
        runCatching { downloadRepository.enqueueDownload(track, tier) }
    }

    fun downloadAllTracks(tracks: List<Track>, tier: DownloadTier = DownloadTier.STANDARD) {
        runCatching { downloadRepository.enqueueDownloadAll(tracks, tier) }
    }

    fun cancelDownload(trackId: String) {
        downloadRepository.cancelDownload(trackId)
    }

    fun retryDownload(trackId: String) {
        viewModelScope.launch(vmExceptionHandler) { runCatching { downloadRepository.retryDownload(trackId) } }
    }

    fun deleteDownload(trackId: String) {
        viewModelScope.launch(vmExceptionHandler) { runCatching { downloadRepository.deleteDownload(trackId) } }
    }

    fun removeDownload(trackId: String) = deleteDownload(trackId)

    fun setOfflineMode(enabled: Boolean) {
        resolverEngine.setOfflineMode(enabled)
        if (enabled) {
            playerManager.isAutoplayEnabled = false
            selectedProviderFilter.value = ProviderFilter.LOCAL
            viewModelScope.launch(vmExceptionHandler) {
                downloadRepository.rescanLocalDownloads()
                loadHomeScreenMusic()
            }
        } else {
            playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value
            if (selectedProviderFilter.value == ProviderFilter.LOCAL) {
                selectedProviderFilter.value = ProviderFilter.ALL
            }
            loadHomeScreenMusic()
            loadDiscoverFeed()
        }
    }

    fun selectResolverNode(nodeId: String) = resolverEngine.selectNode(nodeId)

    fun toggleFollowArtist(artist: Artist) {
        val current = _followedArtists.value.toMutableList()
        val idx = current.indexOfFirst { it.id == artist.id || it.name.equals(artist.name, ignoreCase = true) }
        if (idx >= 0) current.removeAt(idx) else current.add(artist)
        _followedArtists.value = current
    }

    fun createPlaylist(title: String, desc: String, initialTrack: Track? = null) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
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
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                musicRepository.deletePlaylist(playlistId)
                _userPlaylists.value = _userPlaylists.value - playlistId
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                musicRepository.removeTrackFromPlaylist(playlistId, trackId)
                val currentList = _userPlaylists.value[playlistId].orEmpty()
                _userPlaylists.value = _userPlaylists.value + (playlistId to currentList.filterNot { it.id == trackId })
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
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
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                musicRepository.insertTrack(track)
                musicRepository.addTrackToPlaylist(playlistId, track.id)
                val currentList = _userPlaylists.value[playlistId].orEmpty()
                _userPlaylists.value = _userPlaylists.value + (playlistId to (currentList.filterNot { it.id == track.id } + track))
            }
        }
    }

    fun togglePlaylistLike(playlist: Playlist) {
        viewModelScope.launch(vmExceptionHandler) {
            runCatching { musicRepository.togglePlaylistLike(playlist.id, playlist.isLiked) }
        }
    }

    fun clearStreamCacheAndReset() {
        viewModelScope.launch(vmExceptionHandler) {
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
    val playerInstance: androidx.media3.exoplayer.ExoPlayer?
        get() = playerManager.playerInstance

    fun toggleMute() = playerManager.toggleMute()
    fun setMuted(muted: Boolean) = playerManager.setMuted(muted)
    fun setVideoMode(enabled: Boolean) = playerManager.setVideoMode(enabled)
    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)
    fun attachVideoTextureView(textureView: android.view.TextureView?) = playerManager.attachVideoTextureView(textureView)
    fun detachVideoTextureView(textureView: android.view.TextureView?) = playerManager.detachVideoTextureView(textureView)
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
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                val track = downloadedTracks.value.firstOrNull { it.id == trackId }
                    ?: musicRepository.getTrackById(trackId)
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
    }

    fun deleteTrackFromHistory(trackId: String) {
        viewModelScope.launch(vmExceptionHandler) { runCatching { musicRepository.deleteTrackFromHistory(trackId) } }
    }

    fun clearAllHistory() {
        viewModelScope.launch(vmExceptionHandler) { runCatching { musicRepository.clearAllHistory() } }
    }

    fun toggleAutoSuggest() {
        autoSuggestManager.toggleAutoSuggest()
        playerManager.isAutoplayEnabled = autoSuggestManager.isAutoSuggestEnabled.value && !isOfflineMode.value
        if (!isOfflineMode.value) {
            refreshQueueSuggestions(playerState.value.currentTrack)
        }
    }

    fun fetchAndAppendSuggestions() {
        val state = playerState.value
        val seed = state.currentTrack ?: return
        val isLocalSingle = seed.isLocalOnlyTrack && state.queue.all { it.isLocalOnlyTrack }
        if (!isAutoSuggestEnabled.value || isOfflineMode.value || isLocalSingle) return
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                val currentQueue = state.queue
                val freshTracks = autoSuggestManager.fetchNextSuggestions(seed, currentQueue, maxTracksToAppend = 3, requireAutoSuggestEnabled = true)
                    .filterNot { it.isLocalOnlyTrack }
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

    data class PlayerRelatedData(
        val seedTrackId: String = "",
        val isLoading: Boolean = false,
        val relatedTracks: List<Track> = emptyList(),
        val relatedPlaylistsAndMixes: List<com.seitharol.data.resolver.YtmCollectionSummary> = emptyList(),
        val artistAlbumsAndSingles: List<com.seitharol.data.resolver.YtmCollectionSummary> = emptyList(),
        val similarArtists: List<com.seitharol.data.resolver.YtmArtistPreview> = emptyList(),
        val artistName: String = "",
        val artistAvatarUrl: String = "",
        val artistSubscribers: String = "",
        val artistDescription: String = ""
    )

    private val _playerRelatedData = MutableStateFlow(PlayerRelatedData())
    val playerRelatedData: StateFlow<PlayerRelatedData> = _playerRelatedData.asStateFlow()
    private var relatedFetchJob: Job? = null

    /**
     * Loads YouTube Music-style "Related" data (Similar Songs/Videos, Related Playlists & Mixes,
     * More from Artist Albums/Singles, Similar Artists, and About the Artist) WITHOUT modifying
     * or affecting the active Up Next queue.
     */
    fun loadRelatedForTrack(seedTrack: Track?, forceReload: Boolean = false) {
        val track = seedTrack ?: playerState.value.currentTrack ?: return
        if (!forceReload && _playerRelatedData.value.seedTrackId == track.id &&
            (_playerRelatedData.value.relatedTracks.isNotEmpty() || _playerRelatedData.value.isLoading)
        ) {
            return
        }
        if (track.isLocalOnlyTrack || isOfflineMode.value) {
            _playerRelatedData.value = PlayerRelatedData(
                seedTrackId = track.id,
                isLoading = false,
                relatedTracks = downloadedTracks.value.filter { it.id != track.id }.take(16),
                artistName = track.artist
            )
            return
        }

        relatedFetchJob?.cancel()
        _playerRelatedData.value = PlayerRelatedData(
            seedTrackId = track.id,
            isLoading = true,
            artistName = track.artist
        )

        relatedFetchJob = viewModelScope.launch(vmExceptionHandler) {
            val isVid = playerState.value.isVideoMode || track.isVideoTrack
            val cleanArtist = track.artist
                .removeSuffix(" - Topic")
                .removeSuffix("- Topic")
                .split(",", "&", "feat.", "ft.", "/")
                .firstOrNull()
                ?.trim()
                .orEmpty()
            val likedIds = likedTrackIds.value
            val dlMap = downloadedTracksById.value

            val relatedTracksDeferred = async(Dispatchers.IO) {
                runCatching {
                    val seedMeta = com.seitharol.data.resolver.TrackMetadata(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = if (isVid) "YouTube Video" else track.album,
                        durationSeconds = (track.durationMs / 1000L).toInt(),
                        thumbnailUrl = track.artUrl,
                        source = if (isVid) "YOUTUBE_VIDEO" else "YOUTUBE"
                    )
                    val recs = com.seitharol.player.QueueRecommendationEngine.fetchRelevantRecommendations(
                        currentTrack = seedMeta,
                        queueTracks = listOf(seedMeta),
                        limit = 20
                    )
                    recs.map { r ->
                        val recIsVideo = isVid || r.source.equals("YOUTUBE_VIDEO", ignoreCase = true)
                        enrichTrackStatus(
                            Track(
                                id = r.id,
                                title = r.title,
                                artist = r.artist,
                                album = if (recIsVideo) "YouTube Video" else r.album.takeUnless {
                                    it.equals("YouTube Video", ignoreCase = true) || it.equals("YouTube Videos", ignoreCase = true)
                                }.orEmpty(),
                                durationMs = r.durationSeconds * 1000L,
                                artUrl = r.thumbnailUrl,
                                genre = if (recIsVideo) "YouTube Video" else "Music",
                                itag = if (recIsVideo) 18 else 251,
                                codec = if (recIsVideo) "MP4" else "Opus"
                            ),
                            likedIds,
                            dlMap
                        )
                    }
                }.getOrDefault(emptyList())
            }

            val relatedPlaylistsDeferred = async(Dispatchers.IO) {
                runCatching {
                    val list = mutableListOf<com.seitharol.data.resolver.YtmCollectionSummary>()
                    val seedVid = InnerTubeStreamExtractor.extractVideoId(track.id)
                    if (!seedVid.isNullOrBlank()) {
                        list.add(
                            com.seitharol.data.resolver.YtmCollectionSummary(
                                browseId = "RDAMVM$seedVid",
                                playlistId = "RDAMVM$seedVid",
                                title = "${track.title.take(28)} Mix",
                                subtitle = "YouTube Mix • Continuous station",
                                thumbnailUrl = track.artUrl,
                                type = "MIX"
                            )
                        )
                    }
                    val query = if (cleanArtist.isNotBlank()) "$cleanArtist ${track.title} Mix Playlist" else "${track.title} Playlist"
                    val searched = InnerTubeStreamExtractor.searchYtmCollections(
                        query = query,
                        filterParams = "EgWKAQIoAWoKEAkQChADEAUQBA==",
                        type = "PLAYLIST",
                        limit = 8
                    )
                    list.addAll(searched)
                    list.distinctBy { it.browseId }.take(10)
                }.getOrDefault(emptyList())
            }

            val artistPageDeferred = async(Dispatchers.IO) {
                if (cleanArtist.length >= 2 && track.isNavigableArtist) {
                    try {
                        InnerTubeStreamExtractor.fetchArtistPage(artistName = cleanArtist, isChannel = isVid)
                    } catch (_: Throwable) {
                        null
                    }
                } else null
            }

            val relTracks = relatedTracksDeferred.await()
            val relPlaylists = relatedPlaylistsDeferred.await()
            val artistPage = artistPageDeferred.await()

            val artistTopFallback = artistPage?.topSongs.orEmpty().map { cand ->
                enrichTrackStatus(cand.toTrack(), likedIds, dlMap)
            }
            val mergedRelatedTracks = (relTracks + artistTopFallback)
                .filter { it.id != track.id }
                .distinctBy { it.id }
                .take(20)

            val mergedPlaylists = (relPlaylists + artistPage?.playlists.orEmpty())
                .distinctBy { it.browseId }
                .take(10)

            val artistReleases = (artistPage?.albums.orEmpty() + artistPage?.singles.orEmpty())
                .distinctBy { it.browseId }
                .take(12)

            _playerRelatedData.value = PlayerRelatedData(
                seedTrackId = track.id,
                isLoading = false,
                relatedTracks = mergedRelatedTracks,
                relatedPlaylistsAndMixes = mergedPlaylists,
                artistAlbumsAndSingles = artistReleases,
                similarArtists = artistPage?.similarArtists.orEmpty().distinctBy { it.browseId }.take(10),
                artistName = artistPage?.name?.ifBlank { cleanArtist } ?: cleanArtist.ifBlank { track.artist },
                artistAvatarUrl = artistPage?.avatarUrl.orEmpty().ifBlank { track.artUrl },
                artistSubscribers = artistPage?.subscriberCountText.orEmpty(),
                artistDescription = artistPage?.description.orEmpty()
            )
        }
    }

    /**
     * Plays a song selected from the "Related" sheet immediately WITHOUT wiping out the
     * user's existing Up Next queue.
     */
    fun playRelatedTrackPreservingQueue(track: Track) {
        val dlMatch = downloadedTracksById.value[track.id]
        val effectiveTrack = if (dlMatch != null && (!track.isVideoTrack || isOfflineMode.value || dlMatch.localPath?.endsWith(".mp4", true) == true)) {
            track.copy(isDownloaded = true, localPath = dlMatch.localPath)
        } else track
        viewModelScope.launch(vmExceptionHandler) {
            runCatching {
                if (!effectiveTrack.isLocalOnlyTrack) {
                    musicRepository.insertTrack(effectiveTrack)
                }
                musicRepository.recordPlayHistory(effectiveTrack)
            }
        }
        playerManager.playTrackPreservingQueue(effectiveTrack)
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
