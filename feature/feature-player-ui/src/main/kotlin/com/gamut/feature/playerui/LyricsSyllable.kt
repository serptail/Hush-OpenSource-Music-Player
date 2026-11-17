package com.gamut.feature.playerui

import androidx.compose.runtime.Immutable

@Immutable
data class LyricsSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
) {
    companion object {
        fun normalized(syllables: List<LyricsSyllable>, textLength: Int): List<LyricsSyllable> {
            if (syllables.size < 2) return emptyList()
            return syllables.map {
                val s = it.startChar.coerceIn(0, textLength)
                it.copy(startChar = s, endChar = it.endChar.coerceIn(s, textLength))
            }
        }
    }
}
