package com.gamut.feature.playerui

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.gamut.core.designsystem.acrylicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One pre-blurred bitmap for the lyrics backdrop (ported from Auris).
 *
 * This deliberately does not use Compose's full-screen `Modifier.blur`: that is a live
 * RenderEffect over every pixel on screen, on a layer that is also being scaled and faded
 * while the lyrics panel opens. Instead a 128px copy of the cover is decoded from Coil's
 * existing request for the same URL, box-blurred once on a worker thread, and cached by URL.
 * Drawing it afterwards is one bilinear image sample.
 */
@Composable
internal fun rememberFullArtworkBlurImage(
    imageUrl: String?,
    prepare: Boolean = true,
): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(imageUrl) { mutableStateOf(imageUrl?.let { fullBlurCache[it] }) }

    LaunchedEffect(imageUrl, prepare) {
        if (!prepare || imageUrl == null || image != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(FULL_BLUR_SOURCE_PX)
                .allowHardware(false)
                .build()
        val bitmap =
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmapOrNull()
                ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) { bitmap.boxBlurred(FULL_BLUR_PASSES).asImageBitmap() }
        fullBlurCache[imageUrl] = blurred
        image = blurred
    }
    return image
}

/**
 * The lyrics backdrop: one copy of the cover cropped across the whole surface, already blurred,
 * under a scrim that keeps white lyrics legible on a bright sleeve. Fades in once the bitmap is
 * ready; until then the surface is a flat dark floor.
 */
@Composable
internal fun FullArtworkBlurBackdrop(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val imageAlpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "fullBlurBackdropImage",
    )
    // The source sits outside the background so the haze glass layered over the player
    // samples the finished picture, floor and scrim included.
    Box(modifier.fillMaxSize().acrylicSource().background(FullBlurFallback)) {
        image?.let { bitmap ->
            val painter = remember(bitmap) { BitmapPainter(bitmap) }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush =
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.55f to Color.Black.copy(alpha = 0.48f),
                        1f to Color.Black.copy(alpha = 0.64f),
                    ),
            )
        }
    }
}

private val FullBlurFallback = Color(0xFF121212)

private const val FULL_BLUR_SOURCE_PX = 128
private const val FULL_BLUR_PASSES = 3

/** A handful of recent covers; each entry is only 64 KiB at 128x128 ARGB. Main thread only. */
private val fullBlurCache =
    object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 8
    }

/** Three small box passes approximate a broad Gaussian blur at thumbnail scale. */
private fun Bitmap.boxBlurred(passes: Int): Bitmap {
    val w = width
    val h = height
    if (w < 2 || h < 2) return this
    var source = IntArray(w * h).also { getPixels(it, 0, w, 0, 0, w, h) }
    var target = IntArray(source.size)
    val radius = (minOf(w, h) / 12).coerceAtLeast(2)
    repeat(passes) {
        boxBlurPass(source, target, w, h, radius, horizontal = true)
        boxBlurPass(target, source, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(source, w, h, Bitmap.Config.ARGB_8888)
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
            target[index] = blurArgb(red / window, green / window, blue / window)
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

private fun blurArgb(
    red: Int,
    green: Int,
    blue: Int,
): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
