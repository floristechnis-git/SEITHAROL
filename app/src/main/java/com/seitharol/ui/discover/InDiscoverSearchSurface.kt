package com.seitharol.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import com.seitharol.ui.home.SearchMagnifierSvgIcon
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.ui.search.CloseCrossSvgIcon
import com.seitharol.ui.search.DownloadSvgIcon
import com.seitharol.util.ThumbnailTargetSize
import com.seitharol.util.ThumbnailUrlOptimizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InDiscoverSearchSurface(
    audioPlayerManager: AudioPlayerManager,
    onSearch: suspend (String) -> List<NormalizedTrack>,
    onOpenDownloadPicker: (NormalizedTrack) -> Unit,
    onOpenFullSong: (NormalizedTrack) -> Unit,
    onVisitArtist: (String, String?) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val settings by remember { AppSettingsManager.getInstance(context) }.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NormalizedTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasCompletedSearchForQuery by remember { mutableStateOf(false) }
    var latestSearchToken by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val activePreviewId by audioPlayerManager.activePreviewTrackId.collectAsState()
    val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()

    BackHandler {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        audioPlayerManager.pausePreview()
        onClose()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Single Unified Search Pill Bar (Back button + Input + Clear)
            TextField(
                value = query,
                onValueChange = { newText ->
                    query = newText
                    searchJob?.cancel()
                    val clean = newText.trim()
                    if (clean.length < 2) {
                        results = emptyList()
                        isLoading = false
                        hasCompletedSearchForQuery = false
                    } else {
                        // Immediately enter loading state BEFORE debounce so "No songs found" NEVER flashes while typing!
                        val token = ++latestSearchToken
                        isLoading = true
                        hasCompletedSearchForQuery = false
                        searchJob = scope.launch {
                            try {
                                delay(350L)
                                val fetched = onSearch(clean)
                                if (token == latestSearchToken) {
                                    results = fetched
                                    isLoading = false
                                    hasCompletedSearchForQuery = true
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                if (token == latestSearchToken) {
                                    isLoading = false
                                    hasCompletedSearchForQuery = true
                                }
                            }
                        }
                    }
                },
                placeholder = {
                    Text(
                        text = "Search song, artist, or paste Spotify link...",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                singleLine = true,
                maxLines = 1,
                shape = RoundedCornerShape(50),
                leadingIcon = {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            audioPlayerManager.pausePreview()
                            onClose()
                        }
                    ) {
                        BackArrowSvgIcon(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchJob?.cancel()
                                latestSearchToken++
                                query = ""
                                results = emptyList()
                                isLoading = false
                                hasCompletedSearchForQuery = false
                            }
                        ) {
                            CloseCrossSvgIcon(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    val clean = query.trim()
                    if (clean.length >= 2) {
                        searchJob?.cancel()
                        val token = ++latestSearchToken
                        isLoading = true
                        hasCompletedSearchForQuery = false
                        searchJob = scope.launch {
                            val fetched = runCatching { onSearch(clean) }.getOrDefault(emptyList())
                            if (token == latestSearchToken) {
                                results = fetched
                                isLoading = false
                                hasCompletedSearchForQuery = true
                            }
                        }
                    }
                }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!isLoading && hasCompletedSearchForQuery && query.trim().length >= 2 && results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SearchMagnifierSvgIcon(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No songs found for \"${query.trim()}\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(results.distinctBy { it.sourceId }, key = { "disc_sr_${it.sourceId}" }) { item ->
                    val isThisPlaying = activePreviewId == item.sourceId && isPreviewPlaying
                    val primaryArtist = item.artists.firstOrNull().orEmpty().ifBlank { "Unknown Artist" }
                    val itemThumb = remember(item.artUrl, settings.thumbnailQuality) {
                        ThumbnailUrlOptimizer.optimize(
                            item.artUrl,
                            settings.thumbnailQuality,
                            ThumbnailTargetSize.CARD_MEDIUM,
                            context
                        )
                    }
                    val avatarThumb = remember(item.artistAvatarUrl, item.artUrl, settings.thumbnailQuality) {
                        ThumbnailUrlOptimizer.optimize(
                            item.artistAvatarUrl.ifBlank { item.artUrl },
                            settings.thumbnailQuality,
                            ThumbnailTargetSize.LIST_THUMB,
                            context
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isThisPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isThisPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                onOpenFullSong(item)
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Artwork with Instant 30s Play/Pause Overlay
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            if (isThisPlaying) audioPlayerManager.pausePreview()
                                            else audioPlayerManager.playPreview(item)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = itemThumb,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = if (isThisPlaying) 0.45f else 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                id = if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play
                                            ),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Song Title + Bottom-Left Artist Profile Avatar & Name
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = if (isThisPlaying) Color(0xFF1DB954).copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = if (isThisPlaying) "PLAYING" else "TRACK",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isThisPlaying) Color(0xFF1DB954) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            onVisitArtist(primaryArtist, item.primaryArtistBrowseId)
                                        }
                                    ) {
                                        AsyncImage(
                                            model = avatarThumb,
                                            contentDescription = primaryArtist,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.artists.joinToString(", "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action Row: Download + Play
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        onOpenDownloadPicker(item)
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    DownloadSvgIcon(tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        onOpenFullSong(item)
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_play),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Play",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
