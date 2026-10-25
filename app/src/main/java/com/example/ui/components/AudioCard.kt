package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
 * - Removes the direct download SVG from the row so song and video titles have full horizontal room.
 * - Uses the app logo (`R.drawable.ic_splash_logo`) as placeholder/error/fallback for local audio and low-internet loads.
 * - Suppresses artist/channel navigation when playing local tracks or "Unknown Artist".
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
    onOptionsClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onGoToArtistClick: ((String) -> Unit)? = null,
    onRemoveDownloadClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDownloadFormatSheet by remember { mutableStateOf(false) }
    var hasRequestedDownload by remember(track.id, track.isDownloaded) { mutableStateOf(false) }
    val appLogoPainter = painterResource(id = R.drawable.ic_splash_logo)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTrackClick)
            .testTag("track_card_${track.id}"),
        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                    modifier = Modifier.width(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
                            modifier = Modifier.size(20.dp)
                        )
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
                    fontSize = 15.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

            // Like action
            IconButton(
                onClick = onLikeClick,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("track_like_btn_${track.id}")
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (track.isLiked) "Unlike" else "Like",
                    tint = if (track.isLiked) SeitharolRedHeart else SeitharolTextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Options menu (Download remains available here while keeping the card title wide & uncluttered)
            Box {
                IconButton(
                    onClick = {
                        if (onOptionsClick != null) onOptionsClick() else showMenu = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "More actions",
                        tint = SeitharolTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(SeitharolCardElevated)
                ) {
                    if (onPlayNextClick != null) {
                        DropdownMenuItem(
                            text = { Text("Play Next", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = SeitharolDark)
                            },
                            onClick = {
                                showMenu = false
                                onPlayNextClick()
                            }
                        )
                    }
                    if (onAddToQueueClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Queue", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = SeitharolDark)
                            },
                            onClick = {
                                showMenu = false
                                onAddToQueueClick()
                            }
                        )
                    }
                    if (onAddToPlaylistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = SeitharolDark)
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
                                    color = SeitharolTextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (track.isVideoTrack) Icons.Default.Tv else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = SeitharolDark
                                )
                            },
                            onClick = {
                                showMenu = false
                                onGoToArtistClick(track.artist)
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
                                    text = if (track.isVideoTrack) "Download (Choose Format & Quality)" else "Download Audio",
                                    color = SeitharolTextPrimary
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
                                text = { Text("Download Video (MP4)", color = SeitharolTextPrimary) },
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
                        DropdownMenuItem(
                            text = { Text("Download Audio Only • High (320k)", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_mp3),
                                    contentDescription = "Download",
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                hasRequestedDownload = true
                                onDownloadClick(DownloadTier.HIGH)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Download Audio Only • Normal (160k)", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_mp3),
                                    contentDescription = "Download",
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                hasRequestedDownload = true
                                onDownloadClick(DownloadTier.STANDARD)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Download Audio Only • Low (96k)", color = SeitharolTextPrimary) },
                            leadingIcon = {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_mp3),
                                    contentDescription = "Download",
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                hasRequestedDownload = true
                                onDownloadClick(DownloadTier.DATA_SAVER)
                            }
                        )
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
                maxLines = 1,
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
            maxLines = 1,
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
