package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Playlist
import com.example.data.model.Track
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistModal(
    track: Track,
    playlists: List<Playlist>,
    onSelectPlaylist: (String) -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    val modalSheetState = rememberModalBottomSheetState()
    var addedToId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalSheetState,
        containerColor = KangleiBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(KangleiHandleBar)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("add_to_playlist_modal")
        ) {
            Text(
                text = "Add to playlist",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = KangleiDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = track.title,
                fontFamily = OutfitFontFamily,
                fontSize = 13.sp,
                color = KangleiTextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Create new playlist button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        onDismiss()
                        onCreateNewPlaylist()
                    },
                shape = RoundedCornerShape(24.dp),
                color = KangleiPillBg
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = KangleiPillText)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create new playlist",
                        color = KangleiPillText,
                        fontSize = 14.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlists) { pl ->
                    val isAdded = addedToId == pl.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                addedToId = pl.id
                                onSelectPlaylist(pl.id)
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = KangleiWhite
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFE0E0E0)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pl.coverUrl != null) {
                                    AsyncImage(
                                        model = pl.coverUrl,
                                        contentDescription = pl.title,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = KangleiDark)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.title,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = KangleiDark
                                )
                                Text(
                                    text = "${pl.trackCount} songs",
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = KangleiTextSecondary
                                )
                            }

                            if (isAdded) {
                                Surface(
                                    modifier = Modifier.size(28.dp),
                                    shape = CircleShape,
                                    color = KangleiDark
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Added",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun CreatePlaylistModal(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    CreatePlaylistDialog(onConfirm = onConfirm, onDismiss = onDismiss)
}

@Composable
fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("My playlist #1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = KangleiWhite,
        title = {
            Text(
                text = "Create new playlist",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = KangleiDark
            )
        },
        text = {
            Column {
                Text(
                    text = "Give your playlist a name",
                    fontFamily = OutfitFontFamily,
                    fontSize = 13.sp,
                    color = KangleiTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KangleiDark,
                        unfocusedBorderColor = Color(0xFFD0D0D0),
                        focusedTextColor = KangleiDark,
                        unfocusedTextColor = KangleiDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_playlist_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangleiPillBg,
                    contentColor = KangleiPillText
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("create_playlist_confirm_btn")
            ) {
                Text("Create", color = KangleiPillText, fontFamily = OutfitFontFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KangleiTextSecondary, fontFamily = OutfitFontFamily)
            }
        }
    )
}
