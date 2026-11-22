package com.gamut.feature.playerui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gamut.core.designsystem.TablerIcons
import com.gamut.core.player.MusicPlayer
import com.gamut.core.player.VideoStream
import com.gamut.feature.playerui.components.DepthIcon
import com.gamut.feature.playerui.components.TimeRow
import com.gamut.feature.playerui.components.bounceTap
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The picture's shape in its box. */
private const val BoxAspect = 16f / 9f

/** How long the on-video controls stay up while the song plays, with no touch. */
private const val ControlsHideMs = 3_500L

/** Double tapping the left or right half of the picture jumps this far. */
private const val SkipMs = 10_000L

private val BoxCorner = 18.dp

private val OnVideoText =
    Shadow(color = Color.Black.copy(alpha = 0.45f), blurRadius = 8f)

/**
 * The music video as a box that sits upright on the page (the "Boxed" video view), and as the
 * fullscreen landscape player when [expanded].
 *
 * It is one composition for both: expanding only changes this box's size, so the decoder, the audio sync
 * and the picture carry on without a reload. Collapsed, the box is placed exactly over [slot], the room the
 * page keeps for it under the top bar (the page is laid out around that room, so the picture needs no
 * layout of its own). It is drawn above the page; the page never overlaps the slot.
 *
 * [slot] may move (the page scrolls the box away with the rest of it). [viewportTop] is the top edge, in root
 * coordinates, of the area the page scrolls in: anything of the box above it is cut off, drawing and touches
 * alike, so a box scrolled up never covers the top bar.
 *
 * [controls] is drawn over the picture, inside the box's rounded clip.
 */
@Composable
internal fun BoxedVideoPlayer(
    stream: VideoStream,
    player: MusicPlayer,
    active: Boolean,
    speed: Float,
    onFailed: () -> Boolean,
    onRendered: () -> Unit,
    expanded: Boolean,
    slot: () -> Rect?,
    viewportTop: () -> Float,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    controls: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val corner by animateFloatAsState(if (expanded) 0f else BoxCorner.value, tween(200), label = "boxedVideoCorner")

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer {
                // Expanded, the video owns the screen; otherwise it is cut off where the page's scroll area begins.
                clip = !expanded
                shape = if (expanded) RectangleShape else TopCutShape(viewportTop() - origin.y)
            },
    ) {
        Box(
            if (expanded) {
                Modifier.fillMaxSize()
            } else {
                Modifier.placedOver(slot) { origin }
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (expanded) 1f else reveal() }
                    .clip(RoundedCornerShape(corner.dp))
                    .background(Color.Black),
            ) {
                VideoHero(
                    stream = stream,
                    player = player,
                    active = active,
                    speed = speed,
                    onFailed = onFailed,
                    onRendered = onRendered,
                    mode = HeroMode.Boxed,
                    modifier = Modifier.fillMaxSize(),
                )
                controls()
            }
        }
    }
}

/** A rectangle with its top [top] pixels cut off, in the shape's own coordinates. */
private data class TopCutShape(val top: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cut = top.coerceIn(0f, size.height)
        return Outline.Rectangle(Rect(0f, cut, size.width, size.height))
    }
}

/**
 * Lays its child out at [slot]'s size and puts it at [slot]'s place, measured from the host's [origin].
 * Both are read inside layout, so a slot that moves or resizes (the page animating in) re-lays the box
 * out without recomposing anything. Until the page has reported its slot the box is kept out of the way.
 */
private fun Modifier.placedOver(
    slot: () -> Rect?,
    origin: () -> Offset,
): Modifier =
    layout { measurable, constraints ->
        val room = slot()
        if (room == null || room.width < 1f || room.height < 1f) {
            layout(constraints.maxWidth, constraints.maxHeight) {}
        } else {
            val placeable =
                measurable.measure(Constraints.fixed(room.width.roundToInt(), room.height.roundToInt()))
            val from = origin()
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((room.left - from.x).roundToInt(), (room.top - from.y).roundToInt())
            }
        }
    }

/**
 * Controls drawn over the boxed video: tap shows or hides them, a double tap on either half skips ten
 * seconds, and they hide on their own while the song plays. Collapsed it is a compact overlay (transport in
 * the middle, scrubber and the expand button along the bottom); expanded it becomes a landscape player with
 * the title up top and a collapse button.
 */
@Composable
internal fun BoxScope.BoxedVideoControls(
    expanded: Boolean,
    isPlaying: Boolean,
    loading: Boolean,
    title: String,
    artist: String,
    progress: () -> Float,
    positionMs: () -> Long,
    durationMs: Long,
    onScrub: (Float?) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    var shown by remember { mutableStateOf(true) }
    // Bumped by every touch so the auto-hide countdown starts over.
    var touches by remember { mutableIntStateOf(0) }
    val currentProgress by rememberUpdatedState(progress)
    val currentSeek by rememberUpdatedState(onSeekFraction)
    val currentDuration by rememberUpdatedState(durationMs)

    LaunchedEffect(shown, isPlaying, touches, expanded) {
        if (shown && isPlaying) {
            delay(ControlsHideMs)
            shown = false
        }
    }
    // Bring the controls back for the moment the box changes size.
    LaunchedEffect(expanded) {
        shown = true
        touches++
    }

    val visibility by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "boxedVideoControls")

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        shown = !shown
                        touches++
                    },
                    onDoubleTap = { at ->
                        val length = currentDuration
                        if (length > 0L) {
                            val step = SkipMs.toFloat() / length
                            val direction = if (at.x < size.width / 2f) -1f else 1f
                            currentSeek((currentProgress() + direction * step).coerceIn(0f, 1f))
                        }
                        shown = true
                        touches++
                    },
                )
            },
    ) {
        if (visibility > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = visibility }) {
                // Top and bottom shades keep white controls readable over bright footage.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.4f)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))),
                )

                // Play / pause only: previous and next are left off the video (a double tap skips ten seconds).
                MeshTransport(
                    isPlaying = isPlaying,
                    loading = loading,
                    onPrevious = null,
                    onTogglePlay = {
                        touches++
                        onTogglePlay()
                    },
                    onNext = null,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(if (expanded) 0.4f else 0.62f),
                )

                if (expanded) {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DepthIcon(
                            TablerIcons.Collapse,
                            "Exit full screen",
                            Modifier.bounceTap(onClick = onToggleExpand).padding(8.dp),
                            size = 24.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, shadow = OnVideoText),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                artist,
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall.copy(shadow = OnVideoText),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 28.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        MeshScrubber(progress = progress, onScrub = onScrub, onSeekFraction = onSeekFraction)
                        TimeRow(positionMs, durationMs)
                    }
                } else {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, bottom = 4.dp),
                    ) {
                        Box(Modifier.padding(end = 8.dp)) {
                            MeshScrubber(progress = progress, onScrub = onScrub, onSeekFraction = onSeekFraction)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).padding(end = 4.dp)) { TimeRow(positionMs, durationMs) }
                            DepthIcon(
                                TablerIcons.Expand,
                                "Full screen",
                                Modifier.bounceTap(onClick = onToggleExpand).padding(8.dp),
                                size = 20.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * While [enabled] the screen is turned to landscape and the system bars are tucked away (a swipe from the
 * edge brings them back for a moment). Leaving it, or leaving the player, puts the orientation and the
 * bars back the way they were.
 */
@Composable
internal fun ExpandedVideoWindow(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val activity = view.context.findActivity()
        if (!enabled || activity == null) return@DisposableEffect onDispose {}

        val previous = activity.requestedOrientation
        val controller = WindowCompat.getInsetsController(activity.window, view)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity.requestedOrientation = previous
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
