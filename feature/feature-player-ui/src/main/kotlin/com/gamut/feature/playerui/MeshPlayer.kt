package com.gamut.feature.playerui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.SkipBack
import com.adamglin.phosphoricons.regular.SkipForward
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.focusRing
import com.gamut.feature.playerui.components.ArtworkPlaceholder
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.PlayerSpinner
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.coverArtCandidates
import com.gamut.feature.playerui.components.youtubeThumbCandidates
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The "mesh" player style, switched on under Settings > Appearance > Player.
 *
 * Adapted from the player UI of another app (BitChord): the artwork is averaged
 * into a tiny grid of colours, which is flipped, rotated, smoothed and stretched
 * under the Now Playing screen, hung from the artwork's bottom edge so there is
 * no seam between the cover and the colour beneath it. The cover itself is a
 * plain rounded sleeve that eases back while paused, over scrubber + transport
 * controls. Everything here is draw-phase only once the mesh is built: no live
 * full-screen blur, nothing that re-renders while the track plays.
 */

/** The widest the sleeve gets, so a tablet doesn't draw a billboard. */
internal val MeshSleeveMaxWidth = 420.dp

// ---------------------------------------------------------------------------------------------
// Backdrop
// ---------------------------------------------------------------------------------------------

/** The artwork reduced to a small colour texture, ready to stretch over the screen. */
@Immutable
internal class ArtworkMesh internal constructor(internal val image: ImageBitmap)

/**
 * The mesh for the artwork at [url], or null until one has been read.
 *
 * The last mesh on screen is held while the next cover decodes, so a skip never
 * drops the backdrop to its fallback in between: wrong colour for one decode is
 * better than no colour.
 */
@Composable
internal fun rememberArtworkMesh(url: String?): ArtworkMesh? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<ArtworkMesh?>(null) }
    var mesh by remember(url) { mutableStateOf(url?.let { meshCache[it] } ?: held.value) }
    // What `mesh` was actually read from: null while it is a carry-over from the previous cover.
    var meshUrl by remember(url) { mutableStateOf(if (url != null && meshCache[url] != null) url else null) }
    LaunchedEffect(mesh) { held.value = mesh }

    LaunchedEffect(url) {
        if (url == null || meshUrl == url) return@LaunchedEffect
        // Retried: this effect is keyed on the URL alone and Coil doesn't retry a failed
        // read, so one dropped connection would otherwise leave the backdrop flat until
        // the next track.
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MESH_RETRY_DELAY_MS)
            val request = ImageRequest.Builder(context).data(url).size(MESH_PX).allowHardware(false).build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmapOrNull()
            if (bitmap != null) {
                val found = withContext(Dispatchers.Default) { meshOf(bitmap, url.hashCode()) }
                if (found != null) {
                    meshCache[url] = found
                    mesh = found
                }
                // A cover that decoded but had no mesh in it is an answer, not a failure.
                meshUrl = url
                return@LaunchedEffect
            }
        }
    }
    return mesh
}

/**
 * The player's backdrop: [mesh] hung from [seam] (the artwork's bottom edge, in pixels from
 * the top of this surface) and stretched over everything below it. Above the seam the mesh's
 * first row is simply held, so the backdrop is continuous behind the artwork too.
 *
 * [seam] is read while drawing, so a seam that moves with a scroll costs no recomposition.
 */
@Composable
internal fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    seam: () -> Float = { 0f },
) {
    // Two bitmaps rather than one animated colour: the outgoing mesh has to stay drawable
    // until the incoming one has finished fading over it.
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(mesh, animate) {
        val next = mesh ?: return@LaunchedEffect
        // A change arriving mid-fade: what was fading in has been on screen a while and is
        // what the new one should fade *from*.
        incoming?.let { shown = it }
        incoming = null
        val current = shown
        if (next === current) return@LaunchedEffect
        if (current == null || !animate) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    // A source for the app's frosted glass (sheets, bars), as the classic backdrop is.
    Box(modifier.fillMaxSize().acrylicSource()) {
        Canvas(Modifier.fillMaxSize().background(FallbackBackdrop)) {
            val seamY = seam().coerceIn(0f, size.height)
            shown?.let { drawMesh(it, seamY, alpha = 1f) }
            incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

            // Just enough shade to keep white text legible over a bright cover.
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.36f)),
                ),
            )
        }
    }
}

/** Two draws of one small texture, both stretched by the sampler: the held first row, then the grid. */
private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * Tracks where the sleeve's bottom edge is, in the backdrop's own coordinates.
 *
 * Measured against the host rather than the window, so the player layer sliding in or out
 * doesn't skew it. Only [px] is observable, and the backdrop reads it while drawing.
 */
@Stable
internal class MeshSeam {
    private var host: LayoutCoordinates? = null
    private var sleeve: LayoutCoordinates? = null

    var px by mutableFloatStateOf(0f)
        private set

    /** Goes on the surface the backdrop fills. */
    val onHost: Modifier = Modifier.onGloballyPositioned {
        host = it
        update()
    }

    /** Goes on the sleeve's un-scaled layout box. */
    val onSleeve: Modifier = Modifier.onGloballyPositioned {
        sleeve = it
        update()
    }

    private fun update() {
        val root = host?.takeIf { it.isAttached } ?: return
        val art = sleeve?.takeIf { it.isAttached } ?: return
        px = root.localPositionOf(art, Offset(0f, art.size.height.toFloat())).y
    }
}

/** Drawn only until an artwork has been read: never a colour anyone chose. */
private val FallbackBackdrop = Color(0xFF121212)

private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkMesh>?) = size > MESH_CACHE_ENTRIES
}

/** A session's worth of covers, at four kilobytes of texture each. */
private const val MESH_CACHE_ENTRIES = 64

/** Decoded at a whole multiple of [MESH_GRID], so every cell averages the same number of pixels. */
private const val MESH_PX = 120

private const val MESH_ATTEMPTS = 4
private const val MESH_RETRY_DELAY_MS = 1_500L

/** Long enough to read as the screen changing colour rather than cutting. */
private const val MESH_FADE_MS = 900

/** Coarse on purpose: enough that a cover's layout survives, too little for anything recognisable to. */
private const val MESH_GRID = 6

/** The grid is interpolated up to this on the CPU so no cell boundary shows as a crease once magnified. */
private const val MESH_TEX = 32

/** Three box passes of this radius make a Gaussian of ~1.4 texels, baked in rather than a live blur. */
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3

/** Bounds the work per read however large the bitmap is. */
private const val MESH_SAMPLE = 128

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f

/**
 * Averages [source] into the mesh texture, flipped top to bottom (so row 0 is the artwork's own
 * bottom edge) and then rotated sideways below that row, so the colour under the cover doesn't
 * line up column-for-column with the cover above it and read as a reflection.
 *
 * A mean per cell, not a quantised swatch: what sits under the artwork should be what a blur of
 * it would leave there, and a blur has no opinion about which colour was the interesting one. A
 * cover that is nine-tenths black with a red stripe stays nine-tenths black.
 */
private fun meshOf(source: Bitmap, seed: Int): ArtworkMesh? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null

    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        // Flipped as it is read.
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += ((pixel shr 16) and 0xFF).toLong()
            green[cell] += ((pixel shr 8) and 0xFF).toLong()
            blue[cell] += (pixel and 0xFF).toLong()
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val scratch = IntArray(texels.size)
    repeat(MESH_BLUR_PASSES) {
        boxBlurPass(texels, scratch, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = true)
        boxBlurPass(scratch, texels, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = false)
    }
    return ArtworkMesh(Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888).asImageBitmap())
}

/**
 * Every row but row 0 (the seam row, kept literal so there is no visible join) rotated sideways
 * by the same seeded amount, and mirrored half the time.
 *
 * A cyclic shift rather than a shuffle: every cell keeps the exact neighbours it started with, so
 * whatever gradient existed between them survives. A per-cell shuffle breaks that and reads as a
 * mosaic of small blended islands instead of two or three broad blobs. Seeded so the same cover
 * always lands in the same arrangement.
 */
private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

/** Smoothly resamples a [cols] x [rows] grid of control points up to a [size] x [size] texture. */
private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

/** Flat at both ends, so no cell boundary shows up as a crease once magnified. */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * The one liberty taken with the artwork's colours. Averaging greys a block of pixels (the mean
 * of a red stripe and the black around it is a dull maroon), so saturation is nudged back up by
 * roughly what the averaging took. Lightness is only floored off pure black. Neither touches the
 * proportions, which is the point of this backdrop.
 */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private fun boxBlurPass(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1
    repeat(minor) { fixed ->
        var red = 0
        var green = 0
        var blue = 0
        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + position] else source[position * width + fixed]
        }
        for (offset in -radius..radius) {
            val color = pixel(offset)
            red += color shr 16 and 0xFF
            green += color shr 8 and 0xFF
            blue += color and 0xFF
        }
        repeat(major) { moving ->
            val index = if (horizontal) fixed * width + moving else moving * width + fixed
            target[index] = argb((red + window / 2) / window, (green + window / 2) / window, (blue + window / 2) / window)
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sleeve
// ---------------------------------------------------------------------------------------------

private val SleeveShape = RoundedCornerShape(8.dp)

/** How far the cover eases back while paused. */
private const val PausedSleeveScale = 0.86f

/**
 * The cover as a plain rounded sleeve with a soft shadow, easing back on a low-bounce spring while
 * paused. [modifier] is applied to the un-scaled layout box, so anything measuring it (see
 * [MeshSeam.onSleeve]) sees the full size rather than the paused one.
 */
@Composable
internal fun MeshSleeve(url: String?, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else PausedSleeveScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "mesh-sleeve-scale",
    )
    val candidates = remember(url) { coverArtCandidates(url) }
    val isThumb = remember(url) { url?.let(::youtubeThumbCandidates) != null }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val shownUrl = candidates.getOrNull(attempt)
    val placeholderUrl = if (isThumb) null else url

    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // A shadow grounds a photo; on the flat placeholder it would only ring it.
            .then(
                if (shownUrl != null) {
                    Modifier.shadow(10.dp, SleeveShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                } else {
                    Modifier
                },
            )
            .clip(SleeveShape),
    ) {
        if (shownUrl == null) {
            ArtworkPlaceholder()
        } else {
            if (isThumb) {
                // A video thumbnail isn't square: blurred copy fills the sleeve, the frame sits in it.
                AsyncImage(
                    model = shownUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }
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
                        Box(Modifier.fillMaxSize())
                    }
                },
                error = { ArtworkPlaceholder() },
                onError = { if (attempt < candidates.size) attempt++ },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scrubber
// ---------------------------------------------------------------------------------------------

private val ScrubberRestHeight = 5.dp
private val ScrubberActiveHeight = 11.dp
private val ScrubberHitHeight = 32.dp

/**
 * A thin progress bar that thickens under the finger. Tap to seek there, drag to scrub.
 *
 * [progress] is read while drawing, so the bar moving with playback never recomposes anything.
 * [onScrub] reports the fraction under the finger while dragging and null once it lets go;
 * [onSeekFraction] commits a seek. Left and right (or the media keys) step by [seekStep], for
 * remotes and keyboards.
 */
@Composable
internal fun MeshScrubber(
    progress: () -> Float,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    seekStep: Float = 0.04f,
) {
    val currentProgress by rememberUpdatedState(progress)
    val currentScrub by rememberUpdatedState(onScrub)
    val currentSeek by rememberUpdatedState(onSeekFraction)
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var lastFraction by remember { mutableFloatStateOf(0f) }
    val active = pressed || dragging
    val thickness by animateDpAsState(
        targetValue = if (active) ScrubberActiveHeight else ScrubberRestHeight,
        animationSpec = tween(180),
        label = "mesh-scrubber-thickness",
    )
    val trackColor = Color.White.copy(alpha = 0.28f)
    val fillColor = Color.White.copy(alpha = 0.92f)

    Box(
        modifier
            .fillMaxWidth()
            .height(ScrubberHitHeight)
            .focusRing(RoundedCornerShape(8.dp))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.MediaFastForward, Key.MediaStepForward -> {
                        currentSeek((currentProgress() + seekStep).coerceIn(0f, 1f))
                        true
                    }

                    Key.DirectionLeft, Key.MediaRewind, Key.MediaStepBackward -> {
                        currentSeek((currentProgress() - seekStep).coerceIn(0f, 1f))
                        true
                    }

                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { at -> currentSeek((at.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) },
                )
            }
            // Horizontal only, and only past touch slop: a vertical swipe that happens to start
            // on the bar scrolls the page instead of seeking.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        dragging = true
                        lastFraction = (at.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        currentScrub(lastFraction)
                    },
                    onDragEnd = {
                        dragging = false
                        currentSeek(lastFraction)
                        currentScrub(null)
                    },
                    onDragCancel = {
                        dragging = false
                        currentScrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        lastFraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        currentScrub(lastFraction)
                    },
                )
            }
            .semantics { contentDescription = "Seek" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = thickness.toPx()
            val top = (size.height - h) / 2f
            val radius = CornerRadius(h / 2f)
            drawRoundRect(trackColor, Offset(0f, top), Size(size.width, h), radius)
            val fraction = currentProgress().coerceIn(0f, 1f)
            if (fraction > 0f) {
                // Clipped rather than drawn short, so the fill's left end keeps its round cap
                // and a sliver of progress doesn't turn into a pill narrower than it is tall.
                clipRect(right = size.width * fraction) {
                    drawRoundRect(fillColor, Offset(0f, top), Size(size.width, h), radius)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------

/** Previous, play / pause and next as plain glyphs, with the spinner standing in for play while loading. */
@Composable
internal fun MeshTransport(
    isPlaying: Boolean,
    loading: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied before the play button's own gestures, so the host can attach a focus requester. */
    playModifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DepthIcon(
            PhosphorIcons.Regular.SkipBack,
            "Previous",
            Modifier.bounceTap(onClick = onPrevious).padding(8.dp),
            size = 28.dp,
        )
        Box(
            playModifier.bounceTap(onClick = onTogglePlay).padding(4.dp).size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                PlayerSpinner(Modifier.size(34.dp), Color.White, 3.dp)
            } else {
                Crossfade(targetState = isPlaying, animationSpec = tween(150), label = "mesh-play-pause") { playing ->
                    DepthIcon(
                        if (playing) PhosphorIcons.Regular.Pause else PhosphorIcons.Regular.Play,
                        if (playing) "Pause" else "Play",
                        size = 48.dp,
                    )
                }
            }
        }
        DepthIcon(
            PhosphorIcons.Regular.SkipForward,
            "Next",
            Modifier.bounceTap(onClick = onNext).padding(8.dp),
            size = 28.dp,
        )
    }
}