package com.gamut.feature.search

import com.gamut.core.common.TrackIdentity
import com.gamut.core.database.FollowDao
import com.gamut.core.network.DeezerChartsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min

/**
 * One row of the typeahead panel. Music entities (artists, songs, albums) carry artwork and are
 * ranked with plain-text completions and the user's own history in a single list.
 */
sealed interface Suggestion {
    val key: String

    enum class Origin { RECENT, COMPLETION }

    data class Text(
        val text: String,
        val origin: Origin,
    ) : Suggestion {
        override val key: String get() = "q:" + TrackIdentity.fold(text)
    }

    data class Artist(
        val name: String,
        val imageUrl: String?,
        val artistId: Long,
        val fans: Long,
        val followed: Boolean,
    ) : Suggestion {
        override val key: String get() = if (artistId >= 0) "artist:$artistId" else "artist:" + TrackIdentity.fold(name)
    }

    data class Song(
        val title: String,
        val artist: String,
        val imageUrl: String?,
        val rank: Long,
        val explicit: Boolean,
    ) : Suggestion {
        override val key: String get() = "song:" + TrackIdentity.fold("$title $artist")

        /** What a tap searches for. */
        val searchText: String get() = "$title $artist"
    }

    data class Album(
        val title: String,
        val artist: String,
        val imageUrl: String?,
    ) : Suggestion {
        override val key: String get() = "album:" + TrackIdentity.fold("$title $artist")

        val searchText: String get() = "$title $artist"
    }
}

/** What the network and local sources know about the typed text, before ranking. */
data class RemoteSuggestions(
    val completions: List<String> = emptyList(),
    val entities: List<Suggestion> = emptyList(),
)

/**
 * Streams typeahead sources for the search field. Followed artists and the previous completions that
 * still fit appear immediately; text completions and catalog matches (artists, songs, albums) are
 * folded in as each one arrives, so a slow source never holds the panel back.
 */
@Singleton
class SuggestionEngine
    @Inject
    constructor(
        private val completions: SearchSuggestionsRepository,
        private val catalog: DeezerChartsApi,
        private val follows: FollowDao,
    ) {
        private class Cached(
            val values: List<Suggestion>,
            val atMs: Long,
        )

        private val cache = ConcurrentHashMap<String, Cached>()

        @Volatile private var followed: List<Suggestion.Artist> = emptyList()

        @Volatile private var followedAtMs = 0L

        fun stream(input: String): Flow<RemoteSuggestions> =
            channelFlow {
                val key = TrackIdentity.fold(input)
                if (key.length < MIN_LENGTH) {
                    send(RemoteSuggestions())
                    return@channelFlow
                }
                val mine = followedMatching(key)
                val mutex = Mutex()
                var current = RemoteSuggestions(completions.localFor(input), mine)
                send(current)

                suspend fun update(block: (RemoteSuggestions) -> RemoteSuggestions) =
                    mutex.withLock {
                        current = block(current)
                        send(current)
                    }

                launch {
                    val fresh = completions.suggest(input)
                    update { it.copy(completions = fresh) }
                }
                launch {
                    val found = entities(input) ?: return@launch
                    update { it.copy(entities = mine + found) }
                }
            }

        private suspend fun followedMatching(key: String): List<Suggestion.Artist> {
            val now = System.currentTimeMillis()
            if (now - followedAtMs > FOLLOW_TTL_MS) {
                followed =
                    attempt {
                        follows.all().map {
                            Suggestion.Artist(
                                name = it.name,
                                imageUrl = it.imageUrl,
                                // Follows are keyed by name, not catalog id. A negative id marks "resolve on tap".
                                artistId = -1L,
                                fans = 0L,
                                followed = true,
                            )
                        }
                    } ?: followed
                followedAtMs = now
            }
            return followed.filter { SuggestionRanker.matchScore(key, it.name) >= SuggestionRanker.MIN_ENTITY_SCORE }.take(2)
        }

        /** Null when every source failed, so the failure is not cached and the next keystroke retries. */
        private suspend fun entities(input: String): List<Suggestion>? {
            val key = TrackIdentity.fold(input)
            cache[key]?.takeIf { System.currentTimeMillis() - it.atMs < TTL_MS }?.let { return it.values }
            val text = input.trim()
            val out =
                withTimeoutOrNull(TIMEOUT_MS) {
                    coroutineScope {
                        val artists = async { attempt { catalog.searchArtists(text, ARTIST_LIMIT).data } }
                        val songs = async { attempt { catalog.searchTracks(text, SONG_LIMIT).data } }
                        val albums = async { attempt { catalog.searchAlbums(text, ALBUM_LIMIT).data } }
                        val a = artists.await()
                        val s = songs.await()
                        val b = albums.await()
                        if (a == null && s == null && b == null) return@coroutineScope null
                        buildList<Suggestion> {
                            a.orEmpty().forEach {
                                add(
                                    Suggestion.Artist(
                                        name = it.name,
                                        imageUrl = it.pictureBig ?: it.pictureXl,
                                        artistId = it.id,
                                        fans = it.fanCount ?: 0L,
                                        followed = false,
                                    ),
                                )
                            }
                            s.orEmpty().forEach {
                                add(
                                    Suggestion.Song(
                                        title = it.titleShort?.takeIf { t -> t.isNotBlank() } ?: it.title,
                                        artist = it.artist.name,
                                        imageUrl = it.album.coverBig ?: it.album.coverXl,
                                        rank = it.rank,
                                        explicit = it.explicitLyrics == true,
                                    ),
                                )
                            }
                            b.orEmpty().forEach {
                                val title = it.title ?: return@forEach
                                add(
                                    Suggestion.Album(
                                        title = title,
                                        artist = it.artist?.name.orEmpty(),
                                        imageUrl = it.coverBig ?: it.coverXl,
                                    ),
                                )
                            }
                        }
                    }
                } ?: return null
            if (cache.size >= MAX_ENTRIES) cache.clear()
            cache[key] = Cached(out, System.currentTimeMillis())
            return out
        }

        private suspend inline fun <T> attempt(block: () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        private companion object {
            const val MIN_LENGTH = 2
            const val TIMEOUT_MS = 2_000L
            const val TTL_MS = 5 * 60 * 1_000L
            const val FOLLOW_TTL_MS = 60_000L
            const val MAX_ENTRIES = 80
            const val ARTIST_LIMIT = 3
            const val SONG_LIMIT = 5
            const val ALBUM_LIMIT = 4
        }
    }

/** Pure ranking: one score scale for text completions, recents and catalog entities. */
internal object SuggestionRanker {
    const val MAX_ROWS = 8
    const val MIN_ENTITY_SCORE = 50

    private const val MAX_ARTISTS = 2
    private const val MAX_SONGS = 3
    private const val MAX_ALBUMS = 2
    private const val MAX_RECENTS = 2

    private data class Scored(
        val suggestion: Suggestion,
        val score: Double,
    )

    fun rank(
        typed: String,
        recents: List<String>,
        remote: RemoteSuggestions,
    ): List<Suggestion> {
        val q = TrackIdentity.fold(typed)
        if (q.length < 2) return emptyList()

        val scored = ArrayList<Scored>()

        recents.forEachIndexed { index, text ->
            val m = matchScore(q, text)
            if (m >= MIN_ENTITY_SCORE) {
                scored += Scored(Suggestion.Text(text, Suggestion.Origin.RECENT), m + 30.0 - index.coerceAtMost(8))
            }
        }

        remote.completions.forEachIndexed { index, text ->
            // The source already judged these relevant, so a weak textual match still gets a floor.
            val m = maxOf(matchScore(q, text), 40)
            scored += Scored(Suggestion.Text(text, Suggestion.Origin.COMPLETION), m + 5.0 - index.coerceAtMost(8) * 0.75)
        }

        val entities = reconcileFollowed(remote.entities)
        entities.forEachIndexed { index, entity ->
            val s = entityScore(q, entity) ?: return@forEachIndexed
            scored += Scored(entity, s - index * 0.1)
        }

        val seen = HashSet<String>()
        seen += "q:$q"
        val sorted = scored.sortedByDescending { it.score }

        val artistNames = HashSet<String>()
        entities.forEach { if (it is Suggestion.Artist) artistNames += TrackIdentity.fold(it.name) }
        val out = ArrayList<Suggestion>()
        var artists = 0
        var songs = 0
        var albums = 0
        var recentRows = 0
        for (item in sorted) {
            if (out.size >= MAX_ROWS) break
            val s = item.suggestion
            if (!seen.add(s.key)) continue
            when (s) {
                is Suggestion.Artist -> {
                    if (artists++ >= MAX_ARTISTS) continue
                }

                is Suggestion.Song -> {
                    if (songs++ >= MAX_SONGS) continue
                }

                is Suggestion.Album -> {
                    if (albums++ >= MAX_ALBUMS) continue
                }

                is Suggestion.Text -> {
                    // A bare artist name is better served by the artist row with its photo.
                    if (s.origin == Suggestion.Origin.COMPLETION && TrackIdentity.fold(s.text) in artistNames) continue
                    if (s.origin == Suggestion.Origin.RECENT && recentRows++ >= MAX_RECENTS) continue
                }
            }
            out += s
        }
        return out
    }

    /**
     * Followed artists arrive as name-only placeholders (no catalog id). When the catalog returned the same
     * artist, keep the catalog row, mark it followed and drop the placeholder; otherwise keep the placeholder.
     */
    private fun reconcileFollowed(entities: List<Suggestion>): List<Suggestion> {
        val catalogNames = HashSet<String>()
        val followedNames = HashSet<String>()
        entities.forEach {
            if (it is Suggestion.Artist) {
                if (it.artistId >= 0) catalogNames += TrackIdentity.fold(it.name)
                if (it.followed) followedNames += TrackIdentity.fold(it.name)
            }
        }
        return entities.mapNotNull { e ->
            if (e !is Suggestion.Artist) return@mapNotNull e
            val name = TrackIdentity.fold(e.name)
            when {
                e.artistId < 0 && name in catalogNames -> null
                e.artistId >= 0 && name in followedNames -> e.copy(followed = true)
                else -> e
            }
        }
    }

    private fun entityScore(
        q: String,
        entity: Suggestion,
    ): Double? =
        when (entity) {
            is Suggestion.Artist -> {
                val m = matchScore(q, entity.name)
                if (m < MIN_ENTITY_SCORE) {
                    null
                } else {
                    m + popularity(entity.fans, 50_000_000.0, 20.0) + if (entity.followed) 25.0 else 0.0
                }
            }

            is Suggestion.Song -> {
                val m =
                    maxOf(
                        matchScore(q, entity.title),
                        matchScore(q, "${entity.title} ${entity.artist}") - 5,
                        matchScore(q, entity.artist) - 20,
                    )
                if (m < MIN_ENTITY_SCORE) null else m + popularity(entity.rank, 1_000_000.0, 12.0) - 4.0
            }

            is Suggestion.Album -> {
                val m =
                    maxOf(
                        matchScore(q, entity.title),
                        matchScore(q, "${entity.title} ${entity.artist}") - 5,
                    )
                if (m < MIN_ENTITY_SCORE) null else m - 8.0
            }

            is Suggestion.Text -> {
                null
            }
        }

    private fun popularity(
        value: Long,
        ceiling: Double,
        weight: Double,
    ): Double = (ln(1.0 + value.coerceAtLeast(0L)) / ln(1.0 + ceiling)).coerceIn(0.0, 1.0) * weight

    /**
     * 0 = unrelated, up to 100 for an exact match. [q] is already folded; [candidate] is folded here.
     * Prefix beats word-prefix beats substring beats a one-typo prefix.
     */
    fun matchScore(
        q: String,
        candidate: String,
    ): Int {
        val c = TrackIdentity.fold(candidate)
        if (c.isEmpty() || q.isEmpty()) return 0
        if (c == q) return 100
        if (c.startsWith(q)) return 90 - min(10, (c.length - q.length) / 3)
        val words = c.split(' ').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 70
        val tokens = q.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.all { t -> words.any { it.startsWith(t) } }) return 65
        if (c.contains(q)) return 50
        if (q.length >= 4 && withinOneTypo(q, c)) return 35
        return 0
    }

    private fun withinOneTypo(
        q: String,
        c: String,
    ): Boolean {
        for (len in (q.length - 1)..(q.length + 1)) {
            if (len <= 0 || len > c.length) continue
            if (editDistanceAtMostOne(q, c.substring(0, len))) return true
        }
        return false
    }

    /** True when [a] and [b] differ by at most one insert, delete, substitution or adjacent swap. */
    private fun editDistanceAtMostOne(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (a.length == b.length) {
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            return i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
        }
        return if (a.length > b.length) a.substring(i + 1) == b.substring(i) else b.substring(i + 1) == a.substring(i)
    }
}
