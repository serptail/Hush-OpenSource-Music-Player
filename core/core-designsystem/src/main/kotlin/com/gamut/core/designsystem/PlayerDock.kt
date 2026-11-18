package com.gamut.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Player <-> mini strip docking.
 *
 * The spring-driven sheet (one critically damped spring for every open and
 * close, the finger's own release speed fed into it, a flick deciding on its
 * own) and the idea of flying the artwork between two measured rects are
 * adapted from BitChord's PlayerSheetMotion / PlayerDock
 * (https://github.com/kushagrasinghx/BitChord, GPLv3 — compatible with this
 * project's AGPLv3 under GPLv3 section 13).
 *
 * What Hush does differently:
 *  - the player is not slid off the screen: it is an iris, a rounded rect that
 *    shrinks into the strip's own bounds and grows back out of it;
 *  - the artwork leaves the player on a curve (it swings sideways ahead of its
 *    drop) with a small lean, rather than on a straight line;
 *  - the strip's cover lands with a squash and rebound, and a light tick.
 */

/** Opening is brisk, closing is heavier: it is the one a finger is steering. */
private const val OPEN_STIFFNESS = 260f
private const val CLOSE_STIFFNESS = 195f

/** Close enough to stop, as a share of the travel, rather than a spring's long tail. */
private const val SETTLED = 0.0005f

/** Release speed, in px/s, past which a release decides on its own. */
private const val FLING_PX_PER_S = 1100f

/** How far the player has to be pulled for the pill to be at its widest, as a share of travel. */
private const val PULL_FULL = 0.12f

private const val MIN_TRAVEL_PX = 240f
private const val TILT_DEGREES = -3f

internal fun dockSmooth(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun dockSmoother(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

/**
 * Where the player stands, 1 fully open and 0 docked into the strip, moved by
 * one spring however it is being moved: a tap, a back press, a pill dragged.
 */
@Stable
class PlayerMotion(
    private val scope: CoroutineScope,
    private val travel: () -> Float,
    private val onTarget: (open: Boolean) -> Unit = {},
    private val onCovering: (covering: Boolean) -> Unit = {},
    private val onLand: () -> Unit = {},
) {
    var fraction by mutableFloatStateOf(0f)
        private set

    private var settle: Job? = null
    private var dragging = false
    private var pullStartNanos = 0L
    private var pullStartFraction = 1f

    /** 0 untouched, 1 pulled far enough for the pill to be at its widest. */
    fun pulled(): Float = ((1f - fraction) / PULL_FULL).coerceAtLeast(0f)

    fun open(velocityPx: Float = 0f) {
        settle?.cancel()
        dragging = false
        onTarget(true)
        settle =
            scope.launch {
                slide(to = 1f, velocityPx = velocityPx, stiffness = OPEN_STIFFNESS)
                onCovering(true)
            }
    }

    fun close(velocityPx: Float = 0f) {
        settle?.cancel()
        dragging = false
        onCovering(false)
        onTarget(false)
        settle =
            scope.launch {
                val flew = fraction > 0f
                slide(to = 0f, velocityPx = velocityPx, stiffness = CLOSE_STIFFNESS)
                if (flew) land()
            }
    }

    /** From a pill: [deltaPx] is positive going down. */
    fun dragBy(deltaPx: Float) {
        val travelPx = travel()
        if (travelPx <= 0f) return
        if (!dragging) {
            dragging = true
            settle?.cancel()
            onCovering(false)
            pullStartNanos = System.nanoTime()
            pullStartFraction = fraction
        }
        fraction = (fraction - deltaPx / travelPx).coerceIn(0f, 1f)
    }

    /** From a pill, with the finger's speed in px/s, positive going down. */
    fun release(velocityPx: Float) {
        if (!dragging) return
        dragging = false
        // A short fast swipe can lift with no speed on its last event at all,
        // so the whole pull's average counts too, if it was faster.
        val seconds = (System.nanoTime() - pullStartNanos) / 1e9f
        val average = if (seconds > 0f) (pullStartFraction - fraction) * travel() / seconds else 0f
        val speed = if (abs(average) > abs(velocityPx)) average else velocityPx
        val flick = abs(speed) >= FLING_PX_PER_S
        val stay = if (flick) speed < 0f else fraction > 0.5f
        if (stay) open(speed) else close(speed)
    }

    private suspend fun slide(
        to: Float,
        velocityPx: Float,
        stiffness: Float,
    ) {
        val travelPx = travel()
        // Down is positive for the finger and negative for the fraction.
        val velocity = if (travelPx > 0f) -velocityPx / travelPx else 0f
        animate(
            initialValue = fraction,
            targetValue = to,
            initialVelocity = velocity,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = stiffness,
                    visibilityThreshold = SETTLED,
                ),
        ) { value, _ ->
            // Thrown hard enough it can pass its target once: never past either end.
            fraction = value.coerceIn(0f, 1f)
        }
        fraction = to
    }

    private fun land() {
        onLand()
    }
}

/** One frame of the artwork's trip, in the flight layer's own pixels. */
class FlightPose(
    val rect: Rect,
    val cornerPx: Float,
    val tilt: Float,
    val fadeFraction: Float,
    val fadeAmount: Float,
    val scrim: Boolean,
    val border: Float,
)

/**
 * The two ends of the artwork's trip and the strip it collapses into, each
 * reported by the side that owns it and met in root coordinates. Held as
 * coordinates, not rects, and only turned into pixels when a frame asks.
 */
@Stable
class PlayerDock {
    private val strips = ArrayList<LayoutCoordinates>(2)
    private val covers = ArrayList<LayoutCoordinates>(2)
    private var playerArt: LayoutCoordinates? = null
    private var playerCornerPx = 0f
    private var playerFadeFraction = 0f
    private var playerScrim = false
    private var miniCornerPx = 0f

    /** The flight layer's place in the root, set by [PlayerArtFlight]. */
    var hostOrigin: Offset = Offset.Zero
    var hostSize: Size = Size.Zero

    /** The image the player's artwork is really showing, so the flight is a cache hit. */
    var artModel: Any? by mutableStateOf(null)

    /** Written as the flight is laid out, read by its draw in the same frame. */
    var pose: FlightPose? = null

    internal fun reportStrip(c: LayoutCoordinates) {
        if (strips.none { it === c }) strips.add(c)
    }

    internal fun releaseStrip(c: LayoutCoordinates) {
        strips.removeAll { it === c }
    }

    internal fun reportCover(
        c: LayoutCoordinates,
        cornerPx: Float,
    ) {
        if (covers.none { it === c }) covers.add(c)
        miniCornerPx = cornerPx
    }

    internal fun releaseCover(c: LayoutCoordinates) {
        covers.removeAll { it === c }
    }

    internal fun reportPlayerArt(
        c: LayoutCoordinates,
        cornerPx: Float,
        fadeFraction: Float,
        scrim: Boolean,
    ) {
        playerArt = c
        playerCornerPx = cornerPx
        playerFadeFraction = fadeFraction
        playerScrim = scrim
    }

    internal fun releasePlayerArt(c: LayoutCoordinates) {
        if (playerArt === c) playerArt = null
    }

    private fun rect(c: LayoutCoordinates): Rect {
        val a = c.localToRoot(Offset.Zero) - hostOrigin
        val b = c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat())) - hostOrigin
        return Rect(a, b)
    }

    /** The newest one that is actually on screen: tab pages parked off to the side don't count. */
    private fun onScreen(list: List<LayoutCoordinates>): LayoutCoordinates? {
        for (i in list.indices.reversed()) {
            val c = list[i]
            if (!c.isAttached) continue
            val center = rect(c).center
            if (center.x in 0f..hostSize.width && center.y in 0f..hostSize.height) return c
        }
        return null
    }

    fun stripRect(): Rect? = onScreen(strips)?.let(::rect)

    /** How far the iris's top edge has to travel: the finger drags it 1:1. */
    fun travelPx(): Float = (stripRect()?.top ?: hostSize.height).coerceAtLeast(MIN_TRAVEL_PX)

    /** The player's visible rect at [t]: the strip's own bounds at 0, the whole layer at 1. */
    fun irisRect(
        t: Float,
        width: Float,
        height: Float,
    ): Rect {
        val s = stripRect() ?: Rect(0f, height, width, height)
        return Rect(
            left = lerp(s.left, 0f, t),
            top = lerp(s.top, 0f, t),
            right = lerp(s.right, width, t),
            bottom = lerp(s.bottom, height, t),
        )
    }

    /** Whether there is an artwork and a cover to fly between — the same test for both ends. */
    fun ready(): Boolean {
        val art = playerArt
        return artModel != null && art != null && art.isAttached && onScreen(covers) != null
    }

    fun hidesPlayerArt(t: Float): Boolean = t < 1f && ready()

    fun hidesMiniCover(t: Float): Boolean = t > 0f && ready()

    fun poseFor(t: Float): FlightPose? {
        if (t <= 0f || t >= 1f || !ready()) return null
        val art = playerArt ?: return null
        val mini = onScreen(covers) ?: return null
        val to = rect(art)
        val from = rect(mini)
        // 0 with the player open, 1 down on the strip.
        val u = 1f - t
        // Sideways it swings ahead of the drop: out fast, then settling, so the
        // artwork banks into the strip's corner rather than sliding in a line.
        val lead = 1f - (1f - u) * (1f - u)
        val cx = lerp(to.center.x, from.center.x, lead)
        val cy = lerp(to.center.y, from.center.y, u)
        val w = lerp(to.width, from.width, u)
        val h = lerp(to.height, from.height, u)
        val fadeAmount = if (playerFadeFraction > 0f) dockSmooth(0.55f, 1f, t) else 0f
        return FlightPose(
            rect = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
            cornerPx = lerp(playerCornerPx, miniCornerPx, u),
            tilt = sin(PI.toFloat() * u) * TILT_DEGREES,
            fadeFraction = playerFadeFraction,
            fadeAmount = fadeAmount,
            scrim = playerScrim,
            border = 1f - fadeAmount,
        )
    }
}

val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }
val LocalPlayerMotion = staticCompositionLocalOf<PlayerMotion?> { null }

private class CoordsHolder {
    var c: LayoutCoordinates? = null
}

/** On the whole mini strip: where the player collapses into. */
@Composable
fun Modifier.dockStrip(): Modifier {
    val dock = LocalPlayerDock.current ?: return this
    val holder = remember { CoordsHolder() }
    DisposableEffect(dock) {
        onDispose {
            holder.c?.let(dock::releaseStrip)
            holder.c = null
        }
    }
    return this.onGloballyPositioned {
        holder.c = it
        dock.reportStrip(it)
    }
}

/** On the strip's cover: where the artwork lands, standing aside while it is in flight. */
@Composable
fun Modifier.dockCover(corner: Dp): Modifier {
    val dock = LocalPlayerDock.current
    val motion = LocalPlayerMotion.current
    if (dock == null || motion == null) return this
    val holder = remember { CoordsHolder() }
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(dock) {
        onDispose {
            holder.c?.let(dock::releaseCover)
            holder.c = null
        }
    }
    return this
        .onGloballyPositioned {
            holder.c = it
            dock.reportCover(it, cornerPx)
        }.graphicsLayer {
            alpha = if (dock.hidesMiniCover(motion.fraction)) 0f else 1f
        }
}

/**
 * First in an artwork's chain: it stands aside while the flying copy is in its
 * place. Before the artwork's own shadow, so that goes too.
 */
@Composable
fun Modifier.dockArtHide(enabled: Boolean = true): Modifier {
    val dock = LocalPlayerDock.current
    val motion = LocalPlayerMotion.current
    if (dock == null || motion == null || !enabled) return this
    return this.graphicsLayer { alpha = if (dock.hidesPlayerArt(motion.fraction)) 0f else 1f }
}

/**
 * Last in an artwork's chain, after any scale it carries, so the rect reported
 * is the one on screen. [model] is the image it is really showing.
 */
@Composable
fun Modifier.dockArtReport(
    model: Any?,
    corner: Dp,
    enabled: Boolean = true,
    fadeFraction: Float = 0f,
    scrim: Boolean = false,
): Modifier {
    val dock = LocalPlayerDock.current ?: return this
    val holder = remember { CoordsHolder() }
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val currentFade by rememberUpdatedState(fadeFraction)
    val currentScrim by rememberUpdatedState(scrim)
    SideEffect { if (enabled) dock.artModel = model }
    DisposableEffect(dock, enabled) {
        onDispose {
            holder.c?.let(dock::releasePlayerArt)
            holder.c = null
        }
    }
    if (!enabled) return this
    return this.onGloballyPositioned {
        holder.c = it
        dock.reportPlayerArt(it, cornerPx, currentFade, currentScrim)
    }
}

private class IrisShape(
    private val rect: Rect,
    private val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The player as an iris: clipped to a rounded rect that is the strip's bounds at
 * 0 and the whole layer at 1, faded out right at the strip so the strip itself
 * shows through as it lands.
 */
fun androidx.compose.ui.graphics.GraphicsLayerScope.irisLayer(
    dock: PlayerDock,
    t: Float,
) {
    if (t <= 0f) {
        // Closed: invisible is not enough, a layer at zero alpha is still hit-tested and
        // would sit over the whole app catching taps. Park it off the screen instead,
        // exactly where the old slide left it. Only ever at exactly 0, so the artwork's
        // measured place is the real one at every frame of a flight.
        alpha = 0f
        translationY = size.height
        return
    }
    alpha = dockSmooth(0f, 0.22f, t)
    if (t < 1f) {
        shape = IrisShape(dock.irisRect(t, size.width, size.height), (28.dp.toPx()) * (1f - t))
        clip = true
    }
}

/**
 * The player's own contents. They stay fully opaque for almost the whole trip and only
 * dissolve together with the layer right at the strip: fading them earlier left the flat
 * dark plate under them showing as a black rounded rect while folding or unfolding.
 */
fun contentFade(t: Float): Float = dockSmooth(0f, 0.22f, t)

/**
 * Over everything while the player moves: the artwork's flying copy. Placed in
 * the same parent as the player layer so both share one origin.
 */
@Composable
fun PlayerArtFlight(
    dock: PlayerDock,
    motion: PlayerMotion,
    modifier: Modifier = Modifier,
) {
    val model = dock.artModel
    val tile = artTileColor()
    val borderColor = thumbnailBorderColor()
    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            dock.hostOrigin = it.positionInRoot()
            dock.hostSize = it.size.toSize()
        },
    ) {
        if (model == null) return@Box
        Box(
            Modifier.layout { measurable, constraints ->
                val pose = dock.poseFor(motion.fraction)
                dock.pose = pose
                if (pose == null) {
                    layout(0, 0) {}
                } else {
                    val w = pose.rect.width.roundToInt().coerceAtLeast(1)
                    val h = pose.rect.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(pose.rect.left.roundToInt(), pose.rect.top.roundToInt())
                    }
                }
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Read only to hear about every frame: the pose itself is
                        // worked out while the flight is laid out, just before this.
                        motion.fraction
                        val p = dock.pose
                        if (p != null) {
                            rotationZ = p.tilt
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(p.cornerPx)
                            clip = true
                            if (p.fadeAmount > 0f) compositingStrategy = CompositingStrategy.Offscreen
                        }
                    }.drawWithContent {
                        drawRect(tile)
                        drawContent()
                        val p = dock.pose ?: return@drawWithContent
                        if (p.fadeAmount > 0f) {
                            val stops =
                                Array(FADE_STOPS + 1) { i ->
                                    val x = i / FADE_STOPS.toFloat()
                                    x to Color.Black.copy(alpha = 1f - p.fadeAmount * dockSmoother(x))
                                }
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        colorStops = stops,
                                        startY = size.height * (1f - p.fadeFraction),
                                        endY = size.height,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        if (p.border > 0.01f) {
                            val s = 1.dp.toPx()
                            drawRoundRect(
                                color = borderColor.copy(alpha = borderColor.alpha * p.border),
                                topLeft = Offset(s / 2f, s / 2f),
                                size = Size(size.width - s, size.height - s),
                                cornerRadius = CornerRadius(max(0f, p.cornerPx - s / 2f)),
                                style = Stroke(s),
                            )
                        }
                    },
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // The banner's own scrim under the status bar, so touchdown has no pop.
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        val p = dock.pose
                        if (p != null && p.scrim && p.fadeAmount > 0f) {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.38f * p.fadeAmount), Color.Transparent),
                                    endY = 120.dp.toPx(),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

private const val FADE_STOPS = 8