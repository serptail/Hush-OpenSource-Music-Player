package com.hush.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val isrc: String,
    val musicBrainzId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val albumArtUrl: String?,
)

@Entity(tableName = "resolved_sources")
data class ResolvedSourceEntity(
    @PrimaryKey val isrc: String,
    val youtubeVideoId: String,
    val resolutionMethod: String,
    val confidenceScore: Float,
    val verifiedCount: Int,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtEpochMs: Long,
)

@Entity(primaryKeys = ["playlistId", "isrc"], indices = [Index("isrc")])
data class PlaylistTrackCrossRef(val playlistId: Long, val isrc: String, val position: Int)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val isrc: String,
    val localFilePath: String,
    val downloadedAtEpochMs: Long,
    val fileSizeBytes: Long,
)