package com.gamut.feature.playerui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.gamut.core.designsystem.acrylicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The styled player's own backdrop: a progressive blur that grows out of the cover art.
 *
 * The cover stays sharp at the top, then dissolves through three blur levels (soft, medium,
 * heavy) laid over each other with long eased masks, so the picture appears to melt rather than
 * hit a wall of blur. Below the artwork the heavy blur's edge is smeared downward and settles
 * into a dark wash of the cover's own colour. Every level is a tiny pre-blurred bitmap, so
 * nothing here runs a live full-screen blur.
 */
@Immutable
internal class ArtworkBlurSet(
    val soft: ImageBitmap,
    val medium: ImageBitmap,
    val heavy: ImageBitmap,
    val tint: Color,
    val luma: Float,
)

@Composable
internal fun rememberArtworkBlurSet(
    url: String?,
    prepare: Boolean = true,
): ArtworkBlurSet? {
    val context = LocalContext.current
    val held = remember { mutableStateOf<ArtworkBlurSet?>(null) }
    var set by remember(url) { mutableStateOf(url?.let { blurSetCache[it] } ?: held.value) }
    var loadedUrl by remember(url) { mutableStateOf(if (url != null && blurSetCache[url] != null) url else null) }
    LaunchedEffect(set) { held.value = set }

    LaunchedEffect(url, prepare) {
        if (!prepare || url == null || loadedUrl == url) return@LaunchedEffect
        repeat(BLUR_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(BLUR_RETRY_DELAY_MS)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(BLUR_SOURCE_PX)
                    .allowHardware(false)
                    .build()
            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmapOrNull()
            if (bitmap != null) {
                val built = withContext(Dispatchers.Default) { buildBlurSet(bitmap) }
                blurSetCache[url] = built
                set = built
                loadedUrl = url
                return@LaunchedEffect
            }
        }
    }
    return set
}

@Composable
internal fun StyledBackdrop(
    blur: ArtworkBlurSet?,
    coverGeometry: Boolean,
    artPx: Float,
    fadePx: Float,
    scroll: () -> Float,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(blur) }
    var incoming by remember { mutableStateOf<ArtworkBlurSet?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(blur) {
        val next = blur ?: return@LaunchedEffect
        incoming?.let { shown = it }
        incoming = null
        if (next === shown) return@LaunchedEffect
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(BACKDROP_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    // The source sits outside the floor colour so the haze glass samples the finished picture.
    Box(modifier.fillMaxSize().acrylicSource().background(BackdropFloor)) {
        shown?.let { BackdropLayers(it, coverGeometry, artPx, fadePx, scroll) }
        incoming?.let {
            BackdropLayers(
                set = it,
                cover = coverGeometry,
                artPx = artPx,
                fadePx = fadePx,
                scroll = scroll,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
    }
}

@Composable
private fun BackdropLayers(
    set: ArtworkBlurSet,
    cover: Boolean,
    artPx: Float,
    fadePx: Float,
    scroll: () -> Float,
    modifier: Modifier = Modifier,
) {
    // Where each blur level is fully present and where it has fully dissolved, measured down the
    // fade zone (0 = where the sharp cover starts to dissolve, 1 = where it is gone).
    val zoneStart = if (cover) artPx - fadePx else 0f
    val zoneLength = if (cover) fadePx else artPx
    val soft = if (cover) 0.35f to 0.85f else 0f to 0.5f
    val medium = if (cover) 0.6f to 1f else 0.15f to 0.8f

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawBase(set, cover, artPx, scroll()) }
        BlurBand(
            image = set.medium,
            heightPx = artPx,
            maskStartPx = zoneStart + zoneLength * medium.first,
            maskEndPx = zoneStart + zoneLength * medium.second,
            scroll = if (cover) scroll else NoScroll,
        )
        BlurBand(
            image = set.soft,
            heightPx = artPx,
            maskStartPx = zoneStart + zoneLength * soft.first,
            maskEndPx = zoneStart + zoneLength * soft.second,
            scroll = if (cover) scroll else NoScroll,
        )
        Canvas(Modifier.fillMaxSize()) { drawScrim(set) }
    }
}

/** One blur level in the artwork's own geometry, eased out between [maskStartPx] and [maskEndPx]. */
@Composable
private fun BlurBand(
    image: ImageBitmap,
    heightPx: Float,
    maskStartPx: Float,
    maskEndPx: Float,
    scroll: () -> Float,
) {
    val heightDp = with(LocalDensity.current) { heightPx.toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .graphicsLayer {
                translationY = -scroll()
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colorStops = MaskCurve,
                            startY = maskStartPx,
                            endY = maskEndPx.coerceAtLeast(maskStartPx + 1f),
                        ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun DrawScope.drawBase(
    set: ArtworkBlurSet,
    cover: Boolean,
    artPx: Float,
    scrollPx: Float,
) {
    val floor = lerp(set.tint, Color.Black, 0.55f)
    drawRect(floor)
    if (!cover) {
        drawCover(set.heavy, top = 0f, coverHeight = size.height, smearLength = 0f)
        return
    }
    translate(top = -scrollPx) {
        val smear = size.height * 1.2f
        drawCover(set.heavy, top = 0f, coverHeight = artPx, smearLength = smear)
        // Below the artwork the smeared edge slowly settles into the dark tint of the cover.
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(floor.copy(alpha = 0f), floor.copy(alpha = 0.9f)),
                    startY = artPx,
                    endY = artPx + smear * 0.7f,
                ),
            topLeft = Offset(0f, artPx),
            size = Size(size.width, smear),
        )
    }
}

private fun DrawScope.drawScrim(set: ArtworkBlurSet) {
    // Bright covers get a deeper scrim so the white controls and lyrics keep their edge.
    val bottom = 0.46f + 0.2f * set.luma
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.06f),
                0.45f to Color.Black.copy(alpha = 0.16f),
                0.72f to Color.Black.copy(alpha = bottom * 0.8f),
                1f to Color.Black.copy(alpha = bottom),
            ),
    )
}

/**
 * Draws [image] cropped to fill a full-width strip, exactly as `ContentScale.Crop` would, then
 * stretches that strip's last row [smearLength] further down so the colour keeps flowing.
 */
private fun DrawScope.drawCover(
    image: ImageBitmap,
    top: Float,
    coverHeight: Float,
    smearLength: Float,
) {
    if (coverHeight < 1f) return
    val width = size.width
    val scale = maxOf(width / image.width, coverHeight / image.height)
    val srcWidth = (width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (coverHeight / scale).roundToInt().coerceIn(1, image.height)
    val srcX = ((image.width - srcWidth) / 2f).roundToInt().coerceIn(0, image.width - srcWidth)
    val srcY = ((image.height - srcHeight) / 2f).roundToInt().coerceIn(0, image.height - srcHeight)

    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset(0, top.roundToInt()),
        dstSize = IntSize(width.roundToInt(), coverHeight.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
    if (smearLength >= 1f) {
        drawImage(
            image = image,
            srcOffset = IntOffset(srcX, srcY + srcHeight - 1),
            srcSize = IntSize(srcWidth, 1),
            dstOffset = IntOffset(0, (top + coverHeight).roundToInt()),
            dstSize = IntSize(width.roundToInt(), smearLength.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun buildBlurSet(source: Bitmap): ArtworkBlurSet {
    val longest = maxOf(source.width, source.height)
    val soft = source.boxBlurred(BLUR_PASSES, longest / SOFT_DIVISOR)
    val medium = source.boxBlurred(BLUR_PASSES, longest / MEDIUM_DIVISOR)
    val heavy = source.boxBlurred(BLUR_PASSES, longest / HEAVY_DIVISOR)
    val tint = lowerAverage(heavy)
    return ArtworkBlurSet(
        soft = soft.asImageBitmap(),
        medium = medium.asImageBitmap(),
        heavy = heavy.asImageBitmap(),
        tint = tint,
        luma = ColorUtils.calculateLuminance(tint.toArgb()).toFloat().coerceIn(0f, 1f),
    )
}

/** Average colour of the lower part of the blurred cover, where the backdrop settles. */
private fun lowerAverage(bitmap: Bitmap): Color {
    val w = bitmap.width
    val h = bitmap.height
    val y0 = (h * 0.55f).toInt().coerceIn(0, h - 1)
    val rows = h - y0
    val pixels = IntArray(w * rows)
    bitmap.getPixels(pixels, 0, w, 0, y0, w, rows)
    var red = 0L
    var green = 0L
    var blue = 0L
    for (p in pixels) {
        red += p shr 16 and 0xFF
        green += p shr 8 and 0xFF
        blue += p and 0xFF
    }
    val n = pixels.size.coerceAtLeast(1)
    return Color((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
}

private fun easeStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private const val MASK_STOPS = 16

/** Opaque to transparent along an eased curve; used as a DstIn mask. */
private val MaskCurve =
    Array(MASK_STOPS + 1) { i ->
        val t = i / MASK_STOPS.toFloat()
        t to Color.Black.copy(alpha = 1f - easeStep(t))
    }

private val NoScroll: () -> Float = { 0f }

private val BackdropFloor = Color(0xFF121212)

private val blurSetCache =
    object : LinkedHashMap<String, ArtworkBlurSet>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkBlurSet>?) = size > 6
    }

private const val BLUR_SOURCE_PX = 160
private const val BLUR_PASSES = 3

// Box radius = longest side / divisor, so the blur reads the same whatever the source's aspect.
private const val SOFT_DIVISOR = 40
private const val MEDIUM_DIVISOR = 16
private const val HEAVY_DIVISOR = 6

private const val BLUR_ATTEMPTS = 3
private const val BLUR_RETRY_DELAY_MS = 1_500L
private const val BACKDROP_FADE_MS = 700
