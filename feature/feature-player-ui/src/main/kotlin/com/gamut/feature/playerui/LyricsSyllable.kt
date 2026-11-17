package com.gamut.feature.playerui

import androidx.compose.runtime.Immutable

/** One sung piece of a word: its time, and which characters of the word's text it covers. */
@Immutable
data class LyricsSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
) {
    companion object {
        /** A word nobody split (fewer than two pieces) gets none; ranges are clamped to the word's text. */
        fun normalized(syllables: List<LyricsSyllable>, textLength: Int): List<LyricsSyllable> {
            if (syllables.size < 2) return emptyList()
            return syllables.map {
                val s = it.startChar.coerceIn(0, textLength)
                it.copy(startChar = s, endChar = it.endChar.coerceIn(s, textLength))
            }
        }
    }
}