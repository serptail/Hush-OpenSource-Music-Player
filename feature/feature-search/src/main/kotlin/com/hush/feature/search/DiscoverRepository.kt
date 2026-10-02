package com.hush.feature.search

import com.hush.core.model.Track
import com.hush.core.model.TrackSource
import com.hush.core.network.AppleChartItem
import com.hush.core.network.AppleChartsApi
import com.hush.core.network.ItunesApi
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
) {
    private var cachedDiscover: DiscoverState? = null
    private val albumCache = mutableMapOf<String, AlbumDetails>()

    suspend fun load(): DiscoverState {
        cachedDiscover?.let { return it.copy(isLoading = false) }
        val result = coroutineScope {
            val songsJob = async {
                withTimeoutOrNull(CHART_TIMEOUT_MS) {
                    runCatching { apple.topSongs(100).feed.results }.getOrDefault(emptyList())
                }.orEmpty()
            }
            val albumsJob = async {
                withTimeoutOrNull(CHART_TIMEOUT_MS) {
                    runCatching { apple.topAlbums(10).feed.results.take(10) }.getOrDefault(emptyList())
                }.orEmpty()
            }
            val songs = songsJob.await()
            val albums = albumsJob.await()
            if (songs.isEmpty() && albums.isEmpty()) {
                return@coroutineScope DiscoverState(isLoading = false, error = "Discover is unavailable")
            }
            val latest = latestFrom(songs)
            val tabs = buildList {
                add(DiscoverTab("Top 10", "Apple Music top songs", songs.take(10).map { it.toDiscoverItem() }))
                if (latest.isNotEmpty()) add(DiscoverTab("Latest", "New on the charts", latest.map { it.toDiscoverItem() }))
                add(DiscoverTab("Albums", "Apple Music top albums", albums.map { it.toDiscoverAlbum() }))
            }
            DiscoverState(
                albums = albums.map { it.toDiscoverAlbum() },
                artists = resolveChartArtists(songs),
                tabs = tabs,
                isLoading = false,
            )
        }
        if (result.error == null) cachedDiscover = result
        return result
    }

    /** Newest releases from the chart, strictly by release date -- never a copy of Top 10. */
    private fun latestFrom(chart: List<AppleChartItem>): List<AppleChartItem> {
        val dated = chart.filter { !it.releaseDate.isNullOrBlank() }.sortedByDescending { it.releaseDate }
        fun within(days: Long) = LocalDate.now().minusDays(days).toString().let { cut -> dated.filter { it.releaseDate.orEmpty() >= cut } }
        val recent = within(60).takeIf { it.size >= 5 } ?: within(180)
        return recent.take(10)
    }

    /** Chart items carry Apple's artistId, so there is no name matching and no namesake problem. */
    private suspend fun resolveChartArtists(songs: List<AppleChartItem>): List<DiscoverItem> {
        val ids = songs.mapNotNull { item -> item.artistId?.toLongOrNull()?.let { it to item.artistName } }
            .distinctBy { it.first }.take(10)
        return withTimeoutOrNull(ARTIST_IMAGES_TIMEOUT_MS) {
            coroutineScope { ids.map { (id, name) -> async { catalog.artistCard(id, name) } }.awaitAll().filterNotNull() }
        }.orEmpty()
    }

    suspend fun findArtistId(name: String): Long? = catalog.findArtistId(name)

    suspend fun artist(id: Long): ArtistDetails? = catalog.artist(id)

    /** Verified artist photo for an album's artist, or null (never a namesake's). */
    suspend fun artistPhotoFor(name: String, albumTitle: String): String? = catalog.artistPhotoFor(name, albumTitle)

    suspend fun artistHit(query: String): DiscoverItem? = catalog.searchArtistCard(query)

    suspend fun album(item: DiscoverItem): AlbumDetails? {
        albumCache[item.id]?.let { return it }
        if (!item.id.startsWith("apple-album:")) return null
        return withTimeoutOrNull(ALBUM_TIMEOUT_MS) {
            runCatching {
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