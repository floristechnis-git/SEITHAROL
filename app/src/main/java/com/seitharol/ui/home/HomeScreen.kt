// java/com/seitharol/ui/home/HomeScreen.kt
package com.seitharol.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.resolver.TrackMetadata
import java.util.Calendar

val HOME_MOOD_CATEGORIES = listOf(
    "Energize",
    "Trending",
    "Manipuri Song",
    "Sad",
    "Chill",
    "Romance",
    "Workout",
    "Lo-Fi"
)

@Composable
fun HomeScreen(
    userName: String,
    selectedCategory: String,
    categoryTracks: List<TrackMetadata>,
    manipuriHighlights: List<TrackMetadata>,
    isLoading: Boolean,
    onSelectCategory: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenHistory: () -> Unit,
    onPlayTrack: (TrackMetadata) -> Unit
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Late Night Vibes"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        // 1. App Name, Greeting, and History Action Button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SEITHAROL",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (userName.isNotBlank()) "$greeting, $userName" else greeting,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    HistoryClockSvgIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 2. Interactive Search Bar on Home Screen
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onOpenSearch() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchMagnifierSvgIcon(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search Manipuri songs, artists, albums...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 3. Mood & Category Selection Chips (Energize, Trending, Manipuri Song, Sad, etc.)
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(HOME_MOOD_CATEGORIES) { category ->
                    val isSelected = category.equals(selectedCategory, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCategory(category) },
                        label = {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 4. Selected Mood / Category Tracks Section
        item {
            Text(
                text = "$selectedCategory Picks",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (isLoading && categoryTracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            items(categoryTracks, key = { it.id }) { track ->
                HomeTrackRow(track = track, onClick = { onPlayTrack(track) })
            }
        }

        // 5. Dedicated Manipuri Songs Spotlight Section
        if (manipuriHighlights.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Manipuri Hits",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            items(manipuriHighlights, key = { "manipuri_${it.id}" }) { track ->
                HomeTrackRow(track = track, onClick = { onPlayTrack(track) })
            }
        }
    }
}

@Composable
private fun HomeTrackRow(
    track: TrackMetadata,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = painterResource(id = R.drawable.ic_music_note),
                    placeholder = painterResource(id = R.drawable.ic_music_note)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_play),
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Theme-aware Floating Bottom Navigation Bar that properly adapts to Dark and Light themes
 * (Fixes white-on-white invisible icons in both themes).
 */
@Composable
fun SeitharolBottomNavBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 14.dp)
            .height(60.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                shape = RoundedCornerShape(32.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf("Home", "Search", "Settings")
            tabs.forEachIndexed { index, _ ->
                val isSelected = selectedTab == index
                val iconTint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .clickable { onSelectTab(index) },
                    contentAlignment = Alignment.Center
                ) {
                    when (index) {
                        0 -> HomeSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                        1 -> SearchMagnifierSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                        else -> SettingsGearSvgIcon(tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(3f * sx, 10.5f * sy)
            lineTo(12f * sx, 3f * sy)
            lineTo(21f * sx, 10.5f * sy)
            lineTo(21f * sx, 20f * sy)
            lineTo(14.5f * sx, 20f * sy)
            lineTo(14.5f * sx, 14f * sy)
            lineTo(9.5f * sx, 14f * sy)
            lineTo(9.5f * sx, 20f * sy)
            lineTo(3f * sx, 20f * sy)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun SearchMagnifierSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 7.5f * sx,
            center = Offset(10.5f * sx, 10.5f * sy),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(16f * sx, 16f * sy),
            end = Offset(21f * sx, 21f * sy),
            strokeWidth = 2f * sx,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun HistoryClockSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 9f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        val hands = Path().apply {
            moveTo(12f * sx, 7f * sy)
            lineTo(12f * sx, 12f * sy)
            lineTo(15.5f * sx, 14f * sy)
        }
        drawPath(path = hands, color = tint, style = stroke)
    }
}

@Composable
fun SettingsGearSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 3.5f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        drawCircle(
            color = tint,
            radius = 8.5f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round)
        )
    }
}
