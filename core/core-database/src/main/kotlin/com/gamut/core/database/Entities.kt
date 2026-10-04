package com.gamut.core.database

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
    // Additive columns for the unified MusicBrainz+YouTube search. Nullable with defaults
    // so this is a safe migration on top of existing rows (see DatabaseMigrations.kt notes).
    val source: String = "MUSICBRAINZ",
    val youtubeVideoId: String? = null,
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

/**
 * Persistent cover-art URL cache, keyed by MusicBrainz release ID. Closes the "gone on
 * restart, re-fetch every session" gap: SearchRepositoryImpl now checks this table before
 * hitting Cover Art Archive, and writes to it after every successful lookup.
 */
@Entity(tableName = "artwork_cache")
data class ArtworkCacheEntity(
    @PrimaryKey val releaseId: String,
    val artworkUrl: String,
    val cachedAtEpochMs: Long,
)

/** A track the user has liked. One row per track; the track itself lives in [TrackEntity]. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val isrc: String,
    val addedAtEpochMs: Long,
)