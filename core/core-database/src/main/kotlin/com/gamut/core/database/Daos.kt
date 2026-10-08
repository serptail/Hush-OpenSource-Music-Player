package com.gamut.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(track: TrackEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks WHERE isrc = :isrc") suspend fun get(isrc: String): TrackEntity?
    @Query("SELECT * FROM tracks ORDER BY title") fun observeAll(): Flow<List<TrackEntity>>
    @Query("DELETE FROM tracks WHERE isrc = :isrc") suspend fun delete(isrc: String)
}

@Dao
interface ResolvedSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(source: ResolvedSourceEntity)
    @Query("SELECT * FROM resolved_sources WHERE isrc = :isrc") suspend fun get(isrc: String): ResolvedSourceEntity?
    @Query("DELETE FROM resolved_sources WHERE isrc = :isrc") suspend fun delete(isrc: String)
}

@Dao
interface PlaylistDao {
    @Insert suspend fun insert(playlist: PlaylistEntity): Long
    @Query("SELECT * FROM playlists ORDER BY createdAtEpochMs DESC") fun observeAll(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun get(id: Long): PlaylistEntity?
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE playlists SET name = :name, tags = :tags, coverUri = :coverUri WHERE id = :id")
    suspend fun update(id: Long, name: String, tags: String, coverUri: String?)
}

// An abstract class (not an interface) so it can hold the @Transaction method below.
@Dao
abstract class PlaylistTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(ref: PlaylistTrackCrossRef)
    @Query("DELETE FROM playlisttrackcrossref WHERE playlistId = :playlistId AND isrc = :isrc") abstract suspend fun remove(playlistId: Long, isrc: String)
    @Query("SELECT t.* FROM tracks t INNER JOIN playlisttrackcrossref r ON t.isrc = r.isrc WHERE r.playlistId = :playlistId ORDER BY r.position") abstract fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlisttrackcrossref WHERE playlistId = :playlistId") abstract suspend fun nextPosition(playlistId: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM playlisttrackcrossref WHERE playlistId = :playlistId AND isrc = :isrc)") abstract suspend fun contains(playlistId: Long, isrc: String): Boolean
    @Query("DELETE FROM playlisttrackcrossref WHERE playlistId = :playlistId") abstract suspend fun clear(playlistId: Long)

    /** Rewrites the playlist's songs in exactly this order (positions 0..n-1), atomically, so observers see one change. */
    @Transaction
    open suspend fun replaceAll(playlistId: Long, isrcs: List<String>) {
        clear(playlistId)
        isrcs.distinct().forEachIndexed { index, isrc -> upsert(PlaylistTrackCrossRef(playlistId, isrc, index)) }
    }
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(download: DownloadEntity)
    @Query("SELECT * FROM downloads WHERE isrc = :isrc") fun observe(isrc: String): Flow<DownloadEntity?>
    @Query("SELECT * FROM downloads") fun observeAll(): Flow<List<DownloadEntity>>
    @Query("DELETE FROM downloads WHERE isrc = :isrc") suspend fun delete(isrc: String)
}

@Dao
interface ArtworkCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: ArtworkCacheEntity)
    @Query("SELECT * FROM artwork_cache WHERE releaseId = :releaseId") suspend fun get(releaseId: String): ArtworkCacheEntity?
    @Query("SELECT * FROM artwork_cache WHERE releaseId IN (:releaseIds)") suspend fun getAll(releaseIds: List<String>): List<ArtworkCacheEntity>
}

@Dao
abstract class FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun add(favorite: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE isrc = :isrc") abstract suspend fun remove(isrc: String)
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE isrc = :isrc)") abstract suspend fun isFavorite(isrc: String): Boolean
    @Query("SELECT isrc FROM favorites") abstract fun observeIsrcs(): Flow<List<String>>
    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.isrc = f.isrc ORDER BY f.addedAtEpochMs DESC") abstract fun observeTracks(): Flow<List<TrackEntity>>

    /**
     * Puts the favorites in exactly this order (first = shown first). The order IS addedAtEpochMs (newest first), so
     * this hands out descending timestamps: no new column, no migration. Also (re)adds any isrc that is missing,
     * which is how "Undo" brings a removed favorite back to its old place. One transaction = one change for observers.
     */
    @Transaction
    open suspend fun setOrder(isrcs: List<String>) {
        val base = System.currentTimeMillis()
        isrcs.distinct().forEachIndexed { index, isrc -> add(FavoriteEntity(isrc, base - index)) }
    }
}