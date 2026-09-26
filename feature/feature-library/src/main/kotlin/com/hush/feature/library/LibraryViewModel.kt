package com.hush.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.core.model.Playlist
import com.hush.core.model.Track
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
    fun addTrack(playlistId: Long, track: Track) { viewModelScope.launch { repository.addTrack(playlistId, track, 0) } }
}