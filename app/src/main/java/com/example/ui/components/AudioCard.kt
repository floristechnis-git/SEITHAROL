package com.example.ui.components

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadTier
import com.example.data.model.Track
import com.example.ui.theme.*

/**
 * Standard Track Row from Kanglei Tune design:
 * Clean 56dp height row with rank/thumbnail, track title, artist / play count, and options.
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTrackClick)
            .testTag("track_card_${track.id}"),
        color = if (isPlaying) KangleiWhite else Color.Transparent,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Optional Rank Number
            if (rankNumber != null) {
                Text(
                    text = rankNumber.toString(),
                    color = KangleiTextSecondary,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(18.dp)
                )
            }

            // Real Thumbnail with Coil
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                    contentDescription = "Cover for ${track.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
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

            // Metadata
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    color = KangleiTextPrimary,
                    fontSize = 15.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitleText ?: "${track.artist} • ${track.durationFormatted}",
                    color = KangleiTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Like action
            IconButton(
                onClick = onLikeClick,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("track_like_btn_${track.id}")
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (track.isLiked) "Unlike" else "Like",
                    tint = if (track.isLiked) KangleiRedHeart else KangleiTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Options menu
            Box {
                IconButton(
                    onClick = {
                        if (onOptionsClick != null) onOptionsClick() else showMenu = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "More actions",
                        tint = KangleiTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(KangleiCardElevated)
                ) {
                    if (onPlayNextClick != null) {
                        DropdownMenuItem(
                            text = { Text("Play Next", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = KangleiDark)
                            },
                            onClick = {
                                showMenu = false
                                onPlayNextClick()
                            }
                        )
                    }
                    if (onAddToQueueClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Queue", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = KangleiDark)
                            },
                            onClick = {
                                showMenu = false
                                onAddToQueueClick()
                            }
                        )
                    }
                    if (onAddToPlaylistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = KangleiDark)
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylistClick()
                            }
                        )
                    }
                    if (onGoToArtistClick != null && track.artist.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Go to Artist", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, tint = KangleiDark)
                            },
                            onClick = {
                                showMenu = false
                                onGoToArtistClick(track.artist)
                            }
                        )
                    }
                    if (track.isDownloaded) {
                        DropdownMenuItem(
                            text = { Text("Remove Offline Copy", color = KangleiRedHeart) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = KangleiRedHeart)
                            },
                            onClick = {
                                showMenu = false
                                onRemoveDownloadClick?.invoke()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Download High (Opus 160k)", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null, tint = KangleiDark)
                            },
                            onClick = {
                                showMenu = false
                                onDownloadClick(DownloadTier.HIGH)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Download Data Saver (64k)", color = KangleiTextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = KangleiTextSecondary)
                            },
                            onClick = {
                                showMenu = false
                                onDownloadClick(DownloadTier.DATA_SAVER)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Today's Biggest Hits Card (from Home board):
 * White background, radius 20dp, padding 8dp, compact vertical portrait card (136dp width with 120dp square art).
 */
@Composable
fun HitCard(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(136.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("hit_card_${track.id}"),
        color = KangleiWhite,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFE8E8E8)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

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
                color = KangleiTextPrimary,
                fontSize = 14.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Text(
                text = track.artist,
                color = KangleiTextSecondary,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Fresh New Music Banner Card (from Home board):
 * Width 280dp, height 160dp, border-radius 22dp, gradient background or image,
 * bottom-right circular play button (44x44, white with black icon).
 */
@Composable
fun FreshNewMusicCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        // High-res rectangular/rounded artwork — NO circular div/clip inside
        AsyncImage(
            model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(track.artUrl),
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = SyneFontFamily,
            fontWeight = FontWeight.Medium,
            color = KangleiDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = OutfitFontFamily,
            color = KangleiTextSecondary,
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
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient Overlay
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

        // Card Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = title,
                color = KangleiTextLight,
                fontSize = 18.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = KangleiTextLightMuted,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily
            )
        }

        // Subtle play indicator on bottom-right (Rounded shape, not obstructing artwork)
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
                    tint = KangleiDark,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Suggested Artist Circular Avatar (from Home board):
 * 104dp width, 104dp circular photo avatar, artist name centered below in 12sp.
 */
@Composable
fun SuggestedArtistItem(
    name: String,
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                .background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = com.example.data.remote.InnerTubeResolver.upgradeThumbnailUrl(imageUrl),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = name,
            color = KangleiTextSecondary,
            fontSize = 12.sp,
            fontFamily = OutfitFontFamily,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
