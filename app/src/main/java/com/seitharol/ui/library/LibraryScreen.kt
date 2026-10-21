package com.seitharol.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.ui.components.PlaylistThumbnailWithBadge
import com.seitharol.ui.home.PlaylistSummary
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.ui.search.CloseCrossSvgIcon
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class LibraryFilterOption(val label: String) {
    RECENT("Recent"),
    ARCHIVED("Archived"),
    MOST_PLAYED("Most Played"),
    ALPHABETICAL("A - Z")
}

@Composable
fun FilterSlidersSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val strokeW = 2f * sx
        drawLine(tint, Offset(4f * sx, 6f * sy), Offset(20f * sx, 6f * sy), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.4f * sx, center = Offset(9f * sx, 6f * sy))
        drawLine(tint, Offset(4f * sx, 12f * sy), Offset(20f * sx, 12f * sy), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.4f * sx, center = Offset(15f * sx, 12f * sy))
        drawLine(tint, Offset(4f * sx, 18f * sy), Offset(20f * sx, 18f * sy), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.4f * sx, center = Offset(10f * sx, 18f * sy))
    }
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
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
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
            LibraryFilterOption.MOST_PLAYED -> tabFiltered.sortedByDescending { it.trackCount }
            else -> tabFiltered
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        // 1. History Shelf on TOP of Library nav pills
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
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
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(historyTracks.distinctBy { it.videoId }.take(12), key = { "hist_top_${it.videoId}" }) { track ->
                        val optimizedThumb = remember(track.thumbnailUrl, settings.thumbnailQuality) {
                            ThumbnailUrlOptimizer.optimize(
                                track.thumbnailUrl,
                                settings.thumbnailQuality,
                                ThumbnailTargetSize.CARD_MEDIUM,
                                context
                            )
                        }
                        Column(
                            modifier = Modifier
                                .width(104.dp)
                                .clickable { onPlayTrack(track) }
                        ) {
                            AsyncImage(
                                model = optimizedThumb,
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 2. Scrollable Category Pills + Real SVG Filter Button (Strict 36.dp height, 20.dp divider, compact 32.dp filter pill)
        // 2. Scrollable Category Pills (Full-width horizontal scrolling with plenty of room)
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
        }

        // 3. Filter Button Row (Moved directly below the category chips row)
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
                            FilterSlidersSvgIcon(
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

        // 3. Library List Items (System items like Liked/Downloads cannot be swipe-deleted)
        items(filteredEntries.distinctBy { it.id }, key = { "lib_item_${it.id}" }) { item ->
            val isSystemPlaylist = item.id == "liked" || item.id == "downloads"
            val optimizedPlaylistThumb = remember(item.firstVideoThumbnailUrl, settings.thumbnailQuality) {
                ThumbnailUrlOptimizer.optimize(
                    item.firstVideoThumbnailUrl,
                    settings.thumbnailQuality,
                    ThumbnailTargetSize.LIST_THUMB,
                    context
                )
            }

            val rowContent: @Composable () -> Unit = {
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
                        firstTrackThumbnailUrl = optimizedPlaylistThumb,
                        size = 64.dp,
                        cornerRadius = 10.dp
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground,
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
                            if (!isSystemPlaylist) {
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

            if (isSystemPlaylist) {
                rowContent()
            } else {
                ClampedSwipeToDeleteRow(
                    onDelete = { onDeletePlaylist(item.id) },
                    content = rowContent
                )
            }
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
        // Only paint red delete background when actively swiped left (prevents red bleed-through)
        if (offsetX.value < -2f) {
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
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

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

@Composable
fun LibrarySubPageScreen(
    pageTitle: String,
    tracks: List<CandidateStream>,
    onBack: () -> Unit,
    onPlayTrackFromGroup: (CandidateStream) -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

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
            items(filteredTracks.distinctBy { it.videoId }, key = { "sub_${it.videoId}" }) { track ->
                val optimizedThumb = remember(track.thumbnailUrl, settings.thumbnailQuality) {
                    ThumbnailUrlOptimizer.optimize(
                        track.thumbnailUrl,
                        settings.thumbnailQuality,
                        ThumbnailTargetSize.LIST_THUMB,
                        context
                    )
                }
                ClampedSwipeToDeleteRow(onDelete = { onRemoveTrack(track.videoId) }) {
                    ListItem(
                        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            AsyncImage(
                                model = optimizedThumb,
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
