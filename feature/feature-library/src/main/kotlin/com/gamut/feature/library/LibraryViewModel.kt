package com.gamut.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Playlist
import com.gamut.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    val playlists: StateFlow<List<Playlist>> = repository.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun createPlaylist(name: String) { if (name.isNotBlank()) viewModelScope.launch { repository.createPlaylist(name.trim()) } }
    fun addTrack(playlistId: Long, track: Track) { viewModelScope.launch { repository.appendTrack(playlistId, track) } }
    fun createPlaylistAndAdd(name: String, track: Track) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.appendTrack(repository.createPlaylist(name.trim()), track) }
    }

    val favoriteIsrcs: StateFlow<Set<String>> = repository.observeFavoriteIsrcs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val favorites: StateFlow<List<Track>> = repository.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun toggleFavorite(track: Track) { viewModelScope.launch { repository.toggleFavorite(track) } }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: LibraryRepository,
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: -1L
    val tracks: StateFlow<List<Track>> = repository.observeTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}