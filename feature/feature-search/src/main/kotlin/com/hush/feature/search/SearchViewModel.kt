package com.hush.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: SearchRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var artworkJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().filter { it.length >= 2 }.collect { search(it) }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
        _state.value = _state.value.copy(query = value, error = null)
        if (value.isBlank()) _state.value = SearchUiState()
    }

    private suspend fun search(value: String) {
        artworkJob?.cancel()
        _state.value = _state.value.copy(isLoading = true, error = null)
        repository.searchTracks(value).onSuccess { tracks ->
            _state.value = _state.value.copy(tracks = tracks, isLoading = false)
            artworkJob = viewModelScope.launch {
                tracks.take(8).map { track -> async { repository.loadArtwork(track) } }.awaitAll().forEach { artwork ->
                    _state.value = _state.value.copy(
                        tracks = _state.value.tracks.map { if (it.isrc == artwork.isrc) artwork else it },
                    )
                }
            }
        }.onFailure { error ->
            _state.value = _state.value.copy(isLoading = false, error = error.message ?: "Search failed")
        }
    }
}