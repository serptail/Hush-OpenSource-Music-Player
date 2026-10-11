package com.gamut.feature.playerui

/**
 * Re-times the estimated words of synced lines using the song's loudness envelope: words are placed only on the
 * voiced stretches, so a long silence inside a line ("yeah ..... yeah") is skipped instead of highlighted through.
 * Acts only when a clear silence is found inside the line; otherwise the original timing is kept.
 */
internal object LyricsAudioAligner {
    private const val SILENCE_DROP = 0.30f     // below (line peak - this) counts as silence
    private const val MIN_GAP_MS = 700L        // quieter than that for at least this long = a real pause, not a breath
    private const val MAX_LINE_MS = 15_000L

    fun refine(lines: List<LyricsLine>, env: FloatArray, durationMs: Long): List<LyricsLine> {
        if (env.size < 32 || durationMs <= 0L) return lines
        val dt = durationMs.toFloat() / env.size          // ms per envelope sample
        return lines.mapIndexed { i, line ->
            if (line.words.size < 2) return@mapIndexed line
            val limit = minOf(lines.getOrNull(i + 1)?.timestampMs ?: Long.MAX_VALUE, line.timestampMs + MAX_LINE_MS)
            val a = (line.timestampMs / dt).toInt().coerceIn(0, env.size - 1)
            val b = (limit / dt).toInt().coerceIn(a + 1, env.size)
            var peak = 0f
            for (k in a until b) if (env[k] > peak) peak = env[k]
            val floor = peak - SILENCE_DROP

            // Voiced runs [startMs, endMs), with short dips merged.
            val runs = ArrayList<LongArray>()
            var k = a
            while (k < b) {
                if (env[k] >= floor) {
                    var e = k
                    while (e < b && env[e] >= floor) e++
                    val s = (k * dt).toLong(); val en = (e * dt).toLong()
                    val last = runs.lastOrNull()
                    if (last != null && s - last[1] < MIN_GAP_MS) last[1] = en else runs.add(longArrayOf(s, en))
                    k = e
                } else k++
            }
            if (runs.size < 2) return@mapIndexed line      // no clear silence inside the line: keep the estimate

            val voiced = runs.sumOf { it[1] - it[0] }.coerceAtLeast(1L)
            fun realTime(v: Float): Long {                 // voiced-time offset -> song time, skipping the gaps
                var left = v
                for (r in runs) {
                    val len = (r[1] - r[0]).toFloat()
                    if (left <= len) return r[0] + left.toLong()
                    left -= len
                }
                return runs.last()[1]
            }
            val weights = line.words.map { maxOf(it.text.trim().length, 2) }
            val total = weights.sum().toFloat()
            var cum = 0f
            val words = line.words.mapIndexed { wi, w ->
                val s = realTime(voiced * cum / total)
                cum += weights[wi]
                val e = realTime(voiced * cum / total)
                LyricsWord(s, e.coerceAtLeast(s + 1), w.text)
            }
            line.copy(words = words, endMs = runs.last()[1])
        }
    }
}
