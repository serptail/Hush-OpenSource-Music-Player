package com.gamut.feature.library

import android.net.Uri
import com.gamut.core.database.FavoriteDao
import com.gamut.core.database.FavoriteEntity
import com.gamut.core.database.PendingSyncDao
import com.gamut.core.database.PendingSyncEntity
import com.gamut.core.database.PlaylistDao
import com.gamut.core.database.PlaylistEntity
import com.gamut.core.database.PlaylistTrackCrossRef
import com.gamut.core.database.PlaylistTrackDao
import com.gamut.core.database.SyncTypes
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toDomain
import com.gamut.core.database.toEntity
import com.gamut.core.model.GroupedTrack
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

interface LibraryRepository {
    fun observePlaylists(): Flow<List<Playlist>>

    fun observeTracks(playlistId: Long): Flow<List<Track>>

    suspend fun createPlaylist(
        name: String,
        tags: List<String> = emptyList(),
        coverUri: String? = null,
    ): Long

    suspend fun updatePlaylist(
        id: Long,
        name: String,
        tags: List<String>,
        coverUri: String?,
    )

    suspend fun deletePlaylist(id: Long)

    suspend fun replaceTracks(
        playlistId: Long,
        tracks: List<Track>,
    )

    suspend fun addTrack(
        playlistId: Long,
        track: Track,
        position: Int,
    )

    suspend fun removeTrack(
        playlistId: Long,
        isrc: String,
    )

    suspend fun appendTrack(
        playlistId: Long,
        track: Track,
    )

    fun observeFavoriteIsrcs(): Flow<Set<String>>

    fun observeFavorites(): Flow<List<Track>>

    suspend fun toggleFavorite(track: Track)

    suspend fun setFavoriteOrder(tracks: List<Track>)

    suspend fun addFavorite(
        track: Track,
        addedAtEpochMs: Long,
    )

    /** The songs of a playlist in order, each with the album group it was added in (null for a loose song). */
    fun observeGroupedTracks(playlistId: Long): Flow<List<GroupedTrack>>

    /** The favorites in order, each with the album group it was added in (null for a loose song). */
    fun observeGroupedFavorites(): Flow<List<GroupedTrack>>

    /** Adds [tracks] to the end of the playlist as one album group. Returns how many songs were new. */
    suspend fun appendAlbum(
        playlistId: Long,
        tracks: List<Track>,
    ): Int

    /** Adds [tracks] to the top of the favorites as one album group. Returns how many songs were new. */
    suspend fun addFavoriteAlbum(tracks: List<Track>): Int

    /** Turns an album group of a playlist back into loose songs, in place. */
    suspend fun ungroupAlbum(
        playlistId: Long,
        groupId: String,
    )

    /** Takes every song of an album group out of a playlist. */
    suspend fun removeAlbum(
        playlistId: Long,
        groupId: String,
    )

    suspend fun ungroupFavoriteAlbum(groupId: String)

    suspend fun removeFavoriteAlbum(groupId: String)
}

class LibraryRepositoryImpl
    @Inject
    constructor(
        private val playlists: PlaylistDao,
        private val tracks: PlaylistTrackDao,
        private val trackDao: TrackDao,
        private val favorites: FavoriteDao,
        private val pending: PendingSyncDao,
    ) : LibraryRepository {
        private suspend fun mark(
            type: String,
            key: String,
            payload: String? = null,
        ) {
            if (key.contains(LOCAL_ISRC_PREFIX)) return
            pending.put(PendingSyncEntity(type = type, itemKey = key, payload = payload, createdAtEpochMs = System.currentTimeMillis()))
        }

        override fun observePlaylists() =
            playlists
                .observeAll()
                .map { list ->
                    list.map {
                        Playlist(it.id, it.name, it.createdAtEpochMs, it.coverUri, it.tags.toTagList())
                    }
                }.flowOn(Dispatchers.Default)

        override fun observeTracks(playlistId: Long) =
            tracks
                .observeTracks(playlistId)
                .map { list ->
                    list.map {
                        it.toDomain()
                    }
                }.flowOn(Dispatchers.Default)

        override suspend fun createPlaylist(
            name: String,
            tags: List<String>,
            coverUri: String?,
        ): Long {
            val id =
                playlists.insert(
                    PlaylistEntity(
                        name = name,
                        createdAtEpochMs = System.currentTimeMillis(),
                        coverUri = coverUri,
                        tags = tags.toTagColumn(),
                    ),
                )
            mark(SyncTypes.PL, id.toString())
            return id
        }

        override suspend fun updatePlaylist(
            id: Long,
            name: String,
            tags: List<String>,
            coverUri: String?,
        ) {
            playlists.update(id, name, tags.toTagColumn(), coverUri)
            mark(SyncTypes.PL, id.toString())
        }

        override suspend fun deletePlaylist(id: Long) {
            val entity = playlists.get(id)
            val cover = entity?.coverUri
            tracks.clear(id)
            playlists.delete(id)
            pending.delete(SyncTypes.PL, id.toString())
            pending.deleteByPrefix(SyncTypes.PLS, "$id|%")
            entity?.remoteId?.let { mark(SyncTypes.PL_DEL, it) }
            if (cover != null && cover.startsWith("file:")) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        Uri
                            .parse(cover)
                            .path
                            ?.let(::File)
                            ?.delete()
                    }
                }
            }
        }

        override suspend fun replaceTracks(
            playlistId: Long,
            tracks: List<Track>,
        ) {
            val before = this.tracks.isrcsFor(playlistId)
            val after = tracks.map { it.isrc }.distinct()
            this.tracks.replaceAll(playlistId, after)
            (before - after.toSet()).forEach { mark(SyncTypes.PLS, "$playlistId|$it", null) }
            after.forEachIndexed { index, isrc ->
                if (before.getOrNull(index) != isrc) mark(SyncTypes.PLS, "$playlistId|$isrc", index.toString())
            }
        }

        override suspend fun addTrack(
            playlistId: Long,
            track: Track,
            position: Int,
        ) {
            trackDao.upsert(track.toEntity())
            tracks.upsert(PlaylistTrackCrossRef(playlistId, track.isrc, position))
            mark(SyncTypes.PLS, "$playlistId|${track.isrc}", position.toString())
        }

        override suspend fun removeTrack(
            playlistId: Long,
            isrc: String,
        ) {
            tracks.remove(playlistId, isrc)
            mark(SyncTypes.PLS, "$playlistId|$isrc", null)
        }

        override suspend fun appendTrack(
            playlistId: Long,
            track: Track,
        ) {
            if (tracks.contains(playlistId, track.isrc)) return
            trackDao.upsert(track.toEntity())
            val position = tracks.nextPosition(playlistId)
            tracks.upsert(PlaylistTrackCrossRef(playlistId, track.isrc, position))
            mark(SyncTypes.PLS, "$playlistId|${track.isrc}", position.toString())
        }

        override fun observeFavoriteIsrcs() = favorites.observeIsrcs().map { it.toSet() }.flowOn(Dispatchers.Default)

        override fun observeFavorites() = favorites.observeTracks().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

        override suspend fun setFavoriteOrder(tracks: List<Track>) {
            tracks.forEach { trackDao.upsert(it.toEntity()) }
            favorites.setOrder(tracks.map { it.isrc })
            tracks.forEach { mark(SyncTypes.FAV, it.isrc) }
        }

        override suspend fun toggleFavorite(track: Track) {
            if (favorites.isFavorite(track.isrc)) {
                favorites.remove(track.isrc)
            } else {
                trackDao.upsert(track.toEntity())
                favorites.add(FavoriteEntity(track.isrc, System.currentTimeMillis()))
            }
            mark(SyncTypes.FAV, track.isrc)
        }

        override suspend fun addFavorite(
            track: Track,
            addedAtEpochMs: Long,
        ) {
            if (favorites.isFavorite(track.isrc)) return
            trackDao.upsert(track.toEntity())
            favorites.add(FavoriteEntity(track.isrc, addedAtEpochMs))
            mark(SyncTypes.FAV, track.isrc)
        }

        override fun observeGroupedTracks(playlistId: Long) =
            tracks
                .observeGrouped(playlistId)
                .map { rows -> rows.map { GroupedTrack(it.track.toDomain(), it.albumGroupId) } }
                .flowOn(Dispatchers.Default)

        override fun observeGroupedFavorites() =
            favorites
                .observeGrouped()
                .map { rows -> rows.map { GroupedTrack(it.track.toDomain(), it.albumGroupId) } }
                .flowOn(Dispatchers.Default)

        override suspend fun appendAlbum(
            playlistId: Long,
            tracks: List<Track>,
        ): Int {
            val songs = tracks.distinctBy { it.isrc }
            if (songs.isEmpty()) return 0
            songs.forEach { trackDao.upsert(it.toEntity()) }
            val first = this.tracks.nextPosition(playlistId)
            val added = this.tracks.appendGroup(playlistId, newGroupId(), songs.map { it.isrc })
            added.forEachIndexed { index, isrc -> mark(SyncTypes.PLS, "$playlistId|$isrc", (first + index).toString()) }
            return added.size
        }

        override suspend fun addFavoriteAlbum(tracks: List<Track>): Int {
            val songs = tracks.distinctBy { it.isrc }
            if (songs.isEmpty()) return 0
            songs.forEach { trackDao.upsert(it.toEntity()) }
            val added = favorites.addGroup(newGroupId(), songs.map { it.isrc })
            added.forEach { mark(SyncTypes.FAV, it) }
            return added.size
        }

        override suspend fun ungroupAlbum(
            playlistId: Long,
            groupId: String,
        ) = tracks.ungroup(playlistId, groupId)

        override suspend fun removeAlbum(
            playlistId: Long,
            groupId: String,
        ) {
            val isrcs = tracks.isrcsInGroup(playlistId, groupId)
            tracks.removeGroup(playlistId, groupId)
            isrcs.forEach { mark(SyncTypes.PLS, "$playlistId|$it", null) }
        }

        override suspend fun ungroupFavoriteAlbum(groupId: String) = favorites.ungroup(groupId)

        override suspend fun removeFavoriteAlbum(groupId: String) {
            val isrcs = favorites.isrcsInGroup(groupId)
            favorites.removeGroup(groupId)
            isrcs.forEach { mark(SyncTypes.FAV, it) }
        }
    }

private fun newGroupId(): String = UUID.randomUUID().toString()

private fun String.toTagList(): List<String> = split('\n').filter { it.isNotBlank() }

private fun List<String>.toTagColumn(): String = joinToString("\n") { it.trim() }

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryRepositoryModule {
    @Binds abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository
}
