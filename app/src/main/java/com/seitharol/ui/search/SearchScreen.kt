package com.seitharol.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.CanonicalAudioResolver
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer

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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    val verifiedSuggestedArtists = remember(historyTracks) {
        CanonicalAudioResolver.filterVerifiedArtistsOnly(historyTracks)
    }

    // CRITICAL FIX: Rank by query relevance (exact match, prefix, artist, official ATV) instead of raw viewCount
    val rankedResults = remember(query, searchResults) {
        CanonicalAudioResolver.rankSearchResultsByRelevance(query, searchResults)
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
                            focusManager.clearFocus()
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
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        if (onOpenDiscover != null && query.isBlank()) {
            DiscoverEntryCard(onOpenDiscover = onOpenDiscover)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (query.isBlank() && verifiedSuggestedArtists.isNotEmpty()) {
                item {
                    Text(
                        text = "Verified Artists You Listen To",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(verifiedSuggestedArtists, key = { "art_${it.first}" }) { (artistName, browseId) ->
                    ListItem(
                        headlineContent = { Text(artistName) },
                        supportingContent = { Text("Official Artist") },
                        modifier = Modifier.clickable {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onOpenArtistProfile(artistName, browseId)
                        }
                    )
                }
            }

            items(rankedResults, key = { "sr_${it.videoId}" }) { track ->
                val optimizedThumb = remember(track.thumbnailUrl, settings.thumbnailQuality) {
                    ThumbnailUrlOptimizer.optimize(
                        track.thumbnailUrl,
                        settings.thumbnailQuality,
                        ThumbnailTargetSize.LIST_THUMB,
                        context
                    )
                }
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
                    modifier = Modifier.clickable {
                        focusManager.clearFocus()
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
