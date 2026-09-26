package com.hush.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val isrc: String,
    val musicBrainzId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val albumArtUrl: String? = null,
)

@Serializable
data class ResolvedSource(
    val isrc: String,
    val youtubeVideoId: String,
    val resolutionMethod: ResolutionMethod,
    val confidenceScore: Float,
    val verifiedCount: Int,
)

@Serializable
enum class ResolutionMethod { TOPIC_CHANNEL, HARD_FILTER, MANUAL }

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