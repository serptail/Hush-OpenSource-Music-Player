package com.hush.feature.search

import com.hush.core.common.TextSimilarity
import com.hush.core.model.Track
import com.hush.core.model.TrackSource
import com.hush.core.network.ItunesApi
import com.hush.core.network.ItunesTrackDto
import com.hush.core.network.YoutubeCandidate
import com.hush.core.network.YoutubeMusicSearchSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

interface SearchRepository {
    suspend fun searchTracks(query: String): Result<List<Track>>
}

class SearchRepositoryImpl @Inject constructor(
    private val itunesApi: ItunesApi,
    private val youtubeSource: YoutubeMusicSearchSource,
) : SearchRepository {

    // Process-lifetime cache of raw query -> ranked tracks. iTunes has no per-key ETag/
    // cache-control worth respecting, and its own soft rate limit (~20 req/min/IP) means a
    // user backspacing to a query they already typed should never re-hit the network.
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    // Caps concurrent iTunes calls from this repo to 1 in-flight at a time in practice --
    // debounce in the ViewModel already prevents keystroke spam, this is the backstop for
    // rapid programmatic re-search (e.g. retry-on-resume) so we never fan out multiple
    // overlapping requests and burn the rate-limit budget for nothing.
    private val itunesGate = Semaphore(1)

    override suspend fun searchTracks(query: String): Result<List<Track>> = runCatching {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@runCatching emptyList()

        val cacheKey = trimmed.lowercase()
        resultCache[cacheKey]?.takeIf { it.isFresh() }?.let { cached ->
            return@runCatching mergeWithYoutube(cached.tracks, freshYoutube(trimmed))
        }

        coroutineScope {
            // iTunes returns artwork inline, so unlike the old MusicBrainz+CoverArtArchive
            // flow there's no second artwork pass to run afterward -- one round trip, done.
            val itunesDeferred = async { searchItunes(trimmed) }
            val youtubeDeferred = async { runCatching { youtubeSource.searchSongs(trimmed) }.getOrDefault(emptyList()) }

            val itunesTracks = itunesDeferred.await()
            val youtubeCandidates = youtubeDeferred.await()

            if (itunesTracks.isNotEmpty()) {
                resultCache[cacheKey] = CachedResult(itunesTracks, System.currentTimeMillis())
            }

            mergeWithYoutube(itunesTracks, youtubeCandidates)
        }
    }

    private suspend fun freshYoutube(rawQuery: String): List<YoutubeCandidate> =
        runCatching { youtubeSource.searchSongs(rawQuery) }.getOrDefault(emptyList())

    private suspend fun searchItunes(rawQuery: String): List<Track> {
        val response = itunesGate.withPermit {
            withTimeoutOrNull(ITUNES_TIMEOUT_MS) {
                runCatching { itunesApi.search(term = rawQuery, limit = SEARCH_LIMIT) }.getOrNull()
            }
        } ?: return emptyList()

        if (!response.isSuccessful) {
            // 403 = soft rate limit tripped; treat as "no results this round" rather than
            // surfacing a scary error for something that self-resolves in under a minute.
            return emptyList()
        }

        return response.body()?.results.orEmpty()
            .asSequence()
            .filter { it.isPlayableSong() }
            .distinctBy { it.trackId }
            .map { it to it.rankingScore(rawQuery) }
            .sortedByDescending { (_, score) -> score }
            .map { (dto, _) -> dto.toDomain() }
            .toList()
    }

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
        val artist = artistName.orEmpty()
        val normalizedQuery = TextSimilarity.normalize(rawQuery)
        val artistSimilarity = TextSimilarity.similarityNormalized(artist, rawQuery)
        val exactArtistPrefixBoost = if (TextSimilarity.normalize(artist).startsWith(normalizedQuery)) 50.0 else 0.0
        return (artistSimilarity * 40.0) + exactArtistPrefixBoost
    }

    /**
     * Unifies iTunes tracks and YouTube candidates:
     *  - a YouTube candidate closely matching an existing iTunes track is dropped (iTunes
     *    stays canonical -- it carries proper album metadata and free high-res artwork)
     *  - a YouTube candidate with no close match becomes its own Track, using the YouTube
     *    thumbnail as artwork and a fallback resolution key
     */
    private fun mergeWithYoutube(itunesTracks: List<Track>, youtubeCandidates: List<YoutubeCandidate>): List<Track> {
        val normalizedSignatures = itunesTracks.map { TextSimilarity.normalize("${it.artist} ${it.title}") }

        val youtubeOnly = youtubeCandidates
            .filter { candidate ->
                val normalizedCandidate = TextSimilarity.normalize(candidate.title)
                normalizedSignatures.none { TextSimilarity.similarity(it, normalizedCandidate) >= YOUTUBE_DEDUPE_THRESHOLD }
            }
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

    private data class CachedResult(val tracks: List<Track>, val cachedAtMs: Long) {
        fun isFresh(): Boolean = System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS
    }

    private companion object {
        // 4s was too tight -- on slower networks (notably Android emulators, where cold-start
        // DNS/TLS handshakes routinely run 5-8s) a perfectly healthy iTunes response was being
        // cancelled by this timeout before it ever arrived, surfacing as "0 results".
        const val ITUNES_TIMEOUT_MS = 8_000L
        const val SEARCH_LIMIT = 25
        const val YOUTUBE_DEDUPE_THRESHOLD = 0.80
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

/** Rewrites iTunes' 100x100 thumbnail path segment to request a larger size, no extra request. */
private fun String.upscaleArtwork(size: Int = 600): String =
    replace(Regex("/\\d+x\\d+bb\\."), "/${size}x${size}bb.")

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule { @Binds abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository }