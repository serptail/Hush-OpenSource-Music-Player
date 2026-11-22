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
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.dockArtHide
import com.gamut.core.designsystem.dockArtReport
import com.gamut.core.designsystem.focusRing
import com.gamut.core.designsystem.thumbnailBorder
import com.gamut.feature.playerui.components.ArtworkPlaceholder
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.PlayerSpinner
import com.gamut.feature.playerui.components.bounceTap
import com.gamut.feature.playerui.components.coverArtCandidates
import com.gamut.feature.playerui.components.youtubeThumbCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

internal val MeshSleeveMaxWidth = 420.dp

@Immutable
internal class ArtworkMesh internal constructor(
    internal val image: ImageBitmap,
)

@Composable
internal fun rememberArtworkMesh(url: String?): ArtworkMesh? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<ArtworkMesh?>(null) }
    var mesh by remember(url) { mutableStateOf(url?.let { meshCache[it] } ?: held.value) }
    var meshUrl by remember(url) { mutableStateOf(if (url != null && meshCache[url] != null) url else null) }
    LaunchedEffect(mesh) { held.value = mesh }

    LaunchedEffect(url) {
        if (url == null || meshUrl == url) return@LaunchedEffect
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MESH_RETRY_DELAY_MS)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(MESH_PX)
                    .allowHardware(false)
                    .build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmapOrNull()
            if (bitmap != null) {
                val found = withContext(Dispatchers.Default) { meshOf(bitmap, url.hashCode()) }
                if (found != null) {
                    meshCache[url] = found
                    mesh = found
                }
                meshUrl = url
                return@LaunchedEffect
            }
        }
    }
    return mesh
}

@Composable
internal fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    seam: () -> Float = { 0f },
) {
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(mesh, animate) {
        val next = mesh ?: return@LaunchedEffect
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

    Box(modifier.fillMaxSize().acrylicSource()) {
        Canvas(Modifier.fillMaxSize().background(FallbackBackdrop)) {
            val seamY = seam().coerceIn(0f, size.height)
            shown?.let { drawMesh(it, seamY, alpha = 1f) }
            incoming?.let { drawMesh(it, seamY, alpha = fade.value) }

            // Fade the colour wash toward the dark floor. Done as an overlay (not by lowering the
            // mesh alpha) so the track-change crossfade doesn't double up or pop.
            drawRect(FallbackBackdrop.copy(alpha = 1f - MESH_STRENGTH))

            drawRect(
                brush =
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.36f)),
                    ),
            )
        }
    }
}

private fun DrawScope.drawMesh(
    mesh: ArtworkMesh,
    seamY: Float,
    alpha: Float,
) {
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

@Stable
internal class MeshSeam {
    private var host: LayoutCoordinates? = null
    private var sleeve: LayoutCoordinates? = null

    var px by mutableFloatStateOf(0f)
        private set

    val onHost: Modifier =
        Modifier.onGloballyPositioned {
            host = it
            update()
        }

    val onSleeve: Modifier =
        Modifier.onGloballyPositioned {
            sleeve = it
            update()
        }

    private fun update() {
        val root = host?.takeIf { it.isAttached } ?: return
        val art = sleeve?.takeIf { it.isAttached } ?: return
        px = root.localPositionOf(art, Offset(0f, art.size.height.toFloat())).y
    }
}

private val FallbackBackdrop = Color(0xFF121212)

// 1f = original colour strength; lower = fainter blurred colour behind the player.
private const val MESH_STRENGTH = 0.8f

private val meshCache =
    object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkMesh>?) = size > MESH_CACHE_ENTRIES
    }

private const val MESH_CACHE_ENTRIES = 64

private const val MESH_PX = 120

private const val MESH_ATTEMPTS = 4
private const val MESH_RETRY_DELAY_MS = 1_500L

private const val MESH_FADE_MS = 900

private const val MESH_GRID = 6

private const val MESH_TEX = 32

private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3

private const val MESH_SAMPLE = 128

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f

private fun meshOf(
    source: Bitmap,
    seed: Int,
): ArtworkMesh? {
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

    val grid =
        IntArray(cells) { cell ->
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

private fun IntArray.rotatedBelowSeam(
    cols: Int,
    rows: Int,
    seed: Int,
): IntArray {
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

private fun IntArray.resampled(
    cols: Int,
    rows: Int,
    size: Int,
): IntArray {
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

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(
    from: Int,
    to: Int,
    t: Float,
): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to

    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(
    red: Int,
    green: Int,
    blue: Int,
): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private fun boxBlurPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
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

private val SleeveShape = RoundedCornerShape(8.dp)

private const val PausedSleeveScale = 0.86f

@Composable
internal fun MeshSleeve(
    url: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    dockEnabled: Boolean = true,
) {
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
            .dockArtHide(dockEnabled && !isThumb)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.then(
                if (shownUrl != null) {
                    Modifier.shadow(10.dp, SleeveShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                } else {
                    Modifier
                },
            ).clip(SleeveShape)
            .thumbnailBorder(SleeveShape)
            .dockArtReport(model = shownUrl, corner = 8.dp, enabled = dockEnabled && !isThumb),
    ) {
        if (shownUrl == null) {
            ArtworkPlaceholder()
        } else {
            if (isThumb) {
                AsyncImage(
                    model = shownUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                            }.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(28.dp) else Modifier),
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

private val ScrubberRestHeight = 5.dp
private val ScrubberActiveHeight = 11.dp
private val ScrubberHitHeight = 32.dp

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

                    else -> {
                        false
                    }
                }
            }.focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { at -> currentSeek((at.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) },
                )
            }.pointerInput(Unit) {
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
            }.semantics { contentDescription = "Seek" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = thickness.toPx()
            val top = (size.height - h) / 2f
            val radius = CornerRadius(h / 2f)
            drawRoundRect(trackColor, Offset(0f, top), Size(size.width, h), radius)
            val fraction = currentProgress().coerceIn(0f, 1f)
            if (fraction > 0f) {
                clipRect(right = size.width * fraction) {
                    drawRoundRect(fillColor, Offset(0f, top), Size(size.width, h), radius)
                }
            }
        }
    }
}

@Composable
internal fun MeshTransport(
    isPlaying: Boolean,
    loading: Boolean,
    /** Null leaves the previous button out (the boxed video shows only play / pause). */
    onPrevious: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    /** Null leaves the next button out. */
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playModifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPrevious != null) {
            DepthIcon(
                TablerIcons.TrackPrev,
                "Previous",
                Modifier.bounceTap(onClick = onPrevious).padding(8.dp),
                size = 22.dp,
            )
        }
        Box(
            playModifier.bounceTap(onClick = onTogglePlay).padding(4.dp).size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                PlayerSpinner(Modifier.size(34.dp), Color.White, 3.dp)
            } else {
                Crossfade(targetState = isPlaying, animationSpec = tween(150), label = "mesh-play-pause") { playing ->
                    DepthIcon(
                        if (playing) TablerIcons.Pause else TablerIcons.Play,
                        if (playing) "Pause" else "Play",
                        size = 40.dp,
                    )
                }
            }
        }
        if (onNext != null) {
            DepthIcon(
                TablerIcons.TrackNext,
                "Next",
                Modifier.bounceTap(onClick = onNext).padding(8.dp),
                size = 22.dp,
            )
        }
    }
}