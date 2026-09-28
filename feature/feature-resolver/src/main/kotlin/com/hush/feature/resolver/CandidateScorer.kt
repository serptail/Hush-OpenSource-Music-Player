package com.hush.feature.resolver

import com.hush.core.common.TextSimilarity
import com.hush.core.model.ResolutionMethod
import com.hush.core.model.ResolvedSource
import com.hush.core.model.Track
import com.hush.core.network.YoutubeCandidate
import javax.inject.Inject

class CandidateScorer @Inject constructor() {
    companion object {
        const val TOPIC_MATCH_THRESHOLD = 0.86
        const val HARD_MATCH_THRESHOLD = 0.70
        const val DURATION_TOLERANCE_MS = 3_000L
        // --flat-playlist frequently omits duration entirely (returns null -> 0).
        // A missing duration must not be treated as "off by the whole song length".
        private const val MISSING_DURATION_PENALTY = 0.05
        private const val DURATION_MISMATCH_PENALTY_PER_SEC = 0.01
        private const val MAX_DURATION_PENALTY = 0.25
    }

    fun selectBest(candidates: List<YoutubeCandidate>, track: Track): ResolvedSource? {
        if (candidates.isEmpty()) return null
        val expected = TextSimilarity.normalize("${track.artist} ${track.title}")

        // Confident path: a YouTube Music "Songs"-catalog / auto-generated topic-channel
        // result with a close title match. This previously could never fire because
        // channelIsTopicChannel was hardcoded false upstream.
        candidates
            .filter { (it.channelIsTopicChannel || it.isMusicCatalog) }
            .maxByOrNull { TextSimilarity.similarityNormalized(it.title, "${track.artist} ${track.title}") }
            ?.takeIf { TextSimilarity.similarityNormalized(it.title, "${track.artist} ${track.title}") >= TOPIC_MATCH_THRESHOLD }
            ?.let { return ResolvedSource(track.isrc, it.videoId, ResolutionMethod.TOPIC_CHANNEL, 0.95f, 0) }

        // Fallback path: score every candidate on title similarity, then subtract a soft
        // penalty for duration mismatch. A missing/zero duration gets a small flat penalty
        // instead of being compared literally against the real duration and rejected outright.
        return candidates
            .asSequence()
            .map { candidate ->
                val titleScore = TextSimilarity.similarityNormalized(candidate.title, "${track.artist} ${track.title}")
                candidate to (titleScore - durationPenalty(candidate, track))
            }
            .filter { (_, score) -> score >= HARD_MATCH_THRESHOLD }
            .maxByOrNull { (candidate, score) -> score + viewCountTiebreak(candidate) }
            ?.let { (candidate, score) ->
                ResolvedSource(track.isrc, candidate.videoId, ResolutionMethod.HARD_FILTER, score.toFloat().coerceIn(0f, 1f), 0)
            }
    }

    private fun durationPenalty(candidate: YoutubeCandidate, track: Track): Double {
        if (candidate.durationMs <= 0L) return MISSING_DURATION_PENALTY
        val deltaMs = kotlin.math.abs(candidate.durationMs - track.durationMs)
        if (deltaMs <= DURATION_TOLERANCE_MS) return 0.0
        val deltaSeconds = (deltaMs - DURATION_TOLERANCE_MS) / 1000.0
        return (deltaSeconds * DURATION_MISMATCH_PENALTY_PER_SEC).coerceAtMost(MAX_DURATION_PENALTY)
    }

    /** Tiny nudge so that among near-equal title/duration scores, the more-watched upload wins. */
    private fun viewCountTiebreak(candidate: YoutubeCandidate): Double =
        (kotlin.math.ln((candidate.viewCount + 1).toDouble()) / 1_000_000.0)
}
