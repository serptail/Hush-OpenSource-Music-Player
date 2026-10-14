package com.gamut.feature.search

import android.util.Log
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
import kotlinx.coroutines.delay
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
    /** Related artists for the "Similar artists" row; filled in the background after the page opens. */
    val similar: List<DiscoverItem> = emptyList(),
)

data class AlbumDetails(
    val id: String,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val tracks: List<Track>,
)

/**
 * Apple RSS charts + iTunes lookup, blended with the Billboard Hot 100 (resolved through Deezer).
 * No API keys, no server.
 */
@Singleton
class DiscoverRepository @Inject constructor(
    private val apple: AppleChartsApi,
    private val itunes: ItunesApi,
    private val catalog: AppleCatalogRepository,
    private val diskCache: DiscoverDiskCache,
    private val billboard: BillboardRepository,
) {
    @Volatile private var cachedDiscover: DiscoverState? = null
    // Written from several coroutines (album screens opening while Discover loads): must be thread-safe.
    private val albumCache = ConcurrentHashMap<String, AlbumDetails>()

    /** Drops everything held in memory (the disk copy is [DiscoverDiskCache]'s). The next Discover load fetches fresh data. */
    fun clearCache() {
        cachedDiscover = null
        albumCache.clear()
        billboard.clearCache()
    }

    /** Like runCatching, but never swallows cancellation (runCatching would eat timeouts and scope cancels). */
    private inline fun <T> safe(block: () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    /**
     * A chart request that is retried: a timeout, an exception OR an empty answer counts as a miss (Apple/Deezer fail
     * transiently). Without this one bad request made a whole section (albums, artists) vanish for the session.
     */
    private suspend fun <T> fetchList(label: String, timeoutMs: Long, attempts: Int = 3, block: suspend () -> List<T>): List<T> {
        repeat(attempts) { n ->
            // The first attempt gets the full budget (cold radio, DNS, TLS on a slow network); retries only cover a hiccup.
            val budget = if (n == 0) timeoutMs else timeoutMs * 2 / 3
            var failure: Throwable? = null
            val rows = withTimeoutOrNull(budget) { safe { block() }.onFailure { failure = it }.getOrNull() }
            if (!rows.isNullOrEmpty()) return rows
            // Say WHY it missed: timeout, exception (network / parsing) or an empty answer. Filter logcat on TAG.
            val why = when {
                failure != null -> "failed: $failure"
                rows == null -> "timed out after ${budget}ms"
                else -> "returned an empty list"
            }
            logW("$label attempt ${n + 1}/$attempts $why")
            if (n < attempts - 1) delay(500L * (n + 1))
        }
        return emptyList()
    }

    private suspend fun <T : Any> fetchOne(attempts: Int = 3, label: String = "fetchOne", block: suspend () -> T?): T? {
        var last: Throwable? = null
        repeat(attempts) { n ->
            val result = safe { block() }
            result.getOrNull()?.let { return it }
            result.exceptionOrNull()?.let { last = it }
            if (n < attempts - 1) delay(600L * (n + 1))
        }
        logW("$label gave up after $attempts attempts: ${last ?: "no result"}")
        return null
    }

    /** Logging must never be able to break a load (Log is not mocked in plain unit tests). */
    private fun logW(message: String) {
        try { Log.w(TAG, message) } catch (_: Throwable) { }
    }

    /**
     * Emits Discover in stages: the charts as soon as they arrive (that is all the loading screen waits for), then
     * artist portraits and Billboard resolutions (artwork + playable tracks) as they land. A finished load is cached
     * for the rest of the process and on disk.
     */
    fun discoverFlow(): Flow<DiscoverState> = channelFlow {
        cachedDiscover?.let { send(it.copy(isLoading = false)); return@channelFlow }
        // Last session's Discover first: the loading screen can end at once, fresh data replaces it when it lands.
        val stale = diskCache.read()
        if (stale != null) send(stale)
        val (songs, rawAlbums, bb) = coroutineScope {
            val songsJob = async { fetchList("apple top songs", CHART_TIMEOUT_MS) { apple.topSongs(100).feed.results } }
            val albumsJob = async { fetchList("apple top albums", CHART_TIMEOUT_MS) { apple.topAlbums(20).feed.results.take(20) } }
            // chart() never throws; a Billboard outage just means Discover stays Apple-only.
            val billboardJob = async { withTimeoutOrNull(CHART_TIMEOUT_MS) { billboard.chart() }.orEmpty() }
            Triple(songsJob.await(), albumsJob.await(), billboardJob.await())
        }
        if (songs.isEmpty() && rawAlbums.isEmpty()) {
            // Offline or charts down: keep showing last session's Discover rather than replacing it with an error.
            logW("charts unavailable (songs and albums both empty); stale copy " + if (stale != null) "kept on screen" else "missing -> error state")
            if (stale == null) send(DiscoverState(isLoading = false, error = "Discover is unavailable. Check your connection and try again."))
            return@channelFlow
        }
        val albums = boostAlbums(rawAlbums, bb)
        val latest = latestFrom(songs)
        val resolved = HashMap<String, Track>() // Billboard key -> playable track; guarded by [lock]

        fun buildTabs(): List<DiscoverTab> = buildList {
            blendTrending(songs, bb, resolved).takeIf { it.isNotEmpty() }?.let { add(DiscoverTab("Trending", "Trending now", it)) }
            if (bb.isNotEmpty()) {
                add(DiscoverTab("Billboard", "Billboard Hot 100", bb.take(BILLBOARD_TAB_LIMIT).map { it.toDiscoverItem(resolved[it.key]) }))
            }
            if (latest.isNotEmpty()) add(DiscoverTab("Latest", "New on the charts", latest.map { it.toDiscoverItem().copy(meta = releasedAgo(it.releaseDate)) }))
            if (albums.isNotEmpty()) add(DiscoverTab("Albums", "Apple Music top albums", albums.map { it.toDiscoverAlbum() }))
        }

        // Chart items carry Apple's artistId, so there is no name matching and no namesake problem.
        val seeds = songs.mapNotNull { item -> item.artistId?.toLongOrNull()?.let { Triple(it, item.artistName, item) } }
            .distinctBy { it.first }.take(10)
        val slots = arrayOfNulls<DiscoverItem>(seeds.size)
        // Until a real portrait lands (or if it never does) the circle shows the song's cover, so the row is never empty.
        fun placeholder(seed: Triple<Long, String, AppleChartItem>) = DiscoverItem(
            id = "artist-seed:${seed.first}", title = seed.second, subtitle = "", imageUrl = seed.third.artworkUrl100.big(), artistId = seed.first,
        )
        fun shown(): List<DiscoverItem> = seeds.mapIndexed { i, s -> slots[i] ?: placeholder(s) }

        // With a stale copy on screen its portraits stay until the fresh ones are all in (one swap, no shrinking row).
        val progressive = stale == null || stale.artists.isEmpty()
        var artistsNow: List<DiscoverItem> = if (progressive) shown() else stale!!.artists
        // The row stays at 10; the Albums tab has all 20. If the albums request failed, last session's row stays.
        val albumRow = if (albums.isNotEmpty()) albums.take(10).map { it.toDiscoverAlbum() } else stale?.albums.orEmpty()
        var tabsNow = buildTabs()
        fun state() = DiscoverState(albums = albumRow, artists = artistsNow, tabs = tabsNow, isLoading = false)
        send(state())

        val lock = Mutex()
        val gate = Semaphore(4)
        coroutineScope {
            launch {
                withTimeoutOrNull(ARTIST_IMAGES_TIMEOUT_MS) {
                    coroutineScope {
                        seeds.forEachIndexed { index, (id, name, _) ->
                            launch {
                                val card = gate.withPermit { fetchOne(label = "artist card $name") { catalog.artistCard(id, name) } } ?: return@launch
                                lock.withLock {
                                    slots[index] = card
                                    if (progressive) { artistsNow = shown(); send(state()) } // chart order is kept
                                }
                            }
                        }
                    }
                }
            }
            if (bb.isNotEmpty()) launch {
                var lastPushMs = 0L
                withTimeoutOrNull(BILLBOARD_RESOLVE_TIMEOUT_MS) {
                    billboard.resolve(bb.take(BILLBOARD_TAB_LIMIT)) { entry, track ->
                        lock.withLock {
                            resolved[entry.key] = track
                            val now = System.currentTimeMillis()
                            if (now - lastPushMs >= PUSH_THROTTLE_MS) { // throttled: 100 tracks != 100 emissions
                                lastPushMs = now
                                tabsNow = buildTabs()
                                send(state())
                            }
                        }
                    }
                }
                lock.withLock { tabsNow = buildTabs(); send(state()) } // whatever resolved before the timeout
            }
        }
        val portraitsComplete = seeds.isNotEmpty() && slots.all { it != null }
        lock.withLock {
            // A partial refresh never replaces a complete stale row (it would shrink); a fresh row always wins when complete.
            if (portraitsComplete || progressive) artistsNow = shown()
            send(state())
        }
        // Only a COMPLETE load is remembered. A half-loaded one (missing portraits, albums or Billboard) would otherwise be
        // served as-is for the rest of the process and the missing section would never come back.
        val finalState = state()
        if (portraitsComplete && rawAlbums.isNotEmpty() && bb.isNotEmpty()) {
            cachedDiscover = finalState
            safe { diskCache.write(finalState) }
        } else if (finalState.tabs.isNotEmpty()) {
            // The disk copy is only the "something is better than nothing" fallback for the next cold start, so a PARTIAL
            // load is worth saving too (before, a slow Billboard or one missing portrait meant no fallback at all).
            // Placeholder artists are never persisted: they would make a later load think the row is already complete.
            val partial = finalState.copy(artists = if (portraitsComplete) finalState.artists else stale?.artists.orEmpty())
            // ...but it must never replace a better saved copy.
            if (stale == null || partial.quality() >= stale.quality()) safe { diskCache.write(partial) }
        }
    }

    /** How much a saved state is worth: more tabs, albums and real portraits (not placeholders) beat fewer. */
    private fun DiscoverState.quality(): Int =
        tabs.size * 10 + (if (albums.isNotEmpty()) 5 else 0) + artists.count { !it.id.startsWith("artist-seed:") }

    /**
     * Trending = Apple and Billboard blended 50/50 by chart position (101 - rank). A song on both charts scores up to
     * 100, a song on one chart up to 50. Billboard-only songs join once they are resolved (they need artwork).
     */
    private fun blendTrending(apple: List<AppleChartItem>, bb: List<BillboardEntry>, resolved: Map<String, Track>): List<DiscoverItem> {
        val bbByKey = bb.associateBy { it.key }
        val seen = HashSet<String>()
        val scored = ArrayList<Pair<Double, DiscoverItem>>()
        apple.take(100).forEachIndexed { i, item ->
            val key = chartKey(item.name, item.artistName)
            seen += key
            val billboardPoints = bbByKey[key]?.let { BLEND_WEIGHT * (101 - it.rank) } ?: 0.0
            scored += (BLEND_WEIGHT * (100 - i) + billboardPoints) to item.toDiscoverItem()
        }
        bb.forEach { entry ->
            if (entry.key !in seen) resolved[entry.key]?.let { scored += BLEND_WEIGHT * (101 - entry.rank) to entry.toDiscoverItem(it) }
        }
        return scored.sortedByDescending { it.first }.take(50).map { it.second }
    }

    /** Apple albums by chart position, plus a bonus when the artist has songs on the Hot 100 (capped). */
    private fun boostAlbums(albums: List<AppleChartItem>, bb: List<BillboardEntry>): List<AppleChartItem> {
        if (bb.isEmpty()) return albums
        val heat = HashMap<String, Double>()
        bb.forEach { heat.merge(it.artistKey, (101 - it.rank) / 4.0, Double::plus) }
        return albums.withIndex()
            .sortedByDescending { (i, a) -> (albums.size - i) + (heat[artistKey(a.artistName)] ?: 0.0).coerceAtMost(ALBUM_BOOST_CAP) }
            .map { it.value }
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

    /** Artists related to this one (Deezer), already verified against the right Deezer artist. Empty when unknown. */
    suspend fun similarArtists(base: ArtistDetails): List<DiscoverItem> = catalog.similarArtists(base)

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
        const val TAG = "DiscoverRepo"
        const val CHART_TIMEOUT_MS = 10_000L // first attempt; retries get 2/3 of it. 6 s was too tight for a cold mobile connection
        const val ARTIST_IMAGES_TIMEOUT_MS = 20_000L // each portrait is retried up to 3x behind a 4-wide gate
        const val ALBUM_TIMEOUT_MS = 10_000L
        const val BILLBOARD_RESOLVE_TIMEOUT_MS = 45_000L // 100 entries; Deezer is rate-limited to ~8 req/s in NetworkModule
        const val BILLBOARD_TAB_LIMIT = 100 // the full Hot 100; tab rows are separate lazy items now
        const val PUSH_THROTTLE_MS = 500L
        const val BLEND_WEIGHT = 0.5
        const val ALBUM_BOOST_CAP = 30.0
    }
}
/** "Today", "Yesterday", "3 days ago", "2 weeks ago", else "Mar 2026". Null when the date is missing or unparsable. */
private fun releasedAgo(releaseDate: String?): String? {
    val date = runCatching { LocalDate.parse(releaseDate.orEmpty().take(10)) }.getOrNull() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, LocalDate.now())
    return when {
        days < 0 -> null
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "1 week ago"
        days < 31 -> "${days / 7} weeks ago"
        else -> date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + date.year
    }
}