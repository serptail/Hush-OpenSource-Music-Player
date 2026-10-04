package com.gamut.feature.playerui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
import com.adamglin.phosphoricons.light.RepeatOnce
import com.adamglin.phosphoricons.light.Shuffle
import com.adamglin.phosphoricons.light.SkipBack
import com.adamglin.phosphoricons.light.SkipForward
import com.adamglin.phosphoricons.light.SpeakerHigh
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicMaterials
import com.gamut.core.designsystem.AcrylicSurface
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.RepeatMode as PlaybackRepeatMode
import com.gamut.core.model.Track
import com.gamut.core.player.MusicPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.json.JSONArray
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
private val CoverCornerRadius = 30.dp   // same corner as the album cards on Discover
private val BackgroundBlur = 24.dp   // how hard the cover is blurred behind the screen (acrylic wants it soft, not recognisable)
private val MaxCoverSize = 360.dp   // upper bound only; the real size comes from the space available (the width between prev / next)
private val RingGap = 3.5.dp     // same gap distance as before, now measured outward instead of inward
private val RingStrokeWidth = 1.5.dp
private val RingOutlineWidth = 0.5.dp   // extremely thin gray outline, per side, around the progress line
private val RingOutlineColor = Color(0xFF8C8C8C)
private const val PausedCoverScale = 0.96f   // the cover settles a touch smaller while paused (set to 1f to turn off)

// Scrubbing visuals (shown while the progress line is being dragged). Everything here has to fit in
// the space between the ring and the edge of its box: RingInset - (RingGap + stroke/2) ~= 13.75dp.
private val WaveBarWidth = 1.5.dp
private val WaveBarPitch = 4.dp          // distance between bar centers along the line
private val WaveStartOffset = 2.5.dp     // gap between the line's center and the base of a bar
private val WaveMinLength = 2.dp         // quietest bar
private val WaveMaxLength = 10.dp        // tallest bar; base + length + cap (~13.25dp) stays inside the box
private val ScrubKnobRadius = 4.dp
private val ScrubDashHalfLength = 3.dp
private val ScrubDashWidth = 2.dp
private val FavoriteColor = Color(0xFFFF4D6D)   // the heart when the track is a favorite
private val RingClearance = 36.dp     // total (both sides) room for gap + stroke + waveform outside the cover
private val RingInset = RingClearance / 2  // the same room, per side

@Composable
fun PlayerScreen(
    player: MusicPlayer,
    onBack: () -> Unit = {},
    /** ISRCs of the favorited tracks; the heart is filled in when the current track is in this set. */
    favoriteIsrcs: Set<String> = emptySet(),
    onToggleFavorite: (Track) -> Unit = {},
    /** The "more" (three dots) button: e.g. open the add-to-playlist sheet for this track. */
    onMore: (Track) -> Unit = {},
) {
    TransparentStatusBar()
    val state by player.playbackState.collectAsState()
    val track = (state as? PlaybackState.Playing)?.track ?: (state as? PlaybackState.Paused)?.track
    val position = (state as? PlaybackState.Playing)?.positionMs ?: (state as? PlaybackState.Paused)?.positionMs ?: 0L
    val isPlaying = state is PlaybackState.Playing
    val loadProgress by player.loadProgress.collectAsState()
    val duration = (track?.durationMs ?: 0L).coerceAtLeast(1L)
    val shuffleOn by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val queueState by player.queue.collectAsState()
    var showQueue by remember { mutableStateOf(false) }

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
    LaunchedEffect(isPlaying, basePosition, duration) {
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
    // While the progress line is being dragged, the line (and the time label) follow the finger and the
    // real seek happens once, on release. Everything below is read inside lambdas, so the per-frame
    // position only invalidates the small pieces that draw it instead of recomposing the whole screen.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val progressProvider = remember(duration) { { scrubFraction ?: (smoothPositionMs / duration).coerceIn(0f, 1f) } }
    val positionProvider = remember(duration) { { (progressProvider() * duration).toLong() } }

    // Lyrics state
    var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
    var lyricsExpanded by remember { mutableStateOf(true) }
    // Highlight color for the lyrics, pulled from the cover art.
    // Computed once per track (this screen recomposes every frame while playing).
    val rawArt = track?.albumArtUrl
    val artUrl = remember(rawArt) { backdropArtUrl(rawArt) }   // blurred backdrops + accent
    val coverUrl = remember(rawArt) { squareArtUrl(rawArt) }   // the cover itself
    val accent = rememberCoverAccent(artUrl)
    val waveform = rememberWaveform(track)

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

    // Base = the same flat fill as Discover / Library. With a track loaded, an acrylic surface goes on top of
    // it: the cover heavily blurred, a frosted tint in the base color, a faint white veil, and a diagonal
    // sheen. Nothing loaded: none of those layers are drawn, so it is just the flat fill.
    AcrylicHost(Modifier.fillMaxSize().background(CoverPalette.Neutral.base)) {
        if (artUrl != null) {
            // One backdrop (art + tint + veil + sheen) is the acrylic source, so the lyrics card frosts exactly what
            // is behind it instead of the raw, un-tinted art.
            Box(Modifier.fillMaxSize().acrylicSource()) {
            AsyncImage(
                model = rememberFadeRequest(artUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }   // overscan, so the blur never shows an edge
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BackgroundBlur) else Modifier),
            )
            // Frosted tint: the app's base color, translucent, denser toward the bottom where the controls are.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            CoverPalette.Neutral.base.copy(alpha = 0.50f),
                            CoverPalette.Neutral.base.copy(alpha = 0.78f),
                        ),
                    ),
                ),
            )
            // Luminosity veil: a hint of white, the milky depth that makes it read as acrylic and not just dimmed.
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.05f)))
            // Diagonal sheen, brightest top-left where light would hit the slab.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.06f)),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 1700f),
                    ),
                ),
            )
            }
        }

        // Nothing playing (or still loading): no controls, no text, just a black record turning in the
        // middle of the flat background. As soon as a track arrives the real player takes over.
        if (track == null) {
            LoadingVinyl(if (state is PlaybackState.Loading) loadProgress else null, Modifier.align(Alignment.Center))
            return@AcrylicHost
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        ) {
            TopBar(onBack, onMore = { onMore(track) }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BouncingText(
                        text = track.title,
                        style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.3).sp),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(2.dp))
                    BouncingText(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
                val isFavorite = track.isrc in favoriteIsrcs
                DepthIcon(
                    PhosphorIcons.Light.Heart,
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    Modifier.bounceTap(onClick = { onToggleFavorite(track) }).padding(4.dp),
                    tint = if (isFavorite) FavoriteColor else Color.White,
                    size = 26.dp,
                )
            }

            // Gap between the title row and the cover: bigger = cover + controls sit lower / more central.
            Spacer(Modifier.height(40.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                DepthIcon(PhosphorIcons.Light.SkipBack, "Previous", Modifier.bounceTap(onClick = player::skipPrevious).padding(8.dp), size = 28.dp)
                ArtworkWithRing(
                    modifier = Modifier.weight(1f),
                    url = coverUrl,
                    progress = progressProvider,
                    onScrub = { scrubFraction = it },
                    isPlaying = isPlaying,
                    enabled = true,
                    waveform = waveform,
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
                DepthIcon(PhosphorIcons.Light.SkipForward, "Next", Modifier.bounceTap(onClick = player::skipNext).padding(8.dp), size = 28.dp)
            }

            Spacer(Modifier.height(14.dp))
            TimeRow(positionProvider, track.durationMs)

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DepthIcon(
                    PhosphorIcons.Light.Shuffle,
                    if (shuffleOn) "Shuffle on" else "Shuffle off",
                    Modifier.bounceTap(onClick = { player.setShuffle(!shuffleOn) }).padding(8.dp),
                    tint = if (shuffleOn) accent else Color.White.copy(alpha = 0.85f),
                    size = 22.dp,
                )
                DepthIcon(
                    if (repeatMode == PlaybackRepeatMode.ONE) PhosphorIcons.Light.RepeatOnce else PhosphorIcons.Light.Repeat,
                    when (repeatMode) {
                        PlaybackRepeatMode.OFF -> "Repeat off"
                        PlaybackRepeatMode.ALL -> "Repeat all"
                        PlaybackRepeatMode.ONE -> "Repeat one"
                    },
                    Modifier.bounceTap(onClick = {
                        player.setRepeatMode(
                            when (repeatMode) {
                                PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                                PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                                PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                            },
                        )
                    }).padding(8.dp),
                    tint = if (repeatMode != PlaybackRepeatMode.OFF) accent else Color.White.copy(alpha = 0.85f),
                    size = 22.dp,
                )
                DepthIcon(PhosphorIcons.Light.Queue, "Queue", Modifier.bounceTap(onClick = { showQueue = true }).padding(8.dp), tint = Color.White.copy(alpha = 0.85f), size = 22.dp)
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

        if (showQueue) {
            QueueSheet(
                queue = queueState,
                accent = accent,
                onDismiss = { showQueue = false },
                onPlay = player::skipTo,
                onRemove = player::removeFromQueue,
            )
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DepthIcon(PhosphorIcons.Light.CaretDown, "Collapse", Modifier.tapTarget(onClick = onBack).padding(8.dp))
        Spacer(Modifier.weight(1f))
        DepthIcon(PhosphorIcons.Light.SpeakerHigh, "Volume", Modifier.tapTarget(onClick = {}).padding(8.dp))
        DepthIcon(PhosphorIcons.Light.Equalizer, "Equalizer", Modifier.tapTarget(onClick = {}).padding(8.dp))
        DepthIcon(PhosphorIcons.Light.DotsThreeVertical, "More", Modifier.tapTarget(onClick = onMore).padding(8.dp))
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
    progress: () -> Float,
    isPlaying: Boolean,
    enabled: Boolean,
    waveform: FloatArray,
    onTogglePlay: () -> Unit,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    // Stretched + exaggerated once, so loud and quiet parts of the song look clearly different.
    val shapedWaveform = remember(waveform) { shapeWaveform(waveform) }
    var tapTrigger by remember { mutableIntStateOf(0) }
    var iconVisible by remember { mutableStateOf(true) }
    var ringSize by remember { mutableStateOf(IntSize.Zero) } // measured size of the ring box
    var scrubbing by remember { mutableStateOf(false) }
    // 0 -> 1 while the line is being dragged; drives the waveform, dash and knob.
    val scrub by animateFloatAsState(if (scrubbing) 1f else 0f, tween(180), label = "scrub")

    // The gesture block below is keyed on (enabled, ringSize) only, so without these it would keep
    // calling the first lambdas it ever saw (stale `duration`, stale `isPlaying`).
    val currentToggle by rememberUpdatedState(onTogglePlay)
    val currentSeek by rememberUpdatedState(onSeekFraction)
    val currentScrub by rememberUpdatedState(onScrub)
    val haptics = LocalHapticFeedback.current

    // Settles slightly smaller while paused and lifts back when playback starts.
    val coverScale by animateFloatAsState(
        targetValue = if (enabled && !isPlaying) PausedCoverScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "cover-scale",
    )

    LaunchedEffect(isPlaying, tapTrigger) {
        iconVisible = true
        if (isPlaying) {
            delay(1200.milliseconds)
            iconVisible = false
        }
    }
    val iconAlpha by animateFloatAsState(if (iconVisible) 1f else 0f, animationSpec = tween(220), label = "icon-alpha")

    // YouTube thumbnails are 16:9 frames, so for them the cover takes the thumbnail's own shape
    // instead of a square (no bars, nothing cropped). The aspect comes from the loaded image, so
    // odd sizes are exact too; it is 16:9 until the image has loaded. Everything else stays square.
    val youtubeThumbs = remember(url) { url?.let(::youtubeThumbCandidates) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val shownUrl = youtubeThumbs?.get(attempt.coerceAtMost(youtubeThumbs.lastIndex)) ?: url
    var loadedAspect by remember(shownUrl) { mutableStateOf<Float?>(null) }
    val targetAspect = if (enabled && youtubeThumbs != null) (loadedAspect ?: (16f / 9f)).coerceIn(1f, 2f) else 1f
    val aspect by animateFloatAsState(targetAspect, tween(250), label = "cover-aspect")

    // The caller decides how much width we get: the space between the prev/next
    // icons. The ring box fills that width up to a maximum; its height follows the cover's
    // aspect (square by default). Every inner size derives from it, so nothing depends on a
    // fixed dp value.
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = MaxCoverSize + RingClearance)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val w = constraints.maxWidth
                    val clearance = RingClearance.roundToPx()
                    val h = ((w - clearance) / aspect).roundToInt() + clearance
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer { scaleX = coverScale; scaleY = coverScale },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(RingInset)
                    .fillMaxSize()
                    // One soft drop shadow: the same card look as Discover's album cards. No rim, no glow.
                    .shadow(elevation = 14.dp, shape = CoverShape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
                    .clip(CoverShape),
                contentAlignment = Alignment.Center,
            ) {
                if (enabled) {
                    // The whole image is shown (Fit) in a box of its own shape, so nothing is cut off.
                    // If it still doesn't fill the box (an outlier), a heavily blurred copy of it fills
                    // the space around it instead of bars.
                    AsyncImage(
                        model = shownUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(28.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                    SubcomposeAsyncImage(
                        model = shownUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        loading = { Box(Modifier.fillMaxSize()) },   // empty while loading: a light placeholder reads as a white flash on entry
                        error = { ArtworkPlaceholder() },
                        // A YouTube size that doesn't exist for this video: try the next one.
                        onError = { if (youtubeThumbs != null && attempt < youtubeThumbs.lastIndex) attempt++ },
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            if (size.width > 0f && size.height > 0f) loadedAspect = size.width / size.height
                        },
                    )
                }
                // No circle behind the icon anymore, just the bare glyph (a bit larger so it
                // still reads on top of the artwork).
                if (enabled) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .graphicsLayer { alpha = iconAlpha },
                        contentAlignment = Alignment.Center,
                    ) {
                        DepthIcon(
                            if (isPlaying) PhosphorIcons.Light.Pause else PhosphorIcons.Light.Play,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            size = 44.dp,
                        )
                    }
                }
            }

            // Progress line, sitting outside the cover. It is always white, with a
            // hairline gray outline so it stays visible against pale covers.
            // Drawn with drawWithCache: the ring's geometry (path, measurements, paints, waveform bars) is
            // built once per size change instead of every frame, and `progress` is only read while drawing,
            // so a moving line redraws this layer alone and never recomposes anything.
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { ringSize = it }
                    .pointerInput(enabled, ringSize) {
                        if (!enabled || ringSize.width == 0) return@pointerInput
                        val marginPx = RingInset.toPx()
                        val coverW = ringSize.width - RingClearance.toPx()
                        val coverH = ringSize.height - RingClearance.toPx()
                        val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                        val path = outerRingPath(coverW, coverH, marginPx, gapPx, CoverCornerRadius.toPx())
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val total = measure.length
                        val samples = 300
                        val pts = List(samples) { measure.getPosition(total * it / samples) }

                        // The line is only ~1.5dp thick, so it gets a generous touch band. The
                        // band stops short of the cover's interior so taps on the artwork itself
                        // still mean play/pause.
                        val coverInner = Rect(marginPx, marginPx, marginPx + coverW, marginPx + coverH)
                            .deflate(10.dp.toPx())
                        val hitRadius = 20.dp.toPx()
                        fun nearLine(p: Offset) =
                            !coverInner.contains(p) && pts.minOf { hypot(it.x - p.x, it.y - p.y) } < hitRadius
                        // Where along the line (0..1) the finger is, from the closest sampled point.
                        fun fractionAt(p: Offset): Float {
                            var best = 0; var bestD = Float.MAX_VALUE
                            pts.forEachIndexed { i, o ->
                                val d = hypot(o.x - p.x, o.y - p.y)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return (best.toFloat() / samples).coerceIn(0f, 1f)
                        }

                        // detectDragGestures only reacts once the finger has moved past the touch
                        // slop, so a plain tap on the line did nothing. Handle down/drag/up by
                        // hand: a touch on the line grabs it immediately and follows the finger.
                        // While held, only the preview moves; the player is asked to seek once, on
                        // release (a seek per touch event makes the audio stutter and the line creep
                        // away from a resting finger while the song keeps playing).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (nearLine(down.position)) {
                                down.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scrubbing = true
                                var last = fractionAt(down.position)
                                try {
                                    currentScrub(last)
                                    val released = drag(down.id) { change ->
                                        change.consume()
                                        val f = fractionAt(change.position)
                                        // The line is a closed loop: crossing its start/end seam pins the
                                        // thumb to that end instead of teleporting it to the other one.
                                        last = if (abs(f - last) > 0.5f) (if (last > 0.5f) 1f else 0f) else f
                                        currentScrub(last)
                                    }
                                    if (released) currentSeek(last)
                                } finally {
                                    scrubbing = false
                                    currentScrub(null)
                                }
                            } else {
                                // This layer sits above the cover, so it also owns the cover tap.
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentToggle()
                                    tapTrigger++
                                }
                            }
                        }
                    }
                    .drawWithCache {
                        // Nothing playing: no progress line at all.
                        if (!enabled) return@drawWithCache onDrawBehind {}
                        val marginPx = RingInset.toPx()
                        val coverW = size.width - RingClearance.toPx()
                        val coverH = size.height - RingClearance.toPx()
                        val gapPx = RingGap.toPx() + RingStrokeWidth.toPx() / 2f
                        val path = outerRingPath(coverW, coverH, marginPx, gapPx, CoverCornerRadius.toPx())
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val total = measure.length
                        val strokePx = RingStrokeWidth.toPx()
                        val outlinePx = RingOutlineWidth.toPx()

                        // Reused every frame (no per-frame allocation).
                        val filled = Path()
                        // Gray outline underneath: same path, slightly thicker than the white line.
                        val outlinePaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx + outlinePx * 2f
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = RingOutlineColor.toArgb()
                            setShadowLayer(3.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.argb(80, 0, 0, 0))
                        }
                        val linePaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            color = android.graphics.Color.WHITE
                        }

                        // Scrub visuals. Bar positions, directions and heights never change for a given
                        // size / track, so they are worked out once here.
                        val barW = WaveBarWidth.toPx()
                        val startOff = WaveStartOffset.toPx()
                        val minLen = WaveMinLength.toPx()
                        val maxLen = WaveMaxLength.toPx()
                        val barCount = (total / WaveBarPitch.toPx()).toInt().coerceAtLeast(1)
                        val barX = FloatArray(barCount)
                        val barY = FloatArray(barCount)
                        val barNx = FloatArray(barCount)
                        val barNy = FloatArray(barCount)
                        val barAmp = FloatArray(barCount)
                        for (i in 0 until barCount) {
                            val dist = total * (i + 0.5f) / barCount
                            val pos = measure.getPosition(dist)
                            val tan = measure.getTangent(dist)
                            barX[i] = pos.x
                            barY[i] = pos.y
                            // The path runs clockwise, so (ty, -tx) points away from the cover.
                            barNx[i] = tan.y
                            barNy[i] = -tan.x
                            barAmp[i] = waveformAt(shapedWaveform, i, barCount)
                        }
                        val dashHalfLength = ScrubDashHalfLength.toPx()
                        val dashWidth = ScrubDashWidth.toPx()
                        val knobRadius = ScrubKnobRadius.toPx()
                        val startPoint = measure.getPosition(0f)

                        onDrawBehind {
                            val fill = progress()
                            val reveal = scrub

                            // Faint full-length track (with its hairline outline), so the whole line is
                            // visible before it fills.
                            drawPath(
                                path = path,
                                color = RingOutlineColor.copy(alpha = 0.45f),
                                style = Stroke(width = strokePx + outlinePx * 2f),
                            )
                            drawPath(
                                path = path,
                                color = Color.White.copy(alpha = 0.22f),
                                style = Stroke(width = strokePx),
                            )

                            if (fill > 0f) {
                                filled.rewind()
                                measure.getSegment(0f, total * fill, filled, true)
                                drawIntoCanvas { canvas ->
                                    val androidPath = filled.asAndroidPath()
                                    canvas.nativeCanvas.drawPath(androidPath, outlinePaint)
                                    canvas.nativeCanvas.drawPath(androidPath, linePaint)
                                }
                            }

                            // While dragging: the track's waveform along the outside of the line, a small
                            // dash where the track starts, and a knob at the current position.
                            if (reveal > 0.01f) {
                                for (i in 0 until barCount) {
                                    val f = (i + 0.5f) / barCount
                                    val len = (minLen + (maxLen - minLen) * barAmp[i]) * reveal
                                    drawLine(
                                        color = Color.White.copy(alpha = (if (f <= fill) 0.95f else 0.35f) * reveal),
                                        start = Offset(barX[i] + barNx[i] * startOff, barY[i] + barNy[i] * startOff),
                                        end = Offset(barX[i] + barNx[i] * (startOff + len), barY[i] + barNy[i] * (startOff + len)),
                                        strokeWidth = barW,
                                        cap = StrokeCap.Round,
                                    )
                                }

                                // Start dash: the path begins at the top-center, where the line is horizontal,
                                // so the dash is simply vertical.
                                val half = dashHalfLength * reveal
                                drawLine(
                                    color = Color.White,
                                    start = Offset(startPoint.x, startPoint.y - half),
                                    end = Offset(startPoint.x, startPoint.y + half),
                                    strokeWidth = dashWidth,
                                    cap = StrokeCap.Round,
                                )

                                // Knob: plain filled circle, no outline.
                                drawCircle(
                                    color = Color.White,
                                    radius = knobRadius * reveal,
                                    center = measure.getPosition(total * fill),
                                )
                            }
                        }
                    },
            )
        }
    }
}

/**
 * Builds the outward progress-line path (the cover may be wider than tall). It is the cover's rectangle, grown
 * outward by [gapPx] on every side. The corner radius grows by the same
 * amount, so the curve stays concentric with the cover's own corners.
 *
 * The path is built by hand so it starts at the top-center and runs clockwise
 * (right along the top edge, then down the right side, ...). addRoundRect's
 * winding is not something to rely on, and the progress fill, seek math and
 * waveform all depend on this order.
 */
private fun outerRingPath(coverW: Float, coverH: Float, marginPx: Float, gapPx: Float, coverRadiusPx: Float): Path {
    val left = marginPx - gapPx
    val top = marginPx - gapPx
    val right = marginPx + coverW + gapPx
    val bottom = marginPx + coverH + gapPx
    val r = coverRadiusPx + gapPx
    val d = r * 2f
    val cx = (left + right) / 2f
    return Path().apply {
        moveTo(cx, top)
        lineTo(right - r, top)
        arcTo(Rect(right - d, top, right, top + d), -90f, 90f, false)
        lineTo(right, bottom - r)
        arcTo(Rect(right - d, bottom - d, right, bottom), 0f, 90f, false)
        lineTo(left + r, bottom)
        arcTo(Rect(left, bottom - d, left + d, bottom), 90f, 90f, false)
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + d, top + d), 180f, 90f, false)
        close()
    }
}

/** Height of bar [index] of [count] (0..1): the loudest waveform sample that falls in its slice. */
private fun waveformAt(wave: FloatArray, index: Int, count: Int): Float {
    if (wave.isEmpty()) return 0f
    val from = (index.toLong() * wave.size / count).toInt().coerceIn(0, wave.lastIndex)
    val to = ((index + 1).toLong() * wave.size / count).toInt().coerceIn(from + 1, wave.size)
    var m = 0f
    for (k in from until to) if (wave[k] > m) m = wave[k]
    return m.coerceIn(0f, 1f)
}

/**
 * Real amplitude envelope of [track] (0..1 per sample, evenly spaced over its duration).
 *
 * TODO: wire this to actual audio data. Until it returns non-null, [rememberWaveform] shows a
 * placeholder shape so the scrub UI can be built and tuned.
 */
private suspend fun loadWaveform(track: Track): FloatArray? = null

/**
 * Makes differences visible: stretches the samples so the quietest one is 0 and the loudest is 1,
 * then squares-ish them (power > 1) so peaks (high notes / loud vocals) stand tall while
 * everything else stays low. Without this, most bars land in the same mid range and look alike.
 */
private fun shapeWaveform(wave: FloatArray): FloatArray {
    if (wave.isEmpty()) return wave
    var lo = Float.MAX_VALUE
    var hi = 0f
    for (v in wave) {
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    val range = (hi - lo).coerceAtLeast(1e-3f)
    return FloatArray(wave.size) { ((wave[it] - lo) / range).coerceIn(0f, 1f).pow(1.7f) }
}

/** Deterministic stand-in waveform (same track -> same shape). NOT real audio data. */
private fun placeholderWaveform(seed: Int, count: Int = 240): FloatArray {
    val rnd = java.util.Random(seed.toLong())
    var prev = 0.5f
    return FloatArray(count) { i ->
        // Less smoothing than before, so neighbouring samples differ more.
        prev = prev * 0.2f + rnd.nextFloat() * 0.8f
        // Slow "sections" (quiet verse / loud chorus) plus the occasional spike.
        val section = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(i * 0.07f + (seed % 7)))
        val spike = if (rnd.nextFloat() < 0.08f) 1.6f else 1f
        (prev * section * spike).coerceIn(0.03f, 1f)
    }
}

@Composable
private fun rememberWaveform(track: Track?): FloatArray {
    val seed = "${track?.title}|${track?.artist}".hashCode()
    var data by remember(seed) { mutableStateOf(placeholderWaveform(seed)) }
    LaunchedEffect(seed) {
        val real = track?.let { loadWaveform(it) }
        if (real != null && real.isNotEmpty()) data = real
    }
    return data
}

/**
 * The "no song yet" / loading screen: one small black record turning in the exact middle of the flat background,
 * with a thin progress bar under it while a track is loading ([progress] null = no bar). The bar shows real
 * progress (see MusicPlayer.loadProgress), animated smoothly between updates.
 */
@Composable
private fun LoadingVinyl(progress: Float?, modifier: Modifier = Modifier) {
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
private fun PlayerSpinner(modifier: Modifier = Modifier, color: Color = Color.White, strokeWidth: Dp = 2.dp) {
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

/**
 * Elapsed / total time. Digits are tabular so the label doesn't jitter as they change, and it only
 * recomposes when the displayed second changes (not every frame).
 */
@Composable
private fun TimeRow(position: () -> Long, durationMs: Long) {
    val elapsedSecond by remember(position) { derivedStateOf { position() / 1_000 } }
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", letterSpacing = 0.4.sp)
    val color = Color.White.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(elapsedSecond * 1_000), color = color, style = style)
        Text(formatTime(durationMs), color = color, style = style)
    }
}

/** Image request that fades in when the URL changes (a new track) instead of popping in. */
@Composable
private fun rememberFadeRequest(url: String?, durationMs: Int = 600): ImageRequest {
    val context = LocalContext.current
    // The first image (already loaded elsewhere, e.g. in search) must appear instantly; only later
    // track changes fade. A fade from the dark base on entry shows up as a flicker.
    val firstUrl = remember { url }
    return remember(url) {
        ImageRequest.Builder(context).data(url).crossfade(if (url == firstUrl) 0 else durationMs).build()
    }
}

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
private val accentCache = HashMap<String, Color>()

/**
 * The most prominent *colorful* hue in the cover art, tuned to be readable on the dark glass
 * background. Falls back to white for grey / monochrome covers. Animates when the track changes.
 */
@Composable
private fun rememberCoverAccent(url: String?): Color {
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

// ==================== LYRICS SYSTEM ====================

private val LyricsTitleHeight = 36.dp        // the "Lyrics" title row that sits above the card
private val LyricsCollapsedHeight = 92.dp    // card height when folded: 14 + 28 + 8 + 28 + 14 (two lines)
private val PreviewPaddingV = 14.dp          // top/bottom padding of the folded snippet
private val PreviewLineGap = 8.dp            // gap between the two folded lines
private const val PreviewFadeStart = 0.3f    // fade begins at this fraction of line 2's height (top of the letters' body)
private const val PreviewFadeEnd = 0.75f     // fully invisible from here down (~ the baseline, so the bottom half of the letters is truly gone)
private val LyricsCardShape = RoundedCornerShape(14.dp)
private val AcrylicBlur = 72.dp              // how hard the art behind the lyrics card is blurred

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
 * Collapsible lyrics card with its "Lyrics" title outside, on top. Folded, the card shows a snippet
 * of the first lines fading out toward the bottom; expanded, it fills the space it is given and
 * shows the auto-scrolling, word-highlighted lyrics.
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
        val expandedCardHeight = (fullHeight - LyricsTitleHeight).coerceAtLeast(0.dp)
        val collapsedCardHeight = minOf(LyricsCollapsedHeight, expandedCardHeight)
        val cardHeight by animateDpAsState(
            targetValue = if (expanded) expandedCardHeight else collapsedCardHeight,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "lyrics-height",
        )
        val chevron by animateFloatAsState(if (expanded) 0f else 180f, tween(250), label = "lyrics-chevron")

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Title, outside (above) the card.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(LyricsTitleHeight)
                    .tapTarget(onToggle)
                    .padding(horizontal = 6.dp),
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

            // Real frosted glass: blurs the screen's cover backdrop (see acrylicSource above) behind the card.
            AcrylicSurface(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .shadow(
                        elevation = 14.dp,
                        shape = LyricsCardShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                        clip = false,
                    )
                    // Folded, tapping anywhere on the snippet opens it.
                    .tapTarget { if (!expanded) onToggle() },
                shape = LyricsCardShape,
                material = AcrylicMaterials.UltraThin,
            ) {
                Crossfade(targetState = expanded, animationSpec = tween(250), label = "lyrics-content") { isExpanded ->
                    if (!isExpanded) {
                        LyricsPreview(lyricsState)
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            when (lyricsState) {
                                is LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    PlayerSpinner(Modifier.size(26.dp), Color.White.copy(alpha = 0.75f))
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
                                            viewport = fullHeight - LyricsTitleHeight,
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
        }
    }
}

/**
 * An icon with a soft drop shadow: a blurred, dark copy sits just below the real one.
 * Kept subtle on purpose. [size] is the glyph size; put any padding / click handling in [modifier].
 */
@Composable
private fun DepthIcon(
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

/** Folded-card content: the first lyric up top, the lines under it fading out toward the bottom. */
@Composable
private fun LyricsPreview(lyricsState: LyricsState) {
    val font = rememberLyricsFont()
    when (lyricsState) {
        is LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSpinner(Modifier.size(22.dp), Color.White.copy(alpha = 0.75f))
        }
        is LyricsState.Loaded -> {
            if (lyricsState.lines.isEmpty()) {
                LyricsPreviewMessage("No timed lyrics available")
            } else {
                val style = TextStyle(
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                // Measured heights of the two lines, so the fade can be aimed at the second line
                // exactly (its own vertical middle), whatever the font size / wrapping is.
                val density = LocalDensity.current
                var firstLinePx by remember { mutableIntStateOf(0) }
                var secondLinePx by remember { mutableIntStateOf(0) }
                val secondTop = {
                    with(density) { (PreviewPaddingV + PreviewLineGap).toPx() } + firstLinePx
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .fadeOutBetween(
                            // Fully visible down to the top of line 2's letters...
                            start = { secondTop() + secondLinePx * PreviewFadeStart },
                            // ...and completely invisible from its baseline down.
                            end = { secondTop() + secondLinePx * PreviewFadeEnd },
                            hasSecondLine = { secondLinePx > 0 },
                        ),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = PreviewPaddingV),
                        verticalArrangement = Arrangement.spacedBy(PreviewLineGap),
                    ) {
                        lyricsState.lines.take(2).forEachIndexed { i, line ->
                            Text(
                                text = line.text,
                                color = Color.White.copy(alpha = 0.6f), // unhighlighted lyric color (same as unsung words in the expanded view); same on both lines: the fade alone does the cutting
                                style = style,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { if (i == 0) firstLinePx = it.height else secondLinePx = it.height },
                            )
                        }
                    }
                }
            }
        }
        is LyricsState.NotAvailable -> LyricsPreviewMessage(lyricsState.reason)
        is LyricsState.Error -> LyricsPreviewMessage("Error: ${lyricsState.message}")
    }
}

@Composable
private fun LyricsPreviewMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
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

    // The line being sung is full size and full brightness; lines already sung recede the most.
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isPast -> 0.4f
            else -> 0.6f
        },
        animationSpec = tween(300),
        label = "lyric-line-alpha",
    )
    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "lyric-line-scale",
    )
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
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = lineAlpha; scaleX = lineScale; scaleY = lineScale },
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

/**
 * Fades content out between two y positions (in px): fully visible above [start], completely gone
 * at and below [end]. The curve drops quickly right after [start] so the fade reads as strong /
 * deep instead of a lazy linear ramp. Does nothing while [hasSecondLine] is false (e.g. a song
 * with a single lyric line, or before the lines have been measured).
 */
private fun Modifier.fadeOutBetween(
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

/**
 * Makes the status bar transparent with light icons, so the screen's own background (color,
 * gradient, blurred art) runs behind it instead of a separate bar.
 */
@Suppress("DEPRECATION")
@Composable
private fun TransparentStatusBar() {
    // Intentionally empty: the window is configured once in MainActivity.onCreate. Changing decorFitsSystemWindows
    // / status bar appearance here, when the player opens, made the window re-layout and flash its (white)
    // background for a frame.
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Asks the art provider for a square, higher-resolution version where the URL format allows it
 * (Apple, Deezer, Google / YouTube Music, Cover Art Archive). Any other host is returned as is,
 * so this can never break an image, and non-square art from those is handled by the blurred fill.
 */
private fun squareArtUrl(url: String?, px: Int = 800): String? {
    if (url == null) return null
    return try {
        when {
            "mzstatic.com" in url ->
                url.replace(Regex("""/\d{2,4}x\d{2,4}(bb|cc|sr)?(-\d+)?\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)) {
                    "/${px}x${px}bb.${it.groupValues[3]}"
                }
            "dzcdn.net" in url ->
                url.replace(Regex("""/\d{2,4}x\d{2,4}-"""), "/${px}x${px}-")
            "googleusercontent.com" in url || "ggpht.com" in url ->
                url.replace(Regex("""=w\d+-h\d+"""), "=w$px-h$px").replace(Regex("""=s\d+"""), "=s$px")
            "coverartarchive.org" in url ->
                url.replace(Regex("""-(250|500)(\.\w+)$"""), "-1200$2")
            else -> url
        }
    } catch (_: Exception) {
        url
    }
}

private const val BounceSpeedDpPerSecond = 45f  // how fast an overlong title glides

/**
 * One-line text. If it is too long for its box it glides to the far end so everything gets shown,
 * then straight away glides back to the start, and repeats, like a ball bouncing between two walls.
 * Text that fits stays still.
 */
@Composable
private fun BouncingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
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

private val YouTubeThumb = Regex("""^(https?://[^/]*(?:ytimg\.com|img\.youtube\.com))/vi(?:_webp)?/([^/]+)/""")

/**
 * YouTube thumbnails are 16:9 frames. The default `hqdefault` one is a 4:3 image with black bars
 * baked in above and below, and for "Topic" art tracks the real cover is the centered square of the
 * frame. These are the bar-free 16:9 variants, best quality first; null if [url] is not a YouTube thumbnail.
 */
private fun youtubeThumbCandidates(url: String): List<String>? {
    val m = YouTubeThumb.find(url) ?: return null
    val base = m.groupValues[1]
    val id = m.groupValues[2]
    return listOf("hq720", "maxresdefault", "mqdefault").map { "$base/vi/$id/$it.jpg" }
}

/** Art for the blurred backdrops and the accent color: for YouTube, the small bar-free frame (always exists). */
private fun backdropArtUrl(url: String?): String? {
    if (url == null) return null
    val id = YouTubeThumb.find(url) ?: return url
    return "${id.groupValues[1]}/vi/${id.groupValues[2]}/mqdefault.jpg"
}