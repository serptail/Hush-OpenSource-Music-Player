package com.hush.core.common

import kotlin.math.max

/**
 * Shared fuzzy text matching used for:
 *  - re-ranking MusicBrainz search results against the user's raw query
 *  - deduping YouTube candidates against MusicBrainz tracks during unified search
 *  - scoring YouTube candidates during source resolution
 *
 * Extracted to core-common so feature-search and feature-resolver don't duplicate
 * (and drift on) normalization/similarity logic.
 */
object TextSimilarity {

    private val NOISE_PATTERN = Regex(
        "\\(official video\\)|\\(official audio\\)|\\(official music video\\)|\\(lyrics?\\)|" +
            "\\(lyric video\\)|\\(audio\\)|\\[official video\\]|\\(visualizer\\)|\\(official\\)|" +
            "\\(hd\\)|\\(4k\\)|\\(explicit\\)|\\(clean\\)",
    )
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
    private val EXTRA_WHITESPACE = Regex("\\s+")

    fun normalize(value: String): String = value.lowercase()
        .replace(NOISE_PATTERN, "")
        .replace(NON_ALPHANUMERIC, " ")
        .replace(EXTRA_WHITESPACE, " ")
        .trim()

    /** Normalized Levenshtein similarity in [0.0, 1.0], 1.0 = identical. */
    fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        left.forEachIndexed { i, leftChar ->
            current[0] = i + 1
            right.forEachIndexed { j, rightChar ->
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (leftChar == rightChar) 0 else 1,
                )
            }
            val swap = previous; previous = current; current = swap
        }
        return 1.0 - previous[right.length].toDouble() / max(left.length, right.length)
    }

    /** Convenience: similarity between two already-"artist title"-shaped strings, pre-normalized. */
    fun similarityNormalized(left: String, right: String): Double =
        similarity(normalize(left), normalize(right))
}
