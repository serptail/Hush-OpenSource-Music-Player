package com.gamut.feature.playerui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.graphics.get
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.MusicNote
import com.gamut.core.designsystem.squareArtUrl
import com.gamut.core.model.Track
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import android.media.MediaCodec
import android.util.Log
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ArtworkPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
    }
}

internal fun formatTime(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, (milliseconds / 1_000) % 60)

/**
 * Elapsed / total time. Digits are tabular so the label doesn't jitter as they change, and it only
 * recomposes when the displayed second changes (not every frame).
 */
@Composable
internal fun TimeRow(position: () -> Long, durationMs: Long) {
    val elapsedSecond by remember(position) { derivedStateOf { position() / 1_000 } }
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.4.sp)
    val color = Color.White.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(elapsedSecond * 1_000), color = color, style = style)
        Text(formatTime(durationMs), color = color, style = style)
    }
}

@Composable
internal fun Modifier.tapTarget(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Spring-bounce press feedback — scales down on press, springs back on release. */
internal fun Modifier.bounceTap(onClick: () -> Unit, scaleDown: Float = 0.88f): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> scaleDown
            hovered -> 1.08f   // mouse / stylus lift; touch never hovers
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce-tap",
    )
    this.scale(scale).hoverable(interaction).clickable(interactionSource = interaction, indication = null) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/**
 * An icon with a soft drop shadow: a blurred, dark copy sits just below the real one.
 * Kept subtle on purpose. [size] is the glyph size; put any padding / click handling in [modifier].
 */
@Composable
internal fun DepthIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp,
) {
    Box(modifier.size(size)) {
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        Icon(
            imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = if (canBlur) 0.45f else 0.22f),
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 1.5.dp)
                .then(if (canBlur) Modifier.blur(2.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
        )
        Icon(imageVector, contentDescription, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Fades content out between two y positions (in px): fully visible above [start], completely gone
 * at and below [end]. The curve drops quickly right after [start] so the fade reads as strong /
 * deep instead of a lazy linear ramp. Does nothing while [hasSecondLine] is false (e.g. a song
 * with a single lyric line, or before the lines have been measured).
 */
internal fun Modifier.fadeOutBetween(
    start: () -> Float,
    end: () -> Float,
    hasSecondLine: () -> Boolean,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (!hasSecondLine()) return@drawWithContent
        val s = start()
        val e = end()
        if (e <= s) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Black,
                    0.5f to Color.Black.copy(alpha = 0.3f),
                    1f to Color.Transparent,
                ),
                startY = s,
                endY = e,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Fades the top and bottom edges of a scrolling list into the card. */
internal fun Modifier.fadingEdges(fade: Float = 0.12f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private const val BounceSpeedDpPerSecond = 45f  // how fast an overlong title glides

/**
 * One-line text. If it is too long for its box it glides to the far end so everything gets shown,
 * then straight away glides back to the start, and repeats, like a ball bouncing between two walls.
 * Text that fits stays still.
 */
@Composable
internal fun BouncingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var boxWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }
    val overflowPx = (textWidth - boxWidth).coerceAtLeast(0)
    val offset = remember { Animatable(0f) }

    // Restarts from the beginning whenever the text (new track) or the amount it overflows changes.
    LaunchedEffect(text, overflowPx) {
        offset.snapTo(0f)
        if (overflowPx <= 0) return@LaunchedEffect
        val travelMs = (overflowPx / density.density / BounceSpeedDpPerSecond * 1000f).toInt().coerceAtLeast(400)
        while (true) {
            offset.animateTo(overflowPx.toFloat(), tween(travelMs, easing = LinearEasing))
            offset.animateTo(0f, tween(travelMs, easing = LinearEasing))
        }
    }

    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { boxWidth = it.width }) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            modifier = Modifier
                // Let the text lay out at its full width (the box above clips it), and report that width.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { textWidth = it.width }
                .graphicsLayer { translationX = -offset.value },
        )
    }
}

/**
 * The "no song yet" / loading screen: one small black record turning in the exact middle of the flat background,
 * with a thin progress bar under it while a track is loading ([progress] null = no bar). The bar shows real
 * progress (see MusicPlayer.loadProgress), animated smoothly between updates.
 */
@Composable
internal fun LoadingVinyl(progress: Float?, modifier: Modifier = Modifier) {
    Box(modifier.size(104.dp), contentAlignment = Alignment.Center) {
        VinylDisc(Modifier.fillMaxSize())
        if (progress != null) {
            // Hangs below the record, so the record itself stays dead center.
            LoadingBar(progress, Modifier.align(Alignment.BottomCenter).offset(y = 29.dp).width(72.dp))
        }
    }
}

@Composable
private fun LoadingBar(progress: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "loading-progress",
    )
    Box(
        modifier
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .semantics { contentDescription = "Loading" },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(Color.White.copy(alpha = 0.85f)))
    }
}

/** A thin arc that grows and shrinks while it circles, over a faint track. Replaces the stock Material spinner. */
@Composable
internal fun PlayerSpinner(modifier: Modifier = Modifier, color: Color = Color.White, strokeWidth: Dp = 2.dp) {
    val transition = rememberInfiniteTransition(label = "player-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
        label = "spinner-rotation",
    )
    val sweep by transition.animateFloat(
        initialValue = 40f,
        targetValue = 250f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "spinner-sweep",
    )
    Canvas(modifier.semantics { contentDescription = "Loading" }) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(color.copy(alpha = 0.14f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(color, rotation, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun VinylDisc(modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "vinyl-spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),   // one turn per 2.4s
        label = "vinyl-angle",
    )
    val black = Color(0xFF050506)
    Canvas(
        modifier
            // Rotation lives on the layer: the record is drawn once and only turned each frame.
            .graphicsLayer { rotationZ = angle },
    ) {
        val c = center
        val r = size.minDimension / 2f
        val labelR = r * 0.35f

        // Body: near-black, a hair lighter toward the middle so the edge still reads on the dark screen.
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF16161A), black), center = c, radius = r),
            radius = r,
            center = c,
        )
        // Fine grooves, from just outside the label to just inside the rim.
        val groove = 0.6.dp.toPx()
        var g = labelR + r * 0.12f
        var i = 0
        while (g < r * 0.95f) {
            drawCircle(Color.White.copy(alpha = if (i % 3 == 0) 0.06f else 0.03f), radius = g, center = c, style = Stroke(groove))
            g += 2.5.dp.toPx()
            i++
        }
        // Two opposite light glints raking across the grooves.
        drawCircle(
            brush = Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.07f to Color.White.copy(alpha = 0.16f),
                0.15f to Color.Transparent,
                0.50f to Color.Transparent,
                0.57f to Color.White.copy(alpha = 0.16f),
                0.65f to Color.Transparent,
                1.00f to Color.Transparent,
                center = c,
            ),
            radius = r * 0.97f,
            center = c,
        )
        // Raised lip + rim.
        drawCircle(Color.White.copy(alpha = 0.05f), radius = r * 0.965f, center = c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.14f), radius = r - 0.5.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
        // Blank white label, a faint hairline from the spindle hole to its edge, and the hole itself.
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFF4F4F4), Color(0xFFDADADA)), center = c, radius = labelR),
            radius = labelR,
            center = c,
        )
        drawLine(Color.Black.copy(alpha = 0.18f), start = c, end = Offset(c.x - labelR * 0.96f, c.y), strokeWidth = 0.7.dp.toPx())
        drawCircle(black, radius = r * 0.03f, center = c)
    }
}

/** Height of bar [index] of [count] (0..1): the loudest waveform sample that falls in its slice. */
internal fun waveformAt(wave: FloatArray, index: Int, count: Int): Float {
    if (wave.isEmpty()) return 0f
    val from = (index.toLong() * wave.size / count).toInt().coerceIn(0, wave.lastIndex)
    val to = ((index + 1).toLong() * wave.size / count).toInt().coerceIn(from + 1, wave.size)
    var m = 0f
    for (k in from until to) if (wave[k] > m) m = wave[k]
    return m.coerceIn(0f, 1f)
}

/**
 * Heights (0..1) for [count] bars. Each bar first takes the loudest sample of its slice, then every bar is ranked
 * against all the others: the quietest bar becomes 0, the loudest 1, the median 0.5. A song's loudness values
 * tend to bunch up in a narrow band, which makes bars look alike; ranking spreads them evenly over the whole range.
 */
internal fun barHeights(wave: FloatArray, count: Int): FloatArray {
    if (wave.isEmpty() || count <= 0) return FloatArray(maxOf(count, 0))
    val raw = FloatArray(count) { waveformAt(wave, it, count) }
    if (count == 1) return floatArrayOf(raw[0])
    val sorted = raw.sortedArray()
    return FloatArray(count) { i ->
        var lo = 0
        var hi = count   // first index whose value is >= raw[i]
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < raw[i]) lo = mid + 1 else hi = mid
        }
        lo.toFloat() / (count - 1)
    }
}

/**
 * Where the player registers the audio URL of the song it is about to play, so the waveform can be built from the
 * very same audio. Call [register] right before `player.play(track, audioUrl)`.
 */
object WaveformSource {
    private val urls = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun keyOf(track: Track): String = track.isrc.orEmpty().ifBlank { "${track.title}|${track.artist}" }

    @Volatile internal var appContext: android.content.Context? = null

    /** Call once at startup (MainActivity.onCreate), before the first [register]. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    fun register(track: Track, audioUrl: String) {
        val key = keyOf(track)
        urls[key] = audioUrl
        // Songs we moved on from no longer need their waveform: stop spending CPU on them.
        waveformEntries.keys.toList().forEach { k ->
            if (k != key) waveformEntries.remove(k)?.job?.cancel()
        }
        // Start measuring right now, at song launch, so it is (nearly) ready by the time the player screen opens.
        waveformEntry(track)
    }

    internal fun urlFor(track: Track): String? = urls[keyOf(track)]
}

/** Number of samples in a track's envelope. More than the ring has bars, so every bar gets real data. */
private const val EnvelopeSamples = 480

private const val WaveformTag = "Waveform"

/** Audio servers often refuse default Android user agents, so ask like a browser would. */
private const val WaveformUserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/** The audio is fetched in pieces this big: streaming it through MediaExtractor was throttled to a crawl. */
private const val DownloadChunk = 1_048_576
private const val MaxDownload = 48L * 1_048_576

/** Decoded envelopes, so reopening the player (or coming back to a song) is instant. */
private val waveformCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 24
}

/** One song's envelope while it is being measured: [data] fills in progressively and the UI just reads it. */
private class WaveformEntry {
    val data = mutableStateOf(FloatArray(0))
    @Volatile var job: Job? = null
}

private val NoWaveform = FloatArray(0)
private val waveformScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val waveformEntries = java.util.concurrent.ConcurrentHashMap<String, WaveformEntry>()

/**
 * The (possibly still filling) envelope of [track]. The measuring starts once per song, no matter how many times
 * this is called or whether any screen is looking: [WaveformSource.register] calls it at song launch.
 */
private fun waveformEntry(track: Track): WaveformEntry {
    val key = WaveformSource.keyOf(track)
    synchronized(waveformCache) { waveformCache[key] }?.let { cached ->
        return WaveformEntry().also { it.data.value = cached }
    }
    return waveformEntries.computeIfAbsent(key) { k ->
        val entry = WaveformEntry()
        entry.job = waveformScope.launch {
            try {
                val real = loadWaveform(track) { partial -> entry.data.value = partial }
                if (real != null && real.isNotEmpty()) entry.data.value = real
            } finally {
                waveformEntries.remove(k)   // done, failed or cancelled: the cache (or a retry) takes over
            }
        }
        entry
    }
}

/**
 * Real loudness envelope of [track] (0..1 per sample, evenly spaced over the whole audio). Downloads the audio, then
 * measures the loudness at [EnvelopeSamples] points of it (see [sampleEnvelope]). Null when the audio URL is unknown
 * or cannot be decoded (the ring then shows flat minimum ticks).
 */
private suspend fun loadWaveform(track: Track, onPartial: (FloatArray) -> Unit): FloatArray? {
    val key = WaveformSource.keyOf(track)
    synchronized(waveformCache) { waveformCache[key] }?.let { return it }
    // The URL is registered right before playback starts; give it a moment in case this was asked for first.
    var url = WaveformSource.urlFor(track)
    var tries = 0
    while (url == null && tries < 10) {
        delay(300)
        url = WaveformSource.urlFor(track)
        tries++
    }
    val source = url ?: run {
        Log.w(WaveformTag, "No audio URL registered for \"${track.title}\" (played without WaveformSource.register?)")
        return null
    }
    val startedAt = System.currentTimeMillis()
    val result = try {
        val file = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            downloadAudio(source) { job?.isActive != false }
        }
        val downloadedAt = System.currentTimeMillis()
        if (file == null) {
            null
        } else {
            try {
                withContext(Dispatchers.IO) { sampleEnvelope(file.path) }.also {
                    Log.i(WaveformTag, "download ${downloadedAt - startedAt} ms, measuring ${System.currentTimeMillis() - downloadedAt} ms")
                }
            } finally {
                file.delete()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(WaveformTag, "Decoding failed for \"${track.title}\"", e)
        null
    }
    if (result == null) Log.w(WaveformTag, "No waveform for \"${track.title}\"")
    else Log.i(
        WaveformTag,
        "Waveform ready for \"${track.title}\" in ${System.currentTimeMillis() - startedAt} ms " +
            "(min ${result.min()}, max ${result.max()})",
    )
    if (result != null) synchronized(waveformCache) { waveformCache[key] = result }
    return result
}

/**
 * Downloads the audio into a temp file, a chunk at a time (YouTube's servers throttle one long request, but not
 * ranged ones). Returns null on failure; the caller deletes the file.
 */
private fun downloadAudio(url: String, stillActive: () -> Boolean): java.io.File? {
    val file = java.io.File.createTempFile("waveform", ".audio")
    var ok = false
    try {
        val chunked = url.contains("googlevideo.com") && !url.contains("range=")
        var start = 0L
        file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (start < MaxDownload) {
                if (!stillActive()) return null
                val end = start + DownloadChunk - 1
                val connection = java.net.URL(if (chunked) "$url&range=$start-$end" else url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", WaveformUserAgent)
                if (!chunked) connection.setRequestProperty("Range", "bytes=$start-$end")
                try {
                    val code = connection.responseCode
                    if (code == 416) break   // asked past the end
                    if (code !in 200..299) {
                        Log.w(WaveformTag, "Download failed: HTTP $code")
                        return null
                    }
                    var got = 0L
                    connection.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            got += n
                        }
                    }
                    start += got
                    // A server that ignores Range sends the whole file at once; a short chunk is the last one.
                    if (got == 0L || got < DownloadChunk || (!chunked && code == 200)) break
                } finally {
                    connection.disconnect()
                }
            }
        }
        ok = file.length() > 0
        if (!ok) Log.w(WaveformTag, "Download returned no data")
        return if (ok) file else null
    } finally {
        if (!ok) file.delete()
    }
}

/**
 * Measures the loudness of the audio file at [path] with Amplituda (FFmpeg, native code: roughly a second for a
 * 3-minute song) and boils its samples down to [EnvelopeSamples] points (0..1 each). Blocking: call off the main thread.
 * Returns null when the audio cannot be read.
 */
private fun sampleEnvelope(path: String): FloatArray? {
    val context = WaveformSource.appContext
    if (context == null) {
        Log.w(WaveformTag, "WaveformSource.init(context) was never called")
        return null
    }
    val amps: List<Int> = com.linc.amplituda.Amplituda(context).processAudio(path).get()?.amplitudesAsList() ?: emptyList()
    val peak = amps.maxOrNull() ?: 0
    if (peak <= 0) { Log.w(WaveformTag, "Amplituda returned no audio (${amps.size} samples)"); return null }
    val n = amps.size
    val levels = FloatArray(EnvelopeSamples) { b ->
        val from = (b.toLong() * n / EnvelopeSamples).toInt().coerceIn(0, n - 1)
        val to = ((b + 1).toLong() * n / EnvelopeSamples).toInt().coerceIn(from + 1, n)
        var sum = 0L
        for (k in from until to) sum += amps[k]
        sum.toFloat() / (to - from) / peak
    }
    return levelsToEnvelope(levels, EnvelopeSamples - 1)
}

/**
 * Turns the raw per-tick RMS levels into 0..1 loudness values, in dB so quiet parts are not squashed next to loud ones.
 * A tick that got no frame (rare) takes the value before it; ticks after [upTo] stay at 0 (not measured yet).
 */
private fun levelsToEnvelope(levels: FloatArray, upTo: Int): FloatArray {
    var carry = 0f
    return FloatArray(levels.size) { i ->
        if (i > upTo) {
            0f
        } else {
            if (levels[i] > 0f) carry = levels[i]
            val db = 20f * kotlin.math.log10(carry.coerceAtLeast(1e-5f))
            ((db + 55f) / 55f).coerceIn(0f, 1f)
        }
    }
}

/**
 * Makes differences visible: stretches the samples so the quiet parts sit near 0 and the loud parts near 1
 * (using the 5th and 98th percentile, so one outlier cannot flatten everything else), then raises them to a power
 * so peaks (high notes / loud vocals) stand tall while everything else stays lower.
 */
internal fun shapeWaveform(wave: FloatArray): FloatArray {
    if (wave.isEmpty()) return wave
    // While the song is still being decoded the tail is 0 (not measured yet). Only the measured part is used for
    // scaling, otherwise those zeros would drag the scale around with every update and the bars would jump.
    val measured = wave.indexOfLast { it > 0f } + 1
    if (measured < 8) return FloatArray(wave.size)
    // Loudness drifts slowly through a song, so on its own it gives bars of near-equal height. Mix in the local
    // contrast (each sample minus the average of its neighbours) so beats and loud phrases stand out.
    val r = 12   // neighbourhood: this many samples either side
    val prefix = DoubleArray(measured + 1).also { for (i in 0 until measured) it[i + 1] = it[i] + wave[i] }
    val mixed = FloatArray(measured) { i ->
        val a = (i - r).coerceAtLeast(0)
        val b = (i + r + 1).coerceAtMost(measured)
        val local = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
        0.5f * wave[i] + 1.0f * (wave[i] - local)
    }
    val sorted = mixed.sortedArray()
    val lo = sorted[(sorted.size * 0.05f).toInt().coerceIn(0, sorted.lastIndex)]
    val hi = sorted[(sorted.size * 0.97f).toInt().coerceIn(0, sorted.lastIndex)]
    val range = (hi - lo).coerceAtLeast(1e-3f)
    return FloatArray(wave.size) { if (it < measured) ((mixed[it] - lo) / range).coerceIn(0f, 1f).pow(1.3f) else 0f }
}

/**
 * The song's envelope. Empty until measuring has produced something (the ring then shows flat minimum ticks instead
 * of made-up ones); it fills in from the start, and is usually complete before the player screen is even opened.
 */
@Composable
internal fun rememberWaveform(track: Track?): FloatArray {
    val key = track?.let(WaveformSource::keyOf)
    val entry = remember(key) { track?.let { waveformEntry(it) } }
    return entry?.data?.value ?: NoWaveform
}

// ==================== COVER ACCENT ====================

private val DefaultAccent = Color.White
private val accentCache = HashMap<String, Color>()

/**
 * The most prominent *colorful* hue in the cover art, tuned to be readable on the dark glass
 * background. Falls back to white for grey / monochrome covers. Animates when the track changes.
 */
@Composable
internal fun rememberCoverAccent(url: String?): Color {
    val context = LocalContext.current
    var accent by remember { mutableStateOf(url?.let { accentCache[it] } ?: DefaultAccent) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            accent = DefaultAccent
            return@LaunchedEffect
        }
        accent = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmapOrNull() ?: return@runCatching null
                accentFromBitmap(bitmap)
            }.getOrNull()
        } ?: DefaultAccent
        accentCache[url] = accent
    }
    val animated by animateColorAsState(accent, tween(600), label = "lyrics-accent")
    return animated
}

/**
 * Buckets the (downscaled) image into 12 hue sectors, weighting each pixel by saturation * value
 * so vivid pixels dominate and greys / near-blacks are ignored. The winning sector's average hue
 * is returned with saturation clamped and brightness lifted, so it always reads on a dark background.
 */
private fun accentFromBitmap(bitmap: Bitmap): Color? {
    val size = 40
    val sample = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val bins = 12
    val weight = FloatArray(bins)
    val count = IntArray(bins)
    val sumH = FloatArray(bins)
    val sumS = FloatArray(bins)
    val hsv = FloatArray(3)
    for (x in 0 until size) {
        for (y in 0 until size) {
            android.graphics.Color.colorToHSV(sample[x, y], hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (s < 0.28f || v < 0.30f) continue
            val w = s * v
            val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            weight[bin] += w
            count[bin]++
            sumH[bin] += hsv[0] * w
            sumS[bin] += s * w
        }
    }
    var best = 0
    for (i in 1 until bins) if (weight[i] > weight[best]) best = i
    if (count[best] < 12 || weight[best] <= 0f) return null // effectively a grey cover
    val hue = sumH[best] / weight[best]
    val sat = (sumS[best] / weight[best]).coerceIn(0.5f, 0.85f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 0.96f)))
}

/**
 * The URLs to try for the cover, best first. The hi-res / bar-free variant comes first; the exact URL the rest of
 * the app already loaded (so it is in the image cache and always works) is the last resort. A transformed URL that
 * the provider doesn't actually serve then costs one failed request, never a missing cover.
 */
internal fun coverArtCandidates(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    youtubeThumbCandidates(raw)?.let { return it + raw }
    return listOfNotNull(squareArtUrl(raw), raw).distinct()
}

/**
 * Art for the blurred backdrop and the accent color. The same 200px URL MainActivity and the mini player already
 * use (the shared squareArtUrl), so it is in the image cache and shows instantly, and the accent matches the app's.
 */
internal fun backdropArtUrl(url: String?): String? = squareArtUrl(url, 200)


private val YouTubeThumb = Regex("""^(https?://[^/]*(?:ytimg\.com|img\.youtube\.com))/vi(?:_webp)?/([^/]+)/""")

/**
 * YouTube thumbnails are 16:9 frames. The default `hqdefault` one is a 4:3 image with black bars
 * baked in above and below, and for "Topic" art tracks the real cover is the centered square of the
 * frame. These are the bar-free 16:9 variants, best quality first; null if [url] is not a YouTube thumbnail.
 */
internal fun youtubeThumbCandidates(url: String): List<String>? {
    val m = YouTubeThumb.find(url) ?: return null
    val base = m.groupValues[1]
    val id = m.groupValues[2]
    return listOf("hq720", "maxresdefault", "mqdefault").map { "$base/vi/$id/$it.jpg" }
}