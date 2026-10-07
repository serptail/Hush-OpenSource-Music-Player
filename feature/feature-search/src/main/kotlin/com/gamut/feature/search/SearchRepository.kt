package com.gamut.feature.search

import com.gamut.core.common.TextMatch
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.ItunesApi
import com.gamut.core.network.ItunesTrackDto
import com.gamut.core.network.DeezerApi
import com.gamut.core.network.DeezerArtistDto
import com.gamut.core.network.DeezerTrackDto
import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.YoutubeMusicSearchSource
import com.gamut.core.network.isValidYoutubeVideoId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** [artist] is the artist signal for this query (set from the second emission on); the UI uses it to gate the artist card. */
data class SearchResult(val tracks: List<Track>, val isFinal: Boolean, val artist: ArtistSignal? = null)

interface SearchRepository {
    fun search(query: String): Flow<SearchResult>
}

class SearchRepositoryImpl @Inject constructor(
    private val itunesApi: ItunesApi,
    private val deezerApi: DeezerApi,
    private val youtubeSource: YoutubeMusicSearchSource,
) : SearchRepository {

    // Process-lifetime cache of query -> final ranked tracks. iTunes has no per-key ETag/
    // cache-control worth respecting, and its own soft rate limit (~20 req/min/IP) means a
    // user backspacing to a query they already typed should never re-hit the network.
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    private val itunesRateGate = Mutex()
    private val itunesRequestTimes = ArrayDeque<Long>()

    /**
     * Emission 1: iTunes + Deezer songs, ranked (fast, never waits for YouTube).
     * Emission 2: same songs plus the [ArtistSignal] (so the UI can decide whether the artist card is deserved).
     * Emission 3 (final): everything, with YouTube finds and, only for a confidently identified artist, their top songs.
     */
    override fun search(query: String): Flow<SearchResult> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(SearchResult(emptyList(), true))
            return@channelFlow
        }

        val cacheKey = trimmed.lowercase()
        val cached = resultCache[cacheKey]?.takeIf { it.isFresh() }
        if (cached != null) {
            send(SearchResult(cached.tracks, true, cached.artist))
            return@channelFlow
        }

        val itunesDeferred = async { searchItunes(trimmed) }
        val deezerDeferred = async { searchDeezerTracks(trimmed) }
        val artistDeferred = async { lookupArtist(trimmed) }
        val youtubeDeferred = async {
            withTimeoutOrNull(YOUTUBE_BUDGET_MS) {
                runCatching {
                    val songs = youtubeSource.searchSongs(trimmed)
                    if (songs.isNotEmpty()) songs else youtubeSource.searchBroad(trimmed)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val itunes = itunesDeferred.await()
        val deezer = deezerDeferred.await() // capped at DEEZER_TIMEOUT_MS
        val first = assemble(trimmed, itunes, deezer, emptyList(), boostArtist = false, youtube = emptyList())
        send(SearchResult(first, isFinal = false))

        val artist = artistDeferred.await() // capped at DEEZER_TIMEOUT_MS
        val signal = ArtistSignal(TextMatch.norm(trimmed), artist?.name, artist?.fanCount)
        send(SearchResult(first, isFinal = false, artist = signal))

        // Only a confidently identified artist gets their songs mixed in (never replaces the results any more).
        val confidence = artist?.let { SearchRanking.artistConfidence(trimmed, it.name, it.fanCount, first) } ?: 0.0
        val boost = artist != null && confidence >= SearchRanking.ARTIST_BOOST_THRESHOLD
        val artistTracks = if (boost) topTracks(artist!!.id) else emptyList()

        val youtube = youtubeDeferred.await()
        val finalTracks = assemble(trimmed, itunes, deezer, artistTracks, boost, youtube)
        if (finalTracks.isNotEmpty()) {
            resultCache[cacheKey] = CachedResult(finalTracks, signal, System.currentTimeMillis())
        }
        send(SearchResult(finalTracks, isFinal = true, artist = signal))
    }

    private suspend fun searchItunes(rawQuery: String): List<Track> {
        val response = withItunesRateLimit {
            withTimeoutOrNull(ITUNES_TIMEOUT_MS) {
                runCatching { itunesApi.search(term = rawQuery, limit = SEARCH_LIMIT) }.getOrNull()
            }
        } ?: return emptyList()

        if (!response.isSuccessful) {
            // 403 = soft rate limit tripped; treat as "no results this round" rather than
            // surfacing a scary error for something that self-resolves in under a minute.
            return emptyList()
        }

        // iTunes' own order is kept (it is a useful relevance prior); SearchRanking does the real ranking.
        return response.body()?.results.orEmpty()
            .asSequence()
            .filter { it.isPlayableSong() }
            .distinctBy { it.trackId }
            .map { it.toDomain() }
            .toList()
    }

    private suspend fun <T> withItunesRateLimit(block: suspend () -> T): T {
        itunesRateGate.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (itunesRequestTimes.firstOrNull()?.let { now - it >= ITUNES_WINDOW_MS } == true) {
                    itunesRequestTimes.removeFirst()
                }
                if (itunesRequestTimes.size < ITUNES_MAX_REQUESTS) {
                    itunesRequestTimes.addLast(now)
                    break
                }
                delay(ITUNES_WINDOW_MS - (now - itunesRequestTimes.first()))
            }
        }
        return block()
    }

    private suspend fun searchDeezerTracks(query: String): List<DeezerTrackDto> = withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
        runCatching { deezerApi.searchTracks(query, limit = SEARCH_LIMIT).data }.getOrDefault(emptyList())
    }.orEmpty()

    /** Best Deezer artist for the query by name match, ties broken by fan count. No popularity decision here. */
    private suspend fun lookupArtist(query: String): DeezerArtistDto? = withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
        runCatching {
            deezerApi.searchArtists(query, limit = 5).data
                .map { it to SearchRanking.nameMatch(query, it.name) }
                .filter { (_, match) -> match > 0.0 }
                .maxWithOrNull(compareBy({ it.second }, { it.first.fanCount ?: 0L }))
                ?.first
        }.getOrNull()
    }

    private suspend fun topTracks(artistId: Long): List<DeezerTrackDto> = withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
        runCatching {
            deezerApi.topTracks(artistId, limit = ARTIST_TRACK_LIMIT).data
                .distinctBy { it.id }
                .sortedByDescending { it.rank }
                .take(ARTIST_BOOST_TRACKS)
        }.getOrDefault(emptyList())
    }.orEmpty()

    /**
     * entity=song mostly filters this already, but iTunes occasionally leaks music videos
     * or results missing the fields we need -- drop anything we can't render/resolve cleanly.
     */
    private fun ItunesTrackDto.isPlayableSong(): Boolean {
        if (trackId == null || trackName.isNullOrBlank() || artistName.isNullOrBlank()) return false
        if (kind != null && kind != "song") return false
        return true
    }

    private fun Track.key() = TextMatch.norm(title) + "|" + TextMatch.primaryArtist(artist)

    /**
     * Pools iTunes, Deezer, the (optional) boosted artist's songs and YouTube finds into [Candidate]s and ranks them:
     *  - iTunes stays canonical (album metadata, free high-res art); Deezer only adds popularity to a song iTunes
     *    already has, or contributes songs iTunes did not return
     *  - a YouTube find close to an existing catalog song is dropped, otherwise it competes with a small penalty
     */
    private fun assemble(
        query: String,
        itunes: List<Track>,
        deezer: List<DeezerTrackDto>,
        artistTracks: List<DeezerTrackDto>,
        boostArtist: Boolean,
        youtube: List<YoutubeCandidate>,
    ): List<Track> {
        val popularityByKey = HashMap<String, Double>()
        (deezer + artistTracks).forEach { dto ->
            popularityByKey.merge(dto.toDomain().key(), SearchRanking.trackPopularity(dto.rank), ::maxOf)
        }
        val artistKeys = if (boostArtist) artistTracks.map { it.toDomain().key() }.toSet() else emptySet()
        fun boostFor(key: String) = if (key in artistKeys) ARTIST_BOOST else 0.0

        val candidates = ArrayList<Candidate>()
        val known = HashSet<String>()
        itunes.forEachIndexed { i, track ->
            val key = track.key()
            known += key
            candidates += Candidate(track, popularityByKey[key] ?: 0.0, i, boost = boostFor(key))
        }
        deezer.forEachIndexed { i, dto ->
            val track = dto.toDomain()
            val key = track.key()
            if (known.add(key)) {
                candidates += Candidate(track, SearchRanking.trackPopularity(dto.rank), i, penalty = 2.0, boost = boostFor(key))
            }
        }
        artistTracks.forEachIndexed { i, dto ->
            val track = dto.toDomain()
            if (known.add(track.key())) {
                candidates += Candidate(track, SearchRanking.trackPopularity(dto.rank), i, boost = boostFor(track.key()))
            }
        }

        val catalog = candidates.map { it.track }
        youtube
            .filter { isCleanYoutubeResult(it, query) }
            .filter { candidate -> catalog.none { duplicates(candidate, it) } }
            .forEachIndexed { i, candidate ->
                candidates += Candidate(
                    candidate.toYoutubeOnlyTrack(),
                    SearchRanking.viewsPopularity(candidate.viewCount),
                    i,
                    penalty = YOUTUBE_PENALTY,
                )
            }
        return SearchRanking.rank(query, candidates)
    }

    private fun YoutubeCandidate.toYoutubeOnlyTrack(): Track = Track(
        isrc = "youtube:$videoId",
        musicBrainzId = null,
        title = title,
        artist = channelName.removeSuffix(" - Topic"),
        album = null,
        durationMs = durationMs,
        albumArtUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        source = TrackSource.YOUTUBE,
        youtubeVideoId = videoId,
    )

    private fun duplicates(candidate: YoutubeCandidate, track: Track): Boolean {
        val titleTokens = TextMatch.toks(track.title)
        val candidateTokens = TextMatch.toks(candidate.title)
        val coverage = titleTokens.count { it in candidateTokens }.toDouble() / titleTokens.size.coerceAtLeast(1)
        val artistHit = TextMatch.toks(track.artist).any { it in candidateTokens || it in TextMatch.toks(candidate.channelName) }
        val durationOk = candidate.durationMs <= 0 || track.durationMs <= 0 ||
            kotlin.math.abs(candidate.durationMs - track.durationMs) <= 10_000
        return coverage >= 0.8 && artistHit && durationOk
    }

    private fun isCleanYoutubeResult(candidate: YoutubeCandidate, query: String): Boolean {
        if (!isValidYoutubeVideoId(candidate.videoId)) return false
        if (candidate.durationMs > 0 && (candidate.durationMs < 60_000 || candidate.durationMs > 10 * 60_000)) return false
        val tokens = TextMatch.toks(candidate.title + " " + candidate.channelName)
        val queryTokens = TextMatch.toks(query)
        val queryEvidence = queryTokens.count { it in tokens }.toDouble() / queryTokens.size.coerceAtLeast(1)
        if (!candidate.isMusicCatalog && !candidate.channelIsTopicChannel && queryEvidence < 0.5) return false
        return tokens.none { it in TextMatch.DERIVATIVE }
    }

    private data class CachedResult(
        val tracks: List<Track>,
        val artist: ArtistSignal,
        val cachedAtMs: Long,
    ) {
        fun isFresh(): Boolean = System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS
    }

    private companion object {
        // 4s was too tight -- on slower networks (notably Android emulators, where cold-start
        // DNS/TLS handshakes routinely run 5-8s) a perfectly healthy iTunes response was being
        // cancelled by this timeout before it ever arrived, surfacing as "0 results".
        const val ITUNES_TIMEOUT_MS = 8_000L
        const val SEARCH_LIMIT = 25
        const val YOUTUBE_BUDGET_MS = 5_000L
        const val DEEZER_TIMEOUT_MS = 3_000L
        const val ARTIST_TRACK_LIMIT = 30
        const val ARTIST_BOOST_TRACKS = 15
        const val ARTIST_BOOST = 15.0
        const val YOUTUBE_PENALTY = 10.0
        const val ITUNES_MAX_REQUESTS = 15
        const val ITUNES_WINDOW_MS = 60_000L
        const val CACHE_TTL_MS = 10 * 60 * 1_000L // 10 min: iTunes catalog changes rarely
    }
}

private fun ItunesTrackDto.toDomain(): Track {
    // "itunes:" prefix keeps isrc globally unique against youtube: and any real ISRCs that
    // show up elsewhere in the app; trackId is stable and permanent per Apple's catalog.
    val stableIsrc = "itunes:$trackId"
    return Track(
        isrc = stableIsrc,
        musicBrainzId = null,
        title = trackName.orEmpty(),
        artist = artistName.orEmpty(),
        album = collectionName,
        durationMs = trackTimeMillis ?: 0L,
        albumArtUrl = artworkUrl100?.upscaleArtwork(),
        source = TrackSource.ITUNES,
        youtubeVideoId = null,
    )
}

private fun DeezerTrackDto.toDomain(): Track = Track(
    isrc = "deezer:$id",
    title = listOfNotNull(titleShort ?: title, titleVersion?.takeIf { it.isNotBlank() })
        .joinToString(" "),
    artist = artist.name,
    album = album.title,
    durationMs = duration * 1_000L,
    albumArtUrl = album.coverXl ?: album.coverBig,
    source = TrackSource.ITUNES,
)

/** Rewrites iTunes' 100x100 thumbnail path segment to request a larger size, no extra request. */
private fun String.upscaleArtwork(size: Int = 600): String =
    replace(Regex("/\\d+x\\d+bb\\."), "/${size}x${size}bb.")

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule { @Binds abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository }