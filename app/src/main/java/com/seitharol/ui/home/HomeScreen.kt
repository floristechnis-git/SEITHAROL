package com.seitharol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.ui.components.PlaylistThumbnailWithBadge
import java.util.Calendar

data class PlaylistSummary(
    val id: String,
    val title: String,
    val trackCount: Int,
    val firstVideoThumbnailUrl: String?,
    val isLikedSongsCard: Boolean = false
)

@Composable
fun HomeScreen(
    listeningHistory: List<CandidateStream>,
    realYtMixes: List<PlaylistSummary>,
    userPlaylists: List<PlaylistSummary>,
    categoryFeeds: Map<String, List<CandidateStream>>,
    trendingSongs: List<CandidateStream>,
    onPlaySingleTrack: (CandidateStream) -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    // 1. First-Launch Empty State
    if (listeningHistory.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Ready to play your first song",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val categories = remember { listOf("All", "For You", "Energize", "Relax", "Workout", "Focus") }
    var selectedCategory by remember { mutableStateOf("All") }

    // Deduplicated tracks below category filter, dynamically reactive to active category chip
    val activeQuickPicks = remember(selectedCategory, listeningHistory, categoryFeeds) {
        val rawList = if (selectedCategory == "All") {
            listeningHistory + (categoryFeeds["For You"] ?: emptyList())
        } else {
            categoryFeeds[selectedCategory] ?: listeningHistory
        }
        CanonicalAudioResolver.deduplicateTracks(rawList)
    }

    // Featured Row: "Liked Songs" once on far left + non-empty playlists/mixes with valid thumbnails
    val featuredItems = remember(realYtMixes, userPlaylists) {
        val likedCard = userPlaylists.firstOrNull { it.isLikedSongsCard }
        val nonEmptyOthers = (realYtMixes + userPlaylists.filterNot { it.isLikedSongsCard })
            .distinctBy { it.id }
            .filter { it.trackCount > 0 && !it.firstVideoThumbnailUrl.isNullOrBlank() }
        listOfNotNull(likedCard?.takeIf { it.trackCount > 0 }) + nonEmptyOthers
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        // Tight top padding between Top Nav Bar and "Good [Morning/Afternoon/Evening]"
        item {
            Text(
                text = getTimeGreeting(),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            )
        }

        // YouTube Music-style Category Filter Row (16.dp horizontal padding)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category) }
                    )
                }
            }
        }

        // Deduplicated Quick Picks below Category Filter
        items(activeQuickPicks.take(8), key = { "qp_${it.videoId}" }) { track ->
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
                modifier = Modifier.clickable { onPlaySingleTrack(track) }
            )
        }

        // Featured Albums & Real YouTube Mixes (No empty playlists, First Video Thumbnail + Floating Badge)
        if (featuredItems.isNotEmpty()) {
            item {
                Text(
                    text = if (selectedCategory == "All") "Featured Albums & Mixes" else "$selectedCategory Mixes",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(featuredItems, key = { "feat_${it.id}" }) { playlist ->
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { onOpenPlaylist(playlist.id) }
                        ) {
                            PlaylistThumbnailWithBadge(
                                firstTrackThumbnailUrl = playlist.firstVideoThumbnailUrl,
                                size = 140.dp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = playlist.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Trending Songs for You (Div dot removed, sorted by popularity)
        item {
            Text(
                text = "Trending Songs for You",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        val sortedTrending = CanonicalAudioResolver.deduplicateTracks(
            CanonicalAudioResolver.sortByPopularity(trendingSongs)
        )
        items(sortedTrending.take(10), key = { "trend_${it.videoId}" }) { track ->
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
                modifier = Modifier.clickable { onPlaySingleTrack(track) }
            )
        }
    }
}

private fun getTimeGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        else -> "Good Evening"
    }
}
