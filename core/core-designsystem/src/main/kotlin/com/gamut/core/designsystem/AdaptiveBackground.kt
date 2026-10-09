package com.gamut.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.get
import androidx.core.graphics.scale
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// ======================================================================================
// Cover palette
// ======================================================================================

/**
 * A chameleon palette pulled from a cover. [base] is the cover's majority color and becomes the screen
 * background (it can be near-white or near-black, so the whole app flips between light and dark);
 * [onBase] is the readable text/icon color on top of it. [primary], [secondary] and [tertiary] are the
 * cover's accent colors that flow through the background as a gradient; [accent] is the UI tint
 * (cursor, focus ring, spinners).
 */
data class CoverPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val base: Color = Color(0xFF111111),
    val onBase: Color = Color.White,
    val isLight: Boolean = false,
    /** False = flat [base] fill: no gradient, no animation (used when nothing is playing). */
    val flows: Boolean = true,
) {
    companion object {
        /**
         * Used when nothing is playing (or the cover is unreadable): a plain, solid dark fill. No gradient,
         * no animation, no per-frame work.
         */
        val Neutral = CoverPalette(
            primary = Color(0xFF2B2D3A),
            secondary = Color(0xFF2B2D3A),
            tertiary = Color(0xFF2B2D3A),
            accent = Color.White,
            base = Color(0xFF0B0B0D),
            flows = false,
        )

        /** Light twin of [Neutral]: used when the app is forced to light mode and nothing is playing. */
        val NeutralLight = CoverPalette(
            primary = Color(0xFFE4E4EA),
            secondary = Color(0xFFE4E4EA),
            tertiary = Color(0xFFE4E4EA),
            accent = Color(0xFF141414),
            base = Color(0xFFF4F4F6),
            onBase = Color(0xFF141414),
            isLight = true,
            flows = false,
        )
    }
}

private const val ThemeTransitionMs = 450

/** The palette currently driving the UI; provided by [CoverTheme]. */
val LocalCoverPalette = staticCompositionLocalOf { CoverPalette.Neutral }

/**
 * Wrap screen content in this so text, icons and Material components follow the cover: dark text on a
 * light cover, white text on a dark one, accent-tinted controls. Anything that hardcodes Color.White
 * needs to switch to MaterialTheme.colorScheme.onBackground (or LocalContentColor.current).
 */
@Composable
fun CoverTheme(palette: CoverPalette, content: @Composable () -> Unit) {
    // Every frame of these two animations recomposes everything that reads ink() / the accent, so they are kept
    // short (the flowing background itself still takes 1.4 s to cross-fade).
    val onBase by animateColorAsState(palette.onBase, tween(ThemeTransitionMs), label = "cover-onbase")
    val accent by animateColorAsState(palette.accent, tween(ThemeTransitionMs), label = "cover-accent")
    val parent = MaterialTheme.colorScheme
    // Sheets, dialogs and menus take their container from the surface roles, so those follow the palette too:
    // a dark app never gets a white bottom sheet (and vice versa).
    val surfaceBase by animateColorAsState(palette.base, tween(ThemeTransitionMs), label = "cover-surface")
    val scheme = remember(parent, onBase, accent, surfaceBase) {
        val lift = if (palette.isLight) Color.Black else Color.White
        fun tone(a: Float) = androidx.compose.ui.graphics.lerp(surfaceBase, lift, a)
        parent.copy(
            background = Color.Transparent,
            onBackground = onBase,
            surface = surfaceBase,
            onSurface = onBase,
            surfaceVariant = tone(0.06f),
            surfaceContainerLowest = surfaceBase,
            surfaceContainerLow = tone(0.02f),
            surfaceContainer = tone(0.04f),
            surfaceContainerHigh = tone(0.06f),
            surfaceContainerHighest = tone(0.08f),
            onSurfaceVariant = onBase.copy(alpha = 0.65f),
            outline = onBase.copy(alpha = 0.30f),
            outlineVariant = onBase.copy(alpha = 0.15f),
            primary = accent,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalContentColor provides onBase,
            LocalCoverPalette provides palette,
            content = content,
        )
    }
}

private val paletteCache = object : LinkedHashMap<String, CoverPalette>(24, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverPalette>?): Boolean = size > 24
}

/**
 * Palette for the cover at [url]. Extracted once per URL, off the main thread, and cached.
 * [forceLight]: true = the palette is forced to a light base, false = to a dark base, null = the cover decides.
 */
@Composable
fun rememberCoverPalette(url: String?, forceLight: Boolean? = null): CoverPalette {
    val context = LocalContext.current
    val neutral = if (forceLight == true) CoverPalette.NeutralLight else CoverPalette.Neutral
    var palette by remember { mutableStateOf(url?.let { paletteCache["$it|$forceLight"] } ?: neutral) }
    LaunchedEffect(url, forceLight) {
        if (url.isNullOrBlank()) {
            palette = neutral
            return@LaunchedEffect
        }
        val key = "$url|$forceLight"
        palette = paletteCache[key]
            ?: withContext(Dispatchers.IO) { loadPalette(context, url, forceLight) }?.also { paletteCache[key] = it }
                    ?: neutral
    }
    return palette
}

private suspend fun loadPalette(context: Context, url: String, forceLight: Boolean?): CoverPalette? = runCatching {
    val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
    paletteFromBitmap(bitmap, forceLight)
}.getOrNull()

/**
 * Cover palette extraction, done in OKLab (a perceptual color space, so "close" means "looks close").
 *
 *  1. The cover is quantized into a handful of real colors with k-means; near-identical ones are merged.
 *  2. The biggest cluster is the majority color and decides the base: near-white if it is light, otherwise a
 *     deep, tinted near-black. A dark base is tinted with the cover's main accent hue, so an orange cover
 *     gives warm brown instead of black.
 *  3. Accents are chosen from the remaining clusters by size x chroma, so the cover's dominant color can
 *     itself be an accent (it used to be excluded) and a tiny saturated detail cannot outvote it. Greys are
 *     never accents (a partly transparent black over a light base just looks like dirt), and clusters far
 *     less colorful than the best one are skipped so no dusty midtones sneak in.
 *  4. Accents keep their own hue. Only lightness is nudged into a range that reads on the base, and chroma is
 *     boosted slightly. Brown stays brown and cream stays cream. Missing accents are lighter/darker shades of
 *     the first one, never a shifted hue.
 */
private fun paletteFromBitmap(bitmap: Bitmap, forceLight: Boolean? = null): CoverPalette {
    val size = 48
    val sample = bitmap.scale(size, size, true)
    val pixels = IntArray(size * size) { i -> sample[i % size, i / size] }
    return paletteFromPixels(pixels, forceLight)
}

private const val ClusterCount = 8
private const val MergeDistance = 0.06
private const val NeutralChroma = 0.035
private const val LightBaseThreshold = 0.72
private const val MaxAccents = 3

/** A group of similar pixels: [count] pixels whose mean color is ([l], [a], [b]) in OKLab. */
private class Cluster(val count: Int, val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)
    val hue: Double get() = atan2(b, a) // radians
}

/** An OKLCH color (hue in radians). */
private class Lch(val l: Double, val c: Double, val h: Double) {
    fun toColor(): Color = oklch(l, c, h)
    fun shaded(dL: Double, chromaMul: Double, lo: Double, hi: Double) = Lch((l + dL).coerceIn(lo, hi), c * chromaMul, h)
}

internal fun paletteFromPixels(pixels: IntArray, forceLight: Boolean? = null): CoverPalette {
    val n = pixels.size
    val lab = Array(n) { argbToOklab(pixels[it]) }
    val clusters = quantize(lab, ClusterCount)
    val baseCluster = clusters[0] // largest
    // The user's Light / Dark choice wins over the cover; Auto (null) lets the cover decide.
    val isLight = forceLight ?: (baseCluster.l > LightBaseThreshold)
    val onBase = if (isLight) Color(0xFF141414) else Color.White

    // Accents: strict about size first (ignore specks), relaxed only if that leaves nothing.
    val accents = pickAccents(clusters, n, minFraction = 0.015).ifEmpty { pickAccents(clusters, n, minFraction = 0.0) }

    val base = if (isLight) {
        oklch(0.965, minOf(baseCluster.chroma * 0.5, 0.03), baseCluster.hue)
    } else {
        val (tintHue, tintChroma) = when {
            baseCluster.chroma >= 0.03 -> baseCluster.hue to minOf(baseCluster.chroma * 0.7, 0.05)
            accents.isNotEmpty() -> accents[0].hue to 0.04
            else -> baseCluster.hue to baseCluster.chroma
        }
        oklch((0.12 + baseCluster.l * 0.20).coerceIn(0.17, 0.23), tintChroma, tintHue)
    }

    // Same hue and character as the cover; lightness only nudged so it reads on the base.
    val lMin = if (isLight) 0.58 else 0.55
    val glow = accents.map { Lch(it.l.coerceIn(lMin, 0.80), minOf(it.chroma * 1.15, 0.30), it.hue) }
    val first = glow.firstOrNull() ?: Lch(if (isLight) 0.70 else 0.42, minOf(baseCluster.chroma, 0.03), baseCluster.hue)
    val second = glow.getOrNull(1)
        ?: if (isLight) first.shaded(+0.10, 0.7, 0.50, 0.88) else first.shaded(-0.10, 0.9, 0.40, 0.85)
    val third = glow.getOrNull(2)
        ?: if (isLight) first.shaded(-0.10, 0.9, 0.50, 0.88) else first.shaded(+0.08, 0.7, 0.40, 0.88)

    val accent = if (accents.isNotEmpty()) {
        oklch(if (isLight) 0.52 else 0.82, first.c.coerceIn(0.06, 0.16), first.h)
    } else onBase

    return CoverPalette(
        primary = first.toColor(),
        secondary = second.toColor(),
        tertiary = third.toColor(),
        accent = accent,
        base = base,
        onBase = onBase,
        isLight = isLight,
    )
}

private fun labDist(x: Cluster, y: Cluster): Double {
    val dl = x.l - y.l; val da = x.a - y.a; val db = x.b - y.b
    return Math.sqrt(dl * dl + da * da + db * db)
}

/**
 * Up to [MaxAccents] colorful clusters, best first. Score = size^0.5 x (chroma + 0.04), damped when close to
 * one already chosen (more so when it has the same hue) so the picks differ. Anything much duller than the best pick is dropped.
 */
private fun pickAccents(clusters: List<Cluster>, total: Int, minFraction: Double): List<Cluster> {
    var candidates = clusters.filter { it.chroma >= NeutralChroma && it.count.toDouble() / total >= minFraction }
    val chosen = mutableListOf<Cluster>()
    while (candidates.isNotEmpty() && chosen.size < MaxAccents) {
        if (chosen.isNotEmpty()) {
            val floor = 0.5 * chosen[0].chroma
            candidates = candidates.filter { it.chroma >= floor }
            if (candidates.isEmpty()) break
        }
        var best: Cluster? = null
        var bestScore = -1.0
        for (c in candidates) {
            var score = Math.pow(c.count.toDouble() / total, 0.5) * (c.chroma + 0.04)
            for (other in chosen) {
                val f = minOf(1.0, labDist(c, other) / 0.12)
                score *= f * f
                // A second shade of the same hue adds nothing to the gradient; a different hue (gold next to a
                // hot pink cover, say) does. Same-hue candidates are pushed down hard, distinct hues are not.
                if (c.chroma >= NeutralChroma && other.chroma >= NeutralChroma) {
                    var dh = Math.abs(c.hue - other.hue)
                    if (dh > Math.PI) dh = 2 * Math.PI - dh
                    val t = minOf(1.0, dh / 1.0)
                    score *= 0.1 + 0.9 * t * t
                }
            }
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (best == null || bestScore <= 0.0) break
        chosen += best
        candidates = candidates - best
    }
    return chosen
}

/** Deterministic k-means in OKLab (fixed seed: the same cover always gives the same palette), largest cluster first. */
private fun quantize(lab: Array<DoubleArray>, k: Int): List<Cluster> {
    val n = lab.size
    var seed = 42L
    fun rnd(): Double {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return seed / 2147483648.0
    }
    fun dist2(p: DoubleArray, q: DoubleArray): Double {
        val a = p[0] - q[0]; val b = p[1] - q[1]; val c = p[2] - q[2]
        return a * a + b * b + c * c
    }

    // k-means++ seeding.
    val centers = ArrayList<DoubleArray>()
    centers += lab[(rnd() * n).toInt().coerceIn(0, n - 1)].copyOf()
    val d2 = DoubleArray(n) { Double.MAX_VALUE }
    while (centers.size < k) {
        val last = centers.last()
        var sum = 0.0
        for (i in 0 until n) { d2[i] = minOf(d2[i], dist2(lab[i], last)); sum += d2[i] }
        if (sum <= 1e-9) break
        val target = rnd() * sum
        var acc = 0.0
        var pick = n - 1
        for (i in 0 until n) { acc += d2[i]; if (acc >= target) { pick = i; break } }
        centers += lab[pick].copyOf()
    }

    val assign = IntArray(n)
    fun assignAll() {
        for (i in 0 until n) {
            var bestC = 0; var bestD = Double.MAX_VALUE
            for (c in centers.indices) {
                val d = dist2(lab[i], centers[c])
                if (d < bestD) { bestD = d; bestC = c }
            }
            assign[i] = bestC
        }
    }
    repeat(12) {
        assignAll()
        val sums = Array(centers.size) { DoubleArray(4) }
        for (i in 0 until n) {
            val s = sums[assign[i]]
            s[0] += lab[i][0]; s[1] += lab[i][1]; s[2] += lab[i][2]; s[3] += 1.0
        }
        for (c in centers.indices) {
            val s = sums[c]
            if (s[3] > 0.0) { centers[c][0] = s[0] / s[3]; centers[c][1] = s[1] / s[3]; centers[c][2] = s[2] / s[3] }
        }
    }
    assignAll()
    val counts = IntArray(centers.size)
    for (i in 0 until n) counts[assign[i]]++
    val list = ArrayList<Cluster>()
    for (c in centers.indices) if (counts[c] > 0) list += Cluster(counts[c], centers[c][0], centers[c][1], centers[c][2])

    // Merge clusters that are visually the same color, so one color split in two can't win or lose by accident.
    var merged = true
    while (merged) {
        merged = false
        list.sortByDescending { it.count }
        search@ for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (labDist(list[i], list[j]) < MergeDistance) {
                    val x = list[i]; val y = list[j]
                    val t = x.count + y.count
                    list[i] = Cluster(
                        t,
                        (x.count * x.l + y.count * y.l) / t,
                        (x.count * x.a + y.count * y.a) / t,
                        (x.count * x.b + y.count * y.b) / t,
                    )
                    list.removeAt(j)
                    merged = true
                    break@search
                }
            }
        }
    }
    list.sortByDescending { it.count }
    return list
}

// ---------- OKLab conversion (Björn Ottosson's reference matrices) ----------

private fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

private fun linearToSrgb(c: Double): Double {
    val x = c.coerceIn(0.0, 1.0)
    return if (x <= 0.0031308) 12.92 * x else 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055
}

private fun argbToOklab(argb: Int): DoubleArray {
    val r = srgbToLinear(android.graphics.Color.red(argb) / 255.0)
    val g = srgbToLinear(android.graphics.Color.green(argb) / 255.0)
    val b = srgbToLinear(android.graphics.Color.blue(argb) / 255.0)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

/** Linear (not gamma-encoded) RGB for an OKLab color; components can fall outside 0..1 when out of gamut. */
private fun oklabToLinear(lightness: Double, a: Double, b: Double): DoubleArray {
    val l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3.0)
    val m = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3.0)
    val s = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3.0)
    return doubleArrayOf(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )
}

private fun inGamut(lightness: Double, chroma: Double, hue: Double): Boolean {
    val rgb = oklabToLinear(lightness, chroma * cos(hue), chroma * sin(hue))
    return rgb.all { it >= -1e-4 && it <= 1.0 + 1e-4 }
}

/** Largest chroma that still fits in sRGB at this lightness and hue. */
private fun maxChroma(lightness: Double, hue: Double): Double {
    var lo = 0.0; var hi = 0.4
    repeat(14) {
        val mid = (lo + hi) / 2
        if (inGamut(lightness, mid, hue)) lo = mid else hi = mid
    }
    return lo
}

/** sRGB [Color] from OKLCH; chroma is reduced (hue and lightness kept) if it doesn't fit the screen's gamut. */
private fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    val c = minOf(chroma, maxChroma(lightness, hue))
    val rgb = oklabToLinear(lightness, c * cos(hue), c * sin(hue))
    return Color(linearToSrgb(rgb[0]).toFloat(), linearToSrgb(rgb[1]).toFloat(), linearToSrgb(rgb[2]).toFloat())
}

// ======================================================================================
// Flowing background
// ======================================================================================

/** How strong the color blobs are over the base. Lower = calmer, higher = more vivid. */
private const val Intensity = 0.85f

/** Clock units per real second. With the blob frequencies below, one wander cycle takes roughly 15 to 30 s. */
private const val FlowRate = 0.9f

/** How strong the accent gradient is on a light base (kept faint so it reads as a tint, not a stain). */
private const val LightStrength = 0.5f

/** Speed multiplier while paused: the flow doesn't stop, it just breathes. */
private const val PausedSpeed = 0.3f

/** Minimum seconds between flow updates (about 30 per second). */
private const val FlowFrameInterval = 0.030f

/** Size of the fallback (Android 12 and below) glows relative to their natural radius. Lower = smaller. */
private const val BlobScale = 0.33f

private class Blob(
    val cx: Float, val cy: Float,     // resting center, as a fraction of the screen
    val ax: Float, val ay: Float,     // wander distance, as a fraction of the screen
    val fx: Float, val fy: Float,     // wander frequency (different on each axis so paths never repeat)
    val phase: Float,
    val radius: Float,                // relative to the screen's longer side
    val stretch: Float,               // > 1 elongates the blob into a ribbon
    val spin: Float,                  // rotation speed
    val colorIndex: Int,
)

private val Blobs = listOf(
    Blob(0.20f, 0.10f, 0.30f, 0.14f, 0.71f, 0.53f, 0.0f, 0.62f, 1.7f, 0.31f, 0),
    Blob(0.85f, 0.32f, 0.26f, 0.18f, 0.47f, 0.83f, 2.1f, 0.55f, 1.5f, -0.27f, 1),
    Blob(0.35f, 0.62f, 0.34f, 0.16f, 0.59f, 0.41f, 4.0f, 0.60f, 1.8f, 0.22f, 2),
    Blob(0.80f, 0.90f, 0.28f, 0.12f, 0.37f, 0.67f, 5.3f, 0.52f, 1.4f, -0.35f, 0),
)

/**
 * Chameleon background. The majority cover color fills the screen and the cover's accent colors flow
 * through it. On Android 13+ it's an AGSL shader: domain-warped soft color fields with a barely-there
 * dither (no banding, no visible grain, no visible circles). Older versions use the layered radial-glow version below. Everything
 * cross-fades when the track changes, including flipping between light and dark. The flow roams the whole screen; it stays within it.
 * Motion is read in the draw phase only, so
 * nothing recomposes per frame.
 *
 * With [CoverPalette.Neutral] (nothing playing) it is just a flat fill of [CoverPalette.base]: no shader,
 * no clock, no animation. When a track starts, the flow fades in over the solid fill.
 */
@Composable
fun FlowingBackground(
    palette: CoverPalette,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /**
     * False while something opaque covers the whole screen (the player): nothing is drawn and the clock is
     * paused. Everything else is kept, so the flow resumes exactly where it left off, not from zero.
     */
    visible: Boolean = true,
    settings: AppearanceSettings = LocalAppearance.current,
) {
    val base by animateColorAsState(palette.base, tween(1400), label = "flow-base")
    val primary by animateColorAsState(palette.primary, tween(1400), label = "flow-primary")
    val secondary by animateColorAsState(palette.secondary, tween(1400), label = "flow-secondary")
    val tertiary by animateColorAsState(palette.tertiary, tween(1400), label = "flow-tertiary")
    val strength by animateFloatAsState(
        (if (palette.isLight) LightStrength else 1f) * settings.flowIntensity.flowStrength,
        tween(1400), label = "flow-strength",
    )
    // Mercury and Metaballs have defined shapes, so on a light base they keep the full cover color instead of the
    // faint tint the soft Flow gradient needs.
    val fullStrength by animateFloatAsState(settings.flowIntensity.flowStrength, tween(1400), label = "flow-strength-full")
    // 0 = solid fill, 1 = full flow. Fades, so going from "nothing playing" to a track isn't a cut.
    val flow by animateFloatAsState(if (palette.flows) 1f else 0f, tween(1400), label = "flow-amount")
    val clock = rememberFlowClock(
        isPlaying = isPlaying,
        running = visible && (palette.flows || flow > 0f),
        animated = settings.flowAnimated,
        speedScale = settings.flowSpeed.flowSpeed,
        freezeWhenPaused = settings.freezeWhenPaused,
    )

    if (!visible) {
        // Covered by an opaque screen: draw nothing.
    } else if (flow <= 0f) {
        Box(modifier.fillMaxSize().background(base))
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (settings.flowStyle) {
            FlowStyle.Mercury ->
                MercuryBackground(base, primary, secondary, tertiary, fullStrength * flow, palette.isLight, clock, modifier)
            FlowStyle.Metaballs ->
                MetaballsBackground(base, primary, secondary, tertiary, fullStrength * flow, palette.isLight, clock, modifier)
            FlowStyle.Dithering ->
                DitheringBackground(base, primary, secondary, tertiary, strength * flow, palette.isLight, clock, modifier)
            FlowStyle.Flow ->
                MeshBackground(base, primary, secondary, tertiary, strength * flow, clock, modifier)
        }
    } else {
        BlobBackground(base, primary, secondary, tertiary, strength * flow, clock, modifier)
    }
}

/**
 * Shared animation clock. Slows (never stops) while paused; frozen if system animations are off, and not
 * ticking at all while [running] is false (solid background), so a flat screen costs nothing per frame.
 */
@Composable
private fun rememberFlowClock(
    isPlaying: Boolean,
    running: Boolean,
    animated: Boolean,
    speedScale: Float,
    freezeWhenPaused: Boolean,
): FloatState {
    val context = LocalContext.current
    val pausedSpeed = if (freezeWhenPaused) 0f else PausedSpeed
    val speed by animateFloatAsState((if (isPlaying) 1f else pausedSpeed) * speedScale, tween(1200), label = "flow-speed")
    val clock = remember { mutableFloatStateOf(0f) }
    val animationsOn = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } else {
            true
        }
    }
    LaunchedEffect(animationsOn, running, animated) {
        if (!running) return@LaunchedEffect
        // "Animate" off (or system animations off): one still frame, no per-frame work at all.
        if (!animationsOn || !animated) {
            clock.floatValue = 3f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                // The flow drifts over 15 to 30 s per cycle, so 30 updates a second look identical to 60 or 120,
                // but halve the shader + blur work that competes with scrolling for the GPU.
                if (dt >= FlowFrameInterval) {
                    last = now
                    clock.floatValue += dt * FlowRate * speed
                }
            }
        }
    }
    return clock
}

// ---------- Android 13+: AGSL mesh gradient ----------

private const val MeshAgsl = """
uniform float2 resolution;
uniform float time;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 base;
uniform float strength;
uniform float2 pa;
uniform float2 pb;
uniform float2 pc;
uniform float2 pd;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float blob(float2 p, float2 c, float k) {
    float2 d = p - c;
    // Gaussian core, then a steep smoothstep so the edge is defined instead of a long haze.
    return smoothstep(0.10, 0.55, exp(-dot(d, d) * k));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / resolution.y;
    float2 p = float2(uv.x * aspect, uv.y);
    float t = time;

    // Nested domain warp: the second warp is driven by the first, so the field swirls and folds
    // into itself like liquid instead of just swaying.
    float2 w1 = float2(sin(p.y * 2.6 + t * 1.10), cos(p.x * 2.3 - t * 0.90));
    float2 q = p + 0.07 * w1;
    float2 w2 = float2(sin(q.y * 4.1 - t * 0.80 + w1.x * 1.6), cos(q.x * 3.7 + t * 1.00 + w1.y * 1.6));
    q += 0.04 * w2;

    // The four blob centers roam the whole screen (10% to 90% on both axes). They only depend on time, so they
    // are computed once per frame on the CPU and arrive as uniforms (pa..pd) instead of 8 sin/cos per pixel.
    float2 a = pa;
    float2 b = pb;
    float2 c = pc;
    float2 d = pd;

    // Same strength everywhere on the screen.
    float fade = strength;

    float3 col = base;
    col = mix(col, c1, clamp(blob(q, a, 22.0) * 0.95 * fade, 0.0, 1.0));
    col = mix(col, c2, clamp(blob(q, b, 26.0) * 0.90 * fade, 0.0, 1.0));
    col = mix(col, c3, clamp(blob(q, c, 20.0) * 0.85 * fade, 0.0, 1.0));
    col = mix(col, c1, clamp(blob(q, d, 30.0) * 0.70 * fade, 0.0, 1.0));

    // Only a one-level dither (about +-0.5/255), enough to break up banding in the soft gradients without
    // any visible grain.
    col += (hash(fragCoord) - 0.5) * 0.004;
    return half4(half3(col), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun MeshBackground(
    base: Color, c1: Color, c2: Color, c3: Color,
    strength: Float,
    clock: FloatState,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(MeshAgsl) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", clock.floatValue)
        shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
        shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
        shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
        shader.setFloatUniform("base", base.red, base.green, base.blue)
        shader.setFloatUniform("strength", strength)
        val aspect = size.width / size.height
        val t = clock.floatValue
        fun unit(v: Float) = 0.10f + 0.80f * (0.5f + 0.5f * v)
        shader.setFloatUniform("pa", aspect * unit(sin(t * 0.37f)), unit(cos(t * 0.29f + 1.0f)))
        shader.setFloatUniform("pb", aspect * unit(cos(t * 0.31f + 2.0f)), unit(sin(t * 0.41f + 3.0f)))
        shader.setFloatUniform("pc", aspect * unit(sin(t * 0.43f + 4.0f)), unit(cos(t * 0.33f + 5.0f)))
        shader.setFloatUniform("pd", aspect * unit(cos(t * 0.27f + 6.0f)), unit(sin(t * 0.47f + 7.0f)))
        drawRect(brush)
    }
}

// ---------- Android 12 and below: layered radial glows ----------

@Composable
private fun BlobBackground(
    base: Color, primary: Color, secondary: Color, tertiary: Color,
    strength: Float,
    clock: FloatState,
    modifier: Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val w = size.width
        val h = size.height
        val longSide = max(w, h)
        val colors = listOf(primary, secondary, tertiary)

        drawRect(base)
        for ((i, b) in Blobs.withIndex()) {
            val blobCenter = Offset(
                x = w * (0.10f + 0.80f * (0.5f + 0.5f * sin(t * b.fx * 0.6f + b.phase))),
                y = h * (0.10f + 0.80f * (0.5f + 0.5f * cos(t * b.fy * 0.6f + b.phase * 1.3f))),
            )
            val breathe = 1f + 0.10f * sin(t * 1.2f + i * 1.7f)
            val r = longSide * b.radius * BlobScale * breathe
            val color = colors[b.colorIndex]
            withTransform({
                rotate(degrees = t * b.spin * 30f + i * 40f, pivot = blobCenter)
                scale(scaleX = b.stretch, scaleY = 0.8f, pivot = blobCenter)
            }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to color.copy(alpha = Intensity * strength),
                        0.45f to color.copy(alpha = Intensity * strength * 0.75f),
                        0.75f to color.copy(alpha = Intensity * strength * 0.12f),
                        1.00f to Color.Transparent,
                        center = blobCenter,
                        radius = r,
                    ),
                    radius = r,
                    center = blobCenter,
                )
            }
        }
    }
}