package com.gamut.feature.playerui.components

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.Pause
import com.adamglin.phosphoricons.light.Play
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private val CoverShape = RoundedCornerShape(12.dp)   // was 30dp: squarer, closer to a real album sleeve
private val CoverCornerRadius = 12.dp   // keep equal to CoverShape: the progress ring follows this radius
private val MaxCoverSize = 360.dp   // upper bound only; the real size comes from the space available (the width between prev / next)
private val RingGap = 3.5.dp     // same gap distance as before, now measured outward instead of inward
private val RingStrokeWidth = 1.5.dp
private val RingOutlineWidth = 0.5.dp   // extremely thin gray outline, per side, around the progress line
private val RingOutlineColor = Color(0xFF8C8C8C)
private const val PausedCoverScale = 0.96f   // the cover settles a touch smaller while paused (set to 1f to turn off)

// Scrubbing visuals (shown while the progress line is being dragged). Everything here has to fit in
// the space between the ring and the edge of its box: RingInset - (RingGap + stroke/2) ~= 13.75dp.
private val WaveBarWidth = 2.2.dp
private val WaveBarPitch = 3.dp          // distance between bar centers along the line (was 6dp: sparse, 'hairy')
private val WaveStartOffset = 2.5.dp     // gap between the line's center and the base of a bar
private val WaveMinLength = 1.dp         // quietest bar
private val WaveMaxLength = 10.dp        // tallest bar; base + length + cap (~17dp) pokes a few dp past the box, which is fine: nothing clips it
private val ScrubKnobRadius = 4.dp
private val ScrubDashHalfLength = 3.dp
private val ScrubDashWidth = 2.dp
private val RingClearance = 36.dp     // total (both sides) room for gap + stroke + waveform outside the cover
private val RingInset = RingClearance / 2  // the same room, per side

/**
 * Rounded-square cover with a thin progress line sitting just outside its edge.
 * Tapping the cover toggles play/pause. The icon shows briefly after a tap while
 * playing, and stays visible while paused.
 */
@Composable
internal fun ArtworkWithRing(
    modifier: Modifier = Modifier,
    url: String?,
    progress: () -> Float,
    isPlaying: Boolean,
    enabled: Boolean,
    /** True while the next track is being resolved: the play / pause glyph on the cover turns into a spinner. */
    loading: Boolean = false,
    waveform: FloatArray,
    onTogglePlay: () -> Unit,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    // Stretched + exaggerated once, so loud and quiet parts of the song look clearly different.
    val shapedWaveform = remember(waveform) { shapeWaveform(waveform) }
    // Read at draw time (not baked into drawWithCache), so bars update when the waveform finishes decoding.
    val shapedState = rememberUpdatedState(shapedWaveform)
    var tapTrigger by remember { mutableIntStateOf(0) }
    var iconVisible by remember { mutableStateOf(true) }
    var ringSize by remember { mutableStateOf(IntSize.Zero) } // measured size of the ring box
    var scrubbing by remember { mutableStateOf(false) }
    // 0 -> 1 while the line is being dragged; drives the waveform, dash and knob.
    val scrub by animateFloatAsState(if (scrubbing) 1f else 0f, tween(180), label = "scrub")

    // The gesture block below is keyed on (enabled, ringSize) only, so without these it would keep
    // calling the first lambdas it ever saw (stale `duration`, stale `isPlaying`).
    val currentToggle by rememberUpdatedState(onTogglePlay)
    val currentSeek by rememberUpdatedState(onSeekFraction)
    val currentScrub by rememberUpdatedState(onScrub)
    val haptics = LocalHapticFeedback.current

    // Settles slightly smaller while paused and lifts back when playback starts.
    val coverScale by animateFloatAsState(
        targetValue = if (enabled && !isPlaying) PausedCoverScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "cover-scale",
    )

    LaunchedEffect(isPlaying, tapTrigger) {
        iconVisible = true
        if (isPlaying) {
            delay(1200.milliseconds)
            iconVisible = false
        }
    }
    val iconAlpha by animateFloatAsState(if (iconVisible) 1f else 0f, animationSpec = tween(220), label = "icon-alpha")

    // The cover art is tried in order (see coverArtCandidates); each failure moves on to the next one.
    // YouTube thumbnails are 16:9 frames, so for them the cover takes the thumbnail's own shape instead of a square
    // (no bars, nothing cropped). The aspect comes from the loaded image; it is 16:9 until it has loaded.
    val candidates = remember(url) { coverArtCandidates(url) }
    val isThumb = remember(url) { url?.let(::youtubeThumbCandidates) != null }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val shownUrl = candidates.getOrNull(attempt)
    // While the sharp version loads, show the exact image the rest of the app already has (cached, so instant).
    val placeholderUrl = if (isThumb) null else url
    var loadedAspect by remember(shownUrl) { mutableStateOf<Float?>(null) }
    val targetAspect = if (enabled && isThumb) (loadedAspect ?: (16f / 9f)).coerceIn(1f, 2f) else 1f
    val aspect by animateFloatAsState(targetAspect, tween(250), label = "cover-aspect")

    // The caller decides how much width we get: the space between the prev/next
    // icons. The ring box fills that width up to a maximum; its height follows the cover's
    // aspect (square by default). Every inner size derives from it, so nothing depends on a
    // fixed dp value.
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = MaxCoverSize + RingClearance)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val clearance = RingClearance.roundToPx()
                    val h = ((w - clearance) / aspect).roundToInt() + clearance
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer { scaleX = coverScale; scaleY = coverScale },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(RingInset)
                    .fillMaxSize()
                    // One soft drop shadow: the same card look as Discover's album cards. No rim, no glow.
                    .shadow(elevation = 14.dp, shape = CoverShape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
                    .clip(CoverShape),
                contentAlignment = Alignment.Center,
            ) {
                if (enabled) {
                    if (shownUrl == null) {
                        ArtworkPlaceholder()
                    } else {
                        // The whole image is shown (Fit) in a box of its own shape, so nothing is cut off.
                        // If it still doesn't fill the box (an outlier), a heavily blurred copy of it fills
                        // the space around it instead of bars.
                        if (isThumb) {
                            AsyncImage(
                                model = shownUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(28.dp) else Modifier),
                            )
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                        }
                        SubcomposeAsyncImage(
                            model = shownUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = if (isThumb) ContentScale.Fit else ContentScale.Crop,
                            loading = {
                                if (placeholderUrl != null && placeholderUrl != shownUrl) {
                                    AsyncImage(
                                        model = placeholderUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize())   // empty: a light placeholder reads as a white flash on entry
                                }
                            },
                            error = { ArtworkPlaceholder() },
                            // This URL doesn't exist / failed: try the next candidate (past the last one: placeholder).
                            onError = { if (attempt < candidates.size) attempt++ },
                            onSuccess = { state ->
                                val size = state.painter.intrinsicSize
                                if (size.width > 0f && size.height > 0f) loadedAspect = size.width / size.height
                            },
                        )
                    }
                }
                // No circle behind the icon anymore, just the bare glyph (a bit larger so it
                // still reads on top of the artwork).
                if (enabled) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .graphicsLayer { alpha = if (loading) 1f else iconAlpha },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            PlayerSpinner(Modifier.size(40.dp), Color.White, 3.dp)
                        } else {
                            DepthIcon(
                                if (isPlaying) PhosphorIcons.Light.Pause else PhosphorIcons.Light.Play,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                size = 44.dp,
                            )
                        }
                    }
                }
            }

            // Progress line, sitting outside the cover. It is always white, with a
            // hairline gray outline so it stays visible against pale covers.
            // Drawn with drawWithCache: the ring's geometry (path, measurements, paints, waveform bars) is
            // built once per size change instead of every frame, and `progress` is only read while drawing,
            // so a moving line redraws this layer alone and never recomposes anything.
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { ringSize = it }
                    .pointerInput(enabled, ringSize) {
                        if (!enabled || ringSize.width == 0) return@pointerInput
                        val marginPx = RingInset.toPx()
                        val coverW = ringSize.width - RingClearance.toPx()
                        val coverH = ringSize.height - RingClearance.toPx()
                        val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                        val path = outerRingPath(coverW, coverH, marginPx, gapPx, CoverCornerRadius.toPx())
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val total = measure.length
                        val samples = 300
                        val pts = List(samples) { measure.getPosition(total * it / samples) }

                        // The line is only ~1.5dp thick, so it gets a generous touch band. The
                        // band stops short of the cover's interior so taps on the artwork itself
                        // still mean play/pause.
                        val coverInner = Rect(marginPx, marginPx, marginPx + coverW, marginPx + coverH)
                            .deflate(10.dp.toPx())
                        val hitRadius = 16.dp.toPx()   // a bit tighter than before: the cover now reaches near the screen edge, where the volume gesture lives
                        fun nearLine(p: Offset) =
                            !coverInner.contains(p) && pts.minOf { hypot(it.x - p.x, it.y - p.y) } < hitRadius
                        // Where along the line (0..1) the finger is, from the closest sampled point.
                        fun fractionAt(p: Offset): Float {
                            var best = 0; var bestD = Float.MAX_VALUE
                            pts.forEachIndexed { i, o ->
                                val d = hypot(o.x - p.x, o.y - p.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return (best.toFloat() / samples).coerceIn(0f, 1f)
                        }

                        // detectDragGestures only reacts once the finger has moved past the touch
                        // slop, so a plain tap on the line did nothing. Handle down/drag/up by
                        // hand: a touch on the line grabs it immediately and follows the finger.
                        // While held, only the preview moves; the player is asked to seek once, on
                        // release (a seek per touch event makes the audio stutter and the line creep
                        // away from a resting finger while the song keeps playing).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (nearLine(down.position)) {
                                down.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scrubbing = true
                                var last = fractionAt(down.position)
                                try {
                                    currentScrub(last)
                                    val released = drag(down.id) { change ->
                                        change.consume()
                                        val f = fractionAt(change.position)
                                        // The line is a closed loop: crossing its start/end seam pins the
                                        // thumb to that end instead of teleporting it to the other one.
                                        last = if (abs(f - last) > 0.5f) (if (last > 0.5f) 1f else 0f) else f
                                        currentScrub(last)
                                    }
                                    if (released) currentSeek(last)
                                } finally {
                                    scrubbing = false
                                    currentScrub(null)
                                }
                            } else {
                                // This layer sits above the cover, so it also owns the cover tap.
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentToggle()
                                    tapTrigger++
                                }
                            }
                        }
                    }
                    .drawWithCache {
                        // Nothing playing: no progress line at all.
                        if (!enabled) return@drawWithCache onDrawBehind {}
                        val marginPx = RingInset.toPx()
                        val coverW = size.width - RingClearance.toPx()
                        val coverH = size.height - RingClearance.toPx()
                        val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                        val path = outerRingPath(coverW, coverH, marginPx, gapPx, CoverCornerRadius.toPx())
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val total = measure.length
                        val strokePx = RingStrokeWidth.toPx()
                        val outlinePx = RingOutlineWidth.toPx()

                        // Reused every frame (no per-frame allocation).
                        val filled = Path()
                        // Gray outline underneath: same path, slightly thicker than the white line.
                        val outlinePaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx + outlinePx * 2f
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = RingOutlineColor.toArgb()
                            setShadowLayer(3.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.argb(80, 0, 0, 0))
                        }
                        val linePaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = android.graphics.Color.WHITE
                        }

                        // Scrub visuals. Bar positions and directions never change for a given size, so they
                        // are worked out once here. Heights are read at draw time (the waveform arrives later).
                        val barW = WaveBarWidth.toPx()
                        val startOff = WaveStartOffset.toPx()
                        val minLen = WaveMinLength.toPx()
                        val maxLen = WaveMaxLength.toPx()
                        val barCount = (total / WaveBarPitch.toPx()).toInt().coerceAtLeast(1)
                        val barX = FloatArray(barCount)
                        val barY = FloatArray(barCount)
                        val barNx = FloatArray(barCount)
                        val barNy = FloatArray(barCount)
                        for (i in 0 until barCount) {
                            val dist = total * (i + 0.5f) / barCount
                            val pos = measure.getPosition(dist)
                            val tan = measure.getTangent(dist)
                            barX[i] = pos.x
                            barY[i] = pos.y
                            // The path runs clockwise, so (ty, -tx) points away from the cover.
                            barNx[i] = tan.y
                            barNy[i] = -tan.x
                        }
                        val dashHalfLength = ScrubDashHalfLength.toPx()
                        val dashWidth = ScrubDashWidth.toPx()
                        val knobRadius = ScrubKnobRadius.toPx()
                        val startPoint = measure.getPosition(0f)
                        // Bar heights, ranked across the whole ring; redone only when a new waveform arrives.
                        var ampsFor: FloatArray? = null
                        var amps = FloatArray(barCount)

                        onDrawBehind {
                            val fill = progress()
                            val reveal = scrub

                            // Faint full-length track (with its hairline outline), so the whole line is
                            // visible before it fills.
                            drawPath(
                                path = path,
                                color = RingOutlineColor.copy(alpha = 0.45f),
                                style = Stroke(width = strokePx + outlinePx * 2f),
                            )
                            drawPath(
                                path = path,
                                color = Color.White.copy(alpha = 0.22f),
                                style = Stroke(width = strokePx),
                            )

                            if (fill > 0f) {
                                filled.rewind()
                                measure.getSegment(0f, total * fill, filled, true)
                                drawIntoCanvas { canvas ->
                                    val androidPath = filled.asAndroidPath()
                                    canvas.nativeCanvas.drawPath(androidPath, outlinePaint)
                                    canvas.nativeCanvas.drawPath(androidPath, linePaint)
                                }
                            }

                            // While dragging: the track's waveform along the outside of the line, a small
                            // dash where the track starts, and a knob at the current position.
                            if (reveal > 0.01f) {
                                val wave = shapedState.value   // state read: redraws when the waveform arrives
                                if (wave !== ampsFor) {
                                    amps = barHeights(wave, barCount)
                                    ampsFor = wave
                                }
                                for (i in 0 until barCount) {
                                    val f = (i + 0.5f) / barCount
                                    val amp = amps[i]
                                    val len = (minLen + (maxLen - minLen) * amp) * reveal
                                    drawLine(
                                        color = Color.White.copy(alpha = (if (f <= fill) 0.95f else 0.35f) * reveal),
                                        start = Offset(barX[i] + barNx[i] * startOff, barY[i] + barNy[i] * startOff),
                                        end = Offset(barX[i] + barNx[i] * (startOff + len), barY[i] + barNy[i] * (startOff + len)),
                                        strokeWidth = barW,
                                        cap = StrokeCap.Round,
                                    )
                                }

                                // Start dash: the path begins at the top-center, where the line is horizontal,
                                // so the dash is simply vertical.
                                val half = dashHalfLength * reveal
                                drawLine(
                                    color = Color.White,
                                    start = Offset(startPoint.x, startPoint.y - half),
                                    end = Offset(startPoint.x, startPoint.y + half),
                                    strokeWidth = dashWidth,
                                    cap = StrokeCap.Round,
                                )

                                // Knob: plain filled circle, no outline.
                                drawCircle(
                                    color = Color.White,
                                    radius = knobRadius * reveal,
                                    center = measure.getPosition(total * fill),
                                )
                            }
                        }
                    },
            )
        }
    }
}

/**
 * Builds the outward progress-line path (the cover may be wider than tall). It is the cover's rectangle, grown
 * outward by [gapPx] on every side. The corner radius grows by the same
 * amount, so the curve stays concentric with the cover's own corners.
 *
 * The path is built by hand so it starts at the top-center and runs clockwise
 * (right along the top edge, then down the right side, ...). addRoundRect's
 * winding is not something to rely on, and the progress fill, seek math and
 * waveform all depend on this order.
 */
private fun outerRingPath(coverW: Float, coverH: Float, marginPx: Float, gapPx: Float, coverRadiusPx: Float): Path {
    val left = marginPx - gapPx
    val top = marginPx - gapPx
    val right = marginPx + coverW + gapPx
    val bottom = marginPx + coverH + gapPx
    val r = coverRadiusPx + gapPx
    val d = r * 2f
    val cx = (left + right) / 2f
    return Path().apply {
        moveTo(cx, top)
        lineTo(right - r, top)
        arcTo(Rect(right - d, top, right, top + d), -90f, 90f, false)
        lineTo(right, bottom - r)
        arcTo(Rect(right - d, bottom - d, right, bottom), 0f, 90f, false)
        lineTo(left + r, bottom)
        arcTo(Rect(left, bottom - d, left + d, bottom), 90f, 90f, false)
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + d, top + d), 180f, 90f, false)
        close()
    }
}