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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
private const val DISCOVER_ERROR = "Discover is unavailable. Check your connection and try again."

/** Pauses before the automatic retries of a Discover load that ended with nothing to show (offline, charts down). */
private val DISCOVER_RETRY_DELAYS_MS = longArrayOf(4_000L, 12_000L, 30_000L)

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

    /** What Deezer says about the artist matching the CURRENT query; the gate for [artistHit]. */
    private val artistSignal = MutableStateFlow<ArtistSignal?>(null)

    /** Raw lookup: the artist whose name matches what is typed. Not shown directly, see [artistHit]. */
    private val rawArtistHit = query
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

    /**
     * The highlighted artist card above the songs. A name match is not enough any more: [SearchRanking.showArtistCard]
     * also needs the artist to be well known (fan count) and/or backed by the song results, so an obscure account that
     * merely shares a name with the query ("qm") never gets the card.
     */
    val artistHit: StateFlow<DiscoverItem?> = combine(rawArtistHit, artistSignal, _state) { hit, signal, ui ->
        hit?.takeIf { SearchRanking.showArtistCard(ui.query, it.title, signal, ui.tracks) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var discoverJob: Job? = null

    private fun loadDiscover() {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            var autoRetries = 0
            while (true) {
                try {
                    discoverRepository.discoverFlow().collect { _discover.value = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // handled below
                }
                // Never leave the UI on the skeleton forever: surface a retryable error unless something is already shown.
                val now = _discover.value
                if (now.tabs.isEmpty() && (now.isLoading || now.error == null)) {
                    _discover.value = DiscoverState(isLoading = false, error = DISCOVER_ERROR)
                }
                if (_discover.value.tabs.isNotEmpty() || autoRetries >= DISCOVER_RETRY_DELAYS_MS.size) break
                // Still nothing: try again on its own (the network often comes back a moment later). The error stays up
                // while waiting, then the skeleton shows while the retry runs. The "Try again" button and pull-to-refresh
                // restart this whole loop.
                delay(DISCOVER_RETRY_DELAYS_MS[autoRetries++])
                _discover.value = DiscoverState()
            }
        }
    }

    /** Pull-to-refresh / "Try again": drops the in-memory copy and loads Discover from scratch (cancels a load still running). */
    fun refreshDiscover() {
        discoverRepository.clearCache()
        // From the error screen show the skeleton at once, so the tap visibly does something.
        _discover.update { if (it.tabs.isEmpty()) DiscoverState() else it }
        loadDiscover()
    }

    init {
        loadDiscover()
        viewModelScope.launch {
            // No distinctUntilChanged: updateQuery marks the state as loading on every change, so a search must always
            // follow (typing "ab" -> "abc" -> "ab" inside the debounce would otherwise leave it loading forever).
            query.debounce(250).flatMapLatest { value ->
                if (value.trim().length < MIN_QUERY_LENGTH) flowOf(SearchResult(emptyList(), true) to false)
                else repository.search(value)
                    .map { it to false }
                    .catch { emit(SearchResult(emptyList(), true) to true) }
            }.collect { (result, failed) ->
                // A result for an older query can land after the box was cleared; never show it.
                val stillSearching = query.value.trim().length >= MIN_QUERY_LENGTH
                val tracks = if (stillSearching) result.tracks else emptyList()
                artistSignal.value = if (stillSearching) result.artist else null
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
        val changed = query.value != value
        query.value = value
        if (value.isBlank()) artistSignal.value = null
        val searching = value.trim().length >= MIN_QUERY_LENGTH
        // Atomic update: the result collector writes to the same state from another coroutine.
        // Loading starts NOW, not when the debounced request begins: otherwise the screen sat "settled and empty" for a
        // moment and flashed "No results" before the real results arrived.
        _state.update {
            if (value.isBlank()) SearchUiState()
            else it.copy(query = value, error = null, isLoading = if (searching) changed || it.isLoading else false)
        }
    }
}