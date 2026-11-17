package com.gamut.feature.playerui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gamut.feature.playerui.components.fadingEdges
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal fun List<LyricsLine>.toFlowLines(isSynced: Boolean): List<FlowLine> {
    val mapped = map { line ->
        FlowLine(
            timeMs = line.timestampMs,
            text = line.text,
            words = line.words.map { FlowWord(it.startMs, it.endMs, it.text, it.syllables) },
            explicitEndMs = line.endMs,
        )
    }
    return if (isSynced) mapped.withInstrumentalBreaks() else mapped
}

@Stable
internal class FlowPlayhead(private val clock: PlayerClock, private val offsetMs: Long = LyricsSyncOffsetMs) {
    val positionMs: Long get() = (clock.readingMs + offsetMs).coerceAtLeast(0L)
    val sampledAtNanos: Long get() = clock.readingAtNanos
    val discontinuity: Long get() = clock.seeks.toLong() shl 32
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

private const val SAME_CLOCK_NANOS = 250_000_000L

@Composable
internal fun rememberFlowClock(trackKey: Any, playhead: FlowPlayhead, isPlaying: Boolean): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation { playhead.positionMs })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    if (!isPlaying) {
        LaunchedEffect(engine, playhead) {
            snapshotFlow { playhead.positionMs }.collect { positionMs ->
                engine.hold(positionMs, nanosToMs(System.nanoTime()))
                clock.longValue = positionMs
            }
        }
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(engine, playhead, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val sampledAt = playhead.sampledAtNanos
                clock.longValue = engine.frame(
                    nowMs = nanosToMs(now),
                    reportedMs = playhead.positionMs,
                    sampledAtMs = if (sampledAt > 0L) nanosToMs(sampledAt) else Double.NaN,
                    discontinuity = playhead.discontinuity,
                )
            }
        }
    }
    return clock
}

@Composable
private fun rememberIsForeground(): Boolean {
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return foreground
}

private val GAP_ROW_HEIGHT = 40.dp
private val GAP_ROW_SPACING = 16.dp
private const val GAP_RUNNING = -1f

private val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

private val LINE_FALLOFF_ALPHA_PHONE = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)

private const val BROWSING_ALPHA = 0.8f

private const val INACTIVE_SCALE = 0.98f

private const val PRESSED_SCALE = 0.96f

private const val GAP_DOTS = 3
private val GAP_DOT_SIZE = 13.dp
private val GAP_DOT_GAP = 5.dp
private const val GAP_DOT_REST = 0.25f
private const val GAP_REST_SCALE = 0.76f

private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
private const val LYRIC_SETTLE_MS = 400

private const val STAGGER_STEPS = 3
private const val STAGGER_FRACTION = 0.06f

private class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

private fun scrollLead(lines: List<FlowLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

@Composable
internal fun FlowLyricsPanel(
    lines: List<FlowLine>,
    isSynced: Boolean,
    trackKey: String,
    playhead: FlowPlayhead,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    centerFocus: Boolean = false,
    topPadding: Dp = 40.dp,
    gutter: Dp = 24.dp,
    blurEnabled: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    highlight: Color = Color.White,
    centered: Boolean = false,
) {
    val panelPlaying = isPlaying && active
    val clock = rememberFlowClock(trackKey, playhead, panelPlaying)
    val font = rememberLyricsFont()

    val activeRows by remember(lines, isSynced) {
        derivedStateOf { if (!isSynced) emptyList() else activeFlowRows(lines, clock.longValue) }
    }
    val scrollLine = activeRows.firstOrNull() ?: -1

    val leadLine by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                activeFlowRows(lines, now + scrollLead(lines, now)).firstOrNull() ?: -1
            }
        }
    }
    val focusLine = if (leadLine >= 0) leadLine else scrollLine

    val listState = rememberLazyListState()
    val viewportHeight by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize.height } }

    var browsingByDrag by remember { mutableStateOf(false) }
    val browsing = browsingByDrag
    val glowing = blurEnabled

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) browsingByDrag = true
        }
    }
    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine } }
    }
    LaunchedEffect(browsingByDrag, activeOnScreen, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && activeOnScreen && !listState.isScrollInProgress) {
            delay(600)
            browsingByDrag = false
        }
    }
    LaunchedEffect(browsingByDrag, listState.isScrollInProgress, panelPlaying) {
        if (panelPlaying && browsingByDrag && !listState.isScrollInProgress) {
            delay(5_000)
            browsingByDrag = false
        }
    }

    var run by remember(lines) { mutableStateOf(ScrollRun(0, 0f, LYRIC_SETTLE_MS)) }
    val since = remember(lines) { mutableFloatStateOf(0f) }
    LaunchedEffect(run.id) {
        if (run.id == 0) return@LaunchedEffect
        animate(
            initialValue = 0f,
            targetValue = run.spanMs,
            animationSpec = tween(run.spanMs.toInt(), easing = LinearEasing),
        ) { value, _ -> since.floatValue = value }
    }

    var placed by remember(trackKey) { mutableStateOf(false) }
    val centered = centerFocus && isSynced
    val focusOffset: (Int) -> Int = { size ->
        if (!centered) {
            0
        } else {
            val info = listState.layoutInfo
            (info.viewportStartOffset + info.viewportEndOffset) / 2 - size / 2
        }
    }

    LaunchedEffect(focusLine, browsing, active) {
        if (active && isSynced && !browsing && focusLine >= 0 && focusLine in lines.indices) {
            snapshotFlow { listState.layoutInfo }.first {
                it.viewportSize.height > 0 && (!centered || it.beforeContentPadding > 0)
            }
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusLine }
            when {
                !placed -> {
                    listState.scrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let { listState.scrollBy((it.offset - focusOffset(it.size)).toFloat()) }
                    }
                    placed = true
                }
                visible != null -> {
                    val span = scrollLead(lines, clock.longValue).toInt()
                    val distance = (visible.offset - focusOffset(visible.size)).toFloat()
                    run = ScrollRun(run.id + 1, distance, span)
                    listState.animateScrollBy(
                        value = distance,
                        animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                    )
                }
                else -> {
                    listState.animateScrollToItem(focusLine, scrollOffset = -focusOffset(0))
                    if (centered) {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == focusLine }
                            ?.let {
                                listState.animateScrollBy(
                                    value = (it.offset - focusOffset(it.size)).toFloat(),
                                    animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                                )
                            }
                    }
                }
            }
        }
    }

    val density = LocalDensity.current
    val viewportDp = with(density) { viewportHeight.toDp() }
    val syncedStyle = remember(font) {
        TextStyle(fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 41.sp, color = Color.White)
    }
    val plainStyle = remember(font) {
        TextStyle(fontFamily = font, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 38.sp, color = Color.White)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fadingEdges(0.08f),
        contentPadding = PaddingValues(
            top = if (centered) viewportDp * 0.5f else topPadding - GLOW_ROOM,
            bottom = viewportDp * 0.8f,
            start = gutter - GLOW_ROOM,
            end = gutter - GLOW_ROOM,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val offset = if (scrollLine < 0) 0 else index - scrollLine
            val distance = abs(offset)
            val isActive = isSynced && index in activeRows
            val falloff = if (centerFocus) LINE_FALLOFF_ALPHA else LINE_FALLOFF_ALPHA_PHONE
            val step = distance.coerceAtMost(falloff.lastIndex)
            val blur by animateDpAsState(
                targetValue = when {
                    !isSynced || !blurEnabled || browsing || isActive -> 0.dp
                    else -> LINE_FALLOFF_BLUR[step.coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "flowBlur",
            )
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    !isSynced -> 0.95f
                    isActive -> 1f
                    browsing -> BROWSING_ALPHA
                    else -> falloff[step]
                },
                animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                label = "flowAlpha",
            )

            if (line.isGap) {
                FlowGapRow(
                    line = line,
                    until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs,
                    clock = clock,
                    isActive = isActive,
                    blur = blur,
                    lineAlpha = lineAlpha,
                    enabled = isSynced,
                    onClick = { onSeekToLine(line.timeMs) },
                )
            } else {
                val sung = offset < 0
                val behind = if (run.delta >= 0f) index - focusLine else focusLine - index
                val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) * STAGGER_FRACTION * run.durationMs
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> PRESSED_SCALE
                        isActive -> 1f
                        else -> INACTIVE_SCALE
                    },
                    animationSpec = tween(if (pressed) 120 else LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                    label = "flowScale",
                )
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "flowGlow",
                )
                val rtl = isRtl(line.text)
                val shape = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (centered) 0.5f else if (rtl) 1f else 0f, 0.5f)
                        alpha = lineAlpha
                        translationY = if (staggerDelay <= 0f) {
                            0f
                        } else {
                            val elapsed = since.floatValue
                            run.delta * (
                                LYRIC_EASING.transform((elapsed / run.durationMs).coerceIn(0f, 1f)) -
                                    LYRIC_EASING.transform(((elapsed - staggerDelay) / run.durationMs).coerceIn(0f, 1f))
                                )
                        }
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        enabled = isSynced,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                    ) { onSeekToLine(line.timeMs) }

                Column(modifier = shape) {
                    FlowVoice(
                        line = line,
                        clock = clock,
                        style = flowVoiceStyle(if (isSynced) syncedStyle else plainStyle, line.text).let {
                            if (centered) it.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center) else it
                        },
                        highlight = highlight,
                        centered = centered,
                        isActive = isActive,
                        sung = sung,
                        synced = isSynced,
                        browsing = browsing,
                        glowAlpha = glow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowVoice(
    line: FlowLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    highlight: Color,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    if (line.isWordSynced) {
        val tail by animateFloatAsState(targetValue = if (sung) 1f else UNSUNG_ALPHA, label = "flowTail")
        SweptFlowLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = if (browsing) 0f else glowAlpha,
            glowRoom = GLOW_ROOM,
            feather = isActive && !browsing,
            rise = true,
            color = highlight,
            dimColor = Color.White,
            centered = centered,
        )
    } else {
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "flowLit",
        )
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = lit),
            modifier = modifier.padding(GLOW_ROOM),
        )
    }
}

@Composable
private fun FlowGapRow(
    line: FlowLine,
    until: Long,
    clock: MutableLongState,
    isActive: Boolean,
    blur: Dp,
    lineAlpha: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val gapFill = remember(line, until, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now <= line.timeMs -> 0f
                now >= until -> 1f
                else -> GAP_RUNNING
            }
        }
    }
    val swell by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (isActive) 400 else 350, easing = LYRIC_EASING),
        label = "flowGapSwell",
    )
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell).clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .blur(blur, BlurredEdgeTreatment.Unbounded)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(GLOW_ROOM)
                .size(width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2, height = GAP_DOT_SIZE)
                .graphicsLayer {
                    val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = lineAlpha * swell
                }
                .drawBehind {
                    val span = (until - line.timeMs).coerceAtLeast(1L)
                    val through = gapFill.value.takeUnless { it == GAP_RUNNING }
                        ?: ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val radius = GAP_DOT_SIZE.toPx() / 2f
                    val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                    repeat(GAP_DOTS) { dot ->
                        val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = GAP_DOT_REST + (1f - GAP_DOT_REST) * lit),
                            radius = radius,
                            center = Offset(radius + dot * stride, size.height / 2f),
                        )
                    }
                }
                .semantics { contentDescription = "Instrumental break" },
        )
    }
}

private val STRIP_LINE_HEIGHT = 20.sp

private const val LYRIC_FADE_FRACTION = 0.28f
private const val LYRIC_FADE_MIN_MS = 160f
private const val LYRIC_FADE_MAX_MS = 700f

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun FlowLyricStrip(
    lines: List<FlowLine>,
    trackKey: String,
    playhead: FlowPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    centered: Boolean = false,
    highlight: Color = Color.White,
) {
    val clock = rememberFlowClock(trackKey, playhead, isPlaying)
    val index by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } } }
    val current = lines.getOrNull(index)
    val instrumental = current == null || current.isGap
    val font = rememberLyricsFont()
    val stripStyle = MaterialTheme.typography.titleMedium.copy(
        fontFamily = font,
        lineHeight = STRIP_LINE_HEIGHT,
        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
        color = Color.White,
    )
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        AnimatedContent(
            targetState = index to current,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart,
            transitionSpec = {
                val duration = 340
                slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { (it * 0.35f).toInt() }
                    .togetherWith(
                        fadeOut(tween(duration, easing = FastOutSlowInEasing)) +
                            slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { -(it * 0.35f).toInt() },
                    )
                    .using(SizeTransform(clip = false) { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
            },
            label = "flowStripTransition",
        ) { (itemIndex, item) ->
            val itemInstrumental = item == null || item.isGap
            val fading = Modifier.graphicsLayer {
                if (itemInstrumental) return@graphicsLayer
                val start = lines.getOrNull(itemIndex)?.timeMs ?: 0L
                val end = lines.getOrNull(itemIndex + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION).coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            }
            if (item != null && !itemInstrumental && item.isWordSynced) {
                SweptFlowLine(
                    line = item,
                    clock = clock,
                    style = flowVoiceStyle(stripStyle, item.text).let {
                        if (centered) it.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center) else it
                    },
                    dimAlpha = UNSUNG_ALPHA_STRIP,
                    color = highlight,
                    dimColor = Color.White,
                    centered = centered,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    rise = false,
                    modifier = fading,
                )
            } else {
                Text(
                    text = if (itemInstrumental) "\u266A" else item?.text.orEmpty(),
                    style = if (centered) stripStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center) else stripStyle,
                    color = Color.White.copy(alpha = if (itemInstrumental) 0.5f else 1f),
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = fading,
                )
            }
        }
    }
}
