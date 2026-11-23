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
    )

    fun merge(items: List<Sourced>): List<Candidate> {
        val clusters = ArrayList<Cluster>()
        for (item in items) {
            val track = item.track
            val print = TrackIdentity.fingerprint(track.title, track.artist, track.durationMs)
            val hit = clusters.firstOrNull { TrackIdentity.sameRecording(it.print, print) }
            if (hit == null) {
                clusters += Cluster(track, print, item.popularity, item.position, item.penalty, item.boost, mutableSetOf(item.provider))
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
        return clusters.map { Candidate(it.track, it.popularity, it.position, it.penalty, it.boost, it.providers.size) }
    }
}
