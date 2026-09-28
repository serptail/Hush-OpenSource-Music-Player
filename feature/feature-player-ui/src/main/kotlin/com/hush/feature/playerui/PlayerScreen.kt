package com.hush.feature.playerui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Light
import com.adamglin.phosphoricons.light.CaretDown
import com.adamglin.phosphoricons.light.DotsThreeVertical
import com.adamglin.phosphoricons.light.Equalizer
import com.adamglin.phosphoricons.light.Heart
import com.adamglin.phosphoricons.light.MusicNote
import com.adamglin.phosphoricons.light.Pause
import com.adamglin.phosphoricons.light.Play
import com.adamglin.phosphoricons.light.Queue
import com.adamglin.phosphoricons.light.Repeat
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.SkipBack
import com.adamglin.phosphoricons.light.SkipForward
import com.adamglin.phosphoricons.light.SpeakerHigh
import com.hush.core.model.PlaybackState
import com.hush.core.player.MusicPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.graphics.get
import kotlin.time.Duration.Companion.milliseconds
import android.graphics.Bitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

// ---- Sizing (no dynamic-palette dependency) ----
private val CoverShape = RoundedCornerShape(30.dp)
private val CoverCornerRadius = 30.dp
private val MaxCoverSize = 290.dp   // upper bound only; the real size comes from the space available
private val RingGap = 3.5.dp     // same gap distance as before, now measured outward instead of inward
private val RingStrokeWidth = 3.dp
private val RingClearance = 28.dp     // total (both sides) room for gap + stroke + shadow blur outside the cover
private val RingInset = RingClearance / 2  // the same room, per side

@Composable
fun PlayerScreen(player: MusicPlayer, onBack: () -> Unit = {}) {
    val state by player.playbackState.collectAsState()
    val track = (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    val isPlaying = state is PlaybackState.Playing
    val duration = (track?.durationMs ?: 0L).coerceAtLeast(1L)

    // Smooth, continuous progress: the player's `position` only ticks
    // periodically (e.g., once a second), which caused a visible stutter.
    // Between ticks, we advance locally against the system clock, so the fill
    // flows every frame. Whenever a real update lands, we re-sync.
    var basePosition by remember { mutableLongStateOf(position) }
    var baseTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(position) {
        basePosition = position
        baseTimestamp = System.currentTimeMillis()
    }
    var smoothPositionMs by remember { mutableFloatStateOf(position.toFloat()) }
    LaunchedEffect(isPlaying, basePosition) {
        if (!isPlaying) {
            smoothPositionMs = basePosition.toFloat()
        } else {
            while (true) {
                withFrameNanos {}
                val elapsed = System.currentTimeMillis() - baseTimestamp
                smoothPositionMs = (basePosition + elapsed).toFloat().coerceAtMost(duration.toFloat())
            }
        }
    }
    val progress = (smoothPositionMs / duration).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(Color(0xFF121014))) {

        // Real cover art, blurred. It is kept subtle (24dp, not 80), so it reads
        // as frosted glass over a photo rather than a mushy color wash.
        if (track?.albumArtUrl != null) {
            AsyncImage(
                model = track.albumArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp) else Modifier),
            )
        }
        // Glass tint: a translucent, faintly cool-tinted layer (not flat black)
        // reads as acrylic rather than as a dark overlay.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x99151318), Color(0xCC0E0C10)),
                ),
            ),
        )
        // A faint diagonal sheen across the whole glass surface — the gloss cue.
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 1400f),
                ),
            ),
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            TopBar(onBack, Modifier.fillMaxWidth())

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        track?.artist ?: "Choose something to play",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
                Icon(
                    PhosphorIcons.Light.Heart, "Favorite", tint = Color.White,
                    modifier = Modifier.bounceTap(onClick = {}).padding(4.dp).size(26.dp),
                )
            }

            Spacer(Modifier.weight(0.8f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    PhosphorIcons.Light.SkipBack, "Previous", tint = Color.White,
                    modifier = Modifier.bounceTap(onClick = player::skipPrevious).padding(14.dp).size(28.dp),
                )
                ArtworkWithRing(
                    modifier = Modifier.weight(1f),
                    url = track?.albumArtUrl,
                    progress = progress,
                    isPlaying = isPlaying,
                    enabled = track != null,
                    isEdgeLight = rememberIsEdgeLight(track?.albumArtUrl),
                    onTogglePlay = { if (isPlaying) player.pause() else player.resume() },
                    onSeekFraction = { player.seekTo((it * duration).toLong()) },
                )
                Icon(
                    PhosphorIcons.Light.SkipForward, "Next", tint = Color.White,
                    modifier = Modifier.bounceTap(onClick = player::skipNext).padding(14.dp).size(28.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(track?.durationMs ?: 0L), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Icon(PhosphorIcons.Light.Shuffle, "Shuffle", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
                Icon(PhosphorIcons.Light.Repeat, "Repeat", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
                Icon(PhosphorIcons.Light.Queue, "Queue", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
            }

            Spacer(Modifier.weight(1.4f))
            GlassNavBar(Modifier.padding(bottom = 8.dp))
        }
    }
}

/** Frosted glass bottom nav pill — translucent fill, soft shadow, thin gradient rim. */
@Composable
private fun GlassNavBar(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color.White.copy(alpha = 0.07f), shape)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))),
                shape = shape,
            )
            .clip(shape),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Light.MusicNote, "Home", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.bounceTap(onClick = {}).size(24.dp))
        Icon(PhosphorIcons.Light.Queue, "Library", tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.bounceTap(onClick = {}).size(24.dp))
        Icon(PhosphorIcons.Light.Equalizer, "Now playing", tint = Color.White.copy(alpha = 0.65f), modifier = Modifier.bounceTap(onClick = {}).size(24.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(PhosphorIcons.Light.CaretDown, "Collapse", tint = Color.White, modifier = Modifier.tapTarget(onClick = onBack).padding(8.dp))
        Spacer(Modifier.weight(1f))
        Icon(PhosphorIcons.Light.SpeakerHigh, "Volume", tint = Color.White, modifier = Modifier.tapTarget(onClick = {}).padding(8.dp))
        Icon(PhosphorIcons.Light.Equalizer, "Equalizer", tint = Color.White, modifier = Modifier.tapTarget(onClick = {}).padding(8.dp))
        Icon(PhosphorIcons.Light.DotsThreeVertical, "More", tint = Color.White, modifier = Modifier.tapTarget(onClick = {}).padding(8.dp))
    }
}

/**
 * Rounded-square cover with a thin progress line sitting just outside its edge.
 * Tapping the cover toggles play/pause. The icon shows briefly after a tap while
 * playing, and stays visible while paused.
 */
@Composable
private fun ArtworkWithRing(
    modifier: Modifier = Modifier,
    url: String?,
    progress: Float,
    isPlaying: Boolean,
    enabled: Boolean,
    isEdgeLight: Boolean,
    onTogglePlay: () -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    var tapTrigger by remember { mutableIntStateOf(0) }
    var iconVisible by remember { mutableStateOf(true) }
    var ringPx by remember { mutableIntStateOf(0) } // measured side of the square ring box

    LaunchedEffect(isPlaying, tapTrigger) {
        iconVisible = true
        if (isPlaying) {
            delay(1200.milliseconds)
            iconVisible = false
        }
    }
    val iconAlpha by animateFloatAsState(if (iconVisible) 1f else 0f, animationSpec = tween(220), label = "icon-alpha")

    // The caller decides how much width we get: the space between the prev/next
    // icons. The ring box fills that width up to a maximum and stays square.
    // Every inner size derives from it, so nothing depends on a fixed dp value.
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = MaxCoverSize + RingClearance)
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Soft ambient glow, sized and blurred enough to sit behind the
            // shape rather than smear into its edge.
            Box(
                Modifier
                    .padding(RingInset)
                    .fillMaxSize(0.82f)
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
            // Lift the cover off the background, so it doesn't flatten into the
            // blurred backdrop behind it.
            Box(
                Modifier
                    .padding(RingInset)
                    .fillMaxSize()
                    .shadow(elevation = 18.dp, shape = CoverShape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
                    .clip(CoverShape)
                    .background(Color.Transparent),
            )

            Box(
                Modifier
                    .padding(RingInset)
                    .fillMaxSize()
                    .clip(CoverShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        onClick = {
                            onTogglePlay()
                            tapTrigger++
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = { ArtworkPlaceholder() },
                    error = { ArtworkPlaceholder() },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                            start = Offset(0f, 0f),
                            end = Offset(300f, 380f),
                        ),
                    ),
                )
                Box(
                    Modifier
                        .size(64.dp)
                        .graphicsLayer { alpha = iconAlpha }
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) PhosphorIcons.Light.Pause else PhosphorIcons.Light.Play,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Progress line, sitting outside the cover. Its color flips to dark
            // whenever the artwork's own edges read as light (see
            // rememberIsEdgeLight), so it stays visible against a pale cover
            // instead of washing out.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { ringPx = it.width }
                    .pointerInput(enabled, ringPx) {
                        if (!enabled || ringPx == 0) return@pointerInput
                        val marginPx = RingInset.toPx()
                        val coverPx = ringPx - RingClearance.toPx()
                        val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                        val path = outerRingPath(coverPx, marginPx, gapPx, CoverCornerRadius.toPx())
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val total = measure.length
                        val start = topAnchorOffset(path, measure)
                        val samples = 300
                        val pts = List(samples) { measure.getPosition(total * it / samples) }

                        // Only treat this as a seek if the drag starts near the line
                        // itself. Otherwise, let it pass through, so taps on the cover
                        // underneath still work.
                        fun nearLine(p: Offset) = pts.minOf { hypot(it.x - p.x, it.y - p.y) } < 22.dp.toPx()
                        fun seek(p: Offset) {
                            var best = 0; var bestD = Float.MAX_VALUE
                            pts.forEachIndexed { i, o ->
                                val d = hypot(o.x - p.x, o.y - p.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            val dist = total * best / samples
                            onSeekFraction((((dist - start) % total + total) % total / total).coerceIn(0f, 1f))
                        }
                        var active = false
                        detectDragGestures(
                            onDragStart = { active = nearLine(it); if (active) seek(it) },
                            onDrag = { change, _ -> if (active) seek(change.position) },
                            onDragEnd = { active = false },
                            onDragCancel = { active = false },
                        )
                    },
            ) {
                val marginPx = RingInset.toPx()
                val coverPx = size.width - RingClearance.toPx()
                val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                val path = outerRingPath(coverPx, marginPx, gapPx, CoverCornerRadius.toPx())
                val measure = PathMeasure().apply { setPath(path, false) }
                val total = measure.length
                val start = topAnchorOffset(path, measure)

                if (progress > 0f) {
                    val len = total * progress
                    val filled = Path()
                    val end = start + len
                    if (end <= total) {
                        measure.getSegment(start, end, filled, true)
                    } else {
                        measure.getSegment(start, total, filled, true)
                        measure.getSegment(0f, end - total, filled, true)
                    }
                    val lineColor = if (isEdgeLight) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = RingStrokeWidth.toPx()
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = lineColor
                            setShadowLayer(6.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.argb(90, 0, 0, 0))
                        }
                        canvas.nativeCanvas.drawPath(filled.asAndroidPath(), paint)
                    }
                }
            }
        }
    }
}

/**
 * Builds the outward progress-line path. It is the cover's rectangle, grown
 * outward by [gapPx] on every side. The corner radius grows by the same
 * amount, so the curve stays concentric with the cover's own corners.
 */
private fun outerRingPath(coverPx: Float, marginPx: Float, gapPx: Float, coverRadiusPx: Float): Path = Path().apply {
    addRoundRect(
        RoundRect(
            left = marginPx - gapPx,
            top = marginPx - gapPx,
            right = marginPx + coverPx + gapPx,
            bottom = marginPx + coverPx + gapPx,
            cornerRadius = CornerRadius(coverRadiusPx + gapPx, coverRadiusPx + gapPx),
        ),
    )
}

/**
 * Finds the arc-length offset of the point on [path] closest to its own
 * top-center. It samples the path instead of assuming where addRoundRect
 * starts its winding, so the fill always begins at the top of the cover.
 */
private fun topAnchorOffset(path: Path, measure: PathMeasure): Float {
    val bounds = path.getBounds()
    val topCenter = Offset((bounds.left + bounds.right) / 2f, bounds.top)
    val total = measure.length
    val samples = 480
    var bestIdx = 0
    var bestD = Float.MAX_VALUE
    for (i in 0 until samples) {
        val p = measure.getPosition(total * i / samples)
        val dx = p.x - topCenter.x
        val dy = p.y - topCenter.y
        val d = dx * dx + dy * dy
        if (d < bestD) { bestD = d; bestIdx = i }
    }
    return total * bestIdx / samples
}

/**
 * Returns true when the artwork's border region reads as light enough to need
 * a dark progress line instead of a white one.
 *
 * It samples the displayed artwork, center-cropped to a square the same way
 * ContentScale.Crop renders it, so the sampled pixels match what is really at
 * the cover's edges.
 *
 * Metric: perceptual luminance (ITU-R BT.601: 0.299R + 0.587G + 0.114B),
 * averaged over the perimeter pixels of a small scaled-down copy of the image.
 * That is exactly the band the ring sits against, so a dark center with a
 * light border (or vice versa) is judged correctly.
 *
 * This is a plain brightness check, not palette or color extraction.
 */
@Composable
private fun rememberIsEdgeLight(url: String?): Boolean {
    val context = LocalContext.current
    var isLight by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            isLight = false
            return@LaunchedEffect
        }
        val luminance = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmapOrNull() ?: return@runCatching null
                edgeLuminance(bitmap)
            }.getOrNull()
        }
        isLight = (luminance ?: 0f) > 0.75f
    }
    return isLight
}

private fun edgeLuminance(bitmap: Bitmap): Float {
    val side = minOf(bitmap.width, bitmap.height)
    val x = (bitmap.width - side) / 2
    val y = (bitmap.height - side) / 2
    val square = Bitmap.createBitmap(bitmap, x, y, side, side)
    val sample = Bitmap.createScaledBitmap(square, 24, 24, true)
    var total = 0f
    var count = 0
    for (i in 0 until 24) {
        for (j in 0 until 24) {
            if (i != 0 && i != 23 && j != 0 && j != 23) continue // perimeter only — where the line actually sits
            val px = sample[i, j]
            val r = android.graphics.Color.red(px) / 255f
            val g = android.graphics.Color.green(px) / 255f
            val b = android.graphics.Color.blue(px) / 255f
            total += 0.299f * r + 0.587f * g + 0.114f * b
            count++
        }
    }
    return if (count > 0) total / count else 0f
}

@Composable
private fun ArtworkPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PhosphorIcons.Light.MusicNote, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
    }
}

private fun formatTime(milliseconds: Long): String = "%d:%02d".format(milliseconds / 60_000, (milliseconds / 1_000) % 60)

@Composable
private fun Modifier.tapTarget(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Spring-bounce press feedback — scales down on press, springs back on release. */
private fun Modifier.bounceTap(onClick: () -> Unit, scaleDown: Float = 0.88f): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bounce-tap",
    )
    this.scale(scale).clickable(interactionSource = interaction, indication = null, onClick = onClick)
}