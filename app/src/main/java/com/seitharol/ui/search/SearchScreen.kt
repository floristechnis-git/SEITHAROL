package com.seitharol.ui.search

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.data.resolver.InnerTubeStreamExtractor
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer
import org.json.JSONArray

private const val PREF_LAST_FOUR_SEARCHES = "last_four_search_queries"

private fun loadLastFourQueries(context: Context): List<String> {
    val prefs = context.getSharedPreferences("seitharol_search_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString(PREF_LAST_FOUR_SEARCHES, "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until minOf(arr.length(), 4)) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveSearchQueryMaxFour(context: Context, query: String): List<String> {
    val clean = query.trim()
    if (clean.isEmpty()) return loadLastFourQueries(context)
    val current = loadLastFourQueries(context).toMutableList()
    current.removeAll { it.equals(clean, ignoreCase = true) }
    current.add(0, clean)
    val capped = current.take(4)
    val arr = JSONArray().apply { capped.forEach { put(it) } }
    context.getSharedPreferences("seitharol_search_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_LAST_FOUR_SEARCHES, arr.toString())
        .apply()
    return capped
}

@Composable
fun SearchScreen(
    searchResults: List<CandidateStream>,
    historyTracks: List<CandidateStream>,
    onQueryChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDiscover: (() -> Unit)? = null,
    onPlaySongAndMaximizePlayer: (CandidateStream) -> Unit,
    onOpenArtistProfile: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var recentQueries by remember { mutableStateOf(loadLastFourQueries(context)) }
    var ytmTrendingQueries by remember { mutableStateOf<List<String>>(emptyList()) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Fetch live trending queries directly from music.youtube.com
    LaunchedEffect(Unit) {
        val charts = runCatching {
            InnerTubeStreamExtractor.searchOfficialYtmSongs("Trending Music Charts", limit = 10)
                .map { it.title }
                .distinct()
                .take(8)
        }.getOrDefault(emptyList())
        ytmTrendingQueries = charts
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val rankedResults = remember(query, searchResults) {
        CanonicalAudioResolver.rankSearchResultsByRelevance(query, searchResults)
    }

    val mostViewedOfficialId = remember(rankedResults, query) {
        val official = rankedResults.filter { it.isVerifiedArtist || it.videoType == "MUSIC_VIDEO_TYPE_ATV" }
        val withViews = official.filter { it.viewCount > 0L }
        (withViews.maxByOrNull { it.viewCount } ?: official.firstOrNull())?.videoId
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            placeholder = {
                Text(
                    text = "Search songs, official artists, albums...",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = {
                IconButton(
                    onClick = {
                        if (query.isEmpty()) {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onNavigateBack()
                        } else {
                            query = ""
                            onQueryChange("")
                        }
                    }
                ) {
                    if (query.isEmpty()) {
                        BackArrowSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        CloseCrossSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    recentQueries = saveSearchQueryMaxFour(context, query)
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        if (onOpenDiscover != null && query.isBlank()) {
            DiscoverEntryCard(
                onOpenDiscover = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onOpenDiscover()
                }
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 200.dp)
        ) {
            // 1. Last 4 Search Queries
            if (query.isBlank() && recentQueries.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Searches",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(recentQueries, key = { "recent_q_$it" }) { savedQuery ->
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.onSurface,
                            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        headlineContent = { Text(savedQuery, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable {
                            query = savedQuery
                            recentQueries = saveSearchQueryMaxFour(context, savedQuery)
                            onQueryChange(savedQuery)
                        }
                    )
                }
            }

            // 2. Live Trending Search Queries from music.youtube.com
            if (query.isBlank() && ytmTrendingQueries.isNotEmpty()) {
                item {
                    Text(
                        text = "Trending on YouTube Music",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        items(ytmTrendingQueries, key = { "trend_$it" }) { trend ->
                            AssistChip(
                                onClick = {
                                    query = trend
                                    recentQueries = saveSearchQueryMaxFour(context, trend)
                                    onQueryChange(trend)
                                },
                                label = { Text(trend, maxLines = 1) },
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }
                }
            }

            // 3. Search Results (Deduplicated + Indexed Keys + Most Viewed Official Highlight)
            itemsIndexed(rankedResults, key = { idx, item -> "sr_${item.videoId}_$idx" }) { _, track ->
                val isVideoItem = track.videoType == "MUSIC_VIDEO_TYPE_OMV" || track.videoType == "MUSIC_VIDEO_TYPE_UGC"
                val isMostViewedOfficial = query.isNotBlank() && track.videoId == mostViewedOfficialId
                val optimizedThumb = remember(track.thumbnailUrl, settings.thumbnailQuality, isVideoItem) {
                    ThumbnailUrlOptimizer.optimize(
                        track.thumbnailUrl,
                        settings.thumbnailQuality,
                        if (isVideoItem) ThumbnailTargetSize.CARD_MEDIUM else ThumbnailTargetSize.LIST_THUMB,
                        context
                    )
                }
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = if (isMostViewedOfficial) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        } else {
                            Color.Transparent
                        },
                        headlineColor = MaterialTheme.colorScheme.onSurface,
                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    headlineContent = {
                        Text(
                            text = track.title,
                            fontWeight = if (isMostViewedOfficial) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        AsyncImage(
                            model = optimizedThumb,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = if (isVideoItem) {
                                Modifier
                                    .width(124.dp)
                                    .height(70.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            }
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (query.isNotBlank()) {
                                recentQueries = saveSearchQueryMaxFour(context, query)
                            }
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onPlaySongAndMaximizePlayer(track)
                        }
                )
            }
        }
    }
}

@Composable
fun CloseCrossSvgIcon(
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.2f * sx,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(5f * sx, 5f * sy),
            end = androidx.compose.ui.geometry.Offset(19f * sx, 19f * sy),
            strokeWidth = stroke.width,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(19f * sx, 5f * sy),
            end = androidx.compose.ui.geometry.Offset(5f * sx, 19f * sy),
            strokeWidth = stroke.width,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
