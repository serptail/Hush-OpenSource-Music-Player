package com.gamut.core.designsystem

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * THE pressScale (delete the one in GamutTheme.kt): your draw-phase version (the animation runs in a coroutine and is
 * only READ inside graphicsLayer, so pressing a row never recomposes it) plus hover and a haptic tick.
 *
 *  - press:   shrinks fast and firm (critically damped, no wobble while the finger is down)
 *  - release: springs back with a small overshoot, which is the soft bounce
 *  - hover:   mouse / stylus / trackpad (ChromeOS, tablets, desktop mode) gets a gentle lift; touch never hovers
 *  - focus:   D-pad / keyboard focus lifts the item and draws a ring; Enter / D-pad center clicks (clickable handles that)
 *  - a light haptic tick on click
 */
fun Modifier.pressScale(
    onClick: () -> Unit,
    scaleDown: Float = 0.94f,
    hoverScale: Float = 1.02f,
    focusScale: Float = 1.06f,
    enabled: Boolean = true,
    haptics: Boolean = true,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    // 0..1, read only in the draw phase: the D-pad focus ring fades in without recomposing the row.
    val ring = remember { Animatable(0f) }
    val ringColor = ink(0.9f)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(interaction, scaleDown, hoverScale, focusScale) {
        val pressSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f)
        val releaseSpec = spring<Float>(dampingRatio = 0.42f, stiffness = 420f)
        var pressed = false
        var hovered = false
        var focused = false
        interaction.interactions.collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                is HoverInteraction.Enter -> hovered = true
                is HoverInteraction.Exit -> hovered = false
                // D-pad / keyboard focus (TV, ChromeOS): clickable is focusable and emits these on our source.
                is FocusInteraction.Focus -> focused = true
                is FocusInteraction.Unfocus -> focused = false
            }
            val target = when {
                pressed -> scaleDown
                focused -> focusScale
                hovered -> hoverScale
                else -> 1f
            }
            launch { ring.animateTo(if (focused) 1f else 0f, tween(120)) }
            scale.animateTo(target, if (pressed) pressSpec else releaseSpec)
        }
    }
    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        // Drawn above the content so a later .background() can't hide it. Round for circles, 16dp otherwise.
        .drawWithContent {
            drawContent()
            if (ring.value > 0.01f) {
                val corner = minOf(16.dp.toPx(), size.minDimension / 2f)
                drawRoundRect(
                    color = ringColor.copy(alpha = ringColor.alpha * ring.value),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        .hoverable(interaction, enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/**
 * Visible D-pad focus for rows that have their own click handling (combinedClickable, toggleable...).
 * Put it BEFORE the clickable in the chain, so it observes that clickable's focus target.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(14.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = ink(0.9f)
    this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

/** Moving highlight for placeholders. Needs CoverTheme (uses [ink]). */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    val base = ink(0.07f)
    val high = ink(0.15f)
    drawBehind {
        drawRect(
            Brush.horizontalGradient(
                listOf(base, high, base),
                startX = size.width * (x - 0.4f),
                endX = size.width * (x + 0.4f),
            ),
        )
    }
}

/** A placeholder bar. */
@Composable
fun SkeletonBar(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(6.dp)).shimmer())
}

/** One song row placeholder (54dp cover + two lines), same metrics as the real rows. */
@Composable
fun TrackRowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).shimmer())
        Column(Modifier.padding(horizontal = 16.dp)) {
            SkeletonBar(150.dp, 14.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonBar(96.dp, 11.dp)
        }
    }
}

/**
 * Full-screen placeholder for Album / Playlist while the data arrives. Phone: hero square + song rows.
 * Wide window: the hero on the left and the rows on the right, like the real two-pane screen.
 */
@Composable
fun HeroListSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    // 720dp = the app-wide WideBreakpoint (checked here directly so the design system needs no dependency on it).
    if (LocalConfiguration.current.screenWidthDp >= 720) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(start = 44.dp, end = 44.dp, top = 68.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).shimmer())
            }
            Column(Modifier.weight(0.6f).fillMaxHeight().padding(start = 8.dp, end = 24.dp, top = 16.dp)) {
                repeat(rows) { TrackRowSkeleton() }
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).shimmer())
            Spacer(Modifier.height(16.dp))
            repeat(rows) { TrackRowSkeleton(Modifier.padding(horizontal = 24.dp)) }
        }
    }
}