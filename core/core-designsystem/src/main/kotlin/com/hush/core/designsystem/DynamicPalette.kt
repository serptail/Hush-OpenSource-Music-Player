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

/** The two soft colors the player's background gradient eases between. */
data class HushAccent(val top: Color, val bottom: Color)

private val CalmAccent = HushAccent(HushAccentA, HushAccentB)

/**
 * Loads [artworkUrl] (if any), runs it through the Palette API, and returns
 * a pastel-lightened [HushAccent] that *animates* between values (900ms) as
 * artwork changes — so switching tracks eases the background instead of
 * snapping. Falls back to the calm default on null art or extraction
 * failure; never blocks or crashes playback.
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
                extractSoftAccent(bitmap)
            }.getOrNull()
        }
        target = extracted ?: CalmAccent
    }

    val top by animateColorAsState(target.top, animationSpec = tween(900), label = "accent-top")
    val bottom by animateColorAsState(target.bottom, animationSpec = tween(900), label = "accent-bottom")
    return HushAccent(top, bottom)
}

private fun extractSoftAccent(bitmap: Bitmap): HushAccent? {
    val palette = Palette.from(bitmap).generate()
    val a = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return null
    val b = palette.lightMutedSwatch ?: palette.mutedSwatch ?: a

    // Lift both toward pastel: high lightness, moderated saturation — this is
    // what makes an arbitrary cover photo still look like a soft Samsung
    // Music-style wash instead of a loud, high-contrast gradient.
    return HushAccent(top = Color(a.rgb).softened(), bottom = Color(b.rgb).softened(lighter = true))
}

private fun Color.softened(lighter: Boolean = false): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.55f)
    hsv[2] = if (lighter) hsv[2].coerceIn(0.85f, 0.97f) else hsv[2].coerceIn(0.70f, 0.88f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}