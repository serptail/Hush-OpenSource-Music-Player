package com.hush.core.model

import kotlinx.serialization.Serializable

// MUSICBRAINZ kept (not removed) so old cached DB rows / ResolvedSource entries with that
// string value still deserialize cleanly (Mappers.kt falls back to it on unknown values too).
@Serializable
enum class TrackSource { MUSICBRAINZ, ITUNES, YOUTUBE }

@Serializable
data class Track(
    val isrc: String,
    val musicBrainzId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val albumArtUrl: String? = null,
    val source: TrackSource = TrackSource.ITUNES,
    val youtubeVideoId: String? = null,
)

@Serializable
data class ResolvedSource(
    val isrc: String,
    val youtubeVideoId: String,
    val resolutionMethod: ResolutionMethod,
    val confidenceScore: Float,
    val verifiedCount: Int,
)

// DIRECT added: used when a Track already carries its own youtubeVideoId (the new
// YouTube-only unified-search results), so resolution can skip searching/scoring entirely.
@Serializable
enum class ResolutionMethod { TOPIC_CHANNEL, HARD_FILTER, MANUAL, DIRECT }

@Serializable
data class Playlist(val id: Long, val name: String, val createdAtEpochMs: Long)

@Serializable
data class DownloadedTrack(
    val isrc: String,
    val localFilePath: String,
    val downloadedAtEpochMs: Long,
    val fileSizeBytes: Long,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data class Playing(val positionMs: Long, val track: Track) : PlaybackState
    data class Paused(val positionMs: Long, val track: Track) : PlaybackState
    data class Error(val message: String) : PlaybackState
}