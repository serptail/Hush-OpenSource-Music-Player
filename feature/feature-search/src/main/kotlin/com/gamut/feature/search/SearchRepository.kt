package com.gamut.feature.search

import com.gamut.core.common.TextMatch
import com.gamut.core.common.TrackIdentity
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.DeezerApi
import com.gamut.core.network.DeezerArtistDto
import com.gamut.core.network.DeezerTrackDto
import com.gamut.core.network.ItunesApi
import com.gamut.core.network.ItunesTrackDto
import com.gamut.core.network.YoutubeCandidate
import com.gamut.core.network.explicitFromApple
import com.gamut.core.network.YoutubeMusicSearchSource
import com.gamut.core.network.isValidYoutubeVideoId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class SearchResult(
    val tracks: List<Track>,
    val isFinal: Boolean,
    val artist: ArtistSignal? = null,
)

interface SearchRepository {
    fun search(query: String): Flow<SearchResult>

    fun clearCache() {}
}

class SearchRepositoryImpl
    @Inject
    constructor(
        private val itunesApi: ItunesApi,
        private val deezerApi: DeezerApi,
        private val youtubeSource: YoutubeMusicSearchSource,
    ) : SearchRepository {
        private val resultCache = ConcurrentHashMap<String, CachedResult>()

        override fun clearCache() {
            resultCache.clear()
        }

        private val itunesRateGate = Mutex()
        private val itunesRequestTimes = ArrayDeque<Long>()

        override fun search(query: String): Flow<SearchResult> =
            channelFlow {
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
                val youtubeDeferred =
                    async {
                        withTimeoutOrNull(YOUTUBE_BUDGET_MS) {
                            runCatching {
                                val songs = youtubeSource.searchSongs(trimmed)
                                if (songs.isNotEmpty()) songs else youtubeSource.searchBroad(trimmed)
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                    }

                val itunes = itunesDeferred.await()
                val deezer = deezerDeferred.await()
                val first = assemble(trimmed, itunes, deezer, emptyList(), boostArtist = false, youtube = emptyList())
                send(SearchResult(first, isFinal = false))

                val artist = artistDeferred.await()
                val signal = ArtistSignal(TextMatch.norm(trimmed), artist?.name, artist?.fanCount)
                send(SearchResult(first, isFinal = false, artist = signal))

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
            val response =
                withItunesRateLimit {
                    withTimeoutOrNull(ITUNES_TIMEOUT_MS) {
                        runCatching { itunesApi.search(term = rawQuery, limit = ITUNES_LIMIT) }.getOrNull()
                    }
                } ?: return emptyList()

            if (!response.isSuccessful) {
                return emptyList()
            }

            return response
                .body()
                ?.results
                .orEmpty()
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

        private suspend fun searchDeezerTracks(query: String): List<DeezerTrackDto> =
            withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
                runCatching { deezerApi.searchTracks(query, limit = DEEZER_LIMIT).data }.getOrDefault(emptyList())
            }.orEmpty()

        private suspend fun lookupArtist(query: String): DeezerArtistDto? =
            withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
                runCatching {
                    deezerApi
                        .searchArtists(query, limit = 5)
                        .data
                        .map { it to SearchRanking.nameMatch(query, it.name) }
                        .filter { (_, match) -> match > 0.0 }
                        .maxWithOrNull(compareBy({ it.second }, { it.first.fanCount ?: 0L }))
                        ?.first
                }.getOrNull()
            }

        private suspend fun topTracks(artistId: Long): List<DeezerTrackDto> =
            withTimeoutOrNull(DEEZER_TIMEOUT_MS) {
                runCatching {
                    deezerApi
                        .topTracks(artistId, limit = ARTIST_TRACK_LIMIT)
                        .data
                        .distinctBy { it.id }
                        .sortedByDescending { it.rank }
                        .take(ARTIST_BOOST_TRACKS)
                }.getOrDefault(emptyList())
            }.orEmpty()

        private fun ItunesTrackDto.isPlayableSong(): Boolean {
            if (trackId == null || trackName.isNullOrBlank() || artistName.isNullOrBlank()) return false
            if (kind != null && kind != "song") return false
            return true
        }

        private fun assemble(
            query: String,
            itunes: List<Track>,
            deezer: List<DeezerTrackDto>,
            artistTracks: List<DeezerTrackDto>,
            boostArtist: Boolean,
            youtube: List<YoutubeCandidate>,
        ): List<Track> {
            val items = ArrayList<Sourced>()

            // Priority order matters: the first hit of a recording supplies the metadata that is shown.
            itunes.forEachIndexed { i, track ->
                items += Sourced(track, popularity = 0.0, position = i, provider = Provider.APPLE)
            }
            deezer.forEachIndexed { i, dto ->
                items +=
                    Sourced(
                        dto.toDomain(),
                        SearchRanking.trackPopularity(dto.rank),
                        i,
                        Provider.DEEZER,
                        penalty = DEEZER_PENALTY,
                    )
            }
            artistTracks.forEachIndexed { i, dto ->
                items +=
                    Sourced(
                        dto.toDomain(),
                        SearchRanking.trackPopularity(dto.rank),
                        i,
                        Provider.DEEZER,
                        boost = if (boostArtist) ARTIST_BOOST else 0.0,
                    )
            }
            youtube
                .filter { isCleanYoutubeResult(it, query) }
                .forEachIndexed { i, candidate ->
                    items +=
                        Sourced(
                            candidate.toYoutubeOnlyTrack(),
                            SearchRanking.viewsPopularity(candidate.viewCount),
                            i,
                            Provider.YOUTUBE,
                            // Catalogue audio and Topic channels are real song entries; other uploads
                            // (reuploads, lyric videos) stay below them.
                            penalty =
                                if (candidate.isMusicCatalog || candidate.channelIsTopicChannel) {
                                    YOUTUBE_PENALTY
                                } else {
                                    YOUTUBE_UPLOAD_PENALTY
                                },
                        )
                }

            // A recording that Apple, Deezer and YouTube all return becomes one candidate with
            // the best position and popularity any of them gave it, and a cross-provider agreement bonus.
            return SearchRanking.rank(query, ResultMerger.merge(items))
        }

        private fun YoutubeCandidate.toYoutubeOnlyTrack(): Track {
            val artist = channelName.removeSuffix(" - Topic")
            return Track(
                isrc = "youtube:$videoId",
                musicBrainzId = null,
                title = TrackIdentity.displayTitle(title, artist),
                artist = artist,
                album = null,
                durationMs = durationMs,
                // YouTube Music catalogue songs carry square cover art; only plain video uploads fall back
                // to the wide video thumbnail.
                albumArtUrl =
                    thumbnailUrl?.takeIf { isMusicCatalog && it.isNotBlank() }
                        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                source = TrackSource.YOUTUBE,
                youtubeVideoId = videoId,
            )
        }

        private fun isCleanYoutubeResult(
            candidate: YoutubeCandidate,
            query: String,
        ): Boolean {
            if (!isValidYoutubeVideoId(candidate.videoId)) return false
            if (candidate.durationMs > 0 && (candidate.durationMs < 30_000 || candidate.durationMs > 12 * 60_000)) return false
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
            const val ITUNES_TIMEOUT_MS = 8_000L
            const val ITUNES_LIMIT = 50
            const val DEEZER_LIMIT = 40
            const val YOUTUBE_BUDGET_MS = 5_000L
            const val DEEZER_TIMEOUT_MS = 3_000L
            const val ARTIST_TRACK_LIMIT = 30
            const val ARTIST_BOOST_TRACKS = 15
            const val ARTIST_BOOST = 15.0
            const val YOUTUBE_PENALTY = 3.0
            const val YOUTUBE_UPLOAD_PENALTY = 12.0
            const val DEEZER_PENALTY = 2.0
            const val ITUNES_MAX_REQUESTS = 15
            const val ITUNES_WINDOW_MS = 60_000L
            const val CACHE_TTL_MS = 10 * 60 * 1_000L
        }
    }

private fun ItunesTrackDto.toDomain(): Track {
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
        isExplicit = explicitFromApple(trackExplicitness),
        previewUrl = previewUrl?.takeIf { it.isNotBlank() },
    )
}

private fun DeezerTrackDto.toDomain(): Track =
    Track(
        isrc = "deezer:$id",
        title =
            listOfNotNull(titleShort ?: title, titleVersion?.takeIf { it.isNotBlank() })
                .joinToString(" "),
        artist = artist.name,
        album = album.title,
        durationMs = duration * 1_000L,
        albumArtUrl = album.coverXl ?: album.coverBig,
        source = TrackSource.ITUNES,
        isExplicit = explicitLyrics,
        previewUrl = preview?.takeIf { it.isNotBlank() },
    )

private fun String.upscaleArtwork(size: Int = 600): String = replace(Regex("/\\d+x\\d+bb\\."), "/${size}x${size}bb.")

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchRepositoryModule {
    @Binds abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository
}