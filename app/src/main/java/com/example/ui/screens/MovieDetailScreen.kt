package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily
import com.seitharol.R
import com.seitharol.data.resolver.VidLinkRawMovieStreamer

/**
 * Dribbble-inspired Netflix/Prime-style Movie & TV Series Preview Screen
 * styled strictly with SEITHAROL's adaptive MaterialTheme (Syne + Outfit + MaterialTheme.colorScheme).
 */
@Composable
fun MovieDetailScreen(
    seedTrack: Track,
    onBack: () -> Unit,
    onPlayMovie: (Track) -> Unit,
    onPlayEpisodeQueue: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onBgColor = MaterialTheme.colorScheme.onBackground
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    var preview by remember(seedTrack.id) { mutableStateOf<VidLinkRawMovieStreamer.TitlePreview?>(null) }
    var isLoading by remember(seedTrack.id) { mutableStateOf(true) }
    var selectedSeasonNumber by remember(seedTrack.id) { mutableIntStateOf(1) }
    var isSynopsisExpanded by remember(seedTrack.id) { mutableStateOf(false) }

    LaunchedEffect(seedTrack.id) {
        isLoading = true
        val loaded = runCatching { VidLinkRawMovieStreamer.fetchTitlePreview(seedTrack) }.getOrNull()
        preview = loaded
        if (loaded != null && loaded.seasons.isNotEmpty()) {
            selectedSeasonNumber = loaded.seasons.first().number
        }
        isLoading = false
    }

    val activeSeason = remember(preview, selectedSeasonNumber) {
        preview?.seasons?.firstOrNull { it.number == selectedSeasonNumber }
            ?: preview?.seasons?.firstOrNull()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            // 1. Hero Backdrop Banner blending smoothly into SEITHAROL theme background
            item(key = "hero_banner") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                ) {
                    AsyncImage(
                        model = (preview?.backdrop?.ifBlank { preview?.poster } ?: seedTrack.artUrl),
                        contentDescription = preview?.title ?: seedTrack.title,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Top subtle scrim for back button legibility
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.52f), Color.Transparent)
                                )
                            )
                    )

                    // Smooth bottom gradient dissolving seamlessly into SEITHAROL's theme background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(175.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.55f to bgColor.copy(alpha = 0.72f),
                                        1.0f to bgColor
                                    )
                                )
                            )
                    )

                    // Floating Back Button
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = onBgColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Bottom-Aligned Poster + Title & Key Metadata
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 8.dp,
                            color = surfaceColor,
                            modifier = Modifier
                                .width(92.dp)
                                .height(134.dp)
                        ) {
                            AsyncImage(
                                model = (preview?.poster?.ifBlank { seedTrack.artUrl } ?: seedTrack.artUrl),
                                contentDescription = preview?.title ?: seedTrack.title,
                                placeholder = appLogoPainter,
                                error = appLogoPainter,
                                fallback = appLogoPainter,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = 4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = primaryColor.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = if (preview?.isSeries == true || VidLinkRawMovieStreamer.isSeriesTrack(seedTrack)) {
                                        "TV SERIES"
                                    } else {
                                        "MOVIE"
                                    },
                                    color = primaryColor,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.8.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = preview?.title ?: seedTrack.title,
                                color = onBgColor,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                lineHeight = 26.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val rating = preview?.rating.orEmpty()
                                if (rating.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = primaryColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = rating,
                                            color = onBgColor,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp
                                        )
                                    }
                                }
                                val year = preview?.year.orEmpty()
                                val runtime = preview?.runtime.orEmpty()
                                val metaLine = listOf(year, runtime).filter { it.isNotBlank() }.joinToString(" • ")
                                if (metaLine.isNotBlank()) {
                                    Text(
                                        text = metaLine,
                                        color = secondaryText,
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Primary Play CTA & Genre Pills
            item(key = "play_cta") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    val isSeries = preview?.isSeries == true || VidLinkRawMovieStreamer.isSeriesTrack(seedTrack)
                    val firstEp = activeSeason?.episodes?.firstOrNull()
                    val ctaLabel = when {
                        isLoading -> "Loading details..."
                        isSeries && firstEp != null -> "Play S${firstEp.season} · E${firstEp.number}"
                        isSeries -> "Play Season 1 · Episode 1"
                        else -> "Play Movie"
                    }

                    Button(
                        onClick = {
                            val loaded = preview
                            if (loaded != null) {
                                if (loaded.isSeries) {
                                    val seasonEps = activeSeason?.episodes.orEmpty()
                                    if (seasonEps.isNotEmpty()) {
                                        val queue = seasonEps.map { VidLinkRawMovieStreamer.buildEpisodePlayableTrack(loaded, it) }
                                        onPlayEpisodeQueue(queue, 0)
                                    } else {
                                        val ep1 = VidLinkRawMovieStreamer.EpisodeItem(1, 1, "Episode 1", "", loaded.backdrop, loaded.runtime, "")
                                        onPlayMovie(VidLinkRawMovieStreamer.buildEpisodePlayableTrack(loaded, ep1))
                                    }
                                } else {
                                    onPlayMovie(VidLinkRawMovieStreamer.buildMoviePlayableTrack(loaded))
                                }
                            } else {
                                onPlayMovie(seedTrack)
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = onPrimaryColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = onPrimaryColor,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = ctaLabel,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    val genres = preview?.genres.orEmpty()
                    if (genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(genres) { genre ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = surfaceColor
                                ) {
                                    Text(
                                        text = genre,
                                        color = secondaryText,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Synopsis & Cast
            val synopsis = preview?.description.orEmpty()
            if (synopsis.isNotBlank()) {
                item(key = "synopsis") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { isSynopsisExpanded = !isSynopsisExpanded }
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = synopsis,
                            color = onBgColor.copy(alpha = 0.86f),
                            fontFamily = OutfitFontFamily,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            maxLines = if (isSynopsisExpanded) 12 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (synopsis.length > 130) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isSynopsisExpanded) "Show less" else "Read more",
                                color = primaryColor,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            val cast = preview?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        Text(
                            text = "Cast",
                            color = onBgColor,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(cast) { actor ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = surfaceColor
                                ) {
                                    Text(
                                        text = actor,
                                        color = onBgColor,
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Seasons & Episodes Section (Netflix / Prime Style for Series like The Mentalist)
            val seasons = preview?.seasons.orEmpty()
            if (seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Episodes",
                                color = onBgColor,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "${activeSeason?.episodes?.size ?: 0} episodes",
                                color = secondaryText,
                                fontFamily = OutfitFontFamily,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons, key = { "season_${it.number}" }) { season ->
                                val selected = season.number == (activeSeason?.number ?: 1)
                                Surface(
                                    onClick = { selectedSeasonNumber = season.number },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (selected) primaryColor else surfaceColor
                                ) {
                                    Text(
                                        text = season.name,
                                        color = if (selected) onPrimaryColor else secondaryText,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                val epList = activeSeason?.episodes.orEmpty()
                items(epList.indices.toList(), key = { idx -> "ep_${activeSeason?.number}_${epList[idx].number}" }) { idx ->
                    val ep = epList[idx]
                    Surface(
                        onClick = {
                            val loaded = preview ?: return@Surface
                            val queue = epList.map { VidLinkRawMovieStreamer.buildEpisodePlayableTrack(loaded, it) }
                            onPlayEpisodeQueue(queue, idx)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = surfaceColor.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 5.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(124.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    AsyncImage(
                                        model = ep.thumbnail,
                                        contentDescription = ep.title,
                                        placeholder = appLogoPainter,
                                        error = appLogoPainter,
                                        fallback = appLogoPainter,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.58f),
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(30.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play Episode",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = Color.Black.copy(alpha = 0.78f),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(5.dp)
                                    ) {
                                        Text(
                                            text = "E${ep.number}",
                                            color = Color.White,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${ep.number}. ${ep.title}",
                                        color = onBgColor,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val epMeta = listOf(ep.runtime, ep.released).filter { it.isNotBlank() }.joinToString(" • ")
                                    if (epMeta.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = epMeta,
                                            color = secondaryText,
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }

                            if (ep.overview.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = ep.overview,
                                    color = secondaryText,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 16.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
