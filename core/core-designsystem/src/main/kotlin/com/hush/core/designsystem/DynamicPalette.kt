package com.hush.core.designsystem

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The three colors the player's gradient blends between — real colors from the art, kept clean. */
data class HushAccent(val top: Color, val mid: Color, val bottom: Color)

private val CalmAccent = HushAccent(HushAccentA, HushAccentC, HushAccentB)

/**
 * Loads [artworkUrl] (if any), runs it through the Palette API, and returns
 * a [HushAccent] trio that *animates* between values (900ms) as artwork
 * changes. Falls back to the calm default on null art or extraction
 * failure; never blocks or crashes playback.
 *
 * "Clean, not jank" constraint: rather than using Palette's swatches raw
 * (which can be muddy, near-black, or near-white depending on the source
 * photo), every extracted color is normalized into a fixed lightness/
 * saturation band before use — this is what keeps an arbitrary album cover
 * from ever producing a background that looks broken or illegible.
 */
@Composable
fun rememberDynamicAccent(artworkUrl: String?): HushAccent {
    val context = LocalContext.current
    var target by remember { mutableStateOf(CalmAccent) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            target = CalmAccent
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false) // Palette needs a software bitmap
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmapOrNull() ?: return@runCatching null
                extractAccentTrio(bitmap)
            }.getOrNull()
        }
        target = extracted ?: CalmAccent
    }

    val top by animateColorAsState(target.top, animationSpec = tween(900), label = "accent-top")
    val mid by animateColorAsState(target.mid, animationSpec = tween(900), label = "accent-mid")
    val bottom by animateColorAsState(target.bottom, animationSpec = tween(900), label = "accent-bottom")
    return HushAccent(top, mid, bottom)
}

private fun extractAccentTrio(bitmap: Bitmap): HushAccent? {
    val palette = Palette.from(bitmap).generate()

    // Pull three genuinely different swatches so the gradient has real range
    // instead of three near-identical tones (which reads as a flat wash).
    val a = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return null
    val b = palette.mutedSwatch ?: palette.darkVibrantSwatch ?: a
    val c = palette.lightVibrantSwatch ?: palette.lightMutedSwatch ?: a

    return HushAccent(
        top = Color(a.rgb).normalized(),
        mid = Color(b.rgb).normalized(shiftHue = true),
        bottom = Color(c.rgb).normalized(lighter = true),
    )
}

/**
 * Clamps a raw Palette color into a band that always reads clean against
 * white text/icons: moderate-to-high saturation (so it doesn't look grey or
 * muddy) but never so dark it swallows white icon contrast, and never so
 * light it looks washed out. [shiftHue] nudges the hue slightly so the
 * middle gradient stop reads as a distinct color rather than a near-repeat
 * of the top stop when Palette returns very similar swatches.
 */
private fun Color.normalized(lighter: Boolean = false, shiftHue: Boolean = false): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    if (shiftHue) hsv[0] = (hsv[0] + 18f) % 360f
    hsv[1] = hsv[1].coerceIn(0.45f, 0.75f)
    hsv[2] = if (lighter) hsv[2].coerceIn(0.72f, 0.88f) else hsv[2].coerceIn(0.48f, 0.68f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}