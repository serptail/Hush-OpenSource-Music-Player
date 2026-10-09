package com.gamut.core.database

import androidx.room.ColumnInfo
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
    /** User-picked cover (`file:` URI), null = fall back to the last song's art. */
    val coverUri: String? = null,
    /** Tags joined with "\n" (see LibraryRepository). */
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    /** Supabase playlists.id (uuid). Null until the playlist has been pushed once. */
    val remoteId: String? = null,
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

/**
 * Pending-sync queue. State-based "dirty markers": one row per (type, itemKey), newer replaces older, and the payload
 * is only what cannot be re-read from the local tables (a playlist song's position, or null = removed).
 */
@Entity(tableName = "pending_sync", indices = [Index(value = ["type", "itemKey"], unique = true)])
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val itemKey: String,
    val payload: String? = null,
    val createdAtEpochMs: Long,
)

/** A song the user opened (one row per song, replaying moves it to the top). Local only, not synced. */
@Entity(tableName = "recently_played")
data class RecentEntity(@PrimaryKey val isrc: String, val playedAtEpochMs: Long)

/** Small key/value store for sync cursors (server timestamps), owner id, last sync time. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val name: String, val value: String)

object SyncTypes {
    const val FAV = "fav"        // itemKey = isrc
    const val PL = "pl"          // itemKey = local playlist id (name/tags changed or playlist created)
    const val PLS = "pls"        // itemKey = "<local playlist id>|<isrc>", payload = position, null = removed
    const val PL_DEL = "pl_del"  // itemKey = remote playlist uuid
}