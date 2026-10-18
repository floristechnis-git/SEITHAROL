package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.CreatePlaylistModal
import com.example.ui.components.TrackCard
import com.example.ui.theme.*

enum class LibraryTab(val label: String) {
    PLAYLISTS("Playlists"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    HISTORY("History"),
    DOWNLOADED("Downloaded")
}

@Composable
fun LibraryScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val activeHistory by viewModel.activeHistory.collectAsState()
    val archivedHistory by viewModel.archivedHistory.collectAsState()

    var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }
    var historyViewMode by remember { mutableStateOf("active") } // "active" (15 days) or "archived"
    var historySortNewest by remember { mutableStateOf(true) }   // true = Newest First
    var historyMultiSelectMode by remember { mutableStateOf(false) }
    val historySelectedIds = remember { mutableStateListOf<String>() }
    var librarySearchQuery by remember { mutableStateOf("") }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val albums = remember(allTracks) {
        allTracks.groupBy { it.album }.map { (album, tracks) ->
            Triple(album, tracks.first().artist, tracks.first().artUrl)
        }
    }

    val artists = remember(allTracks, likedTracks) {
        (allTracks + likedTracks)
            .filter { it.artist.isNotBlank() && it.artist != "Unknown Artist" }
            .distinctBy { it.artist }
            .map { Pair(it.artist, it.artUrl) }
    }

    val filteredPlaylists = remember(playlists, librarySearchQuery) {
        if (librarySearchQuery.isBlank()) playlists
        else playlists.filter { it.title.contains(librarySearchQuery, ignoreCase = true) }
    }

    val filteredAlbums = remember(albums, librarySearchQuery) {
        if (librarySearchQuery.isBlank()) albums
        else albums.filter { it.first.contains(librarySearchQuery, ignoreCase = true) || it.second.contains(librarySearchQuery, ignoreCase = true) }
    }

    val filteredArtists = remember(artists, librarySearchQuery) {
        if (librarySearchQuery.isBlank()) artists
        else artists.filter { it.first.contains(librarySearchQuery, ignoreCase = true) }
    }

    val filteredDownloaded = remember(downloadedTracks, librarySearchQuery) {
        if (librarySearchQuery.isBlank()) downloadedTracks
        else downloadedTracks.filter { it.title.contains(librarySearchQuery, ignoreCase = true) || it.artist.contains(librarySearchQuery, ignoreCase = true) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("library_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Heading & Action Row: "Your library" + "New" button
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your library",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    color = KangleiDark
                )

                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onCreatePlaylist)
                        .testTag("library_create_playlist_btn"),
                    shape = RoundedCornerShape(18.dp),
                    color = KangleiPillBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = KangleiPillText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New",
                            color = KangleiPillText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Search Bar in Library: Real-time filtering
        item(span = { GridItemSpan(2) }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .testTag("library_search_pill"),
                color = KangleiWhite,
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search library",
                        tint = KangleiTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = librarySearchQuery,
                        onValueChange = { librarySearchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library_search_input"),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = KangleiTextPrimary,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily
                        ),
                        decorationBox = { innerTextField ->
                            if (librarySearchQuery.isEmpty()) {
                                Text(
                                    text = "Filter your library...",
                                    color = KangleiTextSecondary,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (librarySearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { librarySearchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = KangleiTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Filter Pills: Playlists, Albums, Artists, Downloaded
        item(span = { GridItemSpan(2) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                items(LibraryTab.values()) { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedTab = tab },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) KangleiPillBg else KangleiWhite
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = tab.label,
                                color = if (isSelected) KangleiPillText else KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            LibraryTab.PLAYLISTS -> {
                // Distinct Hero Liked Songs Card spanning 2 columns
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (likedTracks.isNotEmpty()) viewModel.playQueue(likedTracks, 0)
                            }
                            .testTag("library_liked_songs_card"),
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF4A154B), Color(0xFF191414))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Liked Songs",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Liked Songs",
                                    color = KangleiDark,
                                    fontSize = 16.sp,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${likedTracks.size} saved tracks • Auto Playlist",
                                    color = KangleiTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }

                            Surface(
                                modifier = Modifier.size(38.dp),
                                shape = CircleShape,
                                color = KangleiPillBg,
                                onClick = {
                                    if (likedTracks.isNotEmpty()) viewModel.playQueue(likedTracks, 0)
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = KangleiPillText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Other Playlists
                items(filteredPlaylists) { pl ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onNavigateToPlaylist(pl.id) }
                            .testTag("library_playlist_${pl.id}"),
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Playlist cover: natural aspect ratio (1:1 for playlists/albums)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
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

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.title,
                                        color = KangleiDark,
                                        fontSize = 14.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${pl.trackCount.coerceAtLeast(18)} songs",
                                        color = KangleiTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily
                                    )
                                }

                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = KangleiCardElevated,
                                    onClick = { onNavigateToPlaylist(pl.id) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = KangleiDark,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.ALBUMS -> {
                items(filteredAlbums) { (album, artist, artUrl) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                val albumTracks = allTracks.filter { it.album == album }
                                if (albumTracks.isNotEmpty()) viewModel.playQueue(albumTracks, 0)
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = KangleiWhite
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Album cover: 1:1 square for albums (standard convention)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFE0E0E0))
                            ) {
                                AsyncImage(
                                    model = artUrl,
                                    contentDescription = album,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = album,
                                color = KangleiDark,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                text = artist,
                                color = KangleiTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            LibraryTab.ARTISTS -> {
                // List rows for ARTISTS tab
                items(filteredArtists, span = { GridItemSpan(2) }) { (name, img) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onNavigateToArtist(name) },
                        shape = RoundedCornerShape(16.dp),
                        color = KangleiWhite
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE0E0E0))
                            ) {
                                AsyncImage(
                                    model = img,
                                    contentDescription = name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    color = KangleiDark,
                                    fontSize = 15.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Artist",
                                    color = KangleiTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = KangleiTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            LibraryTab.HISTORY -> {
                // Sort chips + Multi-select action row
                item(span = { GridItemSpan(2) }) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Active / Archived toggle
                            Surface(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { historyViewMode = "active" },
                                shape = RoundedCornerShape(16.dp),
                                color = if (historyViewMode == "active") KangleiDark else KangleiWhite
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = "Recent • ${activeHistory.size}",
                                        color = if (historyViewMode == "active") KangleiWhite else KangleiDark,
                                        fontSize = 12.sp, fontFamily = OutfitFontFamily,
                                        fontWeight = if (historyViewMode == "active") FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { historyViewMode = "archived" },
                                shape = RoundedCornerShape(16.dp),
                                color = if (historyViewMode == "archived") KangleiDark else KangleiWhite
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = "Archived • ${archivedHistory.size}",
                                        color = if (historyViewMode == "archived") KangleiWhite else KangleiDark,
                                        fontSize = 12.sp, fontFamily = OutfitFontFamily,
                                        fontWeight = if (historyViewMode == "archived") FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Sort chips
                            Surface(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .clickable { historySortNewest = true },
                                shape = RoundedCornerShape(15.dp),
                                color = if (historySortNewest) KangleiPillBg else KangleiWhite
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                    Text("Newest", fontSize = 11.sp, fontFamily = OutfitFontFamily,
                                        color = if (historySortNewest) KangleiPillText else KangleiDark)
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .clickable { historySortNewest = false },
                                shape = RoundedCornerShape(15.dp),
                                color = if (!historySortNewest) KangleiPillBg else KangleiWhite
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                    Text("Oldest", fontSize = 11.sp, fontFamily = OutfitFontFamily,
                                        color = if (!historySortNewest) KangleiPillText else KangleiDark)
                                }
                            }
                        }

                        // Multi-select action row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Select / Deselect All
                            TextButton(onClick = {
                                val current = if (historyViewMode == "active") activeHistory else archivedHistory
                                if (historySelectedIds.isEmpty()) {
                                    historyMultiSelectMode = true
                                    historySelectedIds.addAll(current.map { it.id })
                                } else {
                                    historySelectedIds.clear()
                                    historyMultiSelectMode = false
                                }
                            }) {
                                Text(
                                    if (historySelectedIds.isEmpty()) "Select All" else "Deselect All",
                                    fontFamily = OutfitFontFamily, fontSize = 12.sp, color = KangleiDark
                                )
                            }

                            if (historySelectedIds.isNotEmpty()) {
                                TextButton(onClick = {
                                    // Remove selected from history (delete from repo)
                                    val ids = historySelectedIds.toList()
                                    ids.forEach { id -> viewModel.deleteTrackFromHistory(id) }
                                    historySelectedIds.clear()
                                    historyMultiSelectMode = false
                                }) {
                                    Text(
                                        "Delete ${historySelectedIds.size} Selected",
                                        fontFamily = OutfitFontFamily, fontSize = 12.sp, color = Color(0xFFFF5252)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Clear All button
                            TextButton(onClick = {
                                viewModel.clearAllHistory()
                                historySelectedIds.clear()
                                historyMultiSelectMode = false
                            }) {
                                Text(
                                    "Clear All",
                                    fontFamily = OutfitFontFamily, fontSize = 12.sp, color = Color(0xFFFF5252)
                                )
                            }
                        }
                    }
                }

                val rawHistoryList = if (historyViewMode == "active") activeHistory else archivedHistory
                val currentHistoryList = remember(rawHistoryList, historySortNewest) {
                    if (historySortNewest) rawHistoryList else rawHistoryList.reversed()
                }

                if (currentHistoryList.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (historyViewMode == "active") "No tracks played in the last 15 days" else "No archived history entries",
                                color = KangleiTextSecondary,
                                fontFamily = OutfitFontFamily,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(currentHistoryList, span = { GridItemSpan(2) }) { track ->
                        val isSelected = track.id in historySelectedIds

                        // Swipe-to-delete
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.deleteTrackFromHistory(track.id)
                                    historySelectedIds.remove(track.id)
                                    true
                                } else false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFFF5252).copy(alpha = 0.85f))
                                        .padding(end = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete from History",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (historyMultiSelectMode) {
                                            if (isSelected) historySelectedIds.remove(track.id)
                                            else historySelectedIds.add(track.id)
                                        } else {
                                            viewModel.playTrack(track)
                                        }
                                    },
                                color = if (isSelected)
                                    KangleiWhite.copy(alpha = 0.85f)
                                else KangleiWhite,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Checkbox or Art
                                    if (historyMultiSelectMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) historySelectedIds.add(track.id)
                                                else historySelectedIds.remove(track.id)
                                            },
                                            modifier = Modifier.size(40.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFE0E0E0))
                                        ) {
                                            AsyncImage(
                                                model = track.artUrl,
                                                contentDescription = track.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            color = KangleiDark, fontSize = 14.sp,
                                            fontFamily = OutfitFontFamily, fontWeight = FontWeight.Medium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            color = KangleiTextSecondary, fontSize = 12.sp,
                                            fontFamily = OutfitFontFamily,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // 3-dot menu
                                    var showTrackMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(
                                            onClick = { showTrackMenu = true },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Track Options",
                                                tint = KangleiTextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showTrackMenu,
                                            onDismissRequest = { showTrackMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Play") },
                                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                                onClick = {
                                                    showTrackMenu = false
                                                    viewModel.playTrack(track)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (track.isLiked) "Unlike" else "Like") },
                                                leadingIcon = {
                                                    Icon(
                                                        if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        null
                                                    )
                                                },
                                                onClick = {
                                                    showTrackMenu = false
                                                    viewModel.toggleLike(track)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Add to Queue") },
                                                leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                                                onClick = {
                                                    showTrackMenu = false
                                                    viewModel.addToQueue(track)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Remove from History") },
                                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                                onClick = {
                                                    showTrackMenu = false
                                                    viewModel.deleteTrackFromHistory(track.id)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LibraryTab.DOWNLOADED -> {
                item(span = { GridItemSpan(2) }) {
                    Column {
                        Text(
                            text = "${filteredDownloaded.size} Offline Tracks",
                            color = KangleiTextSecondary,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                // List rows for DOWNLOADED tab
                items(filteredDownloaded, span = { GridItemSpan(2) }) { track ->
                    TrackCard(
                        track = track,
                        isPlaying = playerState.currentTrack?.id == track.id,
                        onTrackClick = { viewModel.playTrack(track) },
                        onLikeClick = { viewModel.toggleLike(track) },
                        onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                        onPlayNextClick = { viewModel.playNext(track) },
                        onAddToQueueClick = { viewModel.addToQueue(track) },
                        onAddToPlaylistClick = { trackToAddToPlaylist = track },
                        onGoToArtistClick = onNavigateToArtist,
                        onRemoveDownloadClick = { viewModel.removeDownload(track.id) }
                    )
                }
            }
        }
    }

    // Modals
    trackToAddToPlaylist?.let { track ->
        AddToPlaylistModal(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, track.id)
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
                viewModel.createPlaylist(name, "User created playlist")
                showCreatePlaylistDialog = false
            },
            onDismiss = {
                showCreatePlaylistDialog = false
            }
        )
    }
}
