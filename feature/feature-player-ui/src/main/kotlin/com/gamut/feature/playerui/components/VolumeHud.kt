package com.gamut.feature.playerui.components

import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** State of the edge-drag volume control. Drives the real music stream volume. */
@Stable
internal class VolumeHud(private val audio: AudioManager, private val haptic: HapticFeedback) {
    private val maxSteps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private var lastStep = -1

    var holding by mutableStateOf(false)
        private set
    var fraction by mutableFloatStateOf(0f)
        private set

    /** Where the artwork row sits on screen; the pills are centered on it. Set by [volumeHudAnchor]. */
    var anchor by mutableStateOf(Rect.Zero)
        internal set

    private fun current(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxSteps.toFloat()

    fun begin() {
        fraction = current()
        lastStep = (fraction * maxSteps).roundToInt()
        holding = true
    }

    fun set(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        val step = (fraction * maxSteps).roundToInt()
        if (step != lastStep) {
            lastStep = step
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)   // flag 0: no system volume panel on top of ours
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun end() { holding = false }

    fun fractionNow(): Float = fraction
}

/** Put this on the artwork row: the volume pills line up with it. */
internal fun Modifier.volumeHudAnchor(hud: VolumeHud): Modifier =
    onGloballyPositioned { hud.anchor = it.boundsInRoot() }

/**
 * Drag up / down along the left or right edge of the screen to change the volume. No hold needed.
 *
 *  - only the outer [edgeWidth] strips react (the player keeps 24 dp side padding, so nothing else lives there);
 *  - it only claims the touch once the finger has clearly moved vertically, so taps and sideways swipes pass through;
 *  - relative: the volume moves by the drag distance from where it is, so it never jumps.
 *
 * Put it BEFORE any padding on the full-screen container so the strips are measured against the real screen edges.
 */
internal fun Modifier.edgeVolumeGesture(
    hud: VolumeHud,
    edgeWidth: Dp = 32.dp,
    fullRange: Dp = 300.dp,   // finger travel for 0 -> 100 %
): Modifier = pointerInput(hud) {
    val edgePx = edgeWidth.toPx()
    val rangePx = fullRange.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edgePx && down.position.x < size.width - edgePx) return@awaitEachGesture

        val slop = viewConfiguration.touchSlop
        var claimed = false
        var startY = down.position.y
        var startFraction = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val c = event.changes.firstOrNull { it.id == down.id } ?: break
            if (c.changedToUp() || !c.pressed) break
            if (!claimed) {
                if (c.isConsumed || event.changes.size > 1) break
                val dx = c.position.x - down.position.x
                val dy = c.position.y - down.position.y
                if (abs(dy) > slop) {
                    if (abs(dy) > abs(dx) * 1.5f) {
                        claimed = true
                        hud.begin()
                        startFraction = hud.fractionNow()
                        startY = c.position.y
                        c.consume()
                    } else break
                }
            } else {
                c.consume()
                hud.set(startFraction + (startY - c.position.y) / rangePx)
            }
        }
        if (claimed) hud.end()
    }
}

/**
 * Two thin pills, one on each side of the artwork. Draw on top of the screen (takes no touches);
 * they fade out shortly after release.
 */
@Composable
internal fun VolumeHudOverlay(hud: VolumeHud, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hud.holding) {
        if (hud.holding) visible = true else { delay(600); visible = false }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val pillHeight = 160.dp
    val pillHeightPx = with(LocalDensity.current) { pillHeight.toPx() }
    val top = (hud.anchor.center.y - origin.y - pillHeightPx / 2f).roundToInt()

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(240)),
        ) {
            Box(Modifier.fillMaxSize()) {
                VolumePill(hud.fraction, pillHeight, Modifier.align(Alignment.TopStart).offset { IntOffset(0, top) }.padding(start = 10.dp))
                VolumePill(hud.fraction, pillHeight, Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top) }.padding(end = 10.dp))
            }
        }
    }
}

@Composable
private fun VolumePill(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .width(5.dp)
            .height(height)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.16f)),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                .background(Color.White.copy(alpha = 0.92f)),
        )
    }
}