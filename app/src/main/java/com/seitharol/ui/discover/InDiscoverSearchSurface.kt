package com.seitharol.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.player.AudioPlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InDiscoverSearchSurface(
    audioPlayerManager: AudioPlayerManager,
    onSearch: suspend (String) -> List<NormalizedTrack>,
    onOpenDownloadPicker: (NormalizedTrack) -> Unit,
    onOpenFullSong: (NormalizedTrack) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NormalizedTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var focusedPreviewTrack by remember { mutableStateOf<NormalizedTrack?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val activePreviewId by audioPlayerManager.activePreviewTrackId.collectAsState()
    val isPreviewPlaying by audioPlayerManager.isPreviewPlaying.collectAsState()

    Surface(
        color = Color(0xFF121316).copy(alpha = 0.97f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Distinct Header: "Preview & Download Finder" badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Discover Preview Finder",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Audition 30s Spotify previews • Direct MP3/FLAC download",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF1DB954)
                    )
                }
                TextButton(
                    onClick = {
                        audioPlayerManager.pausePreview()
                        onClose()
                    }
                ) {
                    Text("Close ✕", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // In-Discover Search Input
            OutlinedTextField(
                value = query,
                onValueChange = { newText ->
                    query = newText
                    searchJob?.cancel()
                    if (newText.isBlank()) {
                        results = emptyList()
                        isLoading = false
                    } else {
                        searchJob = scope.launch {
                            delay(350L)
                            isLoading = true
                            results = onSearch(newText)
                            isLoading = false
                        }
                    }
                },
                placeholder = { Text("Search tracks to preview or download...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; results = emptyList() }) {
                            Text("✕", color = Color.White)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Focused Preview / Detail View (Docked at top when user clicks a search result;
            // plays 30s preview while user can still scroll results below)
            AnimatedVisibility(visible = focusedPreviewTrack != null) {
                focusedPreviewTrack?.let { focused ->
                    val isThisPlaying = activePreviewId == focused.sourceId && isPreviewPlaying
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2229)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = focused.artUrl,
                                    contentDescription = focused.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        color = Color(0xFF1DB954).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (isThisPlaying) "▶ Playing 30s Preview" else "⏸ 30s Preview Paused",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF1DB954),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = focused.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = focused.artists.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isThisPlaying) audioPlayerManager.pausePreview()
                                        else audioPlayerManager.playPreview(focused)
                                    }
                                ) {
                                    Text(if (isThisPlaying) "⏸" else "▶", color = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Primary Terminal Action: Download (opens Provider + Format Picker)
                                Button(
                                    onClick = { onOpenDownloadPicker(focused) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("⬇ Download (MP3/FLAC)", fontWeight = FontWeight.Bold)
                                }

                                // Optional Secondary Action: Route through TrackMatcher for full playback
                                OutlinedButton(
                                    onClick = { onOpenFullSong(focused) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Full Song")
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Scrollable Preview Results List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(results, key = { "disc_sr_${it.sourceId}" }) { item ->
                    val isSelected = focusedPreviewTrack?.sourceId == item.sourceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.12f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .clickable {
                                focusManager.clearFocus()
                                focusedPreviewTrack = item
                                audioPlayerManager.playPreview(item)
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = item.artUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.artists.joinToString(", ")} • 30s Preview",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { onOpenDownloadPicker(item) }
                        ) {
                            Text("⬇")
                        }
                    }
                }
            }
        }
    }
}
