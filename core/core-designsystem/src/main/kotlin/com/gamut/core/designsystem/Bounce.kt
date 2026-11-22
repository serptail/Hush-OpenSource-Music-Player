package com.gamut.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-shot tap bounce driven by any [InteractionSource]: the moment a press lands the content squishes,
 * then springs back with a little overshoot, no matter how long the finger stays down. Hover still grows
 * the content slightly. Use it on things that already own an interaction source.
 */
fun Modifier.bounceOnPress(
    interactionSource: InteractionSource,
    scaleDown: Float = BounceDefaults.ScaleDown,
    enabled: Boolean = true,
): Modifier =
    composed {
        val scale = remember { Animatable(1f) }
        LaunchedEffect(interactionSource, scaleDown, enabled) {
            if (!enabled) {
                scale.snapTo(1f)
                return@LaunchedEffect
            }
            var hovered = false
            var pulse: Job? = null
            coroutineScope {
                interactionSource.interactions.collect { event ->
                    when (event) {
                        is PressInteraction.Press -> {
                            pulse?.cancel()
                            pulse =
                                launch {
                                    scale.animateTo(scaleDown, BounceDefaults.PressSpec)
                                    scale.animateTo(if (hovered) BounceDefaults.HoverScale else 1f, BounceDefaults.ReleaseSpec)
                                }
                        }

                        is HoverInteraction.Enter -> {
                            hovered = true
                            if (pulse?.isActive != true) launch { scale.animateTo(BounceDefaults.HoverScale, BounceDefaults.ReleaseSpec) }
                        }

                        is HoverInteraction.Exit -> {
                            hovered = false
                            if (pulse?.isActive != true) launch { scale.animateTo(1f, BounceDefaults.ReleaseSpec) }
                        }
                    }
                }
            }
        }
        graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    }

/**
 * Drop-in replacement for `clickable` / `combinedClickable` that adds a one-shot tap bounce.
 * No ripple is drawn (matches the rest of the app); the bounce is the feedback.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    scaleDown: Float = BounceDefaults.ScaleDown,
    haptics: Boolean = false,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val interaction = remember { MutableInteractionSource() }
        val haptic = LocalHapticFeedback.current
        this
            .bounceOnPress(interaction, scaleDown, enabled)
            .hoverable(interaction, enabled)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = role,
                onClickLabel = onClickLabel,
                onLongClickLabel = onLongClickLabel,
                onLongClick =
                    onLongClick?.let { long ->
                        {
                            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            long()
                        }
                    },
                onDoubleClick = onDoubleClick,
                onClick = {
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
    }

/**
 * One-shot tap bounce for components that own their click handling (Material buttons, chips, etc.).
 * Watches the first touch-down on the Initial pass and never consumes, so the component still gets every event.
 */
fun Modifier.bounceOnTouch(
    scaleDown: Float = BounceDefaults.ScaleDown,
    enabled: Boolean = true,
): Modifier =
    composed {
        val scale = remember { Animatable(1f) }
        var taps by remember { mutableIntStateOf(0) }
        LaunchedEffect(taps, scaleDown) {
            if (taps == 0) return@LaunchedEffect
            scale.animateTo(scaleDown, BounceDefaults.PressSpec)
            scale.animateTo(1f, BounceDefaults.ReleaseSpec)
        }
        this
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    taps++
                }
            }
    }

/**
 * True for a short moment after each press, even if the finger stays down. Use it to drive a custom scale
 * so the squish is a one-shot bounce, and keep [androidx.compose.foundation.interaction.collectIsPressedAsState]
 * for highlights that should stay lit while held.
 */
@Composable
fun InteractionSource.collectIsPulsedAsState(holdMs: Long = BounceDefaults.PulseMs): State<Boolean> {
    val pulsed = remember { mutableStateOf(false) }
    LaunchedEffect(this) {
        coroutineScope {
            var pulse: Job? = null
            interactions.collect { event ->
                if (event is PressInteraction.Press) {
                    pulse?.cancel()
                    pulse =
                        launch {
                            pulsed.value = true
                            delay(holdMs)
                            pulsed.value = false
                        }
                }
            }
        }
    }
    return pulsed
}

object BounceDefaults {
    /** Wide rows / cards read better with a subtle squish. */
    const val ScaleDown = 0.96f

    /** Small icon buttons, dots and chips can take a stronger squish. */
    const val ScaleDownSmall = 0.88f

    const val HoverScale = 1.02f

    /** How long a custom pulse stays "down" before springing back. */
    const val PulseMs = 110L

    val PressSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f)
    val ReleaseSpec = spring<Float>(dampingRatio = 0.42f, stiffness = 420f)

    /** Springy grow/shrink for slider thumbs and knobs while a finger is down. */
    val ThumbSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 500f)
}
