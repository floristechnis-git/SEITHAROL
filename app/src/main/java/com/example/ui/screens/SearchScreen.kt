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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
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
    onNavigateToHome: () -> Unit = {},
    onNavigateToArtist: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val query by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchingOnline by viewModel.isSearchingOnline.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var selectedTab by remember { mutableStateOf(SearchTab.TOP) }

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

    val allArtists = remember {
        listOf(
            Pair("Taylor Swift", "https://images.unsplash.com/photo-1492562080023-ab3db95bfbce?w=400&auto=format&fit=crop&q=80"),
            Pair("Coldplay", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&auto=format&fit=crop&q=80"),
            Pair("Ed Sheeran", "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400&auto=format&fit=crop&q=80"),
            Pair("Arijit Singh", "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=400&auto=format&fit=crop&q=80"),
            Pair("Tapta", "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&auto=format&fit=crop&q=80"),
            Pair("Ronnie", "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=400&auto=format&fit=crop&q=80")
        )
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("search_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Top Search Header: Input pill + Cancel button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // White Pill Input Container (48dp height, radius 24dp)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = KangleiWhite
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = KangleiDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { viewModel.searchQuery.value = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("search_input_field"),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { viewModel.setQueryImmediately(query) }
                            ),
                            textStyle = TextStyle(
                                color = KangleiDark,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(KangleiDark),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Search YouTube Music songs, artists...",
                                        color = KangleiTextSecondary,
                                        fontSize = 15.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (isSearchingOnline) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = KangleiDark
                            )
                        } else if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.searchQuery.value = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = KangleiTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // "Cancel" button: when there is no text in the search bar, brings user back to home
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .testTag("search_cancel_button")
                        .clickable {
                            if (query.trim().isEmpty()) {
                                onNavigateToHome()
                            } else {
                                viewModel.searchQuery.value = ""
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            color = KangleiDark,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                    color = KangleiDark,
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
                            color = KangleiWhite
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
                                    tint = KangleiTextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = quickQuery,
                                    color = KangleiDark,
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
                            color = Color(0xFFE8E8E8)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = suggestion,
                                    color = KangleiDark,
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
                        color = if (isSelected) KangleiDark else KangleiWhite
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = tab.label,
                                color = if (isSelected) Color.White else KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
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
                        color = KangleiDark,
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
                                    color = KangleiDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Artist",
                                    color = KangleiTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = "View Artist",
                                tint = KangleiDark,
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
                        color = KangleiDark,
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
                                    color = KangleiDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pl.description,
                                    color = KangleiTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = "Open Playlist",
                                tint = KangleiDark,
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
                        color = KangleiDark
                    )
                    if (filteredTracks.isNotEmpty()) {
                        Text(
                            text = "${filteredTracks.size} results",
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            color = KangleiTextSecondary
                        )
                    }
                }
            }

            if (filteredTracks.isEmpty() && query.isNotBlank() && !isSearchingOnline) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs found for \"$query\"",
                            color = KangleiTextSecondary,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily
                        )
                    }
                }
            } else {
                items(filteredTracks) { track ->
                    TrackCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        onTrackClick = { viewModel.playTrack(track) },
                        onLikeClick = { viewModel.toggleLike(track) },
                        onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
