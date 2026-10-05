package com.gamut.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

// One instance for the whole app (MainActivity shares it with every screen), and its flows start immediately:
// the small library tables are already loaded when the Library / Favorites screens first draw, so they never
// paint empty and then pop their content in.
@HiltViewModel
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    val playlists: StateFlow<List<Playlist>> =
        repository.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun createPlaylist(name: String) {
        if (name.isNotBlank()) viewModelScope.launch { repository.createPlaylist(name.trim()) }
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

    /** Just the count, built from the cheap ISRC query: the Library tab never needs the full Track objects. */
    val favoriteCount: StateFlow<Int> =
        repository.observeFavoriteIsrcs().map { it.size }.distinctUntilChanged()
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
    repository: LibraryRepository,
) : ViewModel() {
    private val rawId = savedStateHandle.get<String>("id")

    // "liked" is the Liked songs pseudo-playlist; it used to parse to -1 and show an empty playlist.
    val tracks: StateFlow<List<Track>> =
        (if (rawId == "liked") repository.observeFavorites() else repository.observeTracks(rawId?.toLongOrNull() ?: -1L))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
}