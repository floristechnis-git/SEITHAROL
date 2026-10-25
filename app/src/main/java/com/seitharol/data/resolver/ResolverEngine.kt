package com.seitharol.data.resolver

import android.content.Context
import android.net.Uri
import com.seitharol.domain.model.NormalizedTrack
import com.seitharol.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ResolvedPlaybackTarget {
    data class LocalDeviceUri(val uri: Uri, val isPublicMusicFile: Boolean) : ResolvedPlaybackTarget()
    data class RemoteStreamUrl(val url: String, val resolvedTrack: NormalizedTrack) : ResolvedPlaybackTarget()
}

class ResolverEngine(
    private val context: Context,
    private val trackMatcher: TrackMatcher,
    private val innerTubeProvider: InnerTubeProvider,
    private val jioSaavnProvider: JioSaavnProvider
) {
    /**
     * Resolves a NormalizedTrack for playback:
     * 1. Public Device Music (Discover Downloads & Local Storage via content:// or /storage/emulated/0/Music/...)
     * 2. App-Internal Cache (PlayerScreen Downloads in context.filesDir/downloads)
     * 3. Online Resolution (TrackMatcher -> YouTube ATV / JioSaavn, with automatic failover for restricted Art Tracks)
     */
    suspend fun resolveForFullPlayback(
        track: NormalizedTrack,
        offlineModeEnabled: Boolean
    ): Result<ResolvedPlaybackTarget> = withContext(Dispatchers.IO) {
        // 1. Check local path / URI (handles both public MediaStore content:// URIs and internal cache files)
        track.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("content://")) {
                val contentUri = Uri.parse(path)
                if (isContentUriReadable(contentUri)) {
                    return@withContext Result.success(
                        ResolvedPlaybackTarget.LocalDeviceUri(uri = contentUri, isPublicMusicFile = true)
                    )
                }
            } else {
                val file = File(path.removePrefix("file://"))
                if (file.exists() && file.length() > 0L) {
                    val isPublicStorage = !file.absolutePath.startsWith(context.filesDir.absolutePath)
                    return@withContext Result.success(
                        ResolvedPlaybackTarget.LocalDeviceUri(
                            uri = Uri.fromFile(file),
                            isPublicMusicFile = isPublicStorage
                        )
                    )
                }
            }
        }

        if (offlineModeEnabled) {
            return@withContext Result.failure(
                IllegalStateException("Offline mode is active and track is not available on device.")
            )
        }

        // 2. Non-native tracks (e.g. SPOTIFY Discover items) route through TrackMatcher
        val playableTrack = if (!track.isPlayableNatively || track.source == TrackSource.SPOTIFY) {
            trackMatcher.matchToPlayable(track).getOrElse { return@withContext Result.failure(it) }
        } else {
            track
        }

        val primaryArtist = playableTrack.artists.firstOrNull().orEmpty()
        val targetDurationSec = (playableTrack.durationMs / 1000L).toInt()

        // 3. Extract online stream with automatic fallback for restricted YouTube Music "Art Tracks" (Topic songs)
        when (playableTrack.source) {
            TrackSource.YOUTUBE -> {
                val directYt = innerTubeProvider.resolveStreamUrl(playableTrack.sourceId)
                if (directYt.isSuccess) {
                    return@withContext Result.success(
                        ResolvedPlaybackTarget.RemoteStreamUrl(directYt.getOrThrow(), playableTrack)
                    )
                }

                // Fallback A: Try JioSaavn exact match if direct YouTube Art Track is restricted
                if (playableTrack.title.isNotBlank()) {
                    val saavnUrl = runCatching {
                        StreamResolver.resolveExactStreamUrl(
                            TrackMetadata(
                                id = "",
                                title = playableTrack.title,
                                artist = primaryArtist,
                                album = playableTrack.album.orEmpty(),
                                durationSeconds = targetDurationSec,
                                thumbnailUrl = playableTrack.artUrl
                            ),
                            AudioQuality.NORMAL
                        )
                    }.getOrNull()
                    if (!saavnUrl.isNullOrBlank()) {
                        return@withContext Result.success(
                            ResolvedPlaybackTarget.RemoteStreamUrl(
                                url = saavnUrl,
                                resolvedTrack = playableTrack.copy(
                                    source = TrackSource.JIOSAAVN,
                                    isPlayableNatively = true
                                )
                            )
                        )
                    }
                }

                // Fallback B: Search alternative playable YouTube Music streams by title + artist
                if (playableTrack.title.isNotBlank()) {
                    val query = "${playableTrack.title} $primaryArtist".trim()
                    val altCandidates = runCatching {
                        InnerTubeStreamExtractor.searchOfficialYtmSongs(query, limit = 4)
                    }.getOrDefault(emptyList())
                    for (cand in altCandidates) {
                        if (cand.videoId.isBlank() || cand.videoId == playableTrack.sourceId) continue
                        val altUrl = innerTubeProvider.resolveStreamUrl(cand.videoId).getOrNull()
                        if (!altUrl.isNullOrBlank()) {
                            return@withContext Result.success(
                                ResolvedPlaybackTarget.RemoteStreamUrl(
                                    url = altUrl,
                                    resolvedTrack = playableTrack.copy(
                                        source = TrackSource.YOUTUBE,
                                        sourceId = cand.videoId,
                                        isPlayableNatively = true
                                    )
                                )
                            )
                        }
                    }
                }

                directYt.map { url ->
                    ResolvedPlaybackTarget.RemoteStreamUrl(url, playableTrack)
                }
            }
            TrackSource.JIOSAAVN -> {
                val directSaavn = jioSaavnProvider.resolveStreamUrl(playableTrack.sourceId)
                if (directSaavn.isSuccess) {
                    return@withContext Result.success(
                        ResolvedPlaybackTarget.RemoteStreamUrl(directSaavn.getOrThrow(), playableTrack)
                    )
                }
                val fallbackSaavn = runCatching {
                    StreamResolver.resolveExactStreamUrl(
                        TrackMetadata(
                            id = playableTrack.sourceId,
                            title = playableTrack.title,
                            artist = primaryArtist,
                            album = playableTrack.album.orEmpty(),
                            durationSeconds = targetDurationSec,
                            thumbnailUrl = playableTrack.artUrl
                        ),
                        AudioQuality.NORMAL
                    )
                }.getOrNull()
                if (!fallbackSaavn.isNullOrBlank()) {
                    return@withContext Result.success(
                        ResolvedPlaybackTarget.RemoteStreamUrl(fallbackSaavn, playableTrack)
                    )
                }
                directSaavn.map { url ->
                    ResolvedPlaybackTarget.RemoteStreamUrl(url, playableTrack)
                }
            }
            else -> Result.failure(IllegalStateException("Unsupported source: ${playableTrack.source}"))
        }
    }

    private fun isContentUriReadable(uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        }.getOrDefault(false)
    }
}
