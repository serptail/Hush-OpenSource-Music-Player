package com.hush.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MusicBrainzSearchResponse(val recordings: List<MusicBrainzRecordingDto> = emptyList())

@Serializable
data class MusicBrainzRecordingDto(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCreditDto> = emptyList(),
    val releases: List<MusicBrainzReleaseDto> = emptyList(),
    val isrcs: List<String> = emptyList(),
    val score: Int? = null,
)

@Serializable
data class MusicBrainzArtistCreditDto(
    val name: String? = null,
    val artist: MusicBrainzArtistDto? = null,
)

@Serializable
data class MusicBrainzArtistDto(val name: String)

@Serializable
data class MusicBrainzReleaseDto(
    val id: String,
    val title: String? = null,
)

@Serializable
data class CoverArtResponse(val images: List<CoverArtImageDto> = emptyList())

@Serializable
data class CoverArtImageDto(
    val front: Boolean = false,
    val image: String? = null,
    val thumbnails: CoverArtThumbnailsDto? = null,
)

@Serializable
data class CoverArtThumbnailsDto(
    @SerialName("250") val small: String? = null,
    @SerialName("500") val medium: String? = null,
    @SerialName("1200") val large: String? = null,
)

@Serializable
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesTrackDto> = emptyList(),
)

@Serializable
data class ItunesTrackDto(
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackTimeMillis: Long? = null,
    // Comes back as a 100x100 thumbnail URL (".../100x100bb.jpg"); the size segment can be
    // rewritten to fetch a bigger image with zero extra requests -- see SearchRepository.kt.
    val artworkUrl100: String? = null,
    val kind: String? = null,
)

@Serializable
data class YoutubeSearchRequest(val query: String)

@Serializable
data class YoutubeSearchResponse(val candidates: List<YoutubeCandidateDto>)

@Serializable
data class YoutubeCandidateDto(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelIsTopicChannel: Boolean,
    val durationMs: Long,
    val viewCount: Long,
    val category: String = "songs",
    val isMusicCatalog: Boolean = false,
)

@Serializable
data class ResolvedSourceDto(
    val isrc: String,
    val youtubeVideoId: String,
    val resolutionMethod: String,
    val confidenceScore: Float,
    val verifiedCount: Int,
)

@Serializable
data class AudioStreamDto(val url: String, val expiresAtEpochSeconds: Long? = null)

fun YoutubeCandidateDto.toDomain() = YoutubeCandidate(
    videoId, title, channelName, channelIsTopicChannel, durationMs, viewCount, category, isMusicCatalog,
)