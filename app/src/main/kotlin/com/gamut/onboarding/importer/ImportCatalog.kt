package com.gamut.onboarding.importer

import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import com.gamut.core.network.DeezerApi
import com.gamut.core.network.DeezerTrackDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The importer's fast lookup: one Deezer track search per song, which is what BitChord does against YouTube Music
 * (one request, first page, no ranking pipeline behind it).
 *
 * The app's normal search is built for a person typing: per query it asks iTunes (capped at 15 requests a minute),
 * Deezer for tracks and for the artist, sometimes Deezer top tracks, and YouTube Music, then waits for all of it.
 * An import of a few hundred songs through that is limited to the iTunes cap and mostly times out.
 *
 * The rows are ordinary [Track]s, built the way [SearchRepositoryImpl] builds Deezer rows, so they save and play like
 * any search result.
 */
@Singleton
class ImportCatalog @Inject constructor(
    private val deezer: DeezerApi,
) {
    private val gate = Mutex()
    private val requestTimes = ArrayDeque<Long>()

    /** Tracks for [query], best first as Deezer ranks them. Empty when nothing was found or Deezer didn't answer. */
    suspend fun search(query: String): List<Track> {
        repeat(2) { attempt ->
            val rows = throttled {
                withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        deezer.searchTracks(query, limit = LIMIT).data
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (rows != null) return rows.distinctBy { it.id }.map { it.toTrack() }
            if (attempt == 0) delay(RETRY_DELAY_MS)
        }
        return emptyList()
    }

    /** Keeps the importer under Deezer's request limit (documented as 50 per 5 seconds; kept well below). */
    private suspend fun <T> throttled(block: suspend () -> T): T {
        gate.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (requestTimes.firstOrNull()?.let { now - it >= WINDOW_MS } == true) requestTimes.removeFirst()
                if (requestTimes.size < MAX_REQUESTS) {
                    requestTimes.addLast(now)
                    break
                }
                delay(WINDOW_MS - (now - requestTimes.first()))
            }
        }
        return block()
    }

    private fun DeezerTrackDto.toTrack(): Track =
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
        )

    private companion object {
        const val LIMIT = 25
        const val TIMEOUT_MS = 6_000L
        const val RETRY_DELAY_MS = 1_200L
        const val WINDOW_MS = 5_000L
        const val MAX_REQUESTS = 35
    }
}
