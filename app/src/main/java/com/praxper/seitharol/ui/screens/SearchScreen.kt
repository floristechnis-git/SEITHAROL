package com.praxper.seitharol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.praxper.seitharol.data.SongItem

@Composable
fun SearchScreen(
    onlineSearchResults: List<SongItem>,
    downloadedSongs: List<SongItem>,
    onPerformOnlineSearch: (String) -> Unit,
    onSongClick: (SongItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Songs") } // "Songs" | "Downloaded"

    val displayedSongs = remember(query, selectedTab, onlineSearchResults, downloadedSongs) {
        if (selectedTab == "Downloaded") {
            if (query.isBlank()) {
                downloadedSongs
            } else {
                downloadedSongs.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true)
                }
            }
        } else {
            onlineSearchResults
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (selectedTab != "Downloaded" && it.length >= 2) {
                    onPerformOnlineSearch(it)
                }
            },
            placeholder = { Text("Search songs, artists, or downloaded tracks...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Songs", "Downloaded").forEach { tab ->
                val isSelected = selectedTab == tab
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedTab = tab },
                    label = { Text(tab, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF1B1C1C),
                        labelColor = Color.White
                    ),
                    border = null
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (displayedSongs.isEmpty()) {
            Text(
                text = if (selectedTab == "Downloaded") {
                    "No downloaded songs match your search."
                } else {
                    "Search for a song above."
                },
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyColumn {
                items(displayedSongs.distinctBy { it.id }, key = { "${selectedTab}_${it.id}" }) { song ->
                    SongRowWithDot(song = song, onClick = { onSongClick(song) })
                }
            }
        }
    }
}
