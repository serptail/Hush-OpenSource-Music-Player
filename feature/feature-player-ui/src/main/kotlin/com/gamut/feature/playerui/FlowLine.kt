package com.gamut.feature.playerui

import androidx.compose.runtime.Immutable

/*
 * The model behind the flowing lyrics, ported from BitChord's LyricLine.kt and LyricGaps.kt. Every number kept
 * below is BitChord's, with its reasoning: the feel of the sweep, the lift and the bloom lives in them.
 *
 * Left out, because Gamut's lyric sources carry nothing for them: syllable splits inside a word, answering
 * (background) vocals, duet lanes and translations. A word is therefore always its own single syllable, which is
 * exactly what BitChord does with a word nobody split.
 */

/** One word of a line, with the stretch of the song it is sung over. */
@Immutable
internal data class FlowWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<LyricsSyllable> = emptyList(),
) {
    /**
     * How many of [text]'s characters have been sung at [positionMs], as a fraction. Each syllable fills its own
     * characters over its own time, and a pause between two of them rests on the boundary rather than creeping into
     * the next one before it is sung: "rea" is swept and "lly" is held. A word nobody split fills at a steady rate.
     */
    fun charsSungAt(positionMs: Long): Float {
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()
        if (syllables.isNotEmpty()) {
            for (syllable in syllables) {
                if (positionMs < syllable.startMs) return syllable.startChar.toFloat()
                if (positionMs < syllable.endMs) {
                    val span = (syllable.endMs - syllable.startMs).coerceAtLeast(1L)
                    val through = (positionMs - syllable.startMs).toFloat() / span
                    return syllable.startChar + through * (syllable.endChar - syllable.startChar)
                }
            }
            return text.length.toFloat()
        }
        val span = (endMs - startMs).coerceAtLeast(1L)
        return (positionMs - startMs).toFloat() / span * text.length
    }
}

/**
 * One line. [timeMs] is when it starts; a blank [text] is an instrumental stretch.
 *
 * [explicitEndMs] is the line's own end where the source states or estimates one, which is what lets an
 * interlude be told apart from a slowly sung line.
 */
@Immutable
internal data class FlowLine(
    val timeMs: Long,
    val text: String,
    val words: List<FlowWord> = emptyList(),
    val explicitEndMs: Long? = null,
) {
    val isGap: Boolean get() = text.isEmpty()

    val isWordSynced: Boolean get() = words.isNotEmpty()

    /**
     * Where each of [words] sits in [text], as character ranges. Walked from where the last one ended rather
     * than searched from the start, so a word repeated in the line lines up with its own occurrence.
     */
    val wordSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        var offset = 0
        words.map { word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = start + word.text.length
            offset = end
            start until end
        }
    }

    /** The timed pieces of the line the sweep crosses at a steady pace each: a word's syllables where split, else the word. */
    val sweepSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        words.flatMapIndexed { index, word ->
            val span = wordSpans[index]
            if (word.syllables.isEmpty()) {
                listOf(span)
            } else {
                word.syllables.map { (span.first + it.startChar) until (span.first + it.endChar) }
            }
        }
    }

    /**
     * Whether anything on this line is off the floor at [positionMs]. Two comparisons, so the lines that are
     * not being sung, which is every line but one on every frame, settle the question without walking words.
     */
    fun isLifted(positionMs: Long): Boolean {
        val first = words.firstOrNull() ?: return false
        if (positionMs <= first.startMs) return false
        // A word animated letter by letter runs on past the ordinary fall: its last letter only starts moving as
        // the word ends. Stopping at the fall would drop that letter back to the line mid-swell.
        return positionMs < words.last().endMs + RISE_MS || isGrowing(positionMs)
    }

    /**
     * The stretch of the song over which anything drawn for this line moves: the sweep, the lift and its settle,
     * a held word's letters. Before it the line is drawn unsung, after it sung and at rest, and either way
     * exactly the same on every frame, which is what lets a line outside it stop listening to the clock.
     */
    val animatesFromMs: Long by lazy(LazyThreadSafetyMode.NONE) {
        minOf(timeMs, words.firstOrNull()?.startMs ?: timeMs)
    }

    /** See [animatesFromMs]. */
    val animatesUntilMs: Long by lazy(LazyThreadSafetyMode.NONE) {
        val settled = words.lastOrNull()?.let { it.endMs + RISE_MS.toLong() } ?: endMs
        val grown = growingWords.maxOfOrNull { it.restsAtMs } ?: Long.MIN_VALUE
        maxOf(endMs, settled, grown)
    }

    /**
     * How far the word [index] has lifted, 0..1. Words rise as they land and settle back once they are past.
     * How high a word goes is set by how long it is held: patter barely leaves the line and a carried note goes
     * all the way up. It rises over its own length and settles back over [RISE_MS] from its end.
     */
    fun wordLift(index: Int, positionMs: Long): Float {
        val word = words.getOrNull(index) ?: return 0f
        val riseMs = (word.endMs - word.startMs).toFloat().coerceIn(RISE_MIN_MS, RISE_MS)
        val rising = ((positionMs - word.startMs) / riseMs).coerceIn(0f, 1f)
        val falling = (1f - (positionMs - word.endMs) / RISE_MS).coerceIn(0f, 1f)
        return liftHeight(word) * smooth(minOf(rising, falling))
    }

    /** Whether anything told us when the singing stops, rather than only when it starts. */
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || (explicitEndMs ?: timeMs) > timeMs

    /** When the last word finishes, or the line's own end where the source gave one, or [timeMs]. */
    val endMs: Long
        get() = words.lastOrNull()?.endMs ?: explicitEndMs ?: timeMs

    /**
     * How far through the line the singing has got, as a fractional index into [text]. The sweep reveals up to
     * this character. Within a word it interpolates across that word's own span; whitespace between two words
     * is credited to the gap between them, so it fills as the singer moves on rather than jumping ahead.
     */
    fun revealedChars(positionMs: Long): Float {
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        var offset = 0
        words.forEachIndexed { index, word ->
            val start = text.indexOf(word.text, offset).takeIf { it >= 0 } ?: offset
            val end = start + word.text.length
            if (positionMs < word.startMs) return start.toFloat()
            if (positionMs < word.endMs) return start + word.charsSungAt(positionMs)
            // Past this word: the trailing space fills over the pause before the next one, so the highlight
            // keeps creeping instead of resting on the word's last letter.
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val gapStart = text.indexOf(next.text, end).takeIf { it >= 0 } ?: end
                val pause = (next.startMs - word.endMs).coerceAtLeast(1L)
                val through = (positionMs - word.endMs).toFloat() / pause
                return end + through * (gapStart - end)
            }
            offset = end
        }
        return text.length.toFloat()
    }

    /**
     * How much of a word's lift is left at [positionMs]: its full height while being sung, easing to 0 over
     * [RISE_MS] once it is past. The half of the movement a letter-by-letter word shares with every other word.
     */
    fun wordFall(index: Int, positionMs: Long): Float {
        val word = words.getOrNull(index) ?: return 0f
        val falling = (1f - (positionMs - word.endMs) / RISE_MS).coerceIn(0f, 1f)
        return liftHeight(word) * smooth(falling)
    }

    /**
     * The words held long enough to be worth animating letter by letter. Rare by design: most lines have none
     * and pay a walk of their words once, on the first frame they are drawn.
     */
    val growingWords: List<GrowingWord> by lazy(LazyThreadSafetyMode.NONE) {
        words.mapIndexedNotNull { index, word ->
            if (word.canGrow()) GrowingWord(index, word) else null
        }
    }

    /** The letter-by-letter treatment for word [index], where it has earned one. */
    fun growingAt(index: Int): GrowingWord? = growingWords.firstOrNull { it.index == index }

    /** Whether any word on this line is mid-flight at [positionMs]. */
    fun isGrowing(positionMs: Long): Boolean = growingWords.any {
        positionMs >= it.startMs && positionMs <= it.restsAtMs
    }
}

/**
 * One word held long enough to be animated a letter at a time.
 *
 * Every letter runs the same three-beat move (swell up and forward, hold, then settle back to the small lift every
 * sung word carries), but each starts [GROW_STAGGER] of the word's own length after the one before it, so the
 * movement travels along the word instead of the whole thing pulsing at once. Later letters of a long word, and
 * every letter of a word only just held long enough to qualify, move and light less.
 */
internal class GrowingWord(
    /** Which of the line's words this is. */
    val index: Int,
    word: FlowWord,
) {
    val startMs: Long = word.startMs
    val endMs: Long = word.endMs

    private val chars: Int = word.text.length
    private val scalePeak = FloatArray(chars)
    private val shiftPeak = FloatArray(chars)
    private val risePeak = FloatArray(chars)
    private val bloomPeak = FloatArray(chars)

    /** When the last letter has finished moving and is just sitting lifted. */
    val restsAtMs: Long

    init {
        val held = (endMs - startMs).coerceAtLeast(1L).toFloat()
        // How much of the full treatment this word has earned. Cubed, so the difference between a word held for
        // a second and one held for three is most of the range and everything under a second is barely anything.
        val earned = ((held - GROW_RAMP_MIN_MS) / (GROW_RAMP_MAX_MS - GROW_RAMP_MIN_MS))
            .coerceIn(0f, 1f)
            .let { it * it * it }
        val decay = decayRate(chars, held)
        val bloomPace = minOf(GROW_BLOOM_PACE_MAX, held / GROW_BLOOM_PACE_MS)
        val bloomSpread = when {
            chars <= 3 -> GROW_BLOOM_SHORT
            chars >= 6 -> GROW_BLOOM_LONG
            else -> 1f
        }
        // Short words swell a little more, having fewer letters to do it with.
        val base = if (chars <= 3) GROW_BASE_SHORT else GROW_BASE_LONG
        val liftPace = (held / GROW_LIFT_PACE_MS).coerceIn(GROW_LIFT_FLOOR, 1f)

        for (i in 0 until chars) {
            val place = if (chars > 1) i.toFloat() / (chars - 1) else 0f
            val reach = earned * (1f - place * decay)
            val scale = 1f + base + reach * GROW_SCALE_RANGE
            scalePeak[i] = scale * GROW_SCALE_TRIM
            bloomPeak[i] = (GROW_BLOOM_FLOOR + reach * GROW_BLOOM_RANGE) * bloomPace * bloomSpread
            // The lift is read off the swell rather than set on its own: a letter that grows more rises further,
            // which keeps the two reading as one movement instead of two.
            risePeak[i] = ((scale - 1f) / GROW_SCALE_CEILING) * liftPace
            // Letters lean away from the middle of the word as it swells, so it opens outwards.
            val centre = (i + 0.5f) / chars
            shiftPeak[i] = (centre - 0.5f) * 2f * (scale - 1f) * GROW_SHIFT_EM * GROW_SCALE_TRIM
        }

        val last = (chars - 1).coerceAtLeast(0) * GROW_STAGGER + GROW_SPAN
        restsAtMs = startMs + (held * last).toLong()
    }

    /**
     * Where letter [charIndex] has got to at [positionMs], written into [into] rather than returned: this runs
     * for every letter of the word on every frame, three times over (dim copy, lit copy and bloom), and a line's
     * worth of short-lived objects per frame is a cost with nothing to show for it.
     *
     * [CharGrowth.rise] comes back in multiples of the ordinary sung-word lift, and [CharGrowth.shift] in ems.
     */
    fun sampleInto(charIndex: Int, positionMs: Long, into: CharGrowth) {
        val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val elapsed = positionMs - startMs - charIndex * span * GROW_STAGGER
        val phase = (elapsed / (span * GROW_SPAN)).coerceIn(0f, 1f)
        val peakScale = scalePeak[charIndex]
        when {
            phase < GROW_IN -> {
                val t = smooth(phase / GROW_IN)
                into.scale = 1f + (peakScale - 1f) * t
                into.shift = shiftPeak[charIndex] * t
                into.rise = risePeak[charIndex] * t
                into.bloom = bloomPeak[charIndex] * t
            }

            phase < GROW_HOLD -> {
                into.scale = peakScale
                into.shift = shiftPeak[charIndex]
                into.rise = risePeak[charIndex]
                into.bloom = bloomPeak[charIndex]
            }

            phase < GROW_OUT -> {
                val t = smooth((phase - GROW_HOLD) / (GROW_OUT - GROW_HOLD))
                into.scale = peakScale + (1f - peakScale) * t
                into.shift = shiftPeak[charIndex] * (1f - t)
                into.rise = risePeak[charIndex] + (GROW_REST - risePeak[charIndex]) * t
                into.bloom = bloomPeak[charIndex] * (1f - t)
            }

            else -> {
                into.scale = 1f
                into.shift = 0f
                into.rise = GROW_REST
                into.bloom = 0f
            }
        }
    }
}

/** Where one letter of a [GrowingWord] is, filled in by [GrowingWord.sampleInto]. */
internal class CharGrowth {
    /** Swell, about the letter's own centre. 1 is the letter as laid out. */
    var scale: Float = 1f

    /** Lean away from the middle of the word, in ems. */
    var shift: Float = 0f

    /** Lift, in multiples of the ordinary sung-word rise. */
    var rise: Float = 0f

    /** Bloom, 0..1, at its own peak partway up rather than at the top. */
    var bloom: Float = 0f
}

/** How long a word takes to rise, and to settle back down once it is past. */
private const val RISE_MS = 700f

/** The quickest a word ever rises: a 90 ms syllable snapping up reads as a twitch. */
private const val RISE_MIN_MS = 250f

/**
 * How far up a word goes, as a share of the full lift, from how long it is held. A word sung in [LIFT_QUICK_MS] or
 * less only nudges up by [LIFT_FLOOR]; one held for [LIFT_HELD_MS] or more goes the whole way, eased between.
 */
private fun liftHeight(word: FlowWord): Float {
    val held = (word.endMs - word.startMs).toFloat()
    val through = ((held - LIFT_QUICK_MS) / (LIFT_HELD_MS - LIFT_QUICK_MS)).coerceIn(0f, 1f)
    return LIFT_FLOOR + (1f - LIFT_FLOOR) * smooth(through)
}

private const val LIFT_QUICK_MS = 150f
private const val LIFT_HELD_MS = 900f
private const val LIFT_FLOOR = 0.25f

/** Ease in and out of the ends, so the lift has no corners on it. */
private fun smooth(fraction: Float) = fraction * fraction * (3f - 2f * fraction)

/**
 * Whether this word is held long enough, and is short enough, to be animated a letter at a time. The thresholds
 * are steeper for short words than length alone suggests: a two-letter word held for a second is a held note,
 * whereas a seven-letter one held for the same second is just being sung at an ordinary pace.
 *
 * Scripts whose letters are not laid out side by side independently are left alone (Han, kana and Hangul draw
 * as blocks, right-to-left scripts join up), and so is a hyphenated word, which would break at the hyphen.
 */
private fun FlowWord.canGrow(): Boolean {
    val length = text.length
    if (length == 0 || length > GROW_MAX_CHARS) return false
    if ('-' in text || text.any { it.isBlockScript() || it.isJoinedScript() }) return false
    val held = endMs - startMs
    return when {
        length == 1 -> held >= GROW_MIN_SOLO_MS
        length <= 3 -> held >= GROW_MIN_SHORT_MS + (length - 2) * GROW_SHORT_STEP_MS
        length == 4 -> held >= GROW_MIN_FOUR_MS
        else -> held >= GROW_MIN_LONG_MS && held >= length * GROW_MS_PER_CHAR
    }
}

/**
 * How much of the movement is taken back off the later letters of a word: a long word is a lot of letters to run
 * a wave through, and a word held only just long enough to qualify has no room for a wave at all.
 */
private fun decayRate(length: Int, heldMs: Float): Float {
    val long = length > GROW_DECAY_LONG_CHARS
    val quick = heldMs < GROW_DECAY_QUICK_MS
    if (!long && !quick) return 0f
    var strength = 0f
    if (long) {
        strength += minOf((length - GROW_DECAY_LONG_CHARS) / 5f, 1f) * GROW_DECAY_LONG
    }
    if (quick) {
        val short = maxOf(0f, 1f - (heldMs - GROW_DECAY_QUICK_FLOOR_MS) / 400f)
        strength += short * if (length > 3) GROW_DECAY_QUICK else GROW_DECAY_QUICK_TINY
    }
    return minOf(strength, GROW_DECAY_MAX)
}

private fun Char.isBlockScript(): Boolean =
    this in '\u4E00'..'\u9FFF' || this in '\u3040'..'\u309F' ||
        this in '\u30A0'..'\u30FF' || this in '\uAC00'..'\uD7AF'

private fun Char.isJoinedScript(): Boolean = this in '\u0590'..'\u08FF'

/** Longest word worth running a wave through; past this it reads as a ripple. */
private const val GROW_MAX_CHARS = 7

/** How long a word of each length has to be held before it qualifies. */
private const val GROW_MIN_SOLO_MS = 1_100L
private const val GROW_MIN_SHORT_MS = 1_360L
private const val GROW_SHORT_STEP_MS = 140L
private const val GROW_MIN_FOUR_MS = 1_050L
private const val GROW_MIN_LONG_MS = 900L
private const val GROW_MS_PER_CHAR = 200L

private const val GROW_DECAY_LONG_CHARS = 5
private const val GROW_DECAY_QUICK_MS = 1_200f
private const val GROW_DECAY_QUICK_FLOOR_MS = 800f
private const val GROW_DECAY_LONG = 0.4f
private const val GROW_DECAY_QUICK = 0.3f
private const val GROW_DECAY_QUICK_TINY = 0.1f
private const val GROW_DECAY_MAX = 0.7f

/** Each letter starts this far, as a share of the word's own length, after the one before it. */
private const val GROW_STAGGER = 0.09f

/** The whole move runs half again as long as the word is held. */
private const val GROW_SPAN = 1.5f

/** Up by [GROW_IN], held to [GROW_HOLD], settled by [GROW_OUT]. */
private const val GROW_IN = 0.25f
private const val GROW_HOLD = 0.30f
private const val GROW_OUT = 0.75f

/** Where a letter comes to rest: the same small lift every sung word carries. */
private const val GROW_REST = 1f

/** The window over which a longer hold earns more of the treatment. */
private const val GROW_RAMP_MIN_MS = 400f
private const val GROW_RAMP_MAX_MS = 3_000f

/** Swell every qualifying letter gets, and the range the hold adds on top. */
private const val GROW_BASE_SHORT = 0.05f
private const val GROW_BASE_LONG = 0.04f
private const val GROW_SCALE_RANGE = 0.08f

/** The most any letter swells, which is what [GrowingWord] reads the lift off. */
private const val GROW_SCALE_CEILING = 0.1f

/** Taken back off the swell so it stops a hair short of the ceiling. */
private const val GROW_SCALE_TRIM = 0.98f

/** Lean, as a share of the font size at full swell. */
private const val GROW_SHIFT_EM = 25f / 34f

/** The bloom's floor, and what the hold adds to it. */
private const val GROW_BLOOM_FLOOR = 0.35f
private const val GROW_BLOOM_RANGE = 0.45f
private const val GROW_BLOOM_PACE_MS = 1_500f
private const val GROW_BLOOM_PACE_MAX = 1.1f
private const val GROW_BLOOM_SHORT = 0.85f
private const val GROW_BLOOM_LONG = 1.1f

/** A word held this long lifts as far as it is going to. */
private const val GROW_LIFT_PACE_MS = 2_000f
private const val GROW_LIFT_FLOOR = 0.3f

// ---------------------------------------------------------------------------------------------
// Instrumental breaks and the rows being sung
// ---------------------------------------------------------------------------------------------

/** Shorter instrumental breaks aren't worth interrupting the line for. */
private const val MIN_GAP_MS = 4_000L

/**
 * Marks the instrumental stretches with blank lines. A break is only drawn where the line before it says when its
 * singing stopped (see [FlowLine.hasKnownEnd]), so the dots appear the moment the vocal ends rather than several
 * seconds later, once the next line was due.
 */
internal fun List<FlowLine>.withInstrumentalBreaks(): List<FlowLine> {
    if (isEmpty()) return this
    val out = ArrayList<FlowLine>(size + 4)
    // Nothing stands for the intro, so give the run-up its own break.
    if (first().timeMs >= MIN_GAP_MS) out += FlowLine(0L, "")
    forEachIndexed { index, line ->
        out += line
        val next = getOrNull(index + 1) ?: return@forEachIndexed
        if (!line.hasKnownEnd) return@forEachIndexed
        val silence = next.timeMs - line.endMs
        // A marker sharing its line's stamp could never be reached: the cursor takes the last line whose stamp
        // has passed, so the dots would sit on top of the line they belong to and the words would never light.
        if (silence >= MIN_GAP_MS && line.endMs > line.timeMs) out += FlowLine(line.endMs, "")
    }
    return out
}

/** How far back a line that is still being sung can sit behind the cursor and still own the highlight. */
private const val ACTIVE_LOOKBACK = 3

/**
 * The rows being sung at [positionMs], uppermost first: the last line whose stamp has passed, and any of the few
 * before it that are still going. The uppermost unfinished one owns the scroll anchor until its end, even as later
 * rows begin their own highlight.
 */
internal fun activeFlowRows(lines: List<FlowLine>, positionMs: Long): List<Int> {
    var lo = 0
    var hi = lines.lastIndex
    var cursor = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            cursor = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (cursor < 0) return emptyList()
    var first = cursor
    for (back in 1..ACTIVE_LOOKBACK) {
        val index = cursor - back
        val line = lines.getOrNull(index) ?: break
        if (!line.isGap && line.hasKnownEnd && line.endMs > positionMs) first = index
    }
    if (first == cursor) return listOf(cursor)
    return (first..cursor).filter { index ->
        index == cursor || lines[index].let { !it.isGap && it.hasKnownEnd && it.endMs > positionMs }
    }
}