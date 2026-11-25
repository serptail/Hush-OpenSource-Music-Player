package com.gamut.feature.playerui.components

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamut.core.designsystem.TablerIcons
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** How far a double tap on the cover jumps, each way. */
internal const val DoubleTapSeekMs = 5_000L

/** After a double tap, further single taps keep skipping for this long (like a video player). */
internal const val ChainWindowMs = 650L

private val SeekZoneInset = 20.dp

@Composable
internal fun rememberIsTelevision(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
}

// ---------------------------------------------------------------------------------------------------------------
// Double-tap to skip
// ---------------------------------------------------------------------------------------------------------------

/** What the "5 seconds" bubble shows, plus the bookkeeping the gesture needs between taps. */
@Stable
internal class DoubleTapSeek {
    /** -1 = back, +1 = forward. */
    var side by mutableIntStateOf(1)
        private set

    /** Total skipped in the current streak, in seconds. */
    var seconds by mutableIntStateOf(0)
        private set

    /** Bumped on every seek so the bubble restarts its animation. */
    var tick by mutableIntStateOf(0)
        private set

    internal var lastTapUptime = 0L
    internal var lastTapPos = Offset.Zero
    internal var chainUntil = 0L

    fun registerSeek(
        direction: Int,
        uptime: Long = SystemClock.uptimeMillis(),
    ) {
        val streak = uptime <= chainUntil && direction == side
        seconds = if (streak) seconds + (DoubleTapSeekMs / 1000L).toInt() else (DoubleTapSeekMs / 1000L).toInt()
        side = direction
        chainUntil = uptime + ChainWindowMs
        tick++
    }
}

/**
 * Double tap on the left / right half of the cover skips back / forward [DoubleTapSeekMs]. Used by the styles whose
 * cover does nothing on a single tap (Cover, Mesh). The Classic style owns its cover tap (play / pause), so it does
 * the same thing inside `ArtworkWithRing` where the single tap can wait for a possible second one.
 */
internal fun Modifier.doubleTapSeekGesture(
    hud: VolumeHud,
    seek: DoubleTapSeek,
    topExclusionPx: () -> Float,
    onSeekBy: (Long) -> Unit,
): Modifier =
    pointerInput(hud, seek) {
        val insetPx = SeekZoneInset.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val raw = hud.anchor.deflate(insetPx)
            // Keep clear of the top bar that floats over the cover banner.
            val zone = Rect(raw.left, max(raw.top, topExclusionPx()), raw.right, raw.bottom)
            if (zone.width <= 0f || zone.height <= 0f || !zone.contains(down.position)) return@awaitEachGesture

            val slop = viewConfiguration.touchSlop
            val window = viewConfiguration.doubleTapTimeoutMillis
            val sincePrev = down.uptimeMillis - seek.lastTapUptime
            val nearPrev = hypot(down.position.x - seek.lastTapPos.x, down.position.y - seek.lastTapPos.y) < slop * 8f
            val takeOver =
                down.uptimeMillis <= seek.chainUntil ||
                    (seek.lastTapUptime != 0L && sincePrev in 0L..window && nearPrev)
            if (takeOver) down.consume()

            var tapped = false
            var upTime = down.uptimeMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                // Read this before consuming: a consumed change never reports changedToUp().
                val isUp = c.changedToUp()
                val travelled = hypot(c.position.x - down.position.x, c.position.y - down.position.y)
                val single = event.changes.size == 1
                if (takeOver) c.consume()
                if (isUp) {
                    tapped = single && travelled <= slop
                    upTime = c.uptimeMillis
                    break
                }
                if (!c.pressed || !single || travelled > slop) break
            }
            if (!tapped) {
                if (!takeOver) seek.lastTapUptime = 0L
                return@awaitEachGesture
            }
            seek.lastTapUptime = upTime
            seek.lastTapPos = down.position
            if (takeOver) {
                val direction = if (down.position.x < zone.center.x) -1 else 1
                seek.registerSeek(direction, upTime)
                onSeekBy(direction * DoubleTapSeekMs)
            }
        }
    }

/** Tabler ships the rewind glyphs in 5 s steps up to 20; the number is part of the icon, so a longer streak wraps. */
private const val SeekIconStepSeconds = 5

private val SeekIndicatorSize = 96.dp

private fun seekIcon(
    side: Int,
    seconds: Int,
): ImageVector {
    val index = (seconds.coerceAtLeast(SeekIconStepSeconds) / SeekIconStepSeconds - 1).mod(4)
    return if (side < 0) {
        when (index) {
            0 -> TablerIcons.RewindBackward5
            1 -> TablerIcons.RewindBackward10
            2 -> TablerIcons.RewindBackward15
            else -> TablerIcons.RewindBackward20
        }
    } else {
        when (index) {
            0 -> TablerIcons.RewindForward5
            1 -> TablerIcons.RewindForward10
            2 -> TablerIcons.RewindForward15
            else -> TablerIcons.RewindForward20
        }
    }
}

/**
 * The skip confirmation: one white Tabler rewind glyph on a soft radial scrim. No text and no hard-edged bubble, so
 * nothing to clip or wrap, and the scrim fades out to nothing so the glyph still reads over bright artwork. Shared by
 * the live overlay and the tutorial so they look identical.
 */
@Composable
private fun SeekIndicator(
    side: Int,
    seconds: Int,
    wave: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(SeekIndicatorSize)
            .background(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.28f),
                    0.55f to Color.Black.copy(alpha = 0.14f),
                    1f to Color.Transparent,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            seekIcon(side, seconds),
            contentDescription = if (side < 0) "Back $seconds seconds" else "Forward $seconds seconds",
            tint = Color.White,
            modifier =
                Modifier
                    .size(40.dp)
                    // Each skip nudges the glyph the way the playhead moves.
                    .graphicsLayer { translationX = -side * 10.dp.toPx() * (1f - wave) },
        )
    }
}

/** Confirms a double-tap skip where the finger landed. */
@Composable
internal fun DoubleTapSeekOverlay(
    hud: VolumeHud,
    seek: DoubleTapSeek,
    modifier: Modifier = Modifier,
) {
    val visible = remember { Animatable(0f) }
    val wave = remember { Animatable(0f) }
    LaunchedEffect(seek.tick) {
        if (seek.tick == 0) return@LaunchedEffect
        launch {
            wave.snapTo(0f)
            wave.animateTo(1f, tween(420, easing = LinearOutSlowInEasing))
        }
        visible.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(ChainWindowMs)
        visible.animateTo(0f, tween(220))
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val zone = hud.anchor
        if (visible.value > 0.01f && zone.width > 0f) {
            val half = with(density) { SeekIndicatorSize.toPx() / 2f }
            val cx = zone.left + zone.width * (if (seek.side < 0) 0.25f else 0.75f)
            Box(
                Modifier
                    .offset { IntOffset((cx - origin.x - half).roundToInt(), (zone.center.y - origin.y - half).roundToInt()) }
                    .graphicsLayer {
                        alpha = visible.value.coerceIn(0f, 1f)
                        val s = 0.78f + 0.22f * visible.value
                        scaleX = s
                        scaleY = s
                    },
            ) {
                SeekIndicator(seek.side, seek.seconds, wave.value)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// First-run guide
// ---------------------------------------------------------------------------------------------------------------

/**
 * Shown once. On a touchscreen: a soft touch-dot demonstrates swiping for volume (driving the real volume pill) and
 * the double-tap skip, over a spotlight on the cover. On Android TV: a remote-control guide, since there is no touch.
 */
@Composable
internal fun GestureTutorial(
    hud: VolumeHud,
    remote: Boolean,
    onPreviewVolume: (Float?) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (remote) {
        RemoteTutorial(onDone, modifier)
    } else {
        TouchTutorial(hud, onPreviewVolume, onDone, modifier)
    }
}

private fun DrawScope.chevron(
    center: Offset,
    direction: Int,
    half: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path =
        Path().apply {
            moveTo(center.x - direction * half * 0.5f, center.y - half)
            lineTo(center.x + direction * half * 0.5f, center.y)
            lineTo(center.x - direction * half * 0.5f, center.y + half)
        }
    drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.touchDot(
    center: Offset,
    alpha: Float,
    press: Float,
) {
    if (alpha <= 0.01f) return
    // A fingertip, nothing more: a solid core inside a faint halo, both easing in a little when pressed.
    drawCircle(Color.White.copy(alpha = 0.18f * alpha), 26.dp.toPx() * (1f - 0.12f * press), center)
    drawCircle(Color.White.copy(alpha = 0.92f * alpha), 14.dp.toPx() * (1f - 0.22f * press), center)
}

private fun DrawScope.tapRipple(
    center: Offset,
    progress: Float,
    alpha: Float,
) {
    if (progress <= 0.01f || progress >= 0.99f) return
    drawCircle(
        Color.White.copy(alpha = 0.6f * (1f - progress) * alpha),
        24.dp.toPx() + 42.dp.toPx() * progress,
        center,
        style = Stroke(1.dp.toPx() + 2.dp.toPx() * (1f - progress)),
    )
}

@Composable
private fun TutorialCard(
    step: Int,
    steps: List<Pair<String, String>>,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            // Opaque: the player behind (lyrics, controls) must never show through the copy.
            .background(Color(0xFF1B1B1E))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A fixed-height slot so the card does not change size between steps, and balanced line breaks so a
        // description never ends in a single orphaned word.
        Box(Modifier.fillMaxWidth().heightIn(min = 76.dp), contentAlignment = Alignment.TopCenter) {
            Crossfade(targetState = step, animationSpec = tween(300), label = "tutorial-copy") { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        steps[s].first,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        steps[s].second,
                        color = Color.White.copy(alpha = 0.66f),
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            steps.indices.forEach { i ->
                val w by animateFloatAsState(if (i == step) 1f else 0f, tween(260), label = "tutorial-dot")
                Box(
                    Modifier
                        .width(6.dp + 14.dp * w)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.30f + 0.60f * w)),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            action,
            color = Color.Black.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 32.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun TouchTutorial(
    hud: VolumeHud,
    onPreviewVolume: (Float?) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val done by rememberUpdatedState(onDone)
    val preview by rememberUpdatedState(onPreviewVolume)
    val density = LocalDensity.current
    val reveal = remember { Animatable(0f) }
    val dotX = remember { Animatable(0f) }
    val trailX = remember { Animatable(0f) }
    val dotAlpha = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val ring = remember { Animatable(1f) }
    val bubbleVis = remember { Animatable(0f) }
    val bubbleWave = remember { Animatable(1f) }
    var bubbleSide by remember { mutableIntStateOf(1) }
    var step by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Trying the real gesture is the best way to dismiss the guide.
    LaunchedEffect(hud.holding) { if (hud.holding) done() }
    DisposableEffect(Unit) { onDispose { preview(null) } }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(420)) }

    LaunchedEffect(Unit) {
        delay(550)
        while (true) {
            val zoneW = hud.anchor.width.takeIf { it > 0f } ?: with(density) { 280.dp.toPx() }
            val span = zoneW * 0.27f

            // 1 - swipe: the dot presses, slides across the cover, and the volume pill follows it.
            step = 0
            dotX.snapTo(-span)
            trailX.snapTo(-span)
            press.snapTo(0f)
            dotAlpha.animateTo(1f, tween(260))
            launch {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(560, easing = LinearOutSlowInEasing))
            }
            press.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
            preview(0.25f)
            val volume = Animatable(0.25f)
            coroutineScope {
                launch { dotX.animateTo(span, tween(1300, easing = FastOutSlowInEasing)) }
                launch { trailX.animateTo(span, tween(1300, delayMillis = 120, easing = FastOutSlowInEasing)) }
                launch { volume.animateTo(0.82f, tween(1300, easing = FastOutSlowInEasing)) { preview(value) } }
            }
            delay(260)
            press.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
            dotAlpha.animateTo(0f, tween(240))
            preview(null)
            delay(600)

            // 2 - double tap on the right, then on the left.
            step = 1
            for (side in intArrayOf(1, -1)) {
                val x = side * zoneW * 0.25f
                dotX.snapTo(x)
                trailX.snapTo(x)
                dotAlpha.animateTo(1f, tween(220))
                delay(120)
                repeat(2) {
                    launch {
                        ring.snapTo(0f)
                        ring.animateTo(1f, tween(430, easing = LinearOutSlowInEasing))
                    }
                    press.animateTo(1f, tween(70))
                    press.animateTo(0f, tween(120))
                    delay(90)
                }
                // The finger lifts and the skip confirmation takes its place, exactly as it does for real.
                dotAlpha.animateTo(0f, tween(140))
                bubbleSide = side
                launch {
                    bubbleWave.snapTo(0f)
                    bubbleWave.animateTo(1f, tween(420, easing = LinearOutSlowInEasing))
                }
                bubbleVis.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                delay(850)
                bubbleVis.animateTo(0f, tween(220))
                delay(280)
            }
            delay(500)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { done() } },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value },
        ) {
            drawRect(Color.Black.copy(alpha = 0.6f))
            val zone = hud.anchor
            if (zone.width <= 0f) return@Canvas

            val cx = zone.center.x - origin.x
            val cy = zone.center.y - origin.y
            val a = dotAlpha.value
            if (a > 0.01f && abs(dotX.value - trailX.value) > 1f) {
                // A thin line behind the fingertip shows the direction of the swipe.
                drawLine(
                    Color.White.copy(alpha = 0.35f * a),
                    Offset(cx + trailX.value, cy),
                    Offset(cx + dotX.value, cy),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val centre = Offset(cx + dotX.value, cy)
            tapRipple(centre, ring.value, a)
            touchDot(centre, a, press.value)
        }

        val zone = hud.anchor
        if (zone.width > 0f && bubbleVis.value > 0.01f) {
            val half = with(density) { SeekIndicatorSize.toPx() / 2f }
            val bx = zone.left + zone.width * (if (bubbleSide < 0) 0.25f else 0.75f)
            Box(
                Modifier
                    .offset { IntOffset((bx - origin.x - half).roundToInt(), (zone.center.y - origin.y - half).roundToInt()) }
                    .graphicsLayer {
                        alpha = bubbleVis.value.coerceIn(0f, 1f)
                        val s = 0.78f + 0.22f * bubbleVis.value
                        scaleX = s
                        scaleY = s
                    },
            ) {
                SeekIndicator(bubbleSide, DoubleTapSeekMs.toInt() / 1000, bubbleWave.value)
            }
        }

        TutorialCard(
            step = step,
            steps =
                listOf(
                    "Swipe for volume" to "Slide your finger across the cover to change the volume",
                    "Double-tap to skip" to "Tap either side of the cover twice to jump 5 seconds",
                ),
            action = "Got it",
            onAction = { done() },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = reveal.value },
        )
    }
}

private const val None = 0
private const val Ok = 1
private const val Left = 2
private const val Right = 3
private const val Rewind = 4
private const val Forward = 5

@Composable
private fun RemoteTutorial(
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val done by rememberUpdatedState(onDone)
    val focus = remember { FocusRequester() }
    val reveal = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val ring = remember { Animatable(1f) }
    var step by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(None) }
    val pulse by rememberInfiniteTransition(label = "remote-hint").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "remote-pulse",
    )

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        reveal.animateTo(1f, tween(420))
    }
    LaunchedEffect(Unit) {
        suspend fun tap(which: Int) {
            target = which
            ring.snapTo(0f)
            launch { ring.animateTo(1f, tween(520, easing = LinearOutSlowInEasing)) }
            press.animateTo(1f, tween(180))
            delay(160)
            press.animateTo(0f, tween(220))
            target = None
            delay(280)
        }
        delay(700)
        while (true) {
            step = 0
            repeat(2) { tap(Ok) }
            delay(700)
            step = 1
            tap(Right)
            tap(Left)
            delay(700)
            step = 2
            tap(Forward)
            tap(Rewind)
            delay(900)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = reveal.value }
            .background(Color.Black.copy(alpha = 0.8f))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                when {
                    e.key == Key.Back -> false
                    e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        done()
                        true
                    }
                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 280.dp, height = 252.dp), contentAlignment = Alignment.TopCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, 104.dp.toPx())
                    val dim = Color.White.copy(alpha = 0.5f)
                    val lit = { which: Int -> if (target == which) press.value else 0f }

                    drawCircle(Color.White.copy(alpha = 0.07f), 96.dp.toPx(), c)
                    drawCircle(Color.White.copy(alpha = 0.3f), 96.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.1f + 0.3f * lit(Ok)), 36.dp.toPx(), c)
                    drawCircle(Color.White.copy(alpha = 0.45f), 36.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))

                    val reach = 66.dp.toPx()
                    val arrow = 8.dp.toPx()
                    chevron(c + Offset(0f, -reach), 0, arrow, dim.copy(alpha = 0.3f), 2.5.dp.toPx())
                    chevron(c + Offset(0f, reach), 0, arrow, dim.copy(alpha = 0.3f), 2.5.dp.toPx())
                    for ((which, dir) in listOf(Left to -1, Right to 1)) {
                        val at = c + Offset(dir * reach, 0f)
                        val l = lit(which)
                        if (l > 0f) drawCircle(Color.White.copy(alpha = 0.3f * l), 26.dp.toPx(), at)
                        chevron(at, dir, arrow, Color.White.copy(alpha = 0.55f + 0.45f * l), 2.5.dp.toPx())
                    }
                    val focusAt =
                        when (target) {
                            Ok -> c
                            Left -> c + Offset(-reach, 0f)
                            Right -> c + Offset(reach, 0f)
                            else -> null
                        }
                    if (focusAt != null) tapRipple(focusAt, ring.value, 1f)

                    // Rewind / fast-forward keys.
                    val keyW = 82.dp.toPx()
                    val keyH = 38.dp.toPx()
                    val keyY = 216.dp.toPx()
                    for ((which, dir) in listOf(Rewind to -1, Forward to 1)) {
                        val kc = Offset(size.width / 2f + dir * 52.dp.toPx(), keyY)
                        val l = lit(which)
                        drawRoundRect(
                            Color.White.copy(alpha = 0.08f + 0.3f * l),
                            Offset(kc.x - keyW / 2f, kc.y - keyH / 2f),
                            Size(keyW, keyH),
                            CornerRadius(keyH / 2f),
                        )
                        drawRoundRect(
                            Color.White.copy(alpha = 0.3f),
                            Offset(kc.x - keyW / 2f, kc.y - keyH / 2f),
                            Size(keyW, keyH),
                            CornerRadius(keyH / 2f),
                            style = Stroke(1.5.dp.toPx()),
                        )
                        val tint = Color.White.copy(alpha = 0.6f + 0.4f * l)
                        chevron(kc + Offset(-7.dp.toPx() * dir, 0f), dir, 7.dp.toPx(), tint, 2.5.dp.toPx())
                        chevron(kc + Offset(7.dp.toPx() * dir, 0f), dir, 7.dp.toPx(), tint, 2.5.dp.toPx())
                    }
                }
                Text(
                    "OK",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset(y = 92.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            val copy =
                listOf(
                    "Press OK on the cover" to "to play or pause",
                    "Use the arrow keys" to "to move between the controls",
                    "Rewind and fast-forward keys" to "skip 10 seconds",
                )
            Crossfade(targetState = step, animationSpec = tween(300), label = "remote-copy") { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        copy[s].first,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(copy[s].second, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Press OK to continue",
                color = Color.Black.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .graphicsLayer { alpha = 0.55f + 0.45f * pulse }
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = 34.dp, vertical = 12.dp),
            )
        }
    }
}
