package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.LocalFocusManager
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.TrackCard
import com.example.R
import androidx.compose.ui.res.painterResource
import com.example.ui.theme.*

enum class SearchTab(val label: String) {
    TOP("Top"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    ALBUMS("Albums")
}

@Composable
fun SearchScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToHome: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onPlayTrackAndMaximize: (Track) -> Unit = { viewModel.playTrack(it) },
    modifier: Modifier = Modifier
) {
    val query by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchingOnline by viewModel.isSearchingOnline.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentProvider by viewModel.selectedProviderFilter.collectAsState()
    var selectedTab by remember { mutableStateOf(SearchTab.TOP) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val quickSearches = remember {
        listOf(
            "Taylor Swift",
            "Coldplay",
            "Arijit Singh",
            "Ed Sheeran",
            "Diljit Dosanjh",
            "Lo-Fi Study Beats",
            "Tapta",
            "Ronnie"
        )
    }

    val allTracksList by viewModel.allTracks.collectAsState()
    val historyTracks by viewModel.activeHistory.collectAsState()

    // Verified Artists Only (filter out unverified remix/loop uploaders)
    val allArtists = remember(allTracksList, historyTracks, searchResults) {
        val bannedModifiers = listOf("remix", "loop", "extended", "1 hour", "slowed", "reverb", "cover", "8d", "nightcore", "mashup", "bass boosted")
        (searchResults + historyTracks + allTracksList)
            .filter { track ->
                track.artist.isNotBlank() &&
                track.artist != "Unknown Artist" &&
                bannedModifiers.none { mod -> track.artist.contains(mod, ignoreCase = true) }
            }
            .distinctBy { it.artist.lowercase() }
            .map { Pair(it.artist, it.artUrl) }
    }

    val filteredTracks = searchResults

    val filteredArtists = remember(query, allArtists) {
        if (query.isBlank()) allArtists
        else allArtists.filter { it.first.contains(query, ignoreCase = true) }
    }

    val filteredPlaylists = remember(query, playlists) {
        if (query.isBlank()) playlists
        else playlists.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
    }

    // Auto-focus keyboard when the search screen is opened
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Auto-dismiss keyboard when user scrolls search results
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("search_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Full-Width Search Bar (no Cancel button; Back arrow when empty, Clear when text present)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (query.isEmpty()) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onNavigateToHome()
                            } else {
                                viewModel.searchQuery.value = ""
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (query.isEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                            contentDescription = if (query.isEmpty()) "Back" else "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { viewModel.searchQuery.value = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag("search_input_field"),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                viewModel.setQueryImmediately(query)
                            }
                        ),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search songs, official artists, albums...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (isSearchingOnline) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Multi-Source Provider Filter Chips (All, YouTube, JioSaavn, Downloaded)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                items(com.example.ui.ProviderFilter.values()) { provider ->
                    val isSelected = currentProvider == provider
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectProviderFilter(provider) },
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(17.dp),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        leadingIcon = {
                            when (provider) {
                                com.example.ui.ProviderFilter.YOUTUBE -> {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(id = R.drawable.ic_youtube),
                                        contentDescription = "YouTube",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                com.example.ui.ProviderFilter.JIOSAAVN -> {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(id = R.drawable.ic_jiosaavn_icon),
                                        contentDescription = "JioSaavn",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                com.example.ui.ProviderFilter.LOCAL -> {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "Downloaded",
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                com.example.ui.ProviderFilter.ALL -> {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = "All Sources",
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        label = {
                            Text(
                                text = provider.label,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }
        }

        // Quick Search Chips (when query is empty)
        if (query.isBlank()) {
            item {
                Text(
                    text = "Trending Searches",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = SeitharolDark,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(quickSearches) { quickQuery ->
                        Surface(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .clickable { viewModel.setQueryImmediately(quickQuery) },
                            shape = RoundedCornerShape(17.dp),
                            color = SeitharolWhite
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TrendingUp,
                                    contentDescription = null,
                                    tint = SeitharolTextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = quickQuery,
                                    color = SeitharolDark,
                                    fontSize = 13.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }

        // Typeahead suggestions (when query is entered and suggestions are available)
        if (query.isNotBlank() && searchSuggestions.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    items(searchSuggestions) { suggestion ->
                        Surface(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.setQueryImmediately(suggestion) },
                            shape = RoundedCornerShape(16.dp),
                            color = SeitharolCardElevated
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = suggestion,
                                    color = SeitharolDark,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filter Pills: "Top", "Songs", "Artists", "Playlists", "Albums"
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                items(SearchTab.values()) { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedTab = tab },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) SeitharolPillBg else SeitharolWhite
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = tab.label,
                                color = if (isSelected) SeitharolPillText else SeitharolDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // Discover Entry Card (Placed directly above the "Artists" section)
        item {
            com.seitharol.ui.search.DiscoverEntryCard(
                onOpenDiscover = onOpenDiscover
            )
        }

        // Matching Artists (shown if TOP, ARTISTS, or query matches)
        if (selectedTab == SearchTab.TOP || selectedTab == SearchTab.ARTISTS) {
            if (filteredArtists.isNotEmpty()) {
                item {
                    Text(
                        text = "Artists",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                items(filteredArtists) { (name, img) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigateToArtist(name) },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE0E0E0)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = img,
                                    contentDescription = name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    color = SeitharolDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Artist",
                                    color = SeitharolTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = "View Artist",
                                tint = SeitharolDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Matching Playlists (shown if TOP or PLAYLISTS)
        if (selectedTab == SearchTab.TOP || selectedTab == SearchTab.PLAYLISTS) {
            if (filteredPlaylists.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Playlists",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                items(filteredPlaylists) { pl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigateToPlaylist(pl.id) },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFE0E0E0)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = pl.coverUrl,
                                    contentDescription = pl.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.title,
                                    color = SeitharolDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pl.description,
                                    color = SeitharolTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = "Open Playlist",
                                tint = SeitharolDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Matching Songs (shown if TOP, SONGS, or ALBUMS)
        if (selectedTab == SearchTab.TOP || selectedTab == SearchTab.SONGS || selectedTab == SearchTab.ALBUMS) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (query.isBlank()) "Recommended Songs" else "Songs",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark
                    )
                    if (filteredTracks.isNotEmpty()) {
                        Text(
                            text = "${filteredTracks.size} results",
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            color = SeitharolTextSecondary
                        )
                    }
                }
            }

            if (filteredTracks.isEmpty() && (!isSearchingOnline || currentProvider == com.example.ui.ProviderFilter.LOCAL)) {
                if (query.isNotBlank() || currentProvider == com.example.ui.ProviderFilter.LOCAL) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (currentProvider == com.example.ui.ProviderFilter.LOCAL) {
                                    if (query.isBlank()) "No downloaded songs on this device yet."
                                    else "No downloaded songs match \"$query\""
                                } else {
                                    "No songs found for \"$query\""
                                },
                                color = SeitharolTextSecondary,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            } else {
                items(filteredTracks) { track ->
                    TrackCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        onTrackClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onPlayTrackAndMaximize(track)
                        },
                        onLikeClick = { viewModel.toggleLike(track) },
                        onDownloadClick = { tier: DownloadTier -> viewModel.startDownload(track, tier) },
                        onPlayNextClick = { viewModel.playNext(track) },
                        onAddToQueueClick = { viewModel.addToQueue(track) },
                        onAddToPlaylistClick = { trackToAddToPlaylist = track },
                        onGoToArtistClick = onNavigateToArtist,
                        onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    // Modal to add track to playlist
    trackToAddToPlaylist?.let { track ->
        AddToPlaylistModal(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { pId ->
                viewModel.addTrackToPlaylist(pId, track.id)
                trackToAddToPlaylist = null
            },
            onCreateNewPlaylist = {
                showCreatePlaylistDialog = true
            },
            onDismiss = {
                trackToAddToPlaylist = null
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistModal(
            onConfirm = { name ->
                viewModel.createPlaylist(name, "User created playlist", trackToAddToPlaylist)
                trackToAddToPlaylist = null
                showCreatePlaylistDialog = false
            },
            onDismiss = {
                showCreatePlaylistDialog = false
            }
        )
    }
}
