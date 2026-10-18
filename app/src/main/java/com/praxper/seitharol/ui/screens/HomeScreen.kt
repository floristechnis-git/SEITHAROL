package com.praxper.seitharol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.praxper.seitharol.data.PlaylistItem
import com.praxper.seitharol.data.SongItem

@Composable
fun LikedPlaylistSvgThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF262727), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(56.dp)) {
            drawCircle(color = Color.White.copy(alpha = 0.12f))
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Liked Songs",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun HomeScreen(
    allSongs: List<SongItem>,
    historySongs: List<SongItem>,
    realYtmPlaylists: List<PlaylistItem>,
    likedSongsCount: Int,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onSongClick: (SongItem) -> Unit,
    onPlaylistClick: (PlaylistItem) -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val categories = listOf("All", "For You", "Relax", "Energize", "Workout", "Focus")

    // Strict deduplication so no duplicate songs appear below the category chips
    val deduplicatedFeed = remember(allSongs, historySongs, selectedCategory) {
        val base = when (selectedCategory) {
            "All" -> (historySongs + allSongs)
            "For You" -> historySongs.ifEmpty { allSongs }
            else -> allSongs.filter { it.category.equals(selectedCategory, ignoreCase = true) }
        }
        base.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }

    val featuredPlaylists = remember(realYtmPlaylists, likedSongsCount) {
        val likedPlaylist = PlaylistItem(
            id = "liked_songs",
            title = "Liked Songs",
            subtitle = "$likedSongsCount songs",
            thumbnailUrl = "",
            isLikedPlaylist = true
        )
        listOf(likedPlaylist) + realYtmPlaylists
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F)),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // 1. Top Navigation Bar with "SEITHAROL" (Auto-suggest removed)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SEITHAROL",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                    IconButton(onClick = onProfileClick) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", tint = Color.White)
                    }
                }
            }
        }

        // 2. YouTube Music Style Category Filter Chips (Pure White active state)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            // Tapping an active category resets to "All" like YouTube Music
                            if (isSelected && category != "All") {
                                onCategorySelected("All")
                            } else {
                                onCategorySelected(category)
                            }
                        },
                        label = {
                            Text(
                                text = category,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0xFF1B1C1C),
                            labelColor = Color.White
                        ),
                        border = null
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 3. Quick Picks / Category Feed (Deduplicated)
        item {
            Text(
                text = if (selectedCategory == "All") "Quick Picks" else "$selectedCategory Mix",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        items(deduplicatedFeed.take(8), key = { it.id }) { song ->
            SongRowWithDot(song = song, onClick = { onSongClick(song) })
        }

        // 4. Featured Albums & Real YouTube Music Playlists (Includes SVG Liked Songs Thumbnail)
        if (selectedCategory == "All" || selectedCategory == "For You") {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Featured Albums & Playlists",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(featuredPlaylists, key = { it.id }) { playlist ->
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { onPlaylistClick(playlist) }
                        ) {
                            if (playlist.isLikedPlaylist || playlist.thumbnailUrl.isBlank()) {
                                LikedPlaylistSvgThumbnail(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                AsyncImage(
                                    model = playlist.thumbnailUrl,
                                    contentDescription = playlist.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(140.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1B1C1C))
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = playlist.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = playlist.subtitle,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 5. Trending Songs For You (Index removed and replaced with div dot)
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Trending Songs for You",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            items(deduplicatedFeed.drop(8).take(10), key = { "trending_${it.id}" }) { song ->
                SongRowWithDot(song = song, onClick = { onSongClick(song) })
            }
        }
    }
}

@Composable
fun SongRowWithDot(
    song: SongItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Div dot indicator instead of numeric index
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White)
        )

        Spacer(modifier = Modifier.width(14.dp))

        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1B1C1C))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
