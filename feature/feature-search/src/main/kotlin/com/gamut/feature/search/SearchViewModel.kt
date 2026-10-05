package com.gamut.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.model.Track
import com.gamut.feature.resolver.PrefetchCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** Only the first results are likely to be played; prefetching all of them wastes network and CPU. */
private const val PREFETCH_LIMIT = 5
private const val MIN_QUERY_LENGTH = 2

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
        .mapLatest { value ->
            if (value.trim().length < MIN_QUERY_LENGTH) null
            else try {
                discoverRepository.artistHit(value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // a failed lookup must not kill the flow
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            try {
                discoverRepository.discoverFlow().collect { _discover.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the empty DiscoverState; the UI shows its empty state
            }
        }
        viewModelScope.launch {
            query.debounce(250).distinctUntilChanged().flatMapLatest { value ->
                if (value.trim().length < MIN_QUERY_LENGTH) flowOf(SearchResult(emptyList(), true) to false)
                else repository.search(value)
                    .map { it to false }
                    .catch { emit(SearchResult(emptyList(), true) to true) }
            }.collect { (result, failed) ->
                // A result for an older query can land after the box was cleared; never show it.
                val stillSearching = query.value.trim().length >= MIN_QUERY_LENGTH
                val tracks = if (stillSearching) result.tracks else emptyList()
                _state.update {
                    it.copy(
                        tracks = tracks,
                        isLoading = stillSearching && !result.isFinal,
                        error = if (failed && stillSearching) "Search failed. Check your connection and try again." else null,
                    )
                }
                if (result.isFinal && stillSearching && tracks.isNotEmpty()) {
                    prefetch.prefetch(tracks.take(PREFETCH_LIMIT))
                }
            }
        }
    }

    fun updateQuery(value: String) {
        query.value = value
        // Atomic update: the result collector writes to the same state from another coroutine.
        _state.update { if (value.isBlank()) SearchUiState() else it.copy(query = value, error = null) }
    }
}