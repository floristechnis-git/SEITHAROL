package com.praxper.seitharol.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.praxper.seitharol.data.PlaylistItem
import com.praxper.seitharol.data.SongItem
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer

@Composable
fun LikedPlaylistSvgThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        val circleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        Canvas(modifier = Modifier.size(60.dp)) {
            drawCircle(color = circleColor)
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = "Liked Songs",
            tint = MaterialTheme.colorScheme.primary,
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
    onStartRadioMix: (SongItem) -> Unit = { onSongClick(it) },
    onPlaylistClick: (PlaylistItem) -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
    val categories = listOf("All", "For You", "Manipuri Song", "Energize", "Relax", "Workout", "Romance", "Focus")

    // Interleave history affinity + fresh category recommendations so personalization is always front-and-center
    val personalizedFeed = remember(allSongs, historySongs, selectedCategory) {
        val topHistoryArtists = historySongs
            .map { it.artist.lowercase().trim() }
            .groupingBy { it }
            .eachCount()

        val sortedCandidates = if (selectedCategory == "All" || selectedCategory == "For You") {
            val boosted = allSongs.sortedByDescending { song ->
                (topHistoryArtists[song.artist.lowercase().trim()] ?: 0) * 10
            }
            ( boosted + historySongs.take(4) )
        } else {
            allSongs
        }
        sortedCandidates.distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
    }

    val firstReadyToPlaySong = personalizedFeed.firstOrNull()
    val quickPickSongs = remember(personalizedFeed) { personalizedFeed.drop(1).take(8) }
    val moreRecommendations = remember(personalizedFeed) { personalizedFeed.drop(9).take(12) }

    val featuredPlaylists = remember(realYtmPlaylists, likedSongsCount, firstReadyToPlaySong) {
        val likedPlaylist = PlaylistItem(
            id = "liked_songs",
            title = "Liked Songs",
            subtitle = "$likedSongsCount songs",
            thumbnailUrl = "",
            isLikedPlaylist = true
        )
        val superMix = firstReadyToPlaySong?.let { seed ->
            PlaylistItem(
                id = "RDAMVM${seed.id}",
                title = "My Personalized Mix",
                subtitle = "Seeded by ${seed.artist}",
                thumbnailUrl = seed.thumbnailUrl,
                isLikedPlaylist = false
            )
        }
        listOfNotNull(likedPlaylist, superMix) + realYtmPlaylists.distinctBy { it.id }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // 1. Top Navigation Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SEITHAROL",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.8.sp
                    )
                    Text(
                        text = if (selectedCategory == "All") "Personalized For Your Taste" else "$selectedCategory Mode",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 2. Category & Personalization Filter Chips
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories) { category ->
                    val isSelected = category.equals(selectedCategory, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isSelected && category != "All") onCategorySelected("All")
                            else onCategorySelected(category)
                        },
                        shape = RoundedCornerShape(50),
                        label = {
                            Text(
                                text = category,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = null
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // 3. NEW UI: First Ready-To-Play Spotlight Hero Card for Categories & Personalization
        firstReadyToPlaySong?.let { heroSong ->
            item(key = "hero_ready_${heroSong.id}_$selectedCategory") {
                ReadyToPlayHeroCard(
                    song = heroSong,
                    selectedCategory = selectedCategory,
                    thumbnailQuality = settings.thumbnailQuality,
                    onPlayNow = { onSongClick(heroSong) },
                    onStartMix = { onStartRadioMix(heroSong) }
                )
                Spacer(modifier = Modifier.height(22.dp))
            }
        }

        // 4. Listen Again Shelf
        if (historySongs.isNotEmpty() && (selectedCategory == "All" || selectedCategory == "For You")) {
            item {
                Text(
                    text = "Jump Back In",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(historySongs.distinctBy { it.id }.take(10), key = { "again_${it.id}" }) { song ->
                        val thumb = remember(song.thumbnailUrl, settings.thumbnailQuality) {
                            ThumbnailUrlOptimizer.optimize(
                                song.thumbnailUrl,
                                settings.thumbnailQuality,
                                ThumbnailTargetSize.CARD_MEDIUM,
                                context
                            )
                        }
                        Column(
                            modifier = Modifier
                                .width(118.dp)
                                .clickable { onSongClick(song) }
                        ) {
                            Box {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = song.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(118.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = song.title,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 5. Personalized Quick Picks
        if (quickPickSongs.isNotEmpty()) {
            item {
                Text(
                    text = if (selectedCategory == "All" || selectedCategory == "For You") {
                        "Quick Picks For You"
                    } else {
                        "Top $selectedCategory Tracks"
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
            items(quickPickSongs.distinctBy { it.id }, key = { "qp_${it.id}" }) { song ->
                SongRowWithDot(song = song, onClick = { onSongClick(song) })
            }
        }

        // 6. Real YouTube Mixes, Albums & Playlists
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "YouTube Mixes, Albums & Playlists",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(featuredPlaylists.distinctBy { it.id }, key = { "pl_${it.id}" }) { playlist ->
                    val playlistThumb = remember(playlist.thumbnailUrl, settings.thumbnailQuality) {
                        ThumbnailUrlOptimizer.optimize(
                            playlist.thumbnailUrl,
                            settings.thumbnailQuality,
                            ThumbnailTargetSize.CARD_MEDIUM,
                            context
                        )
                    }
                    Column(
                        modifier = Modifier
                            .width(146.dp)
                            .clickable { onPlaylistClick(playlist) }
                    ) {
                        if (playlist.isLikedPlaylist || playlist.thumbnailUrl.isBlank()) {
                            LikedPlaylistSvgThumbnail(
                                modifier = Modifier
                                    .size(146.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            AsyncImage(
                                model = playlistThumb,
                                contentDescription = playlist.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(146.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playlist.title,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = playlist.subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 7. More Personalized Recommendations
        if (moreRecommendations.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "More Like What You Love",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            items(moreRecommendations.distinctBy { it.id }, key = { "more_${it.id}" }) { song ->
                SongRowWithDot(song = song, onClick = { onSongClick(song) })
            }
        }
    }
}

@Composable
fun ReadyToPlayHeroCard(
    song: SongItem,
    selectedCategory: String,
    thumbnailQuality: com.seitharol.data.preferences.ThumbnailQuality,
    onPlayNow: () -> Unit,
    onStartMix: () -> Unit
) {
    val context = LocalContext.current
    val heroArt = remember(song.thumbnailUrl, thumbnailQuality) {
        ThumbnailUrlOptimizer.optimize(
            song.thumbnailUrl,
            thumbnailQuality,
            ThumbnailTargetSize.HERO_LARGE,
            context
        )
    }

    val badgeLabel = remember(selectedCategory) {
        if (selectedCategory.equals("All", true) || selectedCategory.equals("For You", true)) {
            "READY TO PLAY • PERSONALIZED FOR YOU"
        } else {
            "READY TO PLAY • ${selectedCategory.uppercase()} PICK"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(206.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable { onPlayNow() }
    ) {
        // Blurred Ambient Artwork Background
        AsyncImage(
            model = heroArt,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(26.dp)
        )

        // Rich Gradient Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF0E0F12).copy(alpha = 0.92f),
                            Color(0xFF0E0F12).copy(alpha = 0.72f),
                            Color(0xFF1A1B20).copy(alpha = 0.55f)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Badge, Title, Artist, Play & Radio Buttons
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeLabel,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    lineHeight = 24.sp,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = song.artist,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPlayNow,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onStartMix,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Radio Mix", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Right: Crisp Floating Artwork with Play Badge
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                AsyncImage(
                    model = heroArt,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun SongRowWithDot(
    song: SongItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
    val optimizedThumb = remember(song.thumbnailUrl, settings.thumbnailQuality) {
        ThumbnailUrlOptimizer.optimize(
            song.thumbnailUrl,
            settings.thumbnailQuality,
            ThumbnailTargetSize.LIST_THUMB,
            context
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(14.dp))
        AsyncImage(
            model = optimizedThumb,
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
