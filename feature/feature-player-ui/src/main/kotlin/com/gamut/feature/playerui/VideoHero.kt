package com.gamut.feature.playerui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.Track
import com.gamut.core.player.MusicPlayer
import com.gamut.core.player.VideoStream
import com.gamut.core.player.VideoStreamProvider
import com.gamut.core.player.VideoSyncPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Aspect assumed until the decoder reports the real one. */
private const val HeroAspect = 16f / 9f

/** Client hops a failing picture gets before the page gives up and shows artwork (BitChord allows three). */
private const val MAX_VIDEO_REFRESHES = 3

/**
 * How long a loaded stream gets, while the page is on screen and playing, to put its first frame up.
 * A URL googlevideo answers slowly or throttles raises no player error, so without this a stalled
 * stream sat there forever instead of moving on to the next client.
 */
private const val FIRST_FRAME_TIMEOUT_MS = 12_000L

/**
 * The picture's floor opacity. A TextureView at exactly 0 is skipped by the renderer, so it can get no
 * surface and draw no frame; the picture then never reports one, and so never fades in.
 */
private const val MinTextureAlpha = 0.01f

/** The video is a full-screen backdrop now; the legibility scrim replaces the old bottom fade. */
private const val HeroFadeFraction = 0f

/** Resolved video for the current track, plus a one-shot way to ask for a fresh URL after a failure. */
internal class VideoSource(
    val stream: State<VideoStream?>,
    val refresh: () -> Boolean,
)

/**
 * Resolves the track's music video while the player is on screen.
 *
 * Resolution is skipped while the player is hidden (nothing to show, so nothing to spend data on),
 * and a stream already resolved is kept across that. A failed stream is re-resolved up to
 * [MAX_VIDEO_REFRESHES] times, each time skipping the client whose URL just failed (an expired or
 * refused URL is the usual cause); after that the page falls back to its artwork layout.
 */
@Composable
internal fun rememberVideoSource(
    track: Track?,
    provider: VideoStreamProvider?,
    enabled: Boolean,
    active: Boolean,
): VideoSource {
    val key = track?.let { it.isrc.ifBlank { "${it.title}|${it.artist}" } }
    val stream = remember(key) { mutableStateOf<VideoStream?>(null) }
    var refreshes by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, provider, enabled, active, refreshes) {
        val t = track
        if (!enabled || provider == null || t == null) {
            stream.value = null
            return@LaunchedEffect
        }
        if (!active) return@LaunchedEffect
        if (stream.value != null && refreshes == 0) return@LaunchedEffect
        stream.value = provider.resolve(t, forceRefresh = refreshes > 0)
    }
    return remember(key) {
        VideoSource(stream) {
            if (refreshes < MAX_VIDEO_REFRESHES) {
                refreshes++
                true
            } else {
                stream.value = null
                false
            }
        }
    }
}

/**
 * Full-screen music video backdrop that follows the audio clock. It is scaled to fill the screen's
 * height and cropped at the sides, so a landscape clip on a portrait phone covers the whole page.
 *
 * Ported from Auris' canvas player: a silent ExoPlayer on a TextureView, a wrapped
 * SurfaceTextureListener so a surface the OS took away is repainted instead of left blank,
 * decoding gated on the app being on screen, and the bottom fade done on the view's own render
 * node (a Compose mask over a TextureView never reaches its pixels).
 */
@Composable
internal fun VideoHero(
    stream: VideoStream,
    player: MusicPlayer,
    active: Boolean,
    speed: Float,
    onFailed: () -> Boolean,
    modifier: Modifier = Modifier,
    onRendered: () -> Unit = {},
) {
    val context = LocalContext.current
    val sync = remember { VideoSyncPlayer(context) }
    DisposableEffect(sync) { onDispose { sync.release() } }

    val foreground = rememberVideoForeground()
    val allowPlay by rememberUpdatedState(foreground && active)
    val currentSpeed by rememberUpdatedState(speed)
    val aspect by sync.videoAspect.collectAsState()
    val rendered by sync.rendered.collectAsState()
    val failed by sync.failed.collectAsState()
    val reportFailed by rememberUpdatedState(onFailed)
    val reportRendered by rememberUpdatedState(onRendered)
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    LaunchedEffect(stream.url) {
        sync.load(stream, startMs = player.currentPositionMs().coerceAtLeast(0L))
        // Count only the time the picture is meant to be playing (app on screen, lyrics closed).
        var waited = 0L
        while (waited < FIRST_FRAME_TIMEOUT_MS) {
            delay(500L)
            if (sync.rendered.value || sync.failed.value) return@LaunchedEffect
            if (allowPlay) waited += 500L
        }
        Log.w("GamutVideo", "no first frame within ${FIRST_FRAME_TIMEOUT_MS}ms (${stream.profileId ?: "newpipe"}); trying another client")
        reportFailed()
    }
    LaunchedEffect(foreground, active) { Log.d("GamutVideo", "hero gate foreground=$foreground active=$active") }
    LaunchedEffect(failed) { if (failed) reportFailed() }
    LaunchedEffect(rendered) { if (rendered) reportRendered() }

    // Audio is the master clock; the video only ever follows it.
    LaunchedEffect(sync) {
        while (isActive) {
            val playing = player.playbackState.value is PlaybackState.Playing && !player.awaitingAudio.value
            sync.sync(player.currentPositionMs(), playing, currentSpeed, allowPlay)
            delay(VideoSyncPlayer.SYNC_INTERVAL_MS)
        }
    }

    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(durationMillis = 600), label = "videoHeroAlpha")

    // No detach effect keyed on the view: it read the view when it ran, so the first effect (keyed null)
    // captured the view the factory had just made, and was disposed the moment the key changed, which took
    // the output surface away from the player for good. release() already lets go of the view.

    // Cover the whole area at the video's own shape: the view is sized by layout (nothing stretched, no
    // matrix) to at least the area's height and width, and whatever overhangs is clipped.
    val shownAspect = if (aspect > 0f) aspect else HeroAspect
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewContext ->
                val texture =
                    TextureView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        // Blend rather than punch a hole, so the page shows through during the fade.
                        isOpaque = false
                        this.alpha = MinTextureAlpha
                        sync.attach(this)
                        // setVideoTextureView installed ExoPlayer's own listener; it has to keep working
                        // (it is how the surface reaches the decoder), so wrap it instead of replacing it.
                        val delegate = surfaceTextureListener
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                private var replacing = false

                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    Log.d("GamutVideo", "surface available ${width}x$height")
                                    delegate?.onSurfaceTextureAvailable(surface, width, height)
                                    if (!replacing) return
                                    replacing = false
                                    sync.repaint()
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    delegate?.onSurfaceTextureSizeChanged(surface, width, height)
                                }

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    replacing = true
                                    Log.d("GamutVideo", "surface destroyed")
                                    sync.markSurfaceLost()
                                    return delegate?.onSurfaceTextureDestroyed(surface) ?: true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                    delegate?.onSurfaceTextureUpdated(surface)
                                    // Proof that *this* surface holds a drawable frame.
                                    if (!sync.rendered.value) {
                                        Log.d("GamutVideo", "first frame reached the texture view")
                                        sync.markRendered()
                                    }
                                }
                            }
                    }
                textureView = texture
                FadingBottomFrame(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addView(texture)
                }
            },
            update = { frame ->
                val view = frame.getChildAt(0) as TextureView
                view.alpha = maxOf(alpha, MinTextureAlpha)
                frame.applyFade(view)
            },
            modifier =
                Modifier.layout { measurable, constraints ->
                    val width = constraints.maxWidth
                    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (width / shownAspect).roundToInt()
                    // Cover: the larger of "fill the height" and "fill the width". On a portrait phone that
                    // is the height, with the sides cropped.
                    val coverWidth = maxOf(width.toFloat(), height * shownAspect)
                    val w = coverWidth.roundToInt()
                    val h = (coverWidth / shownAspect).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1)))
                    layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
                },
        )
        // Keeps the title, lyrics and controls readable over any footage; fades in with the picture.
        Box(
            Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.50f), Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )
    }
}

/** Applies the bottom fade, only when the view's size changed since it was last applied. */
private fun FadingBottomFrame.applyFade(view: TextureView) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (applied.fadeDiffers(HeroFadeFraction, view.width, view.height)) view.setBottomFade(HeroFadeFraction)
    } else {
        fadeFraction = HeroFadeFraction
    }
}

/** Dissolves the video's bottom edge on the view's own render node. API 31+. */
@RequiresApi(Build.VERSION_CODES.S)
private fun TextureView.setBottomFade(fraction: Float) {
    val endY = height.toFloat()
    if (fraction <= 0.001f || endY <= 0f) {
        setRenderEffect(null)
        return
    }
    val gradient =
        LinearGradient(
            0f,
            endY * (1f - fraction.coerceAtMost(1f)),
            0f,
            endY,
            android.graphics.Color.BLACK,
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    setRenderEffect(
        RenderEffect.createBlendModeEffect(
            RenderEffect.createOffsetEffect(0f, 0f),
            RenderEffect.createShaderEffect(gradient),
            BlendMode.DST_IN,
        ),
    )
}

private class AppliedLook {
    private var fade = Float.NaN
    private var fadeWidth = -1
    private var fadeHeight = -1

    fun fadeDiffers(
        fraction: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Boolean {
        if (fraction == fade && viewWidth == fadeWidth && viewHeight == fadeHeight) return false
        fade = fraction
        fadeWidth = viewWidth
        fadeHeight = viewHeight
        return true
    }
}

/**
 * Below API 31 there is no RenderEffect, so the mask is applied to the whole group the old way:
 * draw the child into an offscreen layer, paint a gradient over it with DST_IN, compose it down.
 * Costs a full-frame offscreen buffer, so it stays a plain FrameLayout while the fade is zero.
 */
private class FadingBottomFrame(
    context: Context,
) : FrameLayout(context) {
    val applied = AppliedLook()

    var fadeFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            gradient = null
            invalidate()
        }

    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var gradient: LinearGradient? = null
    private var gradientHeight = 0

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val fade = fadeFraction
        val endY = height.toFloat()
        if (fade <= 0.001f || endY <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val shader =
            gradient?.takeIf { gradientHeight == height }
                ?: LinearGradient(
                    0f,
                    endY * (1f - fade),
                    0f,
                    endY,
                    android.graphics.Color.BLACK,
                    android.graphics.Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                ).also {
                    gradient = it
                    gradientHeight = height
                }
        maskPaint.shader = shader
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }
}

/**
 * Lays the hero out edge to edge (undoing [bleed] of horizontal page padding on both sides) and
 * reports [overlap] less height than it draws, so the content below slides up over the faded
 * bottom of the video instead of starting under it.
 */
internal fun Modifier.heroLayout(
    bleed: Dp,
    overlap: Dp,
): Modifier =
    layout { measurable, constraints ->
        val extra = bleed.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth + extra * 2 else constraints.maxWidth
        val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
        val height = (placeable.height - overlap.roundToPx()).coerceAtLeast(0)
        layout(constraints.maxWidth, height) { placeable.place(-extra, 0) }
    }

@Composable
private fun rememberVideoForeground(): Boolean {
    val owner = LocalContext.current.findLifecycleOwner()
    var foreground by remember(owner) {
        mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground = true
                    Lifecycle.Event.ON_STOP -> foreground = false
                    else -> Unit
                }
            }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return foreground
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.findLifecycleOwner()
        else -> null
    }