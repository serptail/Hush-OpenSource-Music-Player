package com.gamut.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/** What a Library row shows for a playlist: the cover of the song added last, and how many songs it holds. */
data class PlaylistInfo(val coverUrl: String?, val songCount: Int)

// One instance for the whole app (MainActivity shares it with every screen), and its flows start immediately:
// the small library tables are already loaded when the Library / Favorites screens first draw, so they never
// paint empty and then pop their content in.
@HiltViewModel
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    val playlists: StateFlow<List<Playlist>> =
        repository.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Per playlist (by id): cover = art of the LAST song added, plus the song count. Small tables, so eager like the rest. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val playlistInfo: StateFlow<Map<Long, PlaylistInfo>> =
        playlists.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf<Map<Long, PlaylistInfo>>(emptyMap())
            } else {
                combine(
                    list.map { p ->
                        repository.observeTracks(p.id).map { tracks ->
                            p.id to PlaylistInfo(tracks.lastOrNull()?.albumArtUrl?.takeIf { it.isNotBlank() }, tracks.size)
                        }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun createPlaylist(name: String) {
        if (name.isNotBlank()) viewModelScope.launch { repository.createPlaylist(name.trim()) }
    }

    /**
     * Create a playlist with its [tags] and an optional user-picked [coverUri], and fill it with [tracks], in order.
     * Without a [coverUri] the playlist falls back to the art of its last song.
     */
    fun createPlaylist(name: String, tracks: List<Track>, tags: List<String> = emptyList(), coverUri: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createPlaylist(name.trim(), tags, coverUri)
            tracks.forEach { repository.appendTrack(id, it) }
        }
    }

    fun addTrack(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.appendTrack(playlistId, track) }
    }

    fun createPlaylistAndAdd(name: String, track: Track) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.appendTrack(repository.createPlaylist(name.trim()), track) }
    }

    val favoriteIsrcs: StateFlow<Set<String>> =
        repository.observeFavoriteIsrcs().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Just the count, derived from the ISRC flow above (one Room query instead of two). */
    val favoriteCount: StateFlow<Int> =
        favoriteIsrcs.map { it.size }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Full favorites (for the Favorites screen only). Only runs while something collects it. */
    val favorites: StateFlow<List<Track>> =
        repository.observeFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { repository.toggleFavorite(track) }
    }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val rawId = savedStateHandle.get<String>("id")
    private val playlistId: Long? = rawId?.toLongOrNull()

    /** This playlist's own data (name, tags, custom cover). Null for the "liked" pseudo-playlist. */
    val playlist: StateFlow<Playlist?> =
        repository.observePlaylists().map { list -> list.firstOrNull { it.id == playlistId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    // "liked" is the Liked songs pseudo-playlist; it used to parse to -1 and show an empty playlist.
    val tracks: StateFlow<List<Track>> =
        (if (rawId == "liked") repository.observeFavorites() else repository.observeTracks(rawId?.toLongOrNull() ?: -1L))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Move the song at [from] to position [to] (the list is rewritten in its new order). */
    fun move(from: Int, to: Int) {
        val id = playlistId ?: return
        val list = tracks.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        viewModelScope.launch { repository.replaceTracks(id, list) }
    }

    fun removeAt(index: Int) {
        val id = playlistId ?: return
        val list = tracks.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        viewModelScope.launch { repository.replaceTracks(id, list) }
    }

    /** Edit mode "Done": name, tags and cover always; [newTracks] only when the list changed. */
    fun save(name: String, tags: List<String>, coverUri: String?, newTracks: List<Track>?) {
        val id = playlistId ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.updatePlaylist(id, name.trim(), tags, coverUri)
            if (newTracks != null) repository.replaceTracks(id, newTracks)
        }
    }
}