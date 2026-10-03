package com.gamut.feature.library

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

interface LibraryRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeTracks(playlistId: Long): Flow<List<Track>>
    suspend fun createPlaylist(name: String): Long
    suspend fun addTrack(playlistId: Long, track: Track, position: Int)
    suspend fun removeTrack(playlistId: Long, isrc: String)
}

class LibraryRepositoryImpl @Inject constructor(private val playlists: PlaylistDao, private val tracks: PlaylistTrackDao, private val trackDao: TrackDao) : LibraryRepository {
    override fun observePlaylists() = playlists.observeAll().map { list -> list.map { Playlist(it.id, it.name, it.createdAtEpochMs) } }
    override fun observeTracks(playlistId: Long) = tracks.observeTracks(playlistId).map { list -> list.map { it.toDomain() } }
    override suspend fun createPlaylist(name: String) = playlists.insert(PlaylistEntity(name = name, createdAtEpochMs = System.currentTimeMillis()))
    override suspend fun addTrack(playlistId: Long, track: Track, position: Int) {
        trackDao.upsert(track.toEntity())
        tracks.upsert(PlaylistTrackCrossRef(playlistId, track.isrc, position))
    }
    override suspend fun removeTrack(playlistId: Long, isrc: String) = tracks.remove(playlistId, isrc)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryRepositoryModule { @Binds abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository }