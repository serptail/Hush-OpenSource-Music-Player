package com.hush.feature.playerui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.sp
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
import com.hush.core.model.Track
import com.hush.core.player.MusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import org.json.JSONArray
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.content.res.ResourcesCompat
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
private val RingStrokeWidth = 1.5.dp
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

    // Lyrics state
    var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
    var lyricsExpanded by remember { mutableStateOf(true) }
    // Highlight color for the lyrics, pulled from the cover art.
    val accent = rememberCoverAccent(track?.albumArtUrl)

    // Fetch lyrics when track changes
    LaunchedEffect(track?.isrc) {
        val currentTrack = track
        if (currentTrack == null || currentTrack.isrc.isNullOrBlank()) {
            lyricsState = LyricsState.NotAvailable("No track info")
            return@LaunchedEffect
        }
        lyricsState = LyricsState.Loading
        lyricsState = LyricsRepository.fetchLyrics(currentTrack)
    }

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

            Spacer(Modifier.height(20.dp))

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
                    onSeekFraction = { fraction ->
                        // Move the line right away instead of waiting for the player's next
                        // position tick (up to a second), otherwise a tap looks like it did nothing.
                        val target = (fraction * duration).toLong()
                        basePosition = target
                        baseTimestamp = System.currentTimeMillis()
                        smoothPositionMs = target.toFloat()
                        player.seekTo(target)
                    },
                )
                Icon(
                    PhosphorIcons.Light.SkipForward, "Next", tint = Color.White,
                    modifier = Modifier.bounceTap(onClick = player::skipNext).padding(14.dp).size(28.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(smoothPositionMs.toLong()), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(track?.durationMs ?: 0L), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Icon(PhosphorIcons.Light.Shuffle, "Shuffle", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
                Icon(PhosphorIcons.Light.Repeat, "Repeat", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
                Icon(PhosphorIcons.Light.Queue, "Queue", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.bounceTap(onClick = {}).padding(8.dp).size(22.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Lyrics: a collapsible card that takes whatever space is left below the controls.
            LyricsSection(
                lyricsState = lyricsState,
                positionMs = { smoothPositionMs.toLong() },
                accent = accent,
                expanded = lyricsExpanded,
                onToggle = { lyricsExpanded = !lyricsExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp),
            )
        }
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

    // The gesture block below is keyed on (enabled, ringPx) only, so without these it would keep
    // calling the first lambdas it ever saw (stale `duration`, stale `isPlaying`).
    val currentToggle by rememberUpdatedState(onTogglePlay)
    val currentSeek by rememberUpdatedState(onSeekFraction)

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
                    .clip(CoverShape),
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

                        // The line is only ~1.5dp thick, so it gets a generous touch band. The
                        // band stops short of the cover's interior so taps on the artwork itself
                        // still mean play/pause.
                        val coverInner = Rect(marginPx, marginPx, marginPx + coverPx, marginPx + coverPx)
                            .deflate(10.dp.toPx())
                        val hitRadius = 20.dp.toPx()
                        fun nearLine(p: Offset) =
                            !coverInner.contains(p) && pts.minOf { hypot(it.x - p.x, it.y - p.y) } < hitRadius
                        fun seek(p: Offset) {
                            var best = 0; var bestD = Float.MAX_VALUE
                            pts.forEachIndexed { i, o ->
                                val d = hypot(o.x - p.x, o.y - p.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            val dist = total * best / samples
                            currentSeek((((dist - start) % total + total) % total / total).coerceIn(0f, 1f))
                        }

                        // detectDragGestures only reacts once the finger has moved past the touch
                        // slop, so a plain tap on the line did nothing. Handle down/drag/up by
                        // hand: a touch on the line seeks immediately and follows the finger.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (nearLine(down.position)) {
                                down.consume()
                                seek(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    seek(change.position)
                                }
                            } else {
                                // This layer sits above the cover, so it also owns the cover tap.
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    currentToggle()
                                    tapTrigger++
                                }
                            }
                        }
                    },
            ) {
                val marginPx = RingInset.toPx()
                val coverPx = size.width - RingClearance.toPx()
                val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                val path = outerRingPath(coverPx, marginPx, gapPx, CoverCornerRadius.toPx())
                val measure = PathMeasure().apply { setPath(path, false) }
                val total = measure.length
                val start = topAnchorOffset(path, measure)

                // Faint full-length track, so the whole line is visible before it fills.
                drawPath(
                    path = path,
                    color = (if (isEdgeLight) Color.Black else Color.White).copy(alpha = 0.22f),
                    style = Stroke(width = RingStrokeWidth.toPx()),
                )

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
                            setShadowLayer(3.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.argb(80, 0, 0, 0))
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

// ==================== COVER ACCENT ====================

private val DefaultAccent = Color.White

/**
 * The most prominent *colorful* hue in the cover art, tuned to be readable on the dark glass
 * background. Falls back to white for grey / monochrome covers. Animates when the track changes.
 */
@Composable
private fun rememberCoverAccent(url: String?): Color {
    val context = LocalContext.current
    var accent by remember { mutableStateOf(DefaultAccent) }
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

// ==================== LYRICS SYSTEM ====================

private val LyricsHeaderHeight = 52.dp
private val LyricsCardShape = RoundedCornerShape(26.dp)

/** Represents the state of lyrics fetching. */
sealed interface LyricsState {
    object Loading : LyricsState
    data class Loaded(val lines: List<LyricsLine>, val isSynced: Boolean = true) : LyricsState
    data class NotAvailable(val reason: String) : LyricsState
    data class Error(val message: String) : LyricsState
}

/** One word (or syllable) of a line, with the time window in which it is sung. */
data class LyricsWord(
    val startMs: Long,
    val endMs: Long,
    /** Includes its trailing space, so words can be concatenated as-is. */
    val text: String,
)

/** A single line of lyrics with its timestamp and per-word timing. */
data class LyricsLine(
    val timestampMs: Long,
    val text: String,
    val endMs: Long = timestampMs,
    val words: List<LyricsWord> = emptyList(),
)

private class FetchedLyrics(val lines: List<LyricsLine>, val isSynced: Boolean)

/** Repository for fetching lyrics from various sources. */
object LyricsRepository {
    private const val LRC_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"

    private val lineStampRegex = Regex("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\]")
    private val wordStampRegex = Regex("<(\\d{2}):(\\d{2})[.:](\\d{2,3})>")
    private val whitespaceRegex = Regex("\\s+")

    /** Fetches lyrics for a track using multiple sources. */
    suspend fun fetchLyrics(track: Track): LyricsState = runCatching {
        // Try yt-dlp first (YouTube subtitles/lyrics)
        val ytDlpLyrics = fetchFromYtDlp(track)
        if (ytDlpLyrics.isNotEmpty()) {
            return@runCatching LyricsState.Loaded(ytDlpLyrics)
        }

        // Try LRCLIB (open lyrics database)
        val lrcLib = fetchFromLrcLib(track)
        if (lrcLib.lines.isNotEmpty()) {
            return@runCatching LyricsState.Loaded(lrcLib.lines, lrcLib.isSynced)
        }

        // Try Genius (if API key available) - placeholder for future
        // val geniusLyrics = fetchFromGenius(track)

        LyricsState.NotAvailable("No lyrics found for this track")
    }.getOrElse { LyricsState.Error(it.message ?: "Failed to fetch lyrics") }

    /** Fetches lyrics from YouTube using yt-dlp's subtitle extraction. */
    private suspend fun fetchFromYtDlp(track: Track): List<LyricsLine> = withContext(Dispatchers.IO) {
        // This would require access to YoutubeDL instance
        // For now, return empty - would need to integrate with AndroidYtDlpEngine
        emptyList()
    }

    /** Fetches lyrics from LRCLIB (https://lrclib.net) - open lyrics database. */
    private suspend fun fetchFromLrcLib(track: Track): FetchedLyrics = withContext(Dispatchers.IO) {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val query = "track_name=${enc(track.title)}&artist_name=${enc(track.artist)}&duration=${track.durationMs / 1000}"
        val url = "https://lrclib.net/api/search?$query"

        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", LRC_USER_AGENT)
                connectTimeout = 10000
                readTimeout = 10000
            }

            val response = connection.getInputStream().bufferedReader().use { it.readText() }
            val results = JSONArray(response)

            // Prefer the first result that has time-synced lyrics; remember the first plain one
            // as a fallback. (Word tracking needs timestamps, so synced is worth looking for.)
            var synced = ""
            var plain = ""
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val s = item.optString("syncedLyrics", "")
                if (s.isNotBlank() && s != "null") {
                    synced = s
                    break
                }
                val p = item.optString("plainLyrics", "")
                if (plain.isEmpty() && p.isNotBlank() && p != "null") plain = p
            }
            when {
                synced.isNotBlank() -> FetchedLyrics(parseLrc(synced), isSynced = true)
                plain.isNotBlank() -> FetchedLyrics(parsePlainLyrics(plain), isSynced = false)
                else -> FetchedLyrics(emptyList(), isSynced = false)
            }
        } catch (e: Exception) {
            FetchedLyrics(emptyList(), isSynced = false)
        }
    }

    private fun toMs(min: String, sec: String, frac: String): Long {
        val fracMs = if (frac.length == 3) frac.toLong() else frac.toLong() * 10
        return (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
    }

    /**
     * Parses LRC. Works with plain line-synced LRC (`[mm:ss.xx] text`) and with "enhanced" LRC
     * that also carries word stamps (`[mm:ss.xx] <mm:ss.xx> word <mm:ss.xx> word`).
     */
    private fun parseLrc(lrcText: String): List<LyricsLine> {
        val raw = mutableListOf<Pair<Long, String>>()
        lrcText.lines().forEach { line ->
            val match = lineStampRegex.find(line) ?: return@forEach
            val ts = toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val body = line.substring(match.range.last + 1).trim()
            if (body.replace(wordStampRegex, "").isNotBlank()) raw.add(ts to body)
        }
        raw.sortBy { it.first }

        return raw.mapIndexed { i, (ts, body) ->
            val text = body.replace(wordStampRegex, "").replace(whitespaceRegex, " ").trim()
            val end = lineEnd(ts, text, raw.getOrNull(i + 1)?.first)
            val words = parseEnhancedWords(body, end) ?: estimateWords(text, ts, end)
            LyricsLine(timestampMs = ts, text = text, endMs = end, words = words)
        }
    }

    /**
     * When the line ends. Right up to the next line if that is close; otherwise a length-based
     * estimate, so a long instrumental gap doesn't stretch the last words of a line across it.
     */
    private fun lineEnd(start: Long, text: String, nextStart: Long?): Long {
        val estimate = 400L + text.length * 85L
        val gap = nextStart?.let { it - start }
        val duration = when {
            gap == null -> estimate
            gap <= estimate * 3 / 2 -> gap
            else -> estimate
        }
        return start + duration.coerceAtLeast(300L)
    }

    /** Exact word timings from enhanced-LRC stamps, or null when the line has none. */
    private fun parseEnhancedWords(body: String, lineEnd: Long): List<LyricsWord>? {
        val stamps = wordStampRegex.findAll(body).toList()
        if (stamps.isEmpty()) return null
        val words = mutableListOf<LyricsWord>()
        stamps.forEachIndexed { i, m ->
            val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val next = stamps.getOrNull(i + 1)
            val end = next?.let { toMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } ?: lineEnd
            val segment = body.substring(m.range.last + 1, next?.range?.first ?: body.length)
            when {
                segment.isBlank() -> {
                    // A bare space between two stamps belongs to the previous word.
                    if (words.isNotEmpty() && segment.isNotEmpty()) {
                        val last = words.removeAt(words.lastIndex)
                        words.add(last.copy(text = last.text.trimEnd() + " "))
                    }
                }
                else -> words.add(LyricsWord(start, end.coerceAtLeast(start + 1), segment.trimStart()))
            }
        }
        return words.ifEmpty { null }
    }

    /**
     * Fallback when the source only has line timestamps: spread the line's duration over its
     * words in proportion to their length. Not exact, but it tracks singing closely enough.
     */
    private fun estimateWords(text: String, start: Long, end: Long): List<LyricsWord> {
        val parts = text.split(whitespaceRegex).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val weights = parts.map { maxOf(it.length, 2) }
        val totalWeight = weights.sum().toFloat()
        val span = (end - start).coerceAtLeast(1L)
        var cursor = start.toFloat()
        return parts.mapIndexed { i, part ->
            val wordStart = cursor.toLong()
            cursor += span * weights[i] / totalWeight
            val wordEnd = if (i == parts.lastIndex) end else cursor.toLong()
            LyricsWord(wordStart, wordEnd, if (i == parts.lastIndex) part else "$part ")
        }
    }

    /** Parses plain lyrics without timestamps. */
    private fun parsePlainLyrics(plainText: String): List<LyricsLine> {
        return plainText.lines()
            .filter { it.isNotBlank() }
            .map { LyricsLine(timestampMs = 0L, text = it.trim()) }
    }
}

/**
 * Collapsible lyrics card. Collapsed, it is just a header docked under the controls; expanded, it
 * fills the space it is given and shows the auto-scrolling, word-highlighted lyrics.
 *
 * [positionMs] is a lambda so only the parts that need the frame-by-frame position read it.
 */
@Composable
fun LyricsSection(
    lyricsState: LyricsState,
    positionMs: () -> Long,
    accent: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val fullHeight = maxHeight
        val cardHeight by animateDpAsState(
            targetValue = if (expanded) fullHeight else LyricsHeaderHeight,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "lyrics-height",
        )
        val chevron by animateFloatAsState(if (expanded) 0f else 180f, tween(250), label = "lyrics-chevron")

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(cardHeight)
                .clip(LyricsCardShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), LyricsCardShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(LyricsHeaderHeight)
                    .tapTarget(onToggle)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lyrics", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    PhosphorIcons.Light.CaretDown,
                    contentDescription = if (expanded) "Hide lyrics" else "Show lyrics",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = chevron },
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (lyricsState) {
                    is LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is LyricsState.Loaded -> {
                        if (lyricsState.lines.isEmpty()) {
                            LyricsEmptyState("No timed lyrics available")
                        } else {
                            LyricsList(
                                lines = lyricsState.lines,
                                isSynced = lyricsState.isSynced,
                                positionMs = positionMs,
                                accent = accent,
                                viewport = fullHeight - LyricsHeaderHeight,
                            )
                        }
                    }
                    is LyricsState.NotAvailable -> LyricsEmptyState(lyricsState.reason)
                    is LyricsState.Error -> LyricsEmptyState("Error: ${lyricsState.message}")
                }
            }
        }
    }
}

/** Empty state for lyrics. */
@Composable
private fun LyricsEmptyState(message: String) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                PhosphorIcons.Light.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Loads `res/font/lyrics.ttf` (it lives in core-designsystem). It is looked up by name at runtime,
 * so this module doesn't need a dependency on that module's R class. Falls back to the system
 * sans if the font can't be found.
 */
@Composable
private fun rememberLyricsFont(): FontFamily {
    val context = LocalContext.current
    return remember {
        runCatching {
            val id = context.resources.getIdentifier("lyrics", "font", context.packageName)
            FontFamily(ResourcesCompat.getFont(context, id)!!)
        }.getOrDefault(FontFamily.SansSerif)
    }
}

/** Auto-scrolling list. The active line sits in the upper-middle of the card. */
@Composable
private fun LyricsList(
    lines: List<LyricsLine>,
    isSynced: Boolean,
    positionMs: () -> Long,
    accent: Color,
    viewport: Dp,
) {
    val font = rememberLyricsFont()
    val listState = rememberLazyListState()

    // One stable reader for every line, so lines whose state didn't change skip recomposition
    // even though the caller's lambda instance may change.
    val latestPosition by rememberUpdatedState(positionMs)
    val readPosition = remember { { latestPosition() } }

    val activeIndex by remember(lines, isSynced) {
        derivedStateOf { if (isSynced) activeLineIndex(lines, readPosition()) else -1 }
    }

    LaunchedEffect(lines) { listState.scrollToItem(0) }
    LaunchedEffect(activeIndex) {
        // Don't fight the user while they're scrolling around manually.
        if (activeIndex >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem(activeIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().fadingEdges(),
        contentPadding = PaddingValues(
            start = 22.dp,
            end = 22.dp,
            top = if (isSynced) viewport * 0.28f else 8.dp,
            bottom = if (isSynced) viewport * 0.60f else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            LyricLine(
                line = line,
                isSynced = isSynced,
                isActive = index == activeIndex,
                isPast = index < activeIndex,
                readPosition = readPosition,
                accent = accent,
                font = font,
            )
        }
    }
}

/**
 * One line. Words already sung are the accent color, the word being sung fades into it as it is
 * sung, and words still to come stay dim. Only the active line reads the position, so only it
 * recomposes every frame.
 */
@Composable
private fun LyricLine(
    line: LyricsLine,
    isSynced: Boolean,
    isActive: Boolean,
    isPast: Boolean,
    readPosition: () -> Long,
    accent: Color,
    font: FontFamily,
) {
    val style = TextStyle(
        fontFamily = font,
        fontWeight = FontWeight.Normal, // the bundled file is already SemiBold; don't let Compose fake-bold it
        fontSize = 22.sp,
        lineHeight = 30.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )

    if (!isSynced || line.words.isEmpty()) {
        Text(
            text = line.text,
            color = Color.White.copy(alpha = 0.85f),
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val lineAlpha by animateFloatAsState(if (isActive) 1f else 0.6f, tween(300), label = "lyric-line-alpha")
    val unsung = Color.White.copy(alpha = 0.6f)
    val now = when {
        isActive -> readPosition()
        isPast -> Long.MAX_VALUE / 2 // every word done
        else -> -1_000_000L          // no word started
    }
    val text = buildAnnotatedString {
        line.words.forEach { word ->
            val span = (word.endMs - word.startMs).coerceAtLeast(1L)
            val p = ((now - word.startMs).toFloat() / span).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = lerp(unsung, accent, p))) { append(word.text) }
        }
    }
    Text(
        text = text,
        style = style,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = lineAlpha },
    )
}

/** Index of the last line that has started at [positionMs], or -1 before the first line. */
private fun activeLineIndex(lines: List<LyricsLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.lastIndex
    var answer = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timestampMs <= positionMs) {
            answer = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return answer
}

/** Fades the top and bottom edges of a scrolling list into the card. */
private fun Modifier.fadingEdges(fade: Float = 0.12f): Modifier = this
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