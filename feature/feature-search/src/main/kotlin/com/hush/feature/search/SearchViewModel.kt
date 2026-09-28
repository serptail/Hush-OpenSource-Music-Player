package com.hush.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    init {
        viewModelScope.launch {
            // collectLatest (not collect): a fresh keystroke cancels the previous search's
            // in-flight MusicBrainz+YouTube+artwork batch instead of letting old and new
            // results race each other into _state.
            query.debounce(350).distinctUntilChanged().filter { it.length >= 2 }.collectLatest { search(it) }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
        _state.value = _state.value.copy(query = value, error = null)
        if (value.isBlank()) _state.value = SearchUiState()
    }

    private suspend fun search(value: String) {
        _state.value = _state.value.copy(isLoading = true, error = null)
        // SearchRepository.searchTracks now resolves cover art (MusicBrainz -> Cover Art
        // Archive, cached in Room) and fans out to YouTube Music internally, so the tracks
        // it returns already have albumArtUrl populated — no separate artwork pass needed.
        repository.searchTracks(value)
            .onSuccess { tracks -> _state.value = _state.value.copy(tracks = tracks, isLoading = false) }
            .onFailure { error -> _state.value = _state.value.copy(isLoading = false, error = error.message ?: "Search failed") }
    }
}
