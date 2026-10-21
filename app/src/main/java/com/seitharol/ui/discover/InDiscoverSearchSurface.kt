package com.seitharol.ui.discover

import android.content.Intent
import android.net.Uri
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

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NormalizedTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val activePreviewId by audioPlayerManager.activePreviewTrackId.collectAsState()
    val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()

    BackHandler {
        audioPlayerManager.pausePreview()
        onClose()
    }

    Surface(
        color = Color(0xFF0B0C0E).copy(alpha = 0.97f),
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
                    if (newText.isBlank()) {
                        results = emptyList()
                        isLoading = false
                    } else {
                        searchJob = scope.launch {
                            delay(280L)
                            isLoading = true
                            try {
                                val fetched = onSearch(newText.trim())
                                results = fetched
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep previous results or empty if none
                            } finally {
                                isLoading = false
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
                        color = Color.White.copy(alpha = 0.5f)
                    )
                },
                singleLine = true,
                maxLines = 1,
                shape = RoundedCornerShape(50),
                leadingIcon = {
                    IconButton(
                        onClick = {
                            audioPlayerManager.pausePreview()
                            onClose()
                        }
                    ) {
                        BackArrowSvgIcon(tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; results = emptyList() }) {
                            CloseCrossSvgIcon(tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = Color(0xFF1A1C22),
                    unfocusedContainerColor = Color(0xFF1A1C22),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    val clean = query.trim()
                    if (clean.isNotBlank()) {
                        searchJob?.cancel()
                        searchJob = scope.launch {
                            isLoading = true
                            results = runCatching { onSearch(clean) }.getOrDefault(emptyList())
                            isLoading = false
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

            if (!isLoading && query.isNotBlank() && results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SearchMagnifierSvgIcon(
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No songs found for \"$query\"",
                            color = Color.White.copy(alpha = 0.6f),
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
                        color = if (isThisPlaying) Color(0xFF1E222B) else Color(0xFF15171E),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isThisPlaying) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.07f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                focusManager.clearFocus()
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
                                            focusManager.clearFocus()
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
                                            else Color.White.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = if (isThisPlaying) "PLAYING" else "TRACK",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isThisPlaying) Color(0xFF1DB954) else Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
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
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action Row: Download + Play Full
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onOpenDownloadPicker(item) },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.White
                                    )
                                ) {
                                    DownloadSvgIcon(tint = Color.White, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onOpenFullSong(item)
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_play),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Play", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
