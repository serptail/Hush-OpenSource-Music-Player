package com.gamut.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.database.DownloadIndex
import com.gamut.core.database.toDomain
import com.gamut.core.model.GroupedTrack
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.abs

private const val STOP_TIMEOUT_MS = 5_000L
private const val SAME_SONG_DURATION_SLACK_MS = 5_000L

private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

internal fun sameSong(
    a: Track,
    b: Track,
): Boolean {
    if (a.isrc.isNotBlank() && a.isrc.trim().equals(b.isrc.trim(), ignoreCase = true)) return true
    if (norm(a.title) != norm(b.title) || norm(a.artist) != norm(b.artist)) return false
    return a.durationMs <= 0L || b.durationMs <= 0L || abs(a.durationMs - b.durationMs) <= SAME_SONG_DURATION_SLACK_MS
}

internal fun List<Track>.withoutDuplicates(): List<Track> {
    val out = ArrayList<Track>(size)
    for (t in this) if (out.none { sameSong(it, t) }) out += t
    return out
}

fun playlistIdsContaining(
    byPlaylist: Map<Long, List<Track>>,
    track: Track,
): Set<String> =
    byPlaylist
        .filterValues { list ->
            list.any {
                sameSong(it, track)
            }
        }.keys
        .mapTo(HashSet()) { it.toString() }

data class PlaylistInfo(
    val coverUrl: String?,
    val songCount: Int,
    val offline: Boolean = false,
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: LibraryRepository,
        private val downloadIndex: DownloadIndex,
        private val trackDao: com.gamut.core.database.TrackDao,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val downloadedTracks: StateFlow<List<Track>> =
            downloadIndex.downloads
                .mapLatest { rows ->
                    rows.keys
                        .toList()
                        .chunked(400)
                        .flatMap { trackDao.getByIsrcs(it) }
                        .map { it.toDomain() }
                        .sortedBy { it.title.lowercase() }
                }.flowOn(kotlinx.coroutines.Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val playlists: StateFlow<List<Playlist>> =
            repository.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        @OptIn(ExperimentalCoroutinesApi::class)
        val tracksByPlaylist: StateFlow<Map<Long, List<Track>>> =
            playlists
                .flatMapLatest { list ->
                    if (list.isEmpty()) {
                        flowOf<Map<Long, List<Track>>>(emptyMap())
                    } else {
                        combine(
                            list.map { p -> repository.observeTracks(p.id).map { tracks -> p.id to tracks } },
                        ) { pairs -> pairs.toMap() }
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        val playlistInfo: StateFlow<Map<Long, PlaylistInfo>> =
            tracksByPlaylist
                .combine(downloadIndex.downloads) { byPlaylist, saved ->
                    byPlaylist.mapValues { (_, tracks) ->
                        PlaylistInfo(
                            coverUrl = tracks.lastOrNull()?.albumArtUrl?.takeIf { it.isNotBlank() },
                            songCount = tracks.size,
                            offline = tracks.isNotEmpty() && tracks.all { it.isLocal || it.isrc in saved },
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

        fun createPlaylist(name: String) {
            if (name.isNotBlank()) viewModelScope.launch { repository.createPlaylist(name.trim()) }
        }

        fun createPlaylist(
            name: String,
            tracks: List<Track>,
            tags: List<String> = emptyList(),
            coverUri: String? = null,
        ) {
            if (name.isBlank()) return
            viewModelScope.launch {
                val id = repository.createPlaylist(name.trim(), tags, coverUri)
                tracks.withoutDuplicates().forEach { repository.appendTrack(id, it) }
            }
        }

        private val addMutex = Mutex()
        private val _duplicateAdds = MutableSharedFlow<String>(extraBufferCapacity = 8)

        val duplicateAdds: SharedFlow<String> = _duplicateAdds.asSharedFlow()

        fun addTrack(
            playlistId: Long,
            track: Track,
        ) {
            viewModelScope.launch {
                addMutex.withLock {
                    val existing = repository.observeTracks(playlistId).first()
                    if (existing.any { sameSong(it, track) }) {
                        _duplicateAdds.tryEmit(playlists.value.firstOrNull { it.id == playlistId }?.name ?: "this playlist")
                        return@withLock
                    }
                    repository.appendTrack(playlistId, track)
                }
            }
        }

        fun removeTrack(
            playlistId: Long,
            track: Track,
        ) {
            viewModelScope.launch {
                addMutex.withLock {
                    repository
                        .observeTracks(playlistId)
                        .first()
                        .filter { sameSong(it, track) }
                        .forEach { repository.removeTrack(playlistId, it.isrc) }
                }
            }
        }

        init {
            viewModelScope.launch {
                addMutex.withLock {
                    repository.observePlaylists().first().forEach { p ->
                        val current = repository.observeTracks(p.id).first()
                        val clean = current.withoutDuplicates()
                        if (clean.size != current.size) repository.replaceTracks(p.id, clean)
                    }
                }
            }
        }

        fun createPlaylistAndAdd(
            name: String,
            track: Track,
        ) {
            if (name.isBlank()) return
            viewModelScope.launch { repository.appendTrack(repository.createPlaylist(name.trim()), track) }
        }

        val favoriteIsrcs: StateFlow<Set<String>> =
            repository.observeFavoriteIsrcs().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        val favoriteCount: StateFlow<Int> =
            favoriteIsrcs
                .map { it.size }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        val favorites: StateFlow<List<Track>> =
            repository.observeFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** The favorites in order with the album group each song was added in. Albums show as one group. */
        val favoriteRows: StateFlow<List<GroupedTrack>> =
            repository.observeGroupedFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Adds an album to the favorites as one group. Returns how many of its songs were not there yet. */
        suspend fun addAlbumToFavorites(tracks: List<Track>): Int = repository.addFavoriteAlbum(tracks)

        /** Adds an album to a playlist as one group. Returns how many of its songs were not there yet. */
        suspend fun addAlbumToPlaylist(
            playlistId: Long,
            tracks: List<Track>,
        ): Int = addMutex.withLock { repository.appendAlbum(playlistId, tracks) }

        fun ungroupFavoriteAlbum(groupId: String) {
            viewModelScope.launch { repository.ungroupFavoriteAlbum(groupId) }
        }

        fun removeFavoriteAlbum(groupId: String) {
            viewModelScope.launch { repository.removeFavoriteAlbum(groupId) }
        }

        /** Moves a song one step ([delta] is -1 or +1) in the favorites. A song of an album stays inside its album. */
        fun moveFavorite(
            index: Int,
            delta: Int,
        ) {
            val order = reorderTrack(favoriteRows.value, index, delta) ?: return
            setFavoriteOrder(order)
        }

        /** Moves a whole album group one step past a loose song or another group in the favorites. */
        fun moveFavoriteAlbum(
            groupId: String,
            delta: Int,
        ) {
            val order = reorderAlbum(favoriteRows.value, groupId, delta) ?: return
            setFavoriteOrder(order)
        }

        val likedOffline: StateFlow<Boolean> =
            combine(
                favorites,
                downloadIndex.downloads,
            ) { liked, saved -> liked.isNotEmpty() && liked.all { it.isLocal || it.isrc in saved } }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        fun setFavoriteOrder(tracks: List<Track>) {
            viewModelScope.launch { repository.setFavoriteOrder(tracks) }
        }

        fun toggleFavorite(track: Track) {
            viewModelScope.launch { repository.toggleFavorite(track) }
        }
    }

@HiltViewModel
class PlaylistViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: LibraryRepository,
    ) : ViewModel() {
        private val rawId = savedStateHandle.get<String>("id")
        private val playlistId: Long? = rawId?.toLongOrNull()

        val playlist: StateFlow<Playlist?> =
            repository
                .observePlaylists()
                .map { list -> list.firstOrNull { it.id == playlistId } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        /** The songs in order, each with the album group it was added in. */
        val rows: StateFlow<List<GroupedTrack>> =
            (if (rawId == "liked") repository.observeGroupedFavorites() else repository.observeGroupedTracks(rawId?.toLongOrNull() ?: -1L))
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        val tracks: StateFlow<List<Track>> =
            rows
                .map { list -> list.map { it.track } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

        /** Moves the song at [index] one step ([delta] is -1 or +1). A song of an album stays inside its album. */
        fun moveTrack(
            index: Int,
            delta: Int,
        ) {
            val id = playlistId ?: return
            val order = reorderTrack(rows.value, index, delta) ?: return
            viewModelScope.launch { repository.replaceTracks(id, order) }
        }

        /** Moves a whole album group one step past a loose song or another group. */
        fun moveAlbum(
            groupId: String,
            delta: Int,
        ) {
            val id = playlistId ?: return
            val order = reorderAlbum(rows.value, groupId, delta) ?: return
            viewModelScope.launch { repository.replaceTracks(id, order) }
        }

        /** Turns an album group back into loose songs. They stay where they are. */
        fun ungroupAlbum(groupId: String) {
            val id = playlistId ?: return
            viewModelScope.launch { repository.ungroupAlbum(id, groupId) }
        }

        /** Takes every song of an album group out of the playlist. */
        fun removeAlbum(groupId: String) {
            val id = playlistId ?: return
            viewModelScope.launch { repository.removeAlbum(id, groupId) }
        }

        fun move(
            from: Int,
            to: Int,
        ) {
            val id = playlistId ?: return
            val list = tracks.value.toMutableList()
            if (from !in list.indices || to !in list.indices) return
            list.add(to, list.removeAt(from))
            viewModelScope.launch { repository.replaceTracks(id, list) }
        }

        fun setTracks(newTracks: List<Track>) {
            val id = playlistId ?: return
            viewModelScope.launch { repository.replaceTracks(id, newTracks) }
        }

        fun removeTrack(isrc: String) {
            val id = playlistId ?: return
            viewModelScope.launch { repository.removeTrack(id, isrc) }
        }

        fun removeAt(index: Int) {
            val id = playlistId ?: return
            val list = tracks.value.toMutableList()
            if (index !in list.indices) return
            list.removeAt(index)
            viewModelScope.launch { repository.replaceTracks(id, list) }
        }

        fun delete(onDone: () -> Unit) {
            val id = playlistId ?: return
            viewModelScope.launch {
                repository.deletePlaylist(id)
                onDone()
            }
        }

        fun save(
            name: String,
            tags: List<String>,
            coverUri: String?,
            newTracks: List<Track>?,
        ) {
            val id = playlistId ?: return
            if (name.isBlank()) return
            viewModelScope.launch {
                repository.updatePlaylist(id, name.trim(), tags, coverUri)
                if (newTracks != null) repository.replaceTracks(id, newTracks)
            }
        }
    }
