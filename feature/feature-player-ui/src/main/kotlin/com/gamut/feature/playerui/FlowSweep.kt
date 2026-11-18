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

internal fun isRtl(text: String): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    for (ch in text) {
        val code = ch.code
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

internal fun flowVoiceStyle(
    base: TextStyle,
    text: String,
): TextStyle {
    val rtl = isRtl(text)
    return base.copy(
        textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
    )
}

internal const val UNSUNG_ALPHA = 0.45f
internal const val UNSUNG_ALPHA_STRIP = 0.55f

internal const val GLOW_ALPHA = 0.62f

internal val GLOW_RADIUS = 6.dp

internal val GLOW_ROOM = 10.dp

private val WIPE_FEATHER = 30.dp

private val WORD_RISE = 2.dp

private const val GROW_HEADROOM = 3f

private const val ASCENT_REACH = 0.82f
private const val DESCENT_FLOOR = 0.32f
private const val LAST_LINE_DESCENT = 0.45f

private const val PHASE_BEFORE = 0
private const val PHASE_MOVING = 1
private const val PHASE_AFTER = 2

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
    dimColor: Color = color,
    centered: Boolean = false,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    val growth = remember { CharGrowth() }

    val phase =
        remember(line, clock) {
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

    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

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
                }.then(inner)
        }
    }

    val sweep =
        Modifier.drawWithContent {
            val position = drawnAt()
            when {
                position >= line.endMs -> {
                    drawContent()
                }

                position <= line.timeMs -> {
                    Unit
                }

                else -> {
                    layout?.let {
                        sweepTo(it, line.revealedChars(position), line.sweepSpans, feather)
                    }
                }
            }
        }

    val dimSweep =
        if (dimColor == color) {
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
        contentAlignment =
            when {
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
                modifier =
                    Modifier
                        .graphicsLayer { alpha = glowAlpha }
                        .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                        .then(room)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
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
            modifier =
                riseAgainst(
                    Modifier
                        .graphicsLayer {
                            compositingStrategy = if (feather) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                        }.then(room)
                        .then(sweep),
                ),
        )
    }
}

private fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: FlowLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em =
        layout.layoutInput.style.fontSize
            .toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val bandTop = layout.bandTop(visualLine, em) + inset
            val bandBottom = layout.bandBottom(visualLine, em) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = minOf(rowTop, bandTop) - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = maxOf(bottom, bandBottom),
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
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

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
    val em =
        layout.layoutInput.style.fontSize
            .toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.rowBottom(visualLine, em) + inset
        val bandTop = layout.bandTop(visualLine, em) + inset
        val bandBottom = layout.bandBottom(visualLine, em) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            if (to <= from) continue
            if (start > at) sliceRisen(edge, bandTop, from, bandBottom, 0f)
            if (held != null) {
                growEach(
                    layout,
                    held,
                    line,
                    positionMs,
                    visualLine,
                    start,
                    end,
                    top,
                    bottom,
                    bandTop,
                    bandBottom,
                    inset,
                    peak,
                    em,
                    growth,
                )
            } else {
                sliceRisen(from, bandTop - peak, to, bandBottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, bandTop, layout.getLineRight(visualLine) + inset, bandBottom, 0f)
        }
    }
}

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
    bandTop: Float,
    bandBottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    val ceiling = minOf(top, bandTop) - peak * GROW_HEADROOM
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
            bottom = maxOf(bottom, bandBottom),
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

private fun TextLayoutResult.xOn(
    offset: Int,
    visualLine: Int,
    inset: Float,
): Float {
    val lineStart = getLineStart(visualLine)
    val lineEndVisible = getLineEnd(visualLine, visibleEnd = true)
    val lineLeft = getLineLeft(visualLine) + inset
    val lineRight = getLineRight(visualLine) + inset

    val rtl =
        getParagraphDirection(lineStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) ==
            ResolvedTextDirection.Rtl

    return when {
        offset <= lineStart -> {
            if (rtl) lineRight else lineLeft
        }

        offset >= lineEndVisible -> {
            if (rtl) lineLeft else lineRight
        }

        else -> {
            (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
                .coerceIn(lineLeft, lineRight)
        }
    }
}

private fun TextLayoutResult.rowBottom(
    visualLine: Int,
    em: Float,
): Float =
    if (visualLine < lineCount - 1) {
        getLineTop(visualLine + 1)
    } else {
        maxOf(getLineBottom(visualLine), getLineBaseline(visualLine) + em * 0.3f)
    }

private fun TextLayoutResult.bandTop(
    visualLine: Int,
    em: Float,
): Float =
    if (visualLine == 0) {
        minOf(getLineTop(0), getLineBaseline(0) - em)
    } else {
        seam(visualLine - 1, em)
    }

private fun TextLayoutResult.bandBottom(
    visualLine: Int,
    em: Float,
): Float =
    if (visualLine < lineCount - 1) {
        seam(visualLine, em)
    } else {
        maxOf(getLineBottom(visualLine), getLineBaseline(visualLine) + em * LAST_LINE_DESCENT)
    }

private fun TextLayoutResult.seam(
    visualLine: Int,
    em: Float,
): Float {
    val baseline = getLineBaseline(visualLine)
    val nextAscender = getLineBaseline(visualLine + 1) - em * ASCENT_REACH
    return maxOf(baseline + em * DESCENT_FLOOR, nextAscender)
}

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
    val em =
        layout.layoutInput.style.fontSize
            .toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
        val top = layout.bandTop(visualLine, em)
        val bottom = layout.bandBottom(visualLine, em)
        when {
            revealedChars <= lineStart -> {
                clipRect(top = top, bottom = bottom) { this@unsungOnly.drawContent() }
            }

            revealedChars >= lineEndAll -> {
                Unit
            }

            else -> {
                val at =
                    horizontalAt(layout, revealedChars, visualLine, spans)
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

private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
    spans: List<IntRange>,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEndVisible = layout.getLineEnd(visualLine, visibleEnd = true)
    val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)
    val word = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (word != null && word.first >= lineStart && word.last + 1 <= lineEndVisible) {
        val from = layout.xOn(word.first, visualLine, 0f)
        val to = layout.xOn(word.last + 1, visualLine, 0f)
        return from + (to - from) * (chars - word.first) / (word.last + 1 - word.first)
    }
    val index = chars.toInt().coerceIn(lineStart, lineEndAll)
    val here = layout.xOn(index, visualLine, 0f)
    val rawNext = index + 1
    val textLen = layout.layoutInput.text.length
    val spills = layout.getLineForOffset(rawNext.coerceIn(0, textLen)) != visualLine
    val next: Float =
        if (spills) {
            if (rawNext > lineEndVisible) {
                val rtl =
                    layout.getParagraphDirection(
                        visualLine.coerceAtMost(lineEndAll).coerceIn(0, (textLen - 1).coerceAtLeast(0)),
                    ) == ResolvedTextDirection.Rtl
                if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
            } else {
                layout.xOn(rawNext.coerceAtMost(lineEndVisible), visualLine, 0f)
            }
        } else {
            layout.xOn(rawNext, visualLine, 0f)
        }
    return here + (next - here) * (chars - index)
}

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
    val offset = layout.getLineStart(0).coerceIn(0, (textLen - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(offset) == ResolvedTextDirection.Rtl

    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEndAll = layout.getLineEnd(visualLine, visibleEnd = false)

        if (revealedChars <= lineStart) return

        val cut = revealedChars < lineEndAll
        val charPos =
            if (cut) {
                horizontalAt(layout, revealedChars, visualLine, spans)
            } else {
                if (rtl) layout.getLineLeft(visualLine) else layout.getLineRight(visualLine)
            }

        val lineLeft = layout.getLineLeft(visualLine)
        val lineRight = layout.getLineRight(visualLine)
        val safeCharPos = charPos.coerceIn(lineLeft, lineRight)

        val featherPx =
            if (feather && cut) {
                minOf(
                    WIPE_FEATHER.toPx(),
                    if (rtl) lineRight - safeCharPos else safeCharPos - lineLeft,
                )
            } else {
                0f
            }
        val lead =
            if (rtl) {
                (safeCharPos - featherPx).coerceAtLeast(lineLeft)
            } else {
                (safeCharPos + featherPx).coerceAtMost(lineRight)
            }

        val (clipLeft, clipRight) = if (rtl) lead to lineRight else lineLeft to lead
        if (clipLeft >= clipRight) continue

        val em =
            layout.layoutInput.style.fontSize
                .toPx()
        val top = layout.bandTop(visualLine, em)
        val bottom = layout.bandBottom(visualLine, em)
        clipRect(left = clipLeft, top = top, right = clipRight, bottom = bottom) {
            this@sweepTo.drawContent()
        }

        if (featherPx < 0.5f) continue

        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush =
                    Brush.horizontalGradient(
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