package com.gamut.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Track
import com.gamut.feature.resolver.PrefetchCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val prefetch: PrefetchCoordinator,
    private val discoverRepository: DiscoverRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val _discover = MutableStateFlow(DiscoverState())
    val discover: StateFlow<DiscoverState> = _discover.asStateFlow()

    /** The artist whose name matches what is typed (null when none). Shown as a highlighted card above the songs. */
    val artistHit: StateFlow<DiscoverItem?> = query
        .debounce(300)
        .distinctUntilChanged()
        .mapLatest { value -> if (value.trim().length < 2) null else discoverRepository.artistHit(value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { _discover.value = discoverRepository.load() }
        viewModelScope.launch {
            query.debounce(250).distinctUntilChanged().flatMapLatest { value ->
                if (value.trim().length < 2) kotlinx.coroutines.flow.flowOf(SearchResult(emptyList(), true))
                else repository.search(value)
            }.collect { result ->
                _state.value = _state.value.copy(
                    tracks = result.tracks,
                    isLoading = !result.isFinal,
                    error = null,
                )
                if (result.isFinal) prefetch.prefetch(result.tracks)
            }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
        _state.value = _state.value.copy(query = value, error = null)
        if (value.isBlank()) _state.value = SearchUiState()
    }

}