package com.gamut.feature.search

import com.gamut.core.database.RecentDao
import com.gamut.core.database.toDomain
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.DeezerArtistDto
import com.gamut.core.network.DeezerChartsApi
import com.gamut.core.network.DeezerTrackDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Moods a station can be started from. Each is played from a public playlist found by [query]. */
enum class Mood(
    val slug: String,
    val label: String,
    internal val query: String,
    val from: Long,
    val to: Long,
) {
    ENERGY("energy", "Energy", "energy boost", 0xFFFF7A45, 0xFFFF3D6E),
    CHILL("chill", "Chill", "chill vibes", 0xFF4FB7C9, 0xFF3B6FD4),
    FOCUS("focus", "Focus", "focus flow", 0xFF6A7BD6, 0xFF3B3F8F),
    FEEL_GOOD("feel-good", "Feel good", "feel good hits", 0xFFFFC145, 0xFFFF8A3D),
    LOVE("love", "Love", "love songs", 0xFFFF6B9A, 0xFFC43A7A),
    BLUE("blue", "Feeling blue", "sad songs", 0xFF5A6B8C, 0xFF2E3B5B),
    PARTY("party", "Party", "party hits", 0xFFB45CFF, 0xFFFF4FA3),
    WORKOUT("workout", "Workout", "workout motivation", 0xFFE5484D, 0xFF8E1F2D),
    LATE_NIGHT("late-night", "Late night", "late night drive", 0xFF2D2A6E, 0xFF0F0E2B),
    SLEEP("sleep", "Sleep", "sleep calm", 0xFF3A4F7A, 0xFF1A2440),
    COMMUTE("commute", "On the road", "road trip", 0xFF3DA57A, 0xFF1D5E5B),
    ;

    companion object {
        fun bySlug(slug: String): Mood? = values().firstOrNull { it.slug == slug }

        /** The moods that suit the hour, best first. */
        fun suitedFor(hour: Int): List<Mood> =
            when (hour) {
                in 5..8 -> listOf(ENERGY, FEEL_GOOD, FOCUS)
                in 9..11 -> listOf(FOCUS, FEEL_GOOD, ENERGY)
                in 12..16 -> listOf(FEEL_GOOD, FOCUS, ENERGY, CHILL)
                in 17..19 -> listOf(WORKOUT, ENERGY, FEEL_GOOD, COMMUTE)
                in 20..22 -> listOf(LOVE, CHILL, PARTY, BLUE)
                else -> listOf(LATE_NIGHT, SLEEP, CHILL, BLUE)
            }

        /** Every mood, those that suit the hour first. */
        fun orderedFor(hour: Int): List<Mood> {
            val suited = suitedFor(hour)
            return suited + values().filter { it !in suited }
        }
    }
}

/**
 * Builds the personal side of Discover from the listener's taste and the public catalog: the Top picks row,
 * moods, new artists reached through the related-artist graph, and the queue behind every station.
 *
 * A station is not queued until it is tapped. Its card only carries an id (`station:<kind>:<arg>`), so the row costs
 * nothing beyond the requests that built it.
 */
@Singleton
class RecommendationEngine
    @Inject
    constructor(
        private val api: DeezerChartsApi,
        private val taste: TasteRepository,
        private val recents: RecentDao,
    ) {
        private data class ArtistRef(
            val id: Long,
            val name: String,
            val picture: String?,
        )

        private data class Seed(
            val taste: TasteArtist,
            val artist: ArtistRef,
        )

        private val artistRefs = ConcurrentHashMap<String, ArtistRef>()
        private val topCache = TtlCache<Pair<Long, Int>, List<Track>>(CACHE_TTL_MS)
        private val relatedCache = TtlCache<Long, List<DeezerArtistDto>>(CACHE_TTL_MS)

        /** Drops what was learned about the listener and what was fetched for them, so the next build starts over. */
        fun clearCache() {
            taste.clear()
            topCache.clear()
            relatedCache.clear()
        }

        // ---- Sections ------------------------------------------------------------------------------------------------

        /** The lead row: the newest release from a favourite artist, stations and a mood, a different mix each build. */
        suspend fun topPicks(): FeedSection? =
            coroutineScope {
                val ranked = taste.artists()
                val seeds = resolve(TasteRepository.weightedSample(ranked.take(SEED_POOL), SEED_COUNT, Random.Default))
                val cards = ArrayList<DiscoverItem>()

                val latest =
                    seeds
                        .map { async { latestRelease(it) } }
                        .awaitAll()
                        .filterNotNull()
                        .maxByOrNull { it.second }
                        ?.first

                val rest = ArrayList<DiscoverItem>()
                if (seeds.size >= 2) {
                    val ids = seeds.joinToString(",") { it.artist.id.toString() }
                    rest +=
                        DiscoverItem(
                            id = "station:mix:$ids",
                            title = "Your mix",
                            subtitle = joinNames(seeds.map { it.artist.name }),
                            imageUrl = seeds.first().artist.picture.orEmpty(),
                            artistId = seeds.first().artist.id,
                            meta = "Made for you",
                        )
                }
                seeds.firstOrNull()?.let { seed ->
                    rest +=
                        DiscoverItem(
                            id = "station:artist:${seed.artist.id}",
                            title = "${seed.artist.name} Radio",
                            subtitle = "${seed.artist.name} and artists like them",
                            imageUrl = seed.artist.picture.orEmpty(),
                            artistId = seed.artist.id,
                            meta = "Artist radio",
                        )
                }
                if (seeds.size >= 2) {
                    rest +=
                        DiscoverItem(
                            id = "station:discover:${seeds.joinToString(",") { it.artist.id.toString() }}",
                            title = "Discovery mix",
                            subtitle = "Artists you haven\u2019t played yet",
                            imageUrl = "",
                            meta = "Discovery",
                        )
                }
                val mood = pickMood()
                rest +=
                    DiscoverItem(
                        id = "station:mood:${mood.slug}",
                        title = mood.label,
                        subtitle = "For this time of day",
                        imageUrl = "",
                        meta = "Mood",
                    )
                if (seeds.isEmpty()) {
                    // No taste yet: the biggest songs and a couple of genres stand in until there is some.
                    rest +=
                        DiscoverItem(
                            id = "station:chart:0",
                            title = "Today\u2019s top hits",
                            subtitle = "The biggest songs right now",
                            imageUrl = "",
                            meta = "Charts",
                        )
                    STARTER_GENRES.shuffled().take(2).forEach { (id, name) ->
                        rest +=
                            DiscoverItem(
                                id = "station:chart:$id",
                                title = "$name mix",
                                subtitle = "What listeners play most",
                                imageUrl = "",
                                meta = "Genre",
                            )
                    }
                }

                latest?.let { cards += it }
                cards += rest.sortedBy { stationRank(it.id) }
                if (cards.size < 2) return@coroutineScope null
                FeedSection(
                    id = "picks:today",
                    kind = FeedKind.TOP_PICKS,
                    slot = FeedSlot.TOP,
                    order = -10,
                    title = if (ranked.isEmpty()) "Start here" else "Top picks for you",
                    subtitle = if (ranked.isEmpty()) "Stations to get you going" else "Based on what you play and follow",
                    items = cards,
                )
            }

        /** What was played lately, ready to pick back up. */
        suspend fun jumpBackIn(): FeedSection? {
            val tracks =
                attempt { recents.observeTracks(RECENT_SCAN).first().map { it.toDomain() } }
                    .orEmpty()
                    .distinctBy { it.isrc }
                    .take(RECENT_COUNT)
            if (tracks.size < 3) return null
            return FeedSection(
                id = "recent:again",
                kind = FeedKind.TRACK_RAILS,
                slot = FeedSlot.TOP,
                order = -5,
                title = "Jump back in",
                subtitle = "Pick up where you left off",
                items =
                    tracks.map {
                        DiscoverItem(
                            id = "recent:${it.isrc}",
                            title = it.title,
                            subtitle = it.artist,
                            imageUrl = it.albumArtUrl.orEmpty(),
                            track = it,
                        )
                    },
            )
        }

        /** Mood tiles, the ones that suit the hour first. No request: each station is built when tapped. */
        fun moods(now: LocalDateTime = LocalDateTime.now()): FeedSection =
            FeedSection(
                id = "moods:tiles",
                kind = FeedKind.MOODS,
                slot = FeedSlot.MIDDLE,
                order = 5,
                title = "Find your mood",
                subtitle = "Stations for how you feel",
                items = Mood.orderedFor(now.hour).map { DiscoverItem(id = "station:mood:${it.slug}", title = it.label, subtitle = "", imageUrl = "") },
            )

        /**
         * Artists the listener does not know yet. Each of their top artists lists the artists fans also play; one that
         * turns up under several of them, and high in each list, scores best.
         */
        suspend fun newArtistsForYou(): FeedSection? {
            val ranked = taste.artists()
            if (ranked.isEmpty()) return null
            val seeds = resolve(TasteRepository.weightedSample(ranked.take(SEED_POOL), GRAPH_SEEDS, Random.Default))
            if (seeds.isEmpty()) return null
            val known = ranked.mapTo(HashSet()) { it.key }
            val found = candidates(seeds, known).take(CAROUSEL_COUNT)
            if (found.size < 4) return null
            return FeedSection(
                id = "newartists:foryou",
                kind = FeedKind.ARTIST_CAROUSEL,
                slot = FeedSlot.MIDDLE,
                order = 12,
                title = "New artists for you",
                subtitle = "Because you like ${joinNames(seeds.take(2).map { it.artist.name })}",
                items =
                    found.map {
                        DiscoverItem(
                            id = "deezer-artist:${it.id}",
                            title = it.name,
                            subtitle = "",
                            imageUrl = (it.pictureBig ?: it.pictureXl).orEmpty(),
                            followers = it.fanCount,
                        )
                    },
            )
        }

        // ---- Stations ------------------------------------------------------------------------------------------------

        /** The queue behind a station card, empty when it cannot be built. */
        suspend fun stationQueue(item: DiscoverItem): List<Track> {
            val parts = item.id.removePrefix(StationId.PREFIX).split(':', limit = 2)
            val arg = parts.getOrNull(1).orEmpty()
            val ids = arg.split(',').mapNotNull { it.toLongOrNull() }
            return when (parts.first()) {
                "mix" -> mixQueue(ids)
                "artist" -> ids.firstOrNull()?.let { radioQueue(it) }.orEmpty()
                "discover" -> discoveryQueue(ids)
                "mood" -> Mood.bySlug(arg)?.let { moodQueue(it) }.orEmpty()
                "chart" -> chartQueue(arg.toIntOrNull() ?: 0)
                else -> emptyList()
            }
        }

        private suspend fun mixQueue(ids: List<Long>): List<Track> =
            coroutineScope {
                if (ids.isEmpty()) return@coroutineScope emptyList()
                val own = ids.map { async { top(it, 12) } }
                val alike =
                    ids.map { id ->
                        async { related(id).take(3).map { r -> async { top(r.id, 5) } }.awaitAll() }
                    }
                val lists = own.awaitAll() + alike.awaitAll().flatten()
                interleave(lists, QUEUE_SIZE)
            }

        private suspend fun radioQueue(id: Long): List<Track> =
            coroutineScope {
                val own = async { top(id, 12) }
                val alike = related(id).take(5).map { r -> async { top(r.id, 6) } }
                interleave(listOf(own.await()) + alike.awaitAll(), QUEUE_SIZE)
            }

        private suspend fun discoveryQueue(ids: List<Long>): List<Track> =
            coroutineScope {
                val ranked = taste.artists()
                val known = ranked.mapTo(HashSet()) { it.key }
                val seeds =
                    ranked
                        .filter { t -> artistRefs[t.key]?.id in ids }
                        .mapNotNull { t -> artistRefs[t.key]?.let { Seed(t, it) } }
                        .ifEmpty { ids.mapNotNull { id -> artistRefs.values.firstOrNull { it.id == id } }.map { Seed(TasteArtist(artistKey(it.name), it.name, 1.0, false), it) } }
                val found = candidates(seeds, known).take(8)
                interleave(found.map { a -> async { top(a.id, 5) } }.awaitAll(), QUEUE_SIZE)
            }

        private suspend fun moodQueue(mood: Mood): List<Track> {
            val lists = attempt { api.searchPlaylists(mood.query, 10).data }.orEmpty()
            val usable = lists.filter { it.trackCount in 20..300 }.ifEmpty { lists }
            val pick = usable.take(3).randomOrNull() ?: return emptyList()
            return attempt { api.playlistTracks(pick.id, QUEUE_SIZE).data }
                .orEmpty()
                .distinctBy { it.id }
                .map { it.toTrack() }
                .shuffled()
        }

        private suspend fun chartQueue(genre: Int): List<Track> =
            attempt { api.chartTracks(genre, QUEUE_SIZE).data }.orEmpty().distinctBy { it.id }.map { it.toTrack() }

        // ---- Building blocks -----------------------------------------------------------------------------------------

        private suspend fun resolve(artists: List<TasteArtist>): List<Seed> =
            coroutineScope {
                artists
                    .map { t -> async { artistRef(t.name)?.let { Seed(t, it) } } }
                    .awaitAll()
                    .filterNotNull()
                    .distinctBy { it.artist.id }
            }

        private suspend fun artistRef(name: String): ArtistRef? {
            val key = artistKey(name)
            artistRefs[key]?.let { return it }
            val hit =
                attempt { api.searchArtists(name, 5).data }
                    .orEmpty()
                    .filter { artistKey(it.name) == key }
                    .maxByOrNull { it.fanCount ?: 0L }
                    ?: return null
            return ArtistRef(hit.id, hit.name, hit.pictureBig ?: hit.pictureXl).also { artistRefs[key] = it }
        }

        private suspend fun candidates(
            seeds: List<Seed>,
            known: Set<String>,
        ): List<DeezerArtistDto> =
            coroutineScope {
                val lists = seeds.map { seed -> async { seed.taste to related(seed.artist.id) } }.awaitAll()
                val scores = HashMap<Long, Double>()
                val byId = HashMap<Long, DeezerArtistDto>()
                lists.forEach { (taste, related) ->
                    related.forEachIndexed { index, artist ->
                        if (artistKey(artist.name) in known) return@forEachIndexed
                        byId.putIfAbsent(artist.id, artist)
                        scores.merge(artist.id, taste.score * (1.0 - index.toDouble() / related.size.coerceAtLeast(1)), Double::plus)
                    }
                }
                scores.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }
            }

        /** Newest release from [seed] within the last half year, with its date, or null. */
        private suspend fun latestRelease(seed: Seed): Pair<DiscoverItem, String>? {
            val today = LocalDate.now()
            val newest =
                attempt { api.artistAlbums(seed.artist.id, 30).data }
                    .orEmpty()
                    .filter { !it.title.isNullOrBlank() && (it.coverXl ?: it.coverBig) != null }
                    .filter { val d = it.releaseDate.orEmpty().take(10); d.isNotEmpty() && d <= today.toString() && d >= today.minusDays(RECENT_RELEASE_DAYS).toString() }
                    .maxByOrNull { it.releaseDate.orEmpty() } ?: return null
            val ago = releasedAgo(newest.releaseDate)
            return DiscoverItem(
                id = FeedRepository.DEEZER_ALBUM_PREFIX + newest.id,
                title = newest.title.orEmpty(),
                subtitle = listOfNotNull(seed.artist.name, ago).joinToString(" \u00B7 "),
                imageUrl = (newest.coverXl ?: newest.coverBig).orEmpty(),
                meta = "Latest release",
            ) to newest.releaseDate.orEmpty()
        }

        private suspend fun top(
            artistId: Long,
            limit: Int,
        ): List<Track> =
            topCache.getOrLoad(artistId to limit) {
                attempt { api.artistTop(artistId, limit).data }?.map { it.toTrack() }?.takeIf { it.isNotEmpty() }
            }.orEmpty()

        private suspend fun related(artistId: Long): List<DeezerArtistDto> =
            relatedCache.getOrLoad(artistId) {
                attempt { api.relatedArtists(artistId, 20).data }?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }
            }.orEmpty()

        private fun pickMood(): Mood {
            val suited = Mood.suitedFor(LocalDateTime.now().hour)
            return if (Random.nextFloat() < 0.7f) suited.random() else Mood.values().random()
        }

        private fun joinNames(names: List<String>): String =
            when (names.size) {
                0 -> ""
                1 -> names[0]
                2 -> "${names[0]} and ${names[1]}"
                else -> "${names[0]}, ${names[1]} and more"
            }

        /**
         * One from each list in turn, so no single artist runs for long. Duplicates dropped, and no artist
         * appears more than [ARTIST_CAP] times however many lists carry them.
         */
        private fun interleave(
            lists: List<List<Track>>,
            limit: Int,
        ): List<Track> {
            val out = ArrayList<Track>()
            val seen = HashSet<String>()
            val perArtist = HashMap<String, Int>()
            var round = 0
            while (out.size < limit && lists.any { round < it.size }) {
                lists.forEach { list ->
                    val track = list.getOrNull(round) ?: return@forEach
                    val artist = artistKey(track.artist)
                    if (out.size >= limit || (perArtist[artist] ?: 0) >= ARTIST_CAP) return@forEach
                    if (seen.add(track.isrc)) {
                        out += track
                        perArtist.merge(artist, 1, Int::plus)
                    }
                }
                round++
            }
            return out
        }

        /** Where a station card sits in the Top picks row: made for you first, then moods, then discovery. */
        private fun stationRank(id: String): Int =
            when (StationId.kindOf(id)) {
                StationId.MIX -> 0
                StationId.ARTIST -> 1
                StationId.MOOD -> 2
                StationId.DISCOVER -> 3
                else -> 4
            }

        private suspend inline fun <T> attempt(crossinline block: suspend () -> T): T? =
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }

        private fun DeezerTrackDto.toTrack() =
            Track(
                isrc = "deezer:$id",
                title = listOfNotNull(titleShort ?: title, titleVersion?.takeIf { it.isNotBlank() }).joinToString(" "),
                artist = artist.name,
                album = album.title,
                durationMs = duration * 1_000L,
                albumArtUrl = album.coverBig ?: album.coverXl,
                source = TrackSource.ITUNES,
                isExplicit = explicitLyrics,
            )

        companion object {
            const val STATION_PREFIX = StationId.PREFIX

            private const val SEED_POOL = 10
            private const val SEED_COUNT = 3
            private const val GRAPH_SEEDS = 4
            private const val CAROUSEL_COUNT = 16
            private const val RECENT_SCAN = 40
            private const val RECENT_COUNT = 24
            private const val QUEUE_SIZE = 50
            private const val RECENT_RELEASE_DAYS = 180L
            private const val REQUEST_TIMEOUT_MS = 8_000L
            private const val CACHE_TTL_MS = 15 * 60_000L
            private const val ARTIST_CAP = 6

            private val STARTER_GENRES = listOf(132 to "Pop", 116 to "Rap & Hip-Hop", 152 to "Rock", 113 to "Dance", 165 to "R&B", 197 to "Latin")
        }
    }

/** The ids of station cards: `station:<kind>:<argument>`. */
internal object StationId {
    const val PREFIX = "station:"
    const val MIX = "mix"
    const val ARTIST = "artist"
    const val DISCOVER = "discover"
    const val MOOD = "mood"
    const val CHART = "chart"

    fun isStation(id: String): Boolean = id.startsWith(PREFIX)

    fun kindOf(id: String): String? = id.takeIf(::isStation)?.removePrefix(PREFIX)?.substringBefore(':')
}

/**
 * A small in-memory cache whose entries expire. [getOrLoad] stores only what [load] returns non-null, so a
 * request that failed is asked for again next time instead of being remembered as empty.
 */
internal class TtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val value: V, val storedAt: Long)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    fun clear() = entries.clear()

    suspend fun getOrLoad(
        key: K,
        load: suspend () -> V?,
    ): V? {
        entries[key]?.takeIf { now() - it.storedAt < ttlMs }?.let { return it.value }
        val loaded = load() ?: return entries[key]?.value
        entries[key] = Entry(loaded, now())
        return loaded
    }
}
