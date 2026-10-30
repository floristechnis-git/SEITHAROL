package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadState
import com.example.data.model.Playlist
import com.example.data.model.Track
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.components.AddToPlaylistModal
import com.example.ui.components.DeletePlaylistConfirmDialog
import com.example.ui.components.ImportPlaylistDialog
import com.example.ui.components.RenamePlaylistDialog
import com.example.ui.components.SeitharolPullToRefreshBox
import com.example.ui.components.TrackCard
import com.example.ui.theme.*
import com.seitharol.data.download.DownloadPreferencesAndService
import com.seitharol.data.download.DownloadTaskState
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
    onNavigateToLocalMusic: () -> Unit = {},
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val activeHistory by viewModel.activeHistory.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val isRefreshingLibrary by viewModel.isRefreshingLibrary.collectAsState()
    val inProgressCount = remember(activeDownloads) {
        activeDownloads.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.FINALIZING || it.state == DownloadState.QUEUED }
    }

    val navTabs = remember { listOf("All", "Playlists", "Downloads", "Liked", "On Device") }
    var selectedNavTab by remember { mutableStateOf("All") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(LibraryFilterOption.RECENT) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }
    val isImportingPlaylist by viewModel.isImportingPlaylist.collectAsState()
    val context = LocalContext.current

    val filteredPlaylists = remember(playlists, selectedNavTab, activeFilter) {
        val base = when (selectedNavTab) {
            "Playlists", "All" -> playlists.filterNot {
                it.id == "pl_liked_songs" || it.id == "liked_songs" || it.title.equals("Liked Songs", ignoreCase = true) ||
                it.id == "downloads" || it.id == "pl_downloads" || it.title.equals("Downloads", ignoreCase = true) || it.title.equals("Downloaded Music", ignoreCase = true) ||
                it.id == "local_music" || it.title.equals("On-Device Music", ignoreCase = true)
            }
            else -> emptyList()
        }
        when (activeFilter) {
            LibraryFilterOption.ALPHABETICAL -> base.sortedBy { it.title.lowercase() }
            else -> base
        }
    }

    SeitharolPullToRefreshBox(
        isRefreshing = isRefreshingLibrary,
        onRefresh = { viewModel.refreshLibrary() },
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
            .testTag("library_screen")
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 215.dp)
        ) {
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { showImportPlaylistDialog = true }
                            .testTag("library_import_playlist_btn"),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Import Playlist",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Import",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

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
        }

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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                items(navTabs) { tab ->
                    val isSelected = selectedNavTab == tab
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            when (tab) {
                                "Downloads" -> onNavigateToDownloads()
                                "Liked" -> onNavigateToLikedSongs()
                                "On Device" -> onNavigateToLocalMusic()
                                else -> selectedNavTab = tab
                            }
                        },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = null,
                        label = {
                            Text(
                                text = if (tab == "Downloads" && inProgressCount > 0) "Downloads ($inProgressCount)" else tab,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        onDismissRequest = { showFilterMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        LibraryFilterOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label, color = MaterialTheme.colorScheme.onSurface) },
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

        if (selectedNavTab == "All") {
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

        if (selectedNavTab == "All") {
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
                        val dlVideoCount = downloadedTracks.count { it.isVideoTrack }
                        val dlAudioCount = (downloadedTracks.size - dlVideoCount).coerceAtLeast(0)
                        val dlSummaryText = buildString {
                            if (dlAudioCount > 0 && dlVideoCount > 0) {
                                append("$dlAudioCount song${if (dlAudioCount == 1) "" else "s"} • $dlVideoCount MP4 video${if (dlVideoCount == 1) "" else "s"}")
                            } else if (dlVideoCount > 0) {
                                append("$dlVideoCount MP4 video${if (dlVideoCount == 1) "" else "s"}")
                            } else {
                                append("$dlAudioCount offline song${if (dlAudioCount == 1) "" else "s"}")
                            }
                            if (inProgressCount > 0) append(" • $inProgressCount downloading...")
                        }
                        Text(
                            text = dlSummaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (inProgressCount > 0) MaterialTheme.colorScheme.primary else SeitharolTextSecondary
                        )
                    }
                }
            }
        }

        if (selectedNavTab == "All") {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLocalMusic() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaylistThumbnailWithBadge(
                        firstTrackThumbnailUrl = null,
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "On-Device Music",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SeitharolDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Scan & play local audio & MP4 videos from phone storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = SeitharolTextSecondary
                        )
                    }
                }
            }
        }

        items(filteredPlaylists.distinctBy { it.id }, key = { "pl_${it.id}" }) { pl ->
            ClampedSwipeToDeleteRow(
                onDelete = { playlistToDelete = pl }
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = pl.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SeitharolDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${pl.trackCount} songs" + if (pl.description.isNotBlank()) " • ${pl.description}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = SeitharolTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                            onDismissRequest = { showItemMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showItemMenu = false
                                    viewModel.playPlaylistOrMix(pl.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Open Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = { Icon(Icons.Default.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    showItemMenu = false
                                    onNavigateToPlaylist(pl.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename Playlist", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    showItemMenu = false
                                    playlistToRename = pl
                                }
                            )
                            val ctx = LocalContext.current
                            DropdownMenuItem(
                                text = { Text("Share Playlist (Online Songs)", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    showItemMenu = false
                                    viewModel.loadPlaylistTracks(pl.id) { loadedTracks ->
                                        val onlineOnly = loadedTracks.filterNot { it.isLocalOnlyTrack }
                                        if (onlineOnly.isEmpty()) {
                                            android.widget.Toast.makeText(
                                                ctx,
                                                "No online (YouTube/JioSaavn) songs to share. Offline device files are excluded.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            val excludedCount = (loadedTracks.size - onlineOnly.size).coerceAtLeast(0)
                                            com.example.ui.components.SeitharolDeepLinkHelper.sharePersonalPlaylistSystemChooser(
                                                context = ctx,
                                                playlistTitle = pl.title,
                                                playlistId = pl.id,
                                                tracks = loadedTracks,
                                                excludedOfflineCount = excludedCount
                                            )
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showItemMenu = false
                                    playlistToDelete = pl
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    playlistToRename?.let { targetPl ->
        RenamePlaylistDialog(
            initialTitle = targetPl.title,
            initialDescription = targetPl.description,
            onConfirm = { newTitle, newDesc ->
                viewModel.renamePlaylist(targetPl.id, newTitle, newDesc)
                playlistToRename = null
            },
            onDismiss = { playlistToRename = null }
        )
    }

    playlistToDelete?.let { targetPl ->
        DeletePlaylistConfirmDialog(
            playlistTitle = targetPl.title,
            onConfirmDelete = {
                viewModel.deletePlaylist(targetPl.id)
                playlistToDelete = null
            },
            onDismiss = { playlistToDelete = null }
        )
    }

    if (showImportPlaylistDialog) {
        ImportPlaylistDialog(
            isImporting = isImportingPlaylist,
            onImport = { urlOrCode: String, customName: String? ->
                viewModel.importPlaylistFromUrlOrShare(
                    rawInput = urlOrCode,
                    customName = customName
                ) { importedId ->
                    showImportPlaylistDialog = false
                    if (importedId != null) {
                        onNavigateToPlaylist(importedId)
                    }
                }
            },
            onDismiss = {
                if (!isImportingPlaylist) {
                    showImportPlaylistDialog = false
                }
            }
        )
    }
}
}

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
            color = SeitharolBg
        ) {
            content()
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (com.example.data.model.Track) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val activeHistory by viewModel.activeHistory.collectAsState()
    val archivedHistory by viewModel.archivedHistory.collectAsState()
    val isRefreshingHistory by viewModel.isRefreshingLibrary.collectAsState()
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = viewMode == "active",
                onClick = { viewMode = "active" },
                label = {
                    Text(
                        text = "Recent (${activeHistory.size})",
                        color = if (viewMode == "active") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
            FilterChip(
                selected = viewMode == "archived",
                onClick = { viewMode = "archived" },
                label = {
                    Text(
                        text = "Archived (${archivedHistory.size})",
                        color = if (viewMode == "archived") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SeitharolPullToRefreshBox(
            isRefreshing = isRefreshingHistory,
            onRefresh = { viewModel.refreshLibrary() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 215.dp)
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
                            subtitleText = if (track.isVideoTrack) "${track.artist} • MP4 Video • ${track.durationFormatted}" else null,
                            onTrackClick = { viewModel.playTrack(track) },
                            onLikeClick = { viewModel.toggleLike(track) },
                            onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                            onPlayNextClick = { viewModel.playNext(track) },
                            onAddToQueueClick = { viewModel.addToQueue(track) },
                            onStartRadioClick = { viewModel.startRadioMix(track) },
                            onAddToPlaylistClick = { onAddToPlaylist(track) },
                            onGoToArtistClick = { creator ->
                                viewModel.navigateToArtist(creator, isChannel = track.isVideoTrack)
                            },
                            onRemoveFromHistoryClick = { viewModel.deleteTrackFromHistory(track.id) },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun DownloadsScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (com.example.data.model.Track) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val isRefreshingDownloads by viewModel.isRefreshingLibrary.collectAsState()
    val downloadService = remember { DownloadPreferencesAndService.getInstance(context) }
    val discoverTaskState by downloadService.downloadState.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showQualitySheet by remember { mutableStateOf(false) }

    val inProgressQueueItems = remember(activeDownloads) {
        activeDownloads.filter {
            it.state == DownloadState.DOWNLOADING ||
                it.state == DownloadState.FINALIZING ||
                it.state == DownloadState.QUEUED
        }
    }

    val activeDiscoverDownloading = discoverTaskState as? DownloadTaskState.Downloading
    val showDiscoverCardSeparately = remember(activeDiscoverDownloading, inProgressQueueItems) {
        activeDiscoverDownloading != null && inProgressQueueItems.none {
            it.trackId == activeDiscoverDownloading.trackId ||
                downloadService.canonicalTrackId(it.trackId) == downloadService.canonicalTrackId(activeDiscoverDownloading.trackId)
        }
    }

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
            placeholder = { Text("Search downloads...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SeitharolPullToRefreshBox(
            isRefreshing = isRefreshingDownloads,
            onRefresh = { viewModel.refreshLibrary() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 215.dp)
        ) {
            if (inProgressQueueItems.isNotEmpty() || showDiscoverCardSeparately) {
                item {
                    Text(
                        text = "Currently Downloading",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                if (showDiscoverCardSeparately && activeDiscoverDownloading != null) {
                    item(key = "active_discover_dl_${activeDiscoverDownloading.trackId}") {
                        ActiveProcessingDownloadCard(
                            title = activeDiscoverDownloading.trackTitle,
                            artist = activeDiscoverDownloading.artistName,
                            artUrl = activeDiscoverDownloading.thumbnailUrl,
                            progressFraction = (activeDiscoverDownloading.progressPercent / 100f).coerceIn(0f, 1f),
                            subtitle = "Downloading via ${activeDiscoverDownloading.provider.displayName} • ${activeDiscoverDownloading.progressPercent}%",
                            onCancel = { downloadService.cancelActiveDownload() }
                        )
                    }
                }

                items(inProgressQueueItems, key = { "active_dl_${it.trackId}" }) { item ->
                    val safeProg = if (item.progress.isNaN() || item.progress.isInfinite()) 0f else item.progress.coerceIn(0f, 1f)
                    val pct = (safeProg * 100).toInt().coerceIn(0, 99)
                    ActiveProcessingDownloadCard(
                        title = item.title,
                        artist = item.artist,
                        artUrl = item.artUrl,
                        progressFraction = safeProg,
                        subtitle = "Downloading (${item.tier.label}) • $pct%",
                        onCancel = { viewModel.cancelDownload(item.trackId) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (filteredTracks.isEmpty() && inProgressQueueItems.isEmpty() && !showDiscoverCardSeparately) {
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
                            onDownloadClick = { tier -> viewModel.startDownload(track, tier) },
                            onPlayNextClick = { viewModel.playNext(track) },
                            onAddToQueueClick = { viewModel.addToQueue(track) },
                            onStartRadioClick = { viewModel.startRadioMix(track) },
                            onAddToPlaylistClick = { onAddToPlaylist(track) },
                            onGoToArtistClick = { creator ->
                                viewModel.navigateToArtist(creator, isChannel = track.isVideoTrack)
                            },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
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

@Composable
fun ActiveProcessingDownloadCard(
    title: String,
    artist: String,
    artUrl: String,
    progressFraction: Float,
    subtitle: String,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel download",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Cancel",
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
fun LikedSongsScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    onAddToPlaylist: (com.example.data.model.Track) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val likedTracks by viewModel.likedTracks.collectAsState()
    val isRefreshingLiked by viewModel.isRefreshingLibrary.collectAsState()
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
                color = SeitharolDark,
                modifier = Modifier.weight(1f)
            )
            if (likedTracks.isNotEmpty()) {
                TextButton(onClick = { viewModel.downloadAllTracks(likedTracks) }) {
                    Icon(Icons.Default.Download, contentDescription = "Download All", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Download All", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search liked songs...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SeitharolPullToRefreshBox(
            isRefreshing = isRefreshingLiked,
            onRefresh = { viewModel.refreshLibrary() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 215.dp)
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
                            onStartRadioClick = { viewModel.startRadioMix(track) },
                            onAddToPlaylistClick = { onAddToPlaylist(track) },
                            onGoToArtistClick = { creator ->
                                viewModel.navigateToArtist(creator, isChannel = track.isVideoTrack)
                            },
                            onRemoveDownloadClick = { viewModel.removeDownload(track.id) },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        }
    }
}
