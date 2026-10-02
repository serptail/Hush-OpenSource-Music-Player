package com.hush.feature.search

import com.hush.core.common.TextMatch
import com.hush.core.model.Track
import com.hush.core.model.TrackSource
import com.hush.core.network.ItunesApi
import com.hush.core.network.ItunesTrackDto
import com.hush.core.network.DeezerApi
import com.hush.core.network.DeezerTrackDto
import com.hush.core.network.YoutubeCandidate
import com.hush.core.network.YoutubeMusicSearchSource
import com.hush.core.network.isValidYoutubeVideoId
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

data class SearchResult(val tracks: List<Track>, val isFinal: Boolean)

interface SearchRepository {
    fun search(query: String): Flow<SearchResult>
}

class SearchRepositoryImpl @Inject constructor(
    private val itunesApi: ItunesApi,
    private val deezerApi: DeezerApi,
    private val youtubeSource: YoutubeMusicSearchSource,
) : SearchRepository {

    // Process-lifetime cache of raw query -> ranked tracks. iTunes has no per-key ETag/
    // cache-control worth respecting, and its own soft rate limit (~20 req/min/IP) means a
    // user backspacing to a query they already typed should never re-hit the network.
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    private val itunesRateGate = Mutex()
    private val itunesRequestTimes = ArrayDeque<Long>()

    override fun search(query: String): Flow<SearchResult> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(SearchResult(emptyList(), true))
            return@channelFlow
        }

        val cacheKey = trimmed.lowercase()
        val cached = resultCache[cacheKey]?.takeIf { it.isFresh() }
        if (cached != null) {
            send(SearchResult(mergeWithYoutube(cached.itunesTracks, cached.youtubeCandidates, trimmed), true))
            return@channelFlow
        }

        val itunesDeferred = async { searchItunes(trimmed) }
        val artistDeferred = async { searchDeezerArtist(trimmed) }
        val youtubeDeferred = async {
            withTimeoutOrNull(YOUTUBE_BUDGET_MS) {
                runCatching {
                    val songs = youtubeSource.searchSongs(trimmed)
                    if (songs.isNotEmpty()) songs else youtubeSource.searchBroad(trimmed)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val itunesTracks = itunesDeferred.await()
        send(SearchResult(itunesTracks, isFinal = false))
        val youtubeCandidates = youtubeDeferred.await()
        if (itunesTracks.isNotEmpty() || youtubeCandidates.isNotEmpty()) {
            resultCache[cacheKey] = CachedResult(itunesTracks, youtubeCandidates, System.currentTimeMillis())
        }
        send(SearchResult(mergeWithYoutube(itunesTracks, youtubeCandidates, trimmed), true))

        // Artist discovery is deliberately a later refinement: Deezer must never delay
        // the first usable result set when its public endpoint is slow or unavailable.
        val artistTracks = artistDeferred.await()
        if (artistTracks.isNotEmpty()) {
            resultCache[cacheKey] = CachedResult(artistTracks, youtubeCandidates, System.currentTimeMillis())
            send(SearchResult(mergeWithYoutube(artistTracks, youtubeCandidates, trimmed), true))
        }
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

        val playable = response.body()?.results.orEmpty()
            .asSequence()
            .filter { it.isPlayableSong() }
            .distinctBy { it.trackId }
            .map { it to it.rankingScore(rawQuery) }
            .sortedByDescending { (_, score) -> score }
            .toList()
        val query = TextMatch.norm(rawQuery)
        val hasExactSong = playable.any { TextMatch.norm(it.first.trackName.orEmpty()) == query }
        return (if (hasExactSong) {
            playable.filter { songMatchesQuery(it.first, query) }
        } else {
            playable
        }).map { (dto, _) -> dto.toDomain() }
    }

    private fun songMatchesQuery(dto: ItunesTrackDto, normalizedQuery: String): Boolean {
        val title = TextMatch.toks(dto.trackName.orEmpty())
        val query = TextMatch.toks(normalizedQuery)
        val coverage = query.count { it in title }.toDouble() / query.size.coerceAtLeast(1)
        return coverage >= 0.8
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

    private suspend fun searchDeezerArtist(query: String): List<Track> = withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
        runCatching {
            val normalizedQuery = TextMatch.norm(query)
            val artist = deezerApi.searchArtists(query).data.firstOrNull {
                TextMatch.norm(it.name) == normalizedQuery
            } ?: return@runCatching emptyList()
            deezerApi.topTracks(artist.id, limit = ARTIST_TRACK_LIMIT).data
                .distinctBy { it.id }
                .sortedByDescending { it.rank }
                .map { it.toDomain() }
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

    /**
     * iTunes' own relevance ranking is decent but has no concept of "this is the well-known
     * artist you obviously meant" -- same client-side boost strategy as the old MusicBrainz
     * scorer, just without MB's separate text-match score to blend in.
     */
    private fun ItunesTrackDto.rankingScore(rawQuery: String): Double {
        val queryTokens = TextMatch.toks(rawQuery)
        val artistTokens = TextMatch.toks(artistName.orEmpty())
        val titleTokens = TextMatch.toks(trackName.orEmpty())
        val albumTokens = TextMatch.toks(collectionName.orEmpty())
        val artistMatch = queryTokens.count { it in artistTokens }.toDouble() / queryTokens.size.coerceAtLeast(1)
        val titleMatch = queryTokens.count { it in titleTokens }.toDouble() / queryTokens.size.coerceAtLeast(1)
        val junk = ((artistTokens + titleTokens + albumTokens) intersect TextMatch.DERIVATIVE).size
        val exactArtist = TextMatch.primaryArtist(artistName.orEmpty()) == TextMatch.norm(rawQuery)
        return (if (exactArtist) 100.0 else 0.0) + artistMatch * 40.0 + titleMatch * 30.0 - junk * 60.0
    }

    /**
     * Unifies iTunes tracks and YouTube candidates:
     *  - a YouTube candidate closely matching an existing iTunes track is dropped (iTunes
     *    stays canonical -- it carries proper album metadata and free high-res artwork)
     *  - a YouTube candidate with no close match becomes its own Track, using the YouTube
     *    thumbnail as artwork and a fallback resolution key
     */
    private fun mergeWithYoutube(
        itunesTracks: List<Track>,
        youtubeCandidates: List<YoutubeCandidate>,
        query: String,
    ): List<Track> {
        val youtubeOnly = youtubeCandidates
            .filter { candidate -> isCleanYoutubeResult(candidate, query) }
            .filter { candidate -> itunesTracks.none { track -> duplicates(candidate, track) } }
            .map { it.toYoutubeOnlyTrack() }

        return itunesTracks + youtubeOnly
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
        val itunesTracks: List<Track>,
        val youtubeCandidates: List<YoutubeCandidate>,
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
        const val ARTIST_TRACK_LIMIT = 100
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