package com.hush.feature.resolver

import com.hush.core.model.ResolvedSource
import com.hush.core.model.ResolutionMethod
import com.hush.core.model.Track
import com.hush.core.network.YoutubeCandidate
import javax.inject.Inject
import kotlin.math.max

class CandidateScorer @Inject constructor() {
    companion object {
        const val TOPIC_MATCH_THRESHOLD = 0.86
        const val HARD_MATCH_THRESHOLD = 0.78
        const val DURATION_TOLERANCE_MS = 3_000L
    }

    fun selectBest(candidates: List<YoutubeCandidate>, track: Track): ResolvedSource? {
        val expected = normalize("${track.artist} ${track.title}")
        candidates.firstOrNull { it.channelIsTopicChannel && similarity(normalize(it.title), expected) >= TOPIC_MATCH_THRESHOLD }?.let {
            return ResolvedSource(track.isrc, it.videoId, ResolutionMethod.TOPIC_CHANNEL, 0.95f, 0)
        }
        return candidates
            .asSequence()
            .filter { kotlin.math.abs(it.durationMs - track.durationMs) <= DURATION_TOLERANCE_MS }
            .filter { similarity(normalize(it.title), expected) >= HARD_MATCH_THRESHOLD }
            .maxByOrNull { it.viewCount }
            ?.let { ResolvedSource(track.isrc, it.videoId, ResolutionMethod.HARD_FILTER, 0.82f, 0) }
    }

    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("\\(official video\\)|\\(official audio\\)|\\(lyrics?\\)|\\(lyric video\\)|\\(audio\\)|\\[official video\\]"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        left.forEachIndexed { i, leftChar ->
            current[0] = i + 1
            right.forEachIndexed { j, rightChar -> current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (leftChar == rightChar) 0 else 1) }
            val swap = previous; previous = current; current = swap
        }
        return 1.0 - previous[right.length].toDouble() / max(left.length, right.length)
    }
}