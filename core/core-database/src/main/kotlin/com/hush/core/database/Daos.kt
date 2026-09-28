package com.hush.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}

@Dao
interface PlaylistTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(ref: PlaylistTrackCrossRef)
    @Query("DELETE FROM playlisttrackcrossref WHERE playlistId = :playlistId AND isrc = :isrc") suspend fun remove(playlistId: Long, isrc: String)
    @Query("SELECT t.* FROM tracks t INNER JOIN playlisttrackcrossref r ON t.isrc = r.isrc WHERE r.playlistId = :playlistId ORDER BY r.position") fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(download: DownloadEntity)
    @Query("SELECT * FROM downloads WHERE isrc = :isrc") fun observe(isrc: String): Flow<DownloadEntity?>
    @Query("DELETE FROM downloads WHERE isrc = :isrc") suspend fun delete(isrc: String)
}

@Dao
interface ArtworkCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: ArtworkCacheEntity)
    @Query("SELECT * FROM artwork_cache WHERE releaseId = :releaseId") suspend fun get(releaseId: String): ArtworkCacheEntity?
    @Query("SELECT * FROM artwork_cache WHERE releaseId IN (:releaseIds)") suspend fun getAll(releaseIds: List<String>): List<ArtworkCacheEntity>
}
