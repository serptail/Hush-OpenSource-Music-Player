package com.gamut.feature.library

import com.gamut.core.database.FavoriteDao
import com.gamut.core.database.FavoriteEntity
import com.gamut.core.database.PlaylistDao
import com.gamut.core.database.PlaylistEntity
import com.gamut.core.database.PlaylistTrackCrossRef
import com.gamut.core.database.PlaylistTrackDao
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toEntity
import com.gamut.core.database.toDomain
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

interface LibraryRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeTracks(playlistId: Long): Flow<List<Track>>
    suspend fun createPlaylist(name: String, tags: List<String> = emptyList(), coverUri: String? = null): Long
    /** Rename, retag and set (or clear, with null) the user-picked cover. */
    suspend fun updatePlaylist(id: Long, name: String, tags: List<String>, coverUri: String?)
    /** Replace the playlist's songs with [tracks], in this order. Used for remove and move up/down. */
    suspend fun replaceTracks(playlistId: Long, tracks: List<Track>)
    suspend fun addTrack(playlistId: Long, track: Track, position: Int)
    suspend fun removeTrack(playlistId: Long, isrc: String)
    /** Adds [track] at the end of the playlist; does nothing if it is already in it. */
    suspend fun appendTrack(playlistId: Long, track: Track)
    fun observeFavoriteIsrcs(): Flow<Set<String>>
    /** Favorited tracks, most recently liked first. */
    fun observeFavorites(): Flow<List<Track>>
    suspend fun toggleFavorite(track: Track)
}

class LibraryRepositoryImpl @Inject constructor(private val playlists: PlaylistDao, private val tracks: PlaylistTrackDao, private val trackDao: TrackDao, private val favorites: FavoriteDao) : LibraryRepository {
    override fun observePlaylists() = playlists.observeAll().map { list -> list.map { Playlist(it.id, it.name, it.createdAtEpochMs, it.coverUri, it.tags.toTagList()) } }.flowOn(Dispatchers.Default)
    override fun observeTracks(playlistId: Long) = tracks.observeTracks(playlistId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun createPlaylist(name: String, tags: List<String>, coverUri: String?) =
        playlists.insert(PlaylistEntity(name = name, createdAtEpochMs = System.currentTimeMillis(), coverUri = coverUri, tags = tags.toTagColumn()))
    override suspend fun updatePlaylist(id: Long, name: String, tags: List<String>, coverUri: String?) =
        playlists.update(id, name, tags.toTagColumn(), coverUri)
    override suspend fun replaceTracks(playlistId: Long, tracks: List<Track>) =
        this.tracks.replaceAll(playlistId, tracks.map { it.isrc })
    override suspend fun addTrack(playlistId: Long, track: Track, position: Int) {
        trackDao.upsert(track.toEntity())
        tracks.upsert(PlaylistTrackCrossRef(playlistId, track.isrc, position))
    }
    override suspend fun removeTrack(playlistId: Long, isrc: String) = tracks.remove(playlistId, isrc)
    override suspend fun appendTrack(playlistId: Long, track: Track) {
        if (tracks.contains(playlistId, track.isrc)) return
        trackDao.upsert(track.toEntity())
        tracks.upsert(PlaylistTrackCrossRef(playlistId, track.isrc, tracks.nextPosition(playlistId)))
    }
    override fun observeFavoriteIsrcs() = favorites.observeIsrcs().map { it.toSet() }.flowOn(Dispatchers.Default)
    override fun observeFavorites() = favorites.observeTracks().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun toggleFavorite(track: Track) {
        if (favorites.isFavorite(track.isrc)) {
            favorites.remove(track.isrc)
        } else {
            trackDao.upsert(track.toEntity())
            favorites.add(FavoriteEntity(track.isrc, System.currentTimeMillis()))
        }
    }
}

// Tags live in one TEXT column, one tag per line (tags are single-line, so "\n" can never be part of one).
private fun String.toTagList(): List<String> = split('\n').filter { it.isNotBlank() }
private fun List<String>.toTagColumn(): String = joinToString("\n") { it.trim() }

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryRepositoryModule { @Binds abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository }