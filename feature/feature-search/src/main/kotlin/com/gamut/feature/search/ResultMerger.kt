package com.gamut.feature.search

import com.gamut.core.common.TrackIdentity
import com.gamut.core.model.Track

internal enum class Provider { APPLE, DEEZER, YOUTUBE }

/** One hit from one provider, before it is known whether another provider returned the same recording. */
internal data class Sourced(
    val track: Track,
    val popularity: Double,
    val position: Int,
    val provider: Provider,
    val penalty: Double = 0.0,
    val boost: Double = 0.0,
    /** The release this hit came from is a single (Apple names those "... - Single"; Deezer says so). */
    val isSingle: Boolean = false,
)

/**
 * Folds hits from every provider into one candidate per recording.
 *
 * Items are expected in priority order (Apple first), because the first hit of a cluster supplies the
 * metadata that is shown. Later hits only fill gaps and contribute ranking evidence: the best position
 * any provider gave it, the highest popularity, and how many independent providers returned it.
 */
internal object ResultMerger {
    private class Cluster(
        var track: Track,
        val print: TrackIdentity.Fingerprint,
        var popularity: Double,
        var position: Int,
        var penalty: Double,
        var boost: Double,
        val providers: MutableSet<Provider>,
    ) {
        /** The cover of the first single this recording was found on, when one was. */
        var singleArt: String? = null
    }

    /**
     * With [preferSingleArt], a recording that appears on a single as well as on an album (or a deluxe) takes the
     * single's cover instead of whichever release happened to come first. Only the picture changes: the title, album
     * name and ranking stay as they were.
     */
    fun merge(
        items: List<Sourced>,
        preferSingleArt: Boolean = false,
    ): List<Candidate> {
        val clusters = ArrayList<Cluster>()
        for (item in items) {
            val track = item.track
            val print = TrackIdentity.fingerprint(track.title, track.artist, track.durationMs)
            val hit = clusters.firstOrNull { TrackIdentity.sameRecording(it.print, print) }
            val cluster =
                hit ?: Cluster(track, print, item.popularity, item.position, item.penalty, item.boost, mutableSetOf(item.provider))
            if (preferSingleArt && item.isSingle && cluster.singleArt == null && !track.albumArtUrl.isNullOrBlank()) {
                cluster.singleArt = track.albumArtUrl
            }
            if (hit == null) {
                clusters += cluster
            } else {
                hit.popularity = maxOf(hit.popularity, item.popularity)
                hit.position = minOf(hit.position, item.position)
                hit.penalty = minOf(hit.penalty, item.penalty)
                hit.boost = maxOf(hit.boost, item.boost)
                hit.providers += item.provider
                hit.track =
                    hit.track.copy(
                        album = hit.track.album ?: track.album,
                        albumArtUrl = hit.track.albumArtUrl ?: track.albumArtUrl,
                        isExplicit = hit.track.isExplicit ?: track.isExplicit,
                        previewUrl = hit.track.previewUrl ?: track.previewUrl,
                        durationMs = if (hit.track.durationMs > 0) hit.track.durationMs else track.durationMs,
                    )
            }
        }
        return clusters.map {
            val shown = it.singleArt?.let { art -> it.track.copy(albumArtUrl = art) } ?: it.track
            Candidate(shown, it.popularity, it.position, it.penalty, it.boost, it.providers.size)
        }
    }
}
