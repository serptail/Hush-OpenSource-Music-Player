package com.gamut.feature.playerui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The sweep: a line with the sung part of it lit, the rest dimmed, and the boundary travelling across the words in
 * time with the vocal. Ported from BitChord's SweptLyricLine and the draw helpers around it, with their numbers and
 * their reasons. The sweep, the feathered edge, the lift of each word and the bloom of a held note are what give the
 * lyrics their feel, so none of it is simplified.
 */

/** Returns true if [text] is mostly written in a right-to-left script. */
internal fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
        // Hebrew presentation forms.
        if (code in 0xFB1D..0xFB4F) return true
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            -> rtlCount++

            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltrCount++
            else -> Unit
        }
    }
    return rtlCount > ltrCount
}

/** The style with alignment and direction taken from the line's own text, so a Hebrew line sits against the right. */
internal fun flowVoiceStyle(base: TextStyle, text: String): TextStyle {
    val rtl = isRtl(text)
    return base.copy(
        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
    )
}

/**
 * How far back the part of the playing line that hasn't been sung yet is held. The strip above the scrubber gets
 * less of a gap than the full panel: it is one line of small type with nothing around it to compare against, and
 * taking it as far down as the panel does left the words ahead of the highlight hard to read at a glance.
 */
internal const val UNSUNG_ALPHA = 0.45f
internal const val UNSUNG_ALPHA_STRIP = 0.55f

/**
 * The bloom behind the line being sung, at its very strongest. Kept well under half strength: the halo is drawn
 * from the same white as the text, so at full alpha it stops reading as light and starts reading as a second,
 * badly printed copy of the words. What is actually drawn is this scaled by each letter's own bloom, so only a
 * properly carried note ever sees the whole of it.
 */
internal const val GLOW_ALPHA = 0.62f

/** How far the bloom spreads off a letter. Tight, because it is a letter's worth of light, not a word's. */
internal val GLOW_RADIUS = 6.dp

/**
 * Room reserved inside each copy of a line for the halo to spread into. A blur is computed on its layer's own
 * bitmap, so a halo with nowhere to go inside those bounds has a hard edge. Every copy carries the same inset so
 * they still lay out identically, and the list gives the width back by taking it off its own padding.
 */
internal val GLOW_ROOM = 10.dp

/**
 * How far the sweep's leading edge fades out instead of ending on a cut. A hard boundary reads as a bar travelling
 * across the words rather than the words lighting up as they are sung; feathered over a character and a half it
 * turns back into a wavefront.
 */
private val WIPE_FEATHER = 30.dp

/** How far the word being sung lifts off the line: enough that the eye catches it, little enough to stay attached. */
private val WORD_RISE = 2.dp

/** How much further up a row is opened when it holds a word animated letter by letter, in multiples of [WORD_RISE]. */
private const val GROW_HEADROOM = 3f

/** Where a lyric line is in its own animation. */
private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

/**
 * A lyric line with the sung part lit and the rest dimmed: two copies of the same text stacked, a dim one and a
 * bright one clipped to whatever has been sung. Same string, same style, same constraints, so the bright copy lands
 * exactly on top of the dim one. Colouring an AnnotatedString word by word can only change a whole word at a time,
 * which turns the sweep into a flicker.
 *
 * The clip is recomputed in the draw phase, so a frame costs one clip and one redraw of already-measured text.
 *
 * [glowAlpha] adds Apple's bloom: a third copy, blurred, behind the other two and clipped to the letters of
 * whatever word is being held. Blurring after the clip rather than before is what makes the halo bleed out past
 * the letter it belongs to.
 */
@Composable
internal fun SweptFlowLine(
    line: FlowLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    color: Color = Color.White,
    /** The unsung copy's colour; the sung copy and the bloom use [color]. */
    dimColor: Color = color,
    centered: Boolean = false,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Filled in and read back a letter at a time inside the draw lambdas, and shared by all three copies of the
    // line: they draw one after another on the same thread, so there is only ever one letter in hand.
    val growth = remember { CharGrowth() }

    // Where this line is in its own animation (not started, moving, or done), which changes three times a line
    // rather than sixty times a second. The draw passes below read the clock only while it says moving; on either
    // side they draw a position that looks identical to any other there, so a line nobody is singing never
    // redraws. Read in draw, every line on screen used to redraw on every frame of playback, and each line away
    // from the playing one sits under a blur that then had to be worked out again.
    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PHASE_BEFORE
                now > line.animatesUntilMs -> PHASE_AFTER
                else -> PHASE_MOVING
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            PHASE_MOVING -> clock.longValue
            PHASE_BEFORE -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    // Carried by every copy: identical insets keep them laying out identically, and the inset is what gives the
    // blurred copy's layer somewhere to put the halo. Off unless asked for: only the full panel can afford it.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    // Sits outside [room] and outside the sweep, so what it moves is the finished picture of the word (dim tail,
    // lit head and all) rather than one copy sliding out from under another. Off for the one-line strip.
    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            // Sung and done with: all of it is lit. Checked first so the lines above and below the playing one,
            // which are in this same state for minutes at a time, cost a comparison per frame.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let {
                sweepTo(it, line.revealedChars(position), line.sweepSpans, feather)
            }
        }
    }

    // With a coloured sung copy the dim one is cut away behind it, so no pale edge shows around the lit letters.
    val dimSweep = if (dimColor == color) {
        Modifier
    } else {
        Modifier.drawWithContent {
            val position = drawnAt()
            when {
                position >= line.endMs -> Unit
                position <= line.timeMs -> drawContent()
                else -> layout?.let { unsungOnly(it, line.revealedChars(position), line.sweepSpans) } ?: drawContent()
            }
        }
    }

    val right = isRtl(line.text)
    Box(
        modifier,
        contentAlignment = when {
            centered -> androidx.compose.ui.Alignment.TopCenter
            right -> AbsoluteAlignment.TopRight
            else -> AbsoluteAlignment.TopLeft
        },
    ) {
        Text(
            text = line.text,
            style = style,
            color = dimColor.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room.then(dimSweep)),
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // Each letter is masked to its own brightness with DstIn, which needs a layer of its own to
                    // erase into: against the backdrop it would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights everything sung so far, and this lights
                        // only the words being held. Most lines draw nothing here at all, which is the whole
                        // difference between this and a halo travelling under the highlight.
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = drawnAt(),
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            // The feather erases into this layer, so the layer has to exist, and only while it is being drawn.
            // Every line carrying one would put the whole panel through an offscreen buffer to soften an edge
            // that at most two of them have.
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * Draws this text clipped to the letters of the words being held, each at its own brightness: the light the singing
 * is actually giving off, rather than a band of it dragged along behind the highlight. Nothing at all on a line of
 * ordinary syllables, so a verse of patter is simply dark and costs one comparison to establish.
 *
 * Each letter is masked to its own bloom rather than drawn at it, because the caller's layer is what this erases
 * into. The mask lands before the blur, so what spreads is already the right brightness.
 */
private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: FlowLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            // Row-aware, for the same reason the sweep is; see [xOn].
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                // Scoped to this letter's own clip, so it takes this letter's brightness down and leaves its
                // neighbours, which have their own, a beat behind, where they are.
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/**
 * Redraws this row with the word being sung lifted off the line, and the ones behind it settling back down.
 *
 * The line is cut at word boundaries and each piece replayed at its own height, which is what CSS gets for free by
 * making every syllable its own box. Cutting between words rather than inside one means no glyph is ever sliced,
 * and the pieces that are on the floor, which is most of them most of the time, are one replay between them.
 * Costs nothing at all until something is off the floor.
 */
private fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: FlowLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        // The row's own box. Anything standing still is clipped to exactly this: a band opened upwards would take
        // in the bottom of the row above and draw it a second time, and two passes of a half-transparent line do
        // not add up to the same line. That doubled sliver along every row is what read as the lines overlapping.
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.rowBottom(visualLine, em) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            // Only while it is actually moving. Once the last letter has come to rest the word is back to being an
            // ordinary sung word settling down, and the two agree exactly at the handover.
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            // A word with nothing happening to it is left to the flat run.
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            if (to <= from) continue
            // Everything between the last risen word and this one is flat, and goes down in a single piece.
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                // Only what is off the floor gets room above the row to be off it in.
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

/**
 * Redraws one held word a letter at a time, each at its own swell and height. The word is cut between characters
 * so a letter can be scaled about its own centre without the ones either side of it coming along. Each piece is
 * clipped to where its letter is going rather than where it sits: a glyph grown about its middle reaches past the
 * box it was laid out in, and clipping to that box would shave both sides off it as it swells.
 */
@Suppress("LongParameterList")
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: FlowLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    // The settle is shared with every other word: a letter comes to rest at the same small lift, and then goes
    // down with the rest of the line.
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    // Room to swell into, above the row rather than inside it. The pivot stays on the row's own middle: scaling
    // about the middle of the band would walk every letter downwards as it grew.
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipShiftedDown(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
            dy = dy,
        ) {
            translate(left = dx) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

/**
 * Where a character offset sits horizontally on the row it was cut out of, in pixels.
 *
 * [TextLayoutResult.getHorizontalPosition] answers for the row the offset itself belongs to, and the offset one past
 * the last character of a wrapped row belongs to the next row, so asking where a word that runs up to a wrap ends
 * gives a position at the far left, one row down. Whole rows disappeared that way.
 *
 * For RTL paragraphs the first character of a visual line is at the right edge and the last at the left. Interior
 * offsets delegate to Compose's own cursor-position calculation, which already handles direction; only the two
 * boundary cases need special handling, because getLineLeft and getLineRight are physical edges.
 */
private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl = getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
        ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> if (rtl) lineRight else lineLeft
        offset >= lineEndVisible -> if (rtl) lineLeft else lineRight
        // The inset belongs on every answer, not just the two edges: the rows are drawn [inset] in from the
        // layout's origin, and an interior cut without it lands that far left of the glyph boundary.
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(lineLeft, lineRight)
    }
}

/**
 * The bottom of a row as far as clipping goes. Rows are cut into bands that tile: each runs down to the top of the
 * next, and the last one runs a little below its baseline. A font whose descenders are longer than its line height
 * leaves (the tail of a y or g) is then drawn whole instead of shaved flat at the row's edge, and because the bands
 * meet rather than overlap, no pixel is drawn by two of them (which showed as bright specks on the tails, as a
 * half-transparent line drawn twice).
 */
private fun TextLayoutResult.rowBottom(visualLine: Int, em: Float): Float =
    if (visualLine < lineCount - 1) {
        getLineTop(visualLine + 1)
    } else {
        maxOf(getLineBottom(visualLine), getLineBaseline(visualLine) + em * 0.3f)
    }

/**
 * Draws only what the sweep has not reached: the counterpart of [sweepTo]. Used for the dim copy when the sung copy
 * is a different colour, so the two never sit on top of each other. Under a coloured sung copy a pale one shows
 * through as a thin pale fringe around every letter, which read as a dead outline behind the words.
 */
private fun ContentDrawScope.unsungOnly(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
) {
    if (revealedChars <= 0f) {
        drawContent()
        return
    }
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) return
    val offset = layout.getLineStart(0).coerceIn(0, (textLen - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == ResolvedTextDirection.Rtl
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
        val top = layout.getLineTop(visualLine)
        val bottom = layout.rowBottom(visualLine, em)
        when {
            // Not reached yet: the whole row is still dim.
            revealedChars <= lineStart -> clipRect(top = top, bottom = bottom) { this@unsungOnly.drawContent() }
            // Passed entirely: all of it is the sung copy's now.
            revealedChars >= lineEndAll -> Unit
            else -> {
                val at = horizontalAt(layout, revealedChars, visualLine, spans)
                    .coerceIn(layout.getLineLeft(visualLine), layout.getLineRight(visualLine))
                if (rtl) {
                    clipRect(left = 0f, top = top, right = at, bottom = bottom) { this@unsungOnly.drawContent() }
                } else {
                    clipRect(left = at, top = top, right = size.width, bottom = bottom) { this@unsungOnly.drawContent() }
                }
            }
        }
    }
}

/** One piece of a line, clipped to its own width and drawn at its own height. */
private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipShiftedDown(left = from, top = top, right = to, bottom = bottom, dy = dy) {
        this@sliceRisen.drawContent()
    }
}

/**
 * Clips to a band of the row as it appears on screen and draws the content moved [dy] inside it. The band is where
 * the piece ends up, so a piece lifted above the row has room above the row to be in, while one at rest is cut to
 * exactly its own row.
 */
private inline fun ContentDrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    crossinline block: ContentDrawScope.() -> Unit,
) {
    val scope = this
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        translate(top = dy) { scope.block() }
    }
}

/**
 * Where a fractional character index sits across a visual line, in pixels.
 *
 * Inside a timed piece (a word nobody split) that sits whole on this row, the index is read as a share of the piece
 * and laid across its width. Read letter by letter instead, the edge crossed a "w" in the same time as an "i": it
 * sped up and slowed down inside every word, a wobble that a held note made plain. am-lyrics wipes each piece
 * across its box at a constant rate, and this is the same thing.
 */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    // The all-end (including trailing whitespace and punctuation) for the boundary check: visibleEnd can exclude
    // characters that sit at the physical edge of an RTL line and would make xOn resolve to the next visual line.
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    // A word broken over a wrap, or the space between two words: letter by letter, which is all a single space or
    // a half-word needs.
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    // Row-aware at both ends: on the last character of a wrapped row the next position belongs to the row below,
    // and read straight it puts the edge back at the left margin: the highlight jumped backwards a letter before
    // every wrap.
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float = if (spills) {
        if (rawNext > lineEndVisible) {
            val rtl = layout.getParagraphDirection(
                visualLine.coerceAtMost(lineEndAll).coerceIn(0, (textLen - 1).coerceAtLeast(0)),
            ) == ResolvedTextDirection.Rtl
            // For RTL the far edge in character order is getLineLeft; for LTR it is getLineRight.
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        } else {
            layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
        }
    } else {
        layout.xOn(rawNext, visualLine, 0f)
    }
    return here + (next - here) * (chars - index)
}

/**
 * Draws this text clipped to its first [revealedChars] characters.
 *
 * Wrapped lines are handled a visual line at a time: the ones already passed are drawn whole, the one holding the
 * boundary is cut at it, and the rest are left to the dim copy. Within a word the cut moves across the word's width
 * at a steady rate (see [horizontalAt]) rather than jumping a letter at a time.
 *
 * The boundary itself is then feathered over the [WIPE_FEATHER] ahead of it rather than left as the cut, which needs
 * the caller to give this an offscreen layer to erase into. Only the line actually being sung carries one;
 * everywhere else the boundary is at one end of the text or the other and there is nothing to soften.
 */
private fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    val textLen = layout.layoutInput.text.length
    if (revealedChars >= textLen) {
        drawContent()
        return
    }
    // RTL from the layout's own resolved paragraph direction, not from text heuristics: this keeps geometry in
    // lock-step with how Compose actually laid out the glyphs.
    val offset = layout.getLineStart(0).coerceIn(0, (textLen - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        // Lines whose first character is past the sweep boundary have nothing lit, and neither do the ones after.
        if (revealedChars <= lineStart) return

        val cut = revealedChars < lineEndAll
        val charPos = if (cut) {
            horizontalAt(layout, revealedChars, visualLine, spans)
        } else {
            // The sweep has passed this entire visual line: its boundary is the far edge.
            if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
        }

        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)
        val safeCharPos = charPos.coerceIn(lineLeft, lineRight)

        // The feather runs ahead of the boundary, into what is still to be sung: lit solid up to now, fading out
        // over the next [WIPE_FEATHER]. Trailing behind it instead, the letter being sung right now was the dimmest
        // part of the lit copy, and the line ran out of time with a faded tail still on it and then snapped to
        // full, which read as the highlight being late. Ahead of it, the fade runs off the end of the text as the
        // last letter is reached, and the line finishes already whole. Grown in over the first [WIPE_FEATHER] of
        // each row, or the first letter of every row would pop in half-lit the frame the sweep reached it.
        val featherPx = if (feather && cut) {
            minOf(
                WIPE_FEATHER.toPx(),
                if (rtl) lineRight - safeCharPos else safeCharPos - lineLeft,
            )
        } else {
            0f
        }
        val lead = if (rtl) {
            (safeCharPos - featherPx).coerceAtLeast(lineLeft)
        } else {
            (safeCharPos + featherPx).coerceAtMost(lineRight)
        }

        // RTL lines fill right to left: clip from the feather's leading edge to the line's right edge.
        val (clipLeft, clipRight) = if (rtl) lead to lineRight else lineLeft to lead
        if (clipLeft >= clipRight) continue

        val top = layout.getLineTop(visualLine)
        val bottom = layout.rowBottom(visualLine, layout.layoutInput.style.fontSize.toPx())
        clipRect(left = clipLeft, top = top, right = clipRight, bottom = bottom) {
            this@sweepTo.drawContent()
        }

        // Only the visual line holding the boundary has an edge to soften.
        if (featherPx < 0.5f) continue

        // Scoped to this line's band so the mask cannot reach the lines above and below it: DstIn erases whatever
        // the source does not cover, and outside the clip there is no source at all. The gradient keeps its full
        // width even where the clip is cut short by the end of the row, so the last letters keep fading up.
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = safeCharPos,
                    endX = if (rtl) safeCharPos - featherPx else safeCharPos + featherPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}