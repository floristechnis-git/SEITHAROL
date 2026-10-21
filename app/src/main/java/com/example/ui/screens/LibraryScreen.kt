package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Playlist
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.TrackCard
import com.example.ui.theme.*
import com.seitharol.ui.components.PlaylistThumbnailWithBadge
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class LibraryFilterOption(val label: String) {
    RECENT("Recent"),
    ARCHIVED("Archived"),
    MOST_PLAYED("Most Played"),
    ALPHABETICAL("A–Z")
}

@Composable
fun LibraryScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToLikedSongs: () -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val activeHistory by viewModel.activeHistory.collectAsState()
    val isRefreshingLibrary by viewModel.isRefreshingLibrary.collectAsState()

    val navTabs = remember { listOf("All", "Playlists", "Downloads", "Liked") }
    var selectedNavTab by remember { mutableStateOf("All") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(LibraryFilterOption.RECENT) }

    val filteredPlaylists = remember(playlists, selectedNavTab, activeFilter) {
        val base = when (selectedNavTab) {
            "Playlists" -> playlists
            "All" -> playlists
            else -> emptyList()
        }
        when (activeFilter) {
            LibraryFilterOption.ALPHABETICAL -> base.sortedBy { it.title.lowercase() }
            else -> base
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
            .testTag("library_screen")
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
        // 1. Heading & New Playlist Button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your library",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = SeitharolDark
                )

                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onCreatePlaylist)
                        .testTag("library_create_playlist_btn"),
                    shape = RoundedCornerShape(18.dp),
                    color = SeitharolPillBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = SeitharolPillText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "New",
                            color = SeitharolPillText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2. History > on TOP of Library nav pills + Horizontal Scrollable Thumbnails
        if (activeHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToHistory() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History >",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = SeitharolDark
                    )
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9EA1A1)
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(activeHistory.distinctBy { it.id }.take(12), key = { "hist_top_${it.id}" }) { track ->
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .clickable { viewModel.playTrack(track) }
                        ) {
                            AsyncImage(
                                model = track.artUrl,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = SeitharolDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 3. Category Nav Row (Strict 36.dp height, 20.dp divider, compact 32.dp filter pill)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    items(navTabs) { tab ->
                        val isSelected = selectedNavTab == tab
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedNavTab = tab },
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = null,
                            label = {
                                Text(
                                    text = tab,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                // Compact Vertical Bar (Strictly 20.dp tall so it NEVER stretches)
                HorizontalDivider(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .height(20.dp)
                        .width(1.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )

                // Compact Filter Button (Strictly 32.dp tall to match chips)
                Box {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showFilterMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.seitharol.ui.library.FilterSlidersSvgIcon(
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = activeFilter.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        LibraryFilterOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    activeFilter = option
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 4. Liked Songs List Item (when All or Liked is selected)
        if (selectedNavTab == "All" || selectedNavTab == "Liked") {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLikedSongs() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistThumbnailWithBadge(
                        firstTrackThumbnailUrl = likedTracks.firstOrNull()?.artUrl,
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Liked Songs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SeitharolDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${likedTracks.size} songs • Auto Playlist",
                            style = MaterialTheme.typography.bodySmall,
                            color = SeitharolTextSecondary
                        )
                    }
                }
            }
        }

        // 5. Downloaded Songs List Item (when All or Downloads is selected)
        if (selectedNavTab == "All" || selectedNavTab == "Downloads") {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToDownloads() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistThumbnailWithBadge(
                        firstTrackThumbnailUrl = downloadedTracks.firstOrNull()?.artUrl,
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Downloaded Music",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SeitharolDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${downloadedTracks.size} offline songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = SeitharolTextSecondary
                        )
                    }
                }
            }
        }

        // 6. User Playlists with Clamped Swipe-to-Delete and Donut/Thumbnail badge
        items(filteredPlaylists.distinctBy { it.id }, key = { "pl_${it.id}" }) { pl ->
            ClampedSwipeToDeleteRow(
                onDelete = { viewModel.deletePlaylist(pl.id) }
            ) {
                var showItemMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SeitharolBg)
                        .clickable { onNavigateToPlaylist(pl.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistThumbnailWithBadge(
                        firstTrackThumbnailUrl = pl.coverUrl.takeIf { pl.trackCount > 0 },
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pl.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SeitharolDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pl.trackCount} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = SeitharolTextSecondary
                        )
                    }
                    Box {
                        IconButton(onClick = { showItemMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = SeitharolDark
                            )
                        }
                        DropdownMenu(
                            expanded = showItemMenu,
                            onDismissRequest = { showItemMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open") },
                                onClick = {
                                    showItemMenu = false
                                    onNavigateToPlaylist(pl.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showItemMenu = false
                                    viewModel.deletePlaylist(pl.id)
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

/**
 * Clamped Swipe-Left Row: never slides past -88.dp so the user can always slide it back
 * to 0.dp or tap the Delete button to remove the item cleanly.
 */
@Composable
fun ClampedSwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxRevealPx = with(density) { -88.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Background Delete Action Button - only visible when actively dragged
        val isSwiping = offsetX.value < -4f
        if (isSwiping) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFF5252).copy(alpha = 0.9f))
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            offsetX.snapTo(0f)
                            onDelete()
                        }
                    }
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Foreground Content clamped strictly to [maxRevealPx, 0f] with opaque background
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (offsetX.value < maxRevealPx * 0.45f) maxRevealPx else 0f
                                offsetX.animateTo(target)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val clamped = (offsetX.value + dragAmount).coerceIn(maxRevealPx, 0f)
                            scope.launch { offsetX.snapTo(clamped) }
                        }
                    )
                },
            shape = RoundedCornerShape(14.dp),
            color = SeitharolWhite
        ) {
            content()
        }
    }
}

/**
 * Dedicated History Screen with Top-Left Back Arrow and Search Bar.
 */
@Composable
fun HistoryScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val activeHistory by viewModel.activeHistory.collectAsState()
    val archivedHistory by viewModel.archivedHistory.collectAsState()
    var viewMode by remember { mutableStateOf("active") }
    var searchQuery by remember { mutableStateOf("") }

    val rawList = if (viewMode == "active") activeHistory else archivedHistory
    val filteredTracks = remember(rawList, searchQuery) {
        if (searchQuery.isBlank()) rawList
        else rawList.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SeitharolDark)
            }
            Text(
                text = "Listening History",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SeitharolDark
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.clearAllHistory() }) {
                Text("Clear All", color = Color(0xFFFF5252))
            }
        }

        // View Mode Filter Tabs (Recent 15 Days vs Archived)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = viewMode == "active",
                onClick = { viewMode = "active" },
                label = { Text("Recent (${activeHistory.size})") }
            )
            FilterChip(
                selected = viewMode == "archived",
                onClick = { viewMode = "archived" },
                label = { Text("Archived (${archivedHistory.size})") }
            )
        }

        // Dedicated Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            if (filteredTracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No history found", color = SeitharolTextSecondary)
                    }
                }
            } else {
                items(filteredTracks.distinctBy { it.id }, key = { "hist_sc_${it.id}" }) { track ->
                    ClampedSwipeToDeleteRow(onDelete = { viewModel.deleteTrackFromHistory(track.id) }) {
                        TrackCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            onTrackClick = { viewModel.playTrack(track) },
                            onLikeClick = { viewModel.toggleLike(track) },
                            onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                            onPlayNextClick = { viewModel.playNext(track) },
                            onAddToQueueClick = { viewModel.addToQueue(track) },
                            onAddToPlaylistClick = { },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Downloads Screen with Top-Left Back Arrow and Search Bar.
 */
@Composable
fun DownloadsScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showQualitySheet by remember { mutableStateOf(false) }

    val filteredTracks = remember(downloadedTracks, searchQuery) {
        if (searchQuery.isBlank()) downloadedTracks
        else downloadedTracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = "Downloaded Songs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showQualitySheet = true }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Audio Quality Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search downloads...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            if (filteredTracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No downloaded songs yet", color = SeitharolTextSecondary)
                    }
                }
            } else {
                items(filteredTracks.distinctBy { it.id }, key = { "dl_sc_${it.id}" }) { track ->
                    ClampedSwipeToDeleteRow(onDelete = { viewModel.removeDownload(track.id) }) {
                        TrackCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            onTrackClick = { viewModel.playTrack(track) },
                            onLikeClick = { viewModel.toggleLike(track) },
                            onDownloadClick = { },
                            onPlayNextClick = { viewModel.playNext(track) },
                            onAddToQueueClick = { viewModel.addToQueue(track) },
                            onAddToPlaylistClick = { },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        if (showQualitySheet) {
            com.example.ui.components.QualitySettingsBottomSheet(
                onDismiss = { showQualitySheet = false }
            )
        }
    }
}

/**
 * Dedicated Liked Songs Screen with Top-Left Back Arrow and Search Bar.
 */
@Composable
fun LikedSongsScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val likedTracks by viewModel.likedTracks.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val filteredTracks = remember(likedTracks, searchQuery) {
        if (searchQuery.isBlank()) likedTracks
        else likedTracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = SeitharolDark)
            }
            Text(
                text = "Liked Songs",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SeitharolDark
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search liked songs...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            if (filteredTracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No liked songs yet", color = SeitharolTextSecondary)
                    }
                }
            } else {
                items(filteredTracks.distinctBy { it.id }, key = { "liked_sc_${it.id}" }) { track ->
                    ClampedSwipeToDeleteRow(onDelete = { viewModel.toggleLike(track) }) {
                        TrackCard(
                            track = track,
                            isPlaying = playerState.currentTrack?.id == track.id,
                            onTrackClick = {
                                val distinctList = filteredTracks.distinctBy { it.id }
                                val idx = distinctList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                viewModel.playQueue(distinctList, idx)
                            },
                            onLikeClick = { viewModel.toggleLike(track) },
                            onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                            onPlayNextClick = { viewModel.playNext(track) },
                            onAddToQueueClick = { viewModel.addToQueue(track) },
                            onAddToPlaylistClick = { },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
