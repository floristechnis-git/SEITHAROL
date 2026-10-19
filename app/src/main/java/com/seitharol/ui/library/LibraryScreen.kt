package com.seitharol.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.ui.components.PlaylistThumbnailWithBadge
import com.seitharol.ui.home.PlaylistSummary
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.ui.search.CloseCrossSvgIcon
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
    historyTracks: List<CandidateStream>,
    libraryEntries: List<PlaylistSummary>,
    onOpenHistoryPage: () -> Unit,
    onOpenSubPage: (PlaylistSummary) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlayTrack: (CandidateStream) -> Unit
) {
    val navTabs = remember { listOf("All", "Playlists", "Downloads", "Liked") }
    var selectedNavTab by remember { mutableStateOf("All") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(LibraryFilterOption.RECENT) }

    val filteredEntries = remember(libraryEntries, selectedNavTab, activeFilter) {
        val tabFiltered = when (selectedNavTab) {
            "Liked" -> libraryEntries.filter { it.id == "liked" }
            "Downloads" -> libraryEntries.filter { it.id == "downloads" }
            "Playlists" -> libraryEntries.filter { it.id != "liked" && it.id != "downloads" }
            else -> libraryEntries
        }
        when (activeFilter) {
            LibraryFilterOption.ALPHABETICAL -> tabFiltered.sortedBy { it.title.lowercase() }
            else -> tabFiltered
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // 1. History > on TOP of Library nav pills + Horizontal Scrollable Thumbnails
        if (historyTracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenHistoryPage() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History >",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    items(historyTracks.take(12), key = { "hist_top_${it.videoId}" }) { track ->
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .clickable { onPlayTrack(track) }
                        ) {
                            AsyncImage(
                                model = track.thumbnailUrl,
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 2. Category Nav Div (Same 16.dp left & right padding as Home Screen) + Filter SVG Action Popup
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    navTabs.forEach { tab ->
                        FilterChip(
                            selected = selectedNavTab == tab,
                            onClick = { selectedNavTab = tab },
                            label = { Text(tab) }
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showFilterMenu = true }) {
                        // Filter SVG Icon
                        Text("⇅", style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        LibraryFilterOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = activeFilter == option,
                                            onCheckedChange = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(option.label)
                                    }
                                },
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

        // 3. Library List Items (Thumbnail on left with Donut/Video + Badge, Title on right, Three-dot menu, Clamped Swipe-to-Delete)
        items(filteredEntries, key = { "lib_item_${it.id}" }) { item ->
            ClampedSwipeToDeleteRow(
                onDelete = { onDeletePlaylist(item.id) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable { onOpenSubPage(item) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var showItemMenu by remember { mutableStateOf(false) }
                    PlaylistThumbnailWithBadge(
                        firstTrackThumbnailUrl = item.firstVideoThumbnailUrl,
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.trackCount} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { showItemMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurface
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
                                    onOpenSubPage(item)
                                }
                            )
                            if (item.id != "liked" && item.id != "downloads") {
                                DropdownMenuItem(
                                    text = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showItemMenu = false
                                        onDeletePlaylist(item.id)
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
        // Background Delete Action Button
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer)
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
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            }
        }

        // Foreground Content clamped strictly to [maxRevealPx, 0f]
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
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
                }
        ) {
            content()
        }
    }
}

/**
 * Dedicated Sub-Page for Playlist, History, Downloads, and Liked Songs:
 * Has a Top-Left Back Arrow and its own Search Bar + Clamped Swipe-to-Delete.
 */
@Composable
fun LibrarySubPageScreen(
    pageTitle: String,
    tracks: List<CandidateStream>,
    onBack: () -> Unit,
    onPlayTrackFromGroup: (CandidateStream) -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top Bar with Back Arrow on Top-Left
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                BackArrowSvgIcon(
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }

        // Dedicated Search Bar for this page
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search in $pageTitle...") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        CloseCrossSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredTracks, key = { "sub_${it.videoId}" }) { track ->
                ClampedSwipeToDeleteRow(onDelete = { onRemoveTrack(track.videoId) }) {
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            AsyncImage(
                                model = track.thumbnailUrl,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        },
                        modifier = Modifier.clickable { onPlayTrackFromGroup(track) }
                    )
                }
            }
        }
    }
}
