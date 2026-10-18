package com.example.domain

import com.example.data.model.Track

data class ArtistSourceProfile(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val bannerUrl: String? = null,
    val bio: String? = null,
    val source: String,
    val tracks: List<Track>,
    val albums: List<Album> = emptyList()
)

data class Album(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val year: String = ""
)

data class UnifiedArtistProfile(
    val canonicalId: String,
    val name: String,
    val avatarUrl: String,
    val bannerUrl: String?,
    val bio: String?,
    val primarySource: String,
    val mergedTracks: List<Track>,
    val mergedAlbums: List<Album>
)

object ArtistAggregator {
    fun buildUnifiedArtistProfile(profilesFromSources: List<ArtistSourceProfile>): UnifiedArtistProfile? {
        if (profilesFromSources.isEmpty()) return null

        // Select the profile with the most tracks as the canonical profile
        val dominantProfile = profilesFromSources.maxByOrNull { it.tracks.size }!!

        // Merge and deduplicate tracks across all sources, prioritizing the dominant source's ordering
        val orderedProfiles = listOf(dominantProfile) + (profilesFromSources - dominantProfile)
        val deduplicatedTracks = orderedProfiles
            .flatMap { it.tracks }
            .distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }

        val deduplicatedAlbums = orderedProfiles
            .flatMap { it.albums }
            .distinctBy { it.title.trim().lowercase() }

        return UnifiedArtistProfile(
            canonicalId = dominantProfile.id,
            name = dominantProfile.name,
            avatarUrl = dominantProfile.avatarUrl.ifBlank {
                orderedProfiles.firstNotNullOfOrNull { it.avatarUrl.takeIf(String::isNotBlank) }.orEmpty()
            },
            bannerUrl = dominantProfile.bannerUrl ?: orderedProfiles.firstNotNullOfOrNull { it.bannerUrl },
            bio = dominantProfile.bio ?: orderedProfiles.firstNotNullOfOrNull { it.bio },
            primarySource = dominantProfile.source,
            mergedTracks = deduplicatedTracks,
            mergedAlbums = deduplicatedAlbums
        )
    }
}
