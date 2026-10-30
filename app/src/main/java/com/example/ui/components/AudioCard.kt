package com.example.ui.components

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Universal Swipe-Down Pull-to-Refresh container for Home, Search, and Library pages.
 * Uses standard Compose NestedScrollConnection for 100% compatibility across all Compose versions.
 */
@Composable
fun SeitharolPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val triggerThresholdPx = with(density) { 76.dp.toPx() }
    val maxPullPx = with(density) { 128.dp.toPx() }
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var localRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && localRefreshing) {
            delay(350L)
            localRefreshing = false
            pullDistancePx = 0f
        }
    }

    val effectiveRefreshing = isRefreshing || localRefreshing

    val nestedScrollConnection = remember(effectiveRefreshing, triggerThresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y < 0f && pullDistancePx > 0f) {
                    val consumedY = available.y.coerceAtLeast(-pullDistancePx)
                    pullDistancePx = (pullDistancePx + consumedY).coerceAtLeast(0f)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!effectiveRefreshing && source == NestedScrollSource.Drag && available.y > 0f && consumed.y == 0f) {
                    pullDistancePx = (pullDistancePx + available.y * 0.52f).coerceIn(0f, maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!effectiveRefreshing && pullDistancePx >= triggerThresholdPx) {
                    localRefreshing = true
                    pullDistancePx = triggerThresholdPx * 0.75f
                    onRefresh()
                    scope.launch {
                        delay(1200L)
                        if (!isRefreshing) {
                            localRefreshing = false
                            pullDistancePx = 0f
                        }
                    }
                } else if (!effectiveRefreshing) {
                    pullDistancePx = 0f
                }
                return Velocity.Zero
            }
        }
    }

    val animatedOffsetPx by animateFloatAsState(
        targetValue = if (effectiveRefreshing) triggerThresholdPx * 0.65f else pullDistancePx,
        animationSpec = spring(),
        label = "pull_refresh_offset"
    )

    Box(
        modifier = modifier.nestedScroll(nestedScrollConnection)
    ) {
        content()

        if (animatedOffsetPx > 6f || effectiveRefreshing) {
            val progressFraction = (animatedOffsetPx / triggerThresholdPx).coerceIn(0f, 1f)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .graphicsLayer {
                        translationY = (animatedOffsetPx - 24f).coerceAtLeast(0f)
                        alpha = if (effectiveRefreshing) 1f else progressFraction
                        scaleX = if (effectiveRefreshing) 1f else (0.7f + 0.3f * progressFraction)
                        scaleY = if (effectiveRefreshing) 1f else (0.7f + 0.3f * progressFraction)
                    }
                    .size(40.dp)
                    .zIndex(10f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (effectiveRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Pull to refresh",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = progressFraction * 220f }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standard Track Row from SEITHAROL design:
 * - Full song/video title visibility (2-line support + full untruncated header in 3-dot menu).
 * - Horizontal auto-scroll is strictly reserved for MiniPlayer, not individual cards.
 * - Supports slightly different card color (`isHighlightedOfficial`) for the most-viewed official song in search results.
 */
@Composable
fun TrackCard(
    track: Track,
    isPlaying: Boolean,
    onTrackClick: () -> Unit,
    onLikeClick: () -> Unit,
    onDownloadClick: (DownloadTier) -> Unit,
    modifier: Modifier = Modifier,
    rankNumber: Int? = null,
    subtitleText: String? = null,
    isHighlightedOfficial: Boolean = false,
    onOptionsClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onGoToArtistClick: ((String) -> Unit)? = null,
    onStartRadioClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    onRemoveFromHistoryClick: (() -> Unit)? = null,
    onRemoveDownloadClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDownloadFormatSheet by remember { mutableStateOf(false) }
    var hasRequestedDownload by remember(track.id, track.isDownloaded) { mutableStateOf(false) }
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    val context = LocalContext.current

    val cardColor = when {
        isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        isHighlightedOfficial -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f)
        else -> Color.Transparent
    }
    val cardBorder: BorderStroke? = null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTrackClick)
            .testTag("track_card_${track.id}"),
        color = cardColor,
        border = cardBorder,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rankNumber != null) {
                Text(
                    text = rankNumber.toString(),
                    color = SeitharolTextSecondary,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(22.dp)
                )
            }

            val isVideoCard = track.isVideoTrack
            Box(
                modifier = if (isVideoCard) {
                    Modifier
                        .width(126.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
                contentAlignment = Alignment.Center
            ) {
                val upgradedArt = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                if (upgradedArt.isBlank()) {
                    Image(
                        painter = appLogoPainter,
                        contentDescription = "Cover for ${track.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = upgradedArt,
                        contentDescription = "Cover for ${track.title}",
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Now playing",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (isVideoCard) {
                    // Rounded-square play badge + bottom-right duration pill on big video thumbnail
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (track.durationMs > 0L) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Color.Black.copy(alpha = 0.80f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = track.durationFormatted,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    color = SeitharolTextPrimary,
                    fontSize = 14.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = if (isPlaying || isHighlightedOfficial) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = Color(0xFF00C853),
                            modifier = Modifier
                                .size(13.dp)
                                .padding(end = 3.dp)
                        )
                    }
                    Text(
                        text = subtitleText ?: "${track.artist} • ${track.durationFormatted}",
                        color = SeitharolTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Connected Google Pixel Split-Action Pill (Like + 3-Dot Options with 0.dp center border-radius)
            Surface(
                onClick = onLikeClick,
                shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(34.dp)
                    .testTag("track_like_btn_${track.id}")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (track.isLiked) "Unlike" else "Like",
                        tint = if (track.isLiked) SeitharolRedHeart else SeitharolTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )

            // Complete 3-Dot Options Menu
            Box {
                Surface(
                    onClick = {
                        if (onOptionsClick != null) onOptionsClick() else showMenu = true
                    },
                    shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 10.dp, bottomEnd = 10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More actions",
                            tint = SeitharolTextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .widthIn(min = 235.dp, max = 290.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // Full untruncated Song / Video Title + Artist header inside 3-dot popup
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = track.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${track.artist} • ${track.durationFormatted}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = OutfitFontFamily,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    DropdownMenuItem(
                        text = { Text("Play Now", color = MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            showMenu = false
                            onTrackClick()
                        }
                    )

                    if (onPlayNextClick != null) {
                        DropdownMenuItem(
                            text = { Text("Play Next", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = {
                                showMenu = false
                                onPlayNextClick()
                            }
                        )
                    }

                    if (onAddToQueueClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Queue", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = {
                                showMenu = false
                                onAddToQueueClick()
                            }
                        )
                    }

                    if (!track.isLocalOnlyTrack) {
                        DropdownMenuItem(
                            text = { Text("Start Radio Mix", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = {
                                showMenu = false
                                if (onStartRadioClick != null) {
                                    onStartRadioClick()
                                } else {
                                    onTrackClick()
                                }
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (track.isLiked) "Remove from Liked Songs" else "Save to Liked Songs",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = if (track.isLiked) SeitharolRedHeart else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            showMenu = false
                            onLikeClick()
                        }
                    )

                    if (onAddToPlaylistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylistClick()
                            }
                        )
                    }

                    if (onGoToArtistClick != null && track.isNavigableArtist) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (track.isVideoTrack) "Visit Channel" else "Go to Artist",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (track.isVideoTrack) Icons.Default.Tv else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                showMenu = false
                                onGoToArtistClick(track.artist)
                            }
                        )
                    }

                    if (!track.isLocalOnlyTrack) {
                        DropdownMenuItem(
                            text = { Text("Share", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = {
                                showMenu = false
                                if (onShareClick != null) {
                                    onShareClick()
                                } else {
                                    runCatching {
                                        val shareMsg = SeitharolDeepLinkHelper.buildTrackShareMessage(track)
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareMsg)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Track"))
                                    }
                                }
                            }
                        )
                    }

                    if (onRemoveFromPlaylistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from Playlist", color = SeitharolRedHeart) },
                            leadingIcon = {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = SeitharolRedHeart)
                            },
                            onClick = {
                                showMenu = false
                                onRemoveFromPlaylistClick()
                            }
                        )
                    }

                    if (onRemoveFromHistoryClick != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from History", color = SeitharolRedHeart) },
                            leadingIcon = {
                                Icon(Icons.Default.HistoryToggleOff, contentDescription = null, tint = SeitharolRedHeart)
                            },
                            onClick = {
                                showMenu = false
                                onRemoveFromHistoryClick()
                            }
                        )
                    }

                    if (track.isDownloaded) {
                        DropdownMenuItem(
                            text = { Text("Remove Offline Copy", color = SeitharolRedHeart) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SeitharolRedHeart)
                            },
                            onClick = {
                                showMenu = false
                                onRemoveDownloadClick?.invoke()
                            }
                        )
                    } else if (!track.isLocalOnlyTrack) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Download (Format & Quality)",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = "Download Options",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                showDownloadFormatSheet = true
                            }
                        )
                        if (track.isVideoTrack) {
                            DropdownMenuItem(
                                text = { Text("Quick Download Video (MP4)", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = "Download Video (MP4)",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    hasRequestedDownload = true
                                    onDownloadClick(DownloadTier.VIDEO_MP4)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDownloadFormatSheet) {
        TrackDownloadFormatQualitySheet(
            track = track,
            onDismiss = { showDownloadFormatSheet = false },
            onConfirmDownload = { chosenTier ->
                hasRequestedDownload = true
                onDownloadClick(chosenTier)
            }
        )
    }
}

@Composable
fun HitCard(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    Surface(
        modifier = modifier
            .width(136.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("hit_card_${track.id}"),
        color = SeitharolWhite,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val upgradedArt = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
                if (upgradedArt.isBlank()) {
                    Image(
                        painter = appLogoPainter,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = upgradedArt,
                        contentDescription = track.title,
                        placeholder = appLogoPainter,
                        error = appLogoPainter,
                        fallback = appLogoPainter,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = track.title,
                color = SeitharolTextPrimary,
                fontSize = 14.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Text(
                text = track.artist,
                color = SeitharolTextSecondary,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun FreshNewMusicCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    Column(
        modifier = modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        val upgradedArt = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl)
        if (upgradedArt.isBlank()) {
            Image(
                painter = appLogoPainter,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AsyncImage(
                model = upgradedArt,
                contentDescription = track.title,
                placeholder = appLogoPainter,
                error = appLogoPainter,
                fallback = appLogoPainter,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = SyneFontFamily,
            fontWeight = FontWeight.Medium,
            color = SeitharolDark,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = OutfitFontFamily,
            color = SeitharolTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FreshMusicCard(
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    imageUrl: String?,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)
    Box(
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onPlayClick)
            .background(Brush.linearGradient(gradientColors))
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(imageUrl),
                contentDescription = title,
                placeholder = appLogoPainter,
                error = appLogoPainter,
                fallback = appLogoPainter,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC0D0D0D))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = SeitharolTextLight,
                fontSize = 18.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = SeitharolTextLightMuted,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xEEFFFFFF),
            onClick = onPlayClick
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play $title",
                    tint = Color(0xFF121212),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

object ArtistAvatarCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun get(name: String): String? = cache[name.lowercase().trim()]
    fun put(name: String, url: String) {
        if (url.isNotBlank()) cache[name.lowercase().trim()] = url
    }
}

@Composable
fun SuggestedArtistItem(
    name: String,
    imageUrl: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var resolvedAvatar by remember(name, imageUrl) {
        mutableStateOf(imageUrl.ifBlank { ArtistAvatarCache.get(name).orEmpty() })
    }

    LaunchedEffect(name) {
        if (resolvedAvatar.isBlank() && name.isNotBlank()) {
            val cached = ArtistAvatarCache.get(name)
            if (!cached.isNullOrBlank()) {
                resolvedAvatar = cached
            } else {
                runCatching {
                    val (_, avatar) = com.seitharol.data.resolver.InnerTubeStreamExtractor.resolveOfficialArtistBrowseId(name)
                    if (avatar.isNotBlank()) {
                        ArtistAvatarCache.put(name, avatar)
                        resolvedAvatar = avatar
                    }
                }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(104.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedAvatar.isNotBlank()) {
                AsyncImage(
                    model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(resolvedAvatar),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"
                Text(
                    text = initial,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SyneFontFamily
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontFamily = OutfitFontFamily,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
