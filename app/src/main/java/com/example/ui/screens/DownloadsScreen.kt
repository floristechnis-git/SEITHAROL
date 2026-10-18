package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*

@Composable
fun DownloadsScreen(
    viewModel: CascadeViewModel,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.activeDownloads.collectAsState()
    val totalBytes by viewModel.totalDownloadedBytes.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()

    val totalMb = totalBytes / (1024 * 1024)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 140.dp)
    ) {
        item {
            Text(
                text = "Downloads & Offline",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                color = KangleiDark
            )
            Text(
                text = "Persistent on-device Opus audio storage",
                fontFamily = OutfitFontFamily,
                color = KangleiTextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Offline Simulator & Info Card
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                color = KangleiWhite
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Airplane Mode Offline",
                                color = KangleiDark,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isOfflineMode)
                                    "Playing from local storage only."
                                else
                                    "Connected. Streaming and downloading enabled.",
                                color = KangleiTextSecondary,
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily
                            )
                        }

                        Switch(
                            checked = isOfflineMode,
                            onCheckedChange = { viewModel.setOfflineMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = KangleiDark
                            ),
                            modifier = Modifier.testTag("downloads_offline_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = KangleiDivider)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Total Stored", color = KangleiTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                            Text("$totalMb MB", color = KangleiDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                        }
                        Column {
                            Text("Downloaded Tracks", color = KangleiTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                            Text("${downloads.count { it.state == DownloadState.COMPLETED }}", color = KangleiDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                        }
                        Column {
                            Text("Format", color = KangleiTextSecondary, fontSize = 11.sp, fontFamily = OutfitFontFamily)
                            Text("Opus 160k", color = KangleiDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Track Download Queue (${downloads.size})",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = KangleiDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (downloads.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Color(0xFFAAAAAA),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No downloaded tracks yet",
                            color = KangleiTextSecondary,
                            fontFamily = OutfitFontFamily,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            items(downloads) { item ->
                DownloadItemRow(
                    item = item,
                    onClick = {
                        if (item.state == DownloadState.COMPLETED) {
                            viewModel.playDownloadedTrack(item.trackId)
                        }
                    },
                    onDelete = { viewModel.deleteDownload(item.trackId) },
                    onRetry = { viewModel.retryDownload(item.trackId) }
                )
            }
        }
    }
}

@Composable
fun DownloadItemRow(
    item: DownloadItem,
    onClick: () -> Unit = {},
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = item.state == DownloadState.COMPLETED, onClick = onClick),
        color = KangleiWhite
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = KangleiDark,
                        fontSize = 14.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${item.artist} • ${item.tier.label}",
                        color = KangleiTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily
                    )
                }

                when (item.state) {
                    DownloadState.COMPLETED -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x2000E676),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Completed",
                                    tint = Color(0xFF00C853),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Saved", color = Color(0xFF00C853), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = OutfitFontFamily)
                            }
                        }
                    }
                    DownloadState.ERROR -> {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry download",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DownloadState.DOWNLOADING, DownloadState.FINALIZING -> {
                        Text(
                            text = "${(item.progress * 100).toInt()}%",
                            color = KangleiDark,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OutfitFontFamily,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    DownloadState.QUEUED -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x20FFA000),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                "Queued",
                                color = Color(0xFFFFA000),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = OutfitFontFamily,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    DownloadState.IDLE -> {}
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete download",
                        tint = KangleiTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.FINALIZING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = KangleiDark,
                    trackColor = Color(0xFFE0E0E0)
                )
            } else if (item.state == DownloadState.ERROR) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Download error • Tap retry or remove",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily
                )
            }
        }
    }
}
