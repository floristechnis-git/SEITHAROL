package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.SeitharolPullToRefreshBox
import com.example.ui.components.TrackCard
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
    val hasCompletedOnlineSearch by viewModel.hasCompletedOnlineSearch.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val trendingSearches by viewModel.trendingSearches.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentProvider by viewModel.selectedProviderFilter.collectAsState()
    val verifiedSearchArtists by viewModel.searchedVerifiedArtists.collectAsState()
    val personalizedTracks by viewModel.personalizedTracks.collectAsState()
    val trendingHits by viewModel.trendingHits.collectAsState()

    var selectedTab by remember { mutableStateOf(SearchTab.TOP) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    var textFieldValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length)))
    }
    var forceCursorToEndForText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (trendingSearches.isEmpty()) {
            viewModel.loadTrendingSearches()
        }
    }

    LaunchedEffect(query) {
        if (query != textFieldValue.text) {
            textFieldValue = androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))
        }
    }

    androidx.activity.compose.BackHandler(onBack = onNavigateToHome)
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val allTracksList by viewModel.allTracks.collectAsState()
    val historyTracks by viewModel.activeHistory.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appPreferences = remember { com.example.data.AppPreferences(context) }
    val recentSearches by appPreferences.recentSearches.collectAsState()

    val allArtists = remember(allTracksList, historyTracks, searchResults, currentProvider, downloadedTracks) {
        val bannedModifiers = listOf("loop", "extended", "1 hour", "slowed", "reverb", "cover", "8d", "nightcore", "mashup", "bass boosted")
        val bannedArtists = listOf("local artist", "device storage", "downloaded in seitharol", "<unknown>", "unknown artist", "unknown", "various artists", "various", "download", "seitharol", "blog", "blogger", "blogspot", ".com", ".net", ".org")
        val splitRegex = Regex("[,/;&]|\\s+(?:feat\\.?|ft\\.?|&|x)\\s+", RegexOption.IGNORE_CASE)
        val sourceTracks = if (currentProvider == com.example.ui.ProviderFilter.LOCAL) {
            downloadedTracks
        } else {
            searchResults + historyTracks + allTracksList
        }
        sourceTracks
            .flatMap { track ->
                track.artist.split(splitRegex).map { it.trim() }
            }
            .filter { artist ->
                artist.isNotBlank() &&
                    artist.length >= 2 &&
                    !artist.contains(",") &&
                    !artist.contains("&") &&
                    !artist.contains("http", ignoreCase = true) &&
                    !artist.contains("www.", ignoreCase = true) &&
                    !artist.contains(".com", ignoreCase = true) &&
                    !artist.contains(".net", ignoreCase = true) &&
                    !artist.contains(".org", ignoreCase = true) &&
                    !artist.contains("blog", ignoreCase = true) &&
                    bannedArtists.none { banned -> artist.contains(banned, ignoreCase = true) } &&
                    bannedModifiers.none { mod -> artist.contains(mod, ignoreCase = true) }
            }
            .distinctBy { it.lowercase() }
    }

    val displayedTracks = remember(searchResults, query, currentProvider, personalizedTracks, trendingHits, historyTracks, downloadedTracks) {
        when {
            currentProvider == com.example.ui.ProviderFilter.LOCAL -> searchResults
            currentProvider == com.example.ui.ProviderFilter.YOUTUBE -> searchResults
            query.isNotBlank() -> searchResults.filterNot { it.isVideoTrack }
            else -> {
                (personalizedTracks + trendingHits + historyTracks)
                    .filterNot {
                        it.isVideoTrack || it.id.startsWith("RD") || it.id.startsWith("VL") || it.id.startsWith("PL")
                    }
                    .distinctBy { it.id }
                    .take(10)
            }
        }
    }

    val filteredArtists = remember(query, allArtists, currentProvider) {
        val cleanQuery = query.trim()
        if (currentProvider == com.example.ui.ProviderFilter.LOCAL) {
            if (cleanQuery.isBlank()) {
                emptyList()
            } else {
                allArtists
                    .filter { it.contains(cleanQuery, ignoreCase = true) }
                    .sortedWith(compareBy<String> {
                        when {
                            it.equals(cleanQuery, ignoreCase = true) -> 0
                            it.startsWith(cleanQuery, ignoreCase = true) -> 1
                            else -> 2
                        }
                    }.thenBy { it.length })
                    .take(5)
            }
        } else {
            if (cleanQuery.isBlank()) {
                allArtists.take(5)
            } else {
                allArtists
                    .filter { it.contains(cleanQuery, ignoreCase = true) }
                    .sortedWith(compareBy<String> {
                        when {
                            it.equals(cleanQuery, ignoreCase = true) -> 0
                            it.startsWith(cleanQuery, ignoreCase = true) -> 1
                            else -> 2
                        }
                    }.thenBy { it.length })
                    .take(5)
            }
        }
    }

    val filteredPlaylists = remember(query, playlists) {
        if (query.isBlank()) playlists
        else playlists.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    val dismissSearchKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        runCatching {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val token = (context as? android.app.Activity)?.currentFocus?.windowToken
                ?: (context as? android.app.Activity)?.window?.decorView?.windowToken
            if (token != null) {
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            dismissSearchKeyboard()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            dismissSearchKeyboard()
        }
    }

    SeitharolPullToRefreshBox(
        isRefreshing = isSearchingOnline,
        onRefresh = { viewModel.refreshSearchAndTrending() },
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("search_screen")
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            // Full-Width Search Bar
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
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                viewModel.searchQuery.value = ""
                                onNavigateToHome()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("search_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { incoming ->
                                if (forceCursorToEndForText != null && incoming.text == forceCursorToEndForText) {
                                    textFieldValue = incoming.copy(selection = androidx.compose.ui.text.TextRange(incoming.text.length))
                                } else {
                                    forceCursorToEndForText = null
                                    textFieldValue = incoming
                                }
                                viewModel.searchQuery.value = incoming.text
                            },
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
                                    if (query.isNotBlank()) {
                                        appPreferences.addRecentSearch(query.trim())
                                    }
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    viewModel.setQueryImmediately(query)
                                }
                            ),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = when (currentProvider) {
                                            com.example.ui.ProviderFilter.YOUTUBE -> "Search YouTube videos, live sets, music..."
                                            com.example.ui.ProviderFilter.MUSIC -> "Search YouTube Music songs, artists..."
                                            else -> "Search songs, official artists, albums..."
                                        },
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
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    forceCursorToEndForText = null
                                    textFieldValue = androidx.compose.ui.text.input.TextFieldValue("", androidx.compose.ui.text.TextRange.Zero)
                                    viewModel.searchQuery.value = ""
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
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

            // Multi-Source Provider Filter Chips: All, Music (YTM API), JioSaavn, YouTube (All Videos/Audio), Downloaded
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    val orderedProviders = listOf(
                        com.example.ui.ProviderFilter.ALL,
                        com.example.ui.ProviderFilter.MUSIC,
                        com.example.ui.ProviderFilter.JIOSAAVN,
                        com.example.ui.ProviderFilter.YOUTUBE,
                        com.example.ui.ProviderFilter.LOCAL
                    )
                    items(orderedProviders) { provider ->
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
                                    com.example.ui.ProviderFilter.ALL -> {
                                        Icon(
                                            imageVector = Icons.Default.Public,
                                            contentDescription = "All Sources",
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    com.example.ui.ProviderFilter.MUSIC -> {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_youtube_music),
                                            contentDescription = "YouTube Music",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    com.example.ui.ProviderFilter.JIOSAAVN -> {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_jiosaavn_icon),
                                            contentDescription = "JioSaavn",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    com.example.ui.ProviderFilter.YOUTUBE -> {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_youtube),
                                            contentDescription = "YouTube",
                                            modifier = Modifier.size(20.dp, 14.dp)
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

            // Recent Searches
            if (recentSearches.isNotEmpty() && query.isBlank() && currentProvider != com.example.ui.ProviderFilter.LOCAL) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Recent Searches",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "Clear all",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { appPreferences.clearRecentSearches() }
                                .padding(4.dp)
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        items(recentSearches.take(4)) { recentQuery ->
                            Surface(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(17.dp))
                                    .clickable {
                                        val newText = recentQuery
                                        forceCursorToEndForText = newText
                                        textFieldValue = androidx.compose.ui.text.input.TextFieldValue(newText, androidx.compose.ui.text.TextRange(newText.length))
                                        viewModel.setQueryImmediately(newText)
                                        appPreferences.addRecentSearch(newText)
                                        focusRequester.requestFocus()
                                    },
                                shape = RoundedCornerShape(17.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(start = 12.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = recentQuery,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                    IconButton(
                                        onClick = { appPreferences.removeRecentSearch(recentQuery) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove search",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Real Dynamic Trending Searches from YouTube (only shown when live trending items exist)
            if (query.isBlank() && currentProvider != com.example.ui.ProviderFilter.LOCAL && trendingSearches.isNotEmpty()) {
                item {
                    Text(
                        text = "Trending Searches",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        items(trendingSearches) { trendingQuery ->
                            Surface(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(17.dp))
                                    .clickable {
                                        val newText = trendingQuery
                                        forceCursorToEndForText = newText
                                        textFieldValue = androidx.compose.ui.text.input.TextFieldValue(newText, androidx.compose.ui.text.TextRange(newText.length))
                                        viewModel.setQueryImmediately(newText)
                                        appPreferences.addRecentSearch(newText)
                                        focusRequester.requestFocus()
                                    },
                                shape = RoundedCornerShape(17.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
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
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = trendingQuery,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Typeahead suggestions
            if (query.isNotBlank() && searchSuggestions.isNotEmpty() && currentProvider != com.example.ui.ProviderFilter.LOCAL) {
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
                                    .clickable {
                                        val newText = suggestion
                                        forceCursorToEndForText = newText
                                        textFieldValue = androidx.compose.ui.text.input.TextFieldValue(newText, androidx.compose.ui.text.TextRange(newText.length))
                                        viewModel.setQueryImmediately(newText)
                                        appPreferences.addRecentSearch(newText)
                                        focusRequester.requestFocus()
                                    },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = suggestion,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = if (isSelected) SeitharolPillBg else MaterialTheme.colorScheme.surfaceVariant
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

            // Discover Entry Card
            if (currentProvider != com.example.ui.ProviderFilter.LOCAL) {
                item {
                    com.seitharol.ui.search.DiscoverEntryCard(
                        onOpenDiscover = {
                            dismissSearchKeyboard()
                            onOpenDiscover()
                        }
                    )
                }
            }

            // Matching Verified Artists (strictly from real YouTube Music artist profiles, never scraped from song results)
            val shouldShowArtists = if (currentProvider == com.example.ui.ProviderFilter.LOCAL || currentProvider == com.example.ui.ProviderFilter.YOUTUBE) {
                false
            } else {
                query.trim().length >= 2 &&
                    (selectedTab == SearchTab.TOP || selectedTab == SearchTab.ARTISTS) &&
                    verifiedSearchArtists.isNotEmpty()
            }
            if (shouldShowArtists) {
                item {
                    Text(
                        text = "Artists",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                items(verifiedSearchArtists, key = { "ver_art_${it.browseId}" }) { artistPreview ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                dismissSearchKeyboard()
                                viewModel.navigateToArtist(artistPreview.name, isChannel = false)
                            },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = artistPreview.avatarUrl,
                                contentDescription = artistPreview.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artistPreview.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = artistPreview.subscribers.ifBlank { "Verified Artist" },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = "View Artist",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Matching Playlists
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
                                .clickable {
                                    dismissSearchKeyboard()
                                    onNavigateToPlaylist(pl.id)
                                },
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
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
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

            // Matching Songs / YouTube Results (ONLY show header when displayedTracks is non-empty or an active search completed!)
            if (selectedTab == SearchTab.TOP || selectedTab == SearchTab.SONGS || selectedTab == SearchTab.ALBUMS) {
                val hasActiveSearchQuery = query.trim().length >= 2
                val showHeader = displayedTracks.isNotEmpty() || (hasActiveSearchQuery && hasCompletedOnlineSearch)
                if (showHeader) {
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
                                text = when {
                                    query.isBlank() -> "Recommended Songs"
                                    currentProvider == com.example.ui.ProviderFilter.YOUTUBE -> "YouTube Videos & Audio"
                                    else -> "Songs"
                                },
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = SeitharolDark
                            )
                            if (displayedTracks.isNotEmpty()) {
                                Text(
                                    text = "${displayedTracks.size} results",
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 12.sp,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }
                }

                if (displayedTracks.isEmpty() && ((!isSearchingOnline && hasCompletedOnlineSearch && hasActiveSearchQuery) || currentProvider == com.example.ui.ProviderFilter.LOCAL)) {
                    if (hasActiveSearchQuery || currentProvider == com.example.ui.ProviderFilter.LOCAL) {
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
                                        "No results found for \"$query\""
                                    },
                                    color = SeitharolTextSecondary,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }
                    }
                }

                items(displayedTracks, key = { "search_item_${it.id}" }) { track ->
                    val isVideo = track.isVideoTrack
                    TrackCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        subtitleText = if (isVideo) {
                            "${track.artist} • MP4 Video • ${track.durationFormatted}"
                        } else null,
                        onTrackClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onPlayTrackAndMaximize(track)
                        },
                        onLikeClick = { viewModel.toggleLike(track) },
                        onDownloadClick = { tier: DownloadTier ->
                            viewModel.startDownload(track, tier)
                        },
                        onPlayNextClick = { viewModel.playNext(track) },
                        onAddToQueueClick = { viewModel.addToQueue(track) },
                        onAddToPlaylistClick = { trackToAddToPlaylist = track },
                        onGoToArtistClick = { creatorName ->
                            viewModel.navigateToArtist(creatorName, isChannel = isVideo)
                        },
                        onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    trackToAddToPlaylist?.let { track ->
        AddToPlaylistModal(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { pId ->
                viewModel.addTrackToPlaylist(pId, track)
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

@Composable
fun SearchArtistAvatar(
    name: String,
    modifier: Modifier = Modifier
) {
    var avatarUrl by remember(name) {
        mutableStateOf(com.example.ui.components.ArtistAvatarCache.get(name).orEmpty())
    }

    LaunchedEffect(name) {
        if (avatarUrl.isBlank() && name.isNotBlank()) {
            val cached = com.example.ui.components.ArtistAvatarCache.get(name)
            if (!cached.isNullOrBlank()) {
                avatarUrl = cached
            } else {
                runCatching {
                    val (_, avatar) = com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveOfficialArtistBrowseId(name)
                    if (avatar.isNotBlank()) {
                        com.example.ui.components.ArtistAvatarCache.put(name, avatar)
                        avatarUrl = avatar
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
