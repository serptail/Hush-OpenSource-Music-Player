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

/** State of the lazily loaded part of the Discover feed (see [SearchViewModel.loadMoreFeed]). */
private data class FeedTail(
    val sections: List<FeedSection> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val failed: Boolean = false,
)

/** Lazily loaded sections sort after the head's BOTTOM sections (their orders are 30 and 31). */
private const val FEED_TAIL_ORDER_BASE = 100

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
    /** What the repository's flow produced: charts, artists, albums and the head of the feed. Replaced as a whole on each emission. */
    private val _discover = MutableStateFlow(DiscoverState())

    /**
     * The endless part of the feed. It lives here, not in [_discover], because the repository re-emits the whole state many
     * times while Billboard resolves and would wipe sections the user has already scrolled to.
     */
    private val _tail = MutableStateFlow(FeedTail())
    private var moreJob: Job? = null
    private val consumedFeedIds = HashSet<String>()

    /** The screen's Discover: the repository state with the lazily loaded sections appended. */
    val discover: StateFlow<DiscoverState> = combine(_discover, _tail) { base, tail ->
        if (base.tabs.isEmpty()) base
        else base.copy(
            feed = base.feed + tail.sections,
            feedLoadingMore = tail.loading,
            feedExhausted = tail.exhausted,
            feedFailed = tail.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoverState())

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
        // A new mix: forget the endless part too (clearCache also changed the seed, so the genres come in a new order).
        moreJob?.cancel()
        consumedFeedIds.clear()
        _tail.value = FeedTail()
        // From the error screen show the skeleton at once, so the tap visibly does something.
        _discover.update { if (it.tabs.isEmpty()) DiscoverState() else it }
        loadDiscover()
    }

    /**
     * Loads the next section of the endless feed. Called by the footer whenever it scrolls into view; a call while one is
     * running (or after the end) does nothing.
     */
    fun loadMoreFeed() {
        val tail = _tail.value
        if (tail.loading || tail.exhausted || _discover.value.tabs.isEmpty()) return
        moreJob = viewModelScope.launch {
            _tail.update { it.copy(loading = true, failed = false) }
            val result = try {
                discoverRepository.moreFeed(consumedFeedIds.toSet(), FEED_TAIL_ORDER_BASE + _tail.value.sections.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                _tail.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            consumedFeedIds += result.consumed
            _tail.update {
                it.copy(
                    sections = it.sections + listOfNotNull(result.section),
                    loading = false,
                    exhausted = result.exhausted,
                    failed = result.failed,
                )
            }
        }
    }

    /** The Apple artist id for a feed card (Deezer-only artists are matched by name), or null. */
    suspend fun artistIdFor(item: DiscoverItem): Long? = try {
        discoverRepository.artistIdFor(item)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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