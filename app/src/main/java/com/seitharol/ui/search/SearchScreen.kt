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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.data.resolver.CandidateStream
import com.seitharol.data.resolver.CanonicalAudioResolver

@Composable
fun SearchScreen(
    searchResults: List<CandidateStream>,
    historyTracks: List<CandidateStream>,
    onQueryChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onPlaySongAndMaximizePlayer: (CandidateStream) -> Unit,
    onOpenArtistProfile: (String, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Auto-dismiss keyboard & unfocus search bar when user scrolls results
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // Verified Artists Only from history (filters out remix/unverified uploaders)
    val verifiedSuggestedArtists = remember(historyTracks) {
        CanonicalAudioResolver.filterVerifiedArtistsOnly(historyTracks)
    }

    val rankedResults = remember(searchResults) {
        CanonicalAudioResolver.sortByPopularity(searchResults)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Full-Width Search Bar (No Cancel button; Back Arrow when empty, Clear when text present)
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange(it)
            },
            placeholder = { Text("Search songs, official artists, albums...") },
            singleLine = true,
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
                ListItem(
                    headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = track.title,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    },
                    modifier = Modifier.clickable {
                        // Unfocus search bar, hide keyboard, and maximize PlayerScreen
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

