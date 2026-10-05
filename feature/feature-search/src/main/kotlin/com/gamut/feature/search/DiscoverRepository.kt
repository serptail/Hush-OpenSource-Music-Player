package com.gamut.feature.search

import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.AppleChartItem
import com.gamut.core.network.AppleChartsApi
import com.gamut.core.network.ItunesApi
import java.time.LocalDate
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoverState(
    val albums: List<DiscoverItem> = emptyList(),
    val artists: List<DiscoverItem> = emptyList(),
    val tabs: List<DiscoverTab> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** [songs] is the complete catalogue; [popular] is only the short list shown on Overview. */
data class ArtistDetails(
    val id: Long,
    val name: String,
    val imageUrl: String?,
    val popular: List<Track>,
    val songs: List<Track>,
    val albums: List<DiscoverItem>,
    val latestSongs: List<Track> = emptyList(),
    val listeners: String = "",
    /** False while only the fast overview is loaded; the Songs / Latest tabs show a spinner until it flips. */
    val songsLoaded: Boolean = true,
)

data class AlbumDetails(
    val id: String,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val tracks: List<Track>,
)

/** Everything here is keyless Apple: RSS charts (marketingtools) + iTunes lookup/search. No server needed. */
@Singleton
class DiscoverRepository @Inject constructor(
    private val apple: AppleChartsApi,
    private val itunes: ItunesApi,
    private val catalog: AppleCatalogRepository,
    private val diskCache: DiscoverDiskCache,
) {
    @Volatile private var cachedDiscover: DiscoverState? = null
    // Written from several coroutines (album screens opening while Discover loads): must be thread-safe.
    private val albumCache = ConcurrentHashMap<String, AlbumDetails>()

    /** Like runCatching, but never swallows cancellation (runCatching would eat timeouts and scope cancels). */
    private inline fun <T> safe(block: () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    /**
     * Emits Discover in stages: the charts as soon as they arrive (that is all the loading screen waits for), then
     * the artist portraits one by one in chart order. A finished load is cached for the rest of the process.
     */
    fun discoverFlow(): Flow<DiscoverState> = channelFlow {
        cachedDiscover?.let { send(it.copy(isLoading = false)); return@channelFlow }
        // Last session's Discover first: the loading screen can end at once, fresh data replaces it when it lands.
        val stale = diskCache.read()
        if (stale != null) send(stale)
        val (songs, albums) = coroutineScope {
            val songsJob = async {
                withTimeoutOrNull(CHART_TIMEOUT_MS) {
                    safe { apple.topSongs(100).feed.results }.getOrDefault(emptyList())
                }.orEmpty()
            }
            val albumsJob = async {
                withTimeoutOrNull(CHART_TIMEOUT_MS) {
                    safe { apple.topAlbums(10).feed.results.take(10) }.getOrDefault(emptyList())
                }.orEmpty()
            }
            songsJob.await() to albumsJob.await()
        }
        if (songs.isEmpty() && albums.isEmpty()) {
            // Offline or charts down: keep showing last session's Discover rather than replacing it with an error.
            if (stale == null) send(DiscoverState(isLoading = false, error = "Discover is unavailable"))
            return@channelFlow
        }
        val latest = latestFrom(songs)
        val tabs = buildList {
            add(DiscoverTab("Top 10", "Apple Music top songs", songs.take(10).map { it.toDiscoverItem() }))
            if (latest.isNotEmpty()) add(DiscoverTab("Latest", "New on the charts", latest.map { it.toDiscoverItem() }))
            add(DiscoverTab("Albums", "Apple Music top albums", albums.map { it.toDiscoverAlbum() }))
        }
        // With a stale copy on screen its portraits stay until the fresh ones are all in (one swap, no shrinking row).
        val progressive = stale == null || stale.artists.isEmpty()
        val base = DiscoverState(
            albums = albums.map { it.toDiscoverAlbum() },
            artists = if (progressive) emptyList() else stale!!.artists,
            tabs = tabs,
            isLoading = false,
        )
        send(base)

        // Chart items carry Apple's artistId, so there is no name matching and no namesake problem.
        val ids = songs.mapNotNull { item -> item.artistId?.toLongOrNull()?.let { it to item.artistName } }
            .distinctBy { it.first }.take(10)
        val slots = arrayOfNulls<DiscoverItem>(ids.size)
        val lock = Mutex()
        val gate = Semaphore(4)
        withTimeoutOrNull(ARTIST_IMAGES_TIMEOUT_MS) {
            coroutineScope {
                ids.forEachIndexed { index, (id, name) ->
                    launch {
                        val card = gate.withPermit { catalog.artistCard(id, name) } ?: return@launch
                        lock.withLock {
                            slots[index] = card
                            if (progressive) send(base.copy(artists = slots.filterNotNull())) // chart order is kept
                        }
                    }
                }
            }
        }
        val artists = slots.filterNotNull()
        val finalState = if (artists.isNotEmpty()) base.copy(artists = artists) else base
        if (!progressive && artists.isNotEmpty()) send(finalState)
        if (artists.isNotEmpty()) {
            cachedDiscover = finalState
            diskCache.write(finalState)
        }
    }

    /** Newest releases from the chart, strictly by release date -- never a copy of Top 10. */
    private fun latestFrom(chart: List<AppleChartItem>): List<AppleChartItem> {
        val dated = chart.filter { !it.releaseDate.isNullOrBlank() }.sortedByDescending { it.releaseDate }
        fun within(days: Long) = LocalDate.now().minusDays(days).toString().let { cut -> dated.filter { it.releaseDate.orEmpty() >= cut } }
        val recent = within(60).takeIf { it.size >= 5 } ?: within(180)
        return recent.take(10)
    }

    suspend fun findArtistId(name: String): Long? = catalog.findArtistId(name)

    suspend fun artistOverview(id: Long): ArtistDetails? = catalog.artistOverview(id)

    suspend fun artistSongs(base: ArtistDetails): ArtistDetails = catalog.artistSongs(base)

    suspend fun artistPhoto(base: ArtistDetails): String? =
        catalog.artistPhoto(base.id, base.name, base.albums.map { it.title }.take(40))

    /** Verified artist photo for an album's artist, or null (never a namesake's). */
    suspend fun artistPhotoFor(name: String, albumTitle: String): String? = catalog.artistPhotoFor(name, albumTitle)

    suspend fun artistHit(query: String): DiscoverItem? = catalog.searchArtistCard(query)

    suspend fun album(item: DiscoverItem): AlbumDetails? {
        albumCache[item.id]?.let { return it }
        if (!item.id.startsWith("apple-album:")) return null
        return withTimeoutOrNull(ALBUM_TIMEOUT_MS) {
            safe {
                val rows = itunes.lookup(item.id.removePrefix("apple-album:")).body()?.results.orEmpty()
                val tracks = rows.filter { it.kind == "song" && it.trackName != null }.map {
                    Track(
                        isrc = "apple:${it.trackId}",
                        title = it.trackName.orEmpty(),
                        artist = it.artistName ?: item.subtitle,
                        album = item.title,
                        durationMs = it.trackTimeMillis ?: 0L,
                        albumArtUrl = item.imageUrl,
                        source = TrackSource.ITUNES,
                    )
                }
                if (tracks.isEmpty()) null else AlbumDetails(item.id, item.title, tracks.first().artist.ifBlank { item.subtitle }, item.imageUrl, tracks)
            }.getOrNull()
        }?.also { albumCache[item.id] = it }
    }

    private fun AppleChartItem.toDiscoverItem() = DiscoverItem(
        id = "apple-track:$id", title = name, subtitle = artistName, imageUrl = artworkUrl100.big(), track = toTrack(),
    )

    private fun AppleChartItem.toTrack() = Track(
        isrc = "apple:$id", title = name, artist = artistName, album = null,
        durationMs = 0L, albumArtUrl = artworkUrl100.big(), source = TrackSource.ITUNES,
    )

    private fun AppleChartItem.toDiscoverAlbum() = DiscoverItem(
        id = "apple-album:$id", title = name, subtitle = artistName, imageUrl = artworkUrl100.big(),
    )

    private companion object {
        const val CHART_TIMEOUT_MS = 6_000L
        const val ARTIST_IMAGES_TIMEOUT_MS = 6_000L
        const val ALBUM_TIMEOUT_MS = 10_000L
    }
}