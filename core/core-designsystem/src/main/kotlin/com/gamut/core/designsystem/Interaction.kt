package com.gamut.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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

/**
 * REPLACES your existing pressScale (same name, same parameters, so no call site changes).
 *
 *  - press:   shrinks fast and firm (critically damped, no wobble while the finger is down)
 *  - release: springs back with a small overshoot, which is the "soft bounce"
 *  - hover:   mouse / stylus / trackpad (ChromeOS, tablets, desktop mode) gets a gentle lift; touch ignores it
 *  - a light haptic tick on click
 */
fun Modifier.pressScale(
    onClick: () -> Unit,
    scaleDown: Float = 0.96f,
    hoverScale: Float = 1.02f,
    enabled: Boolean = true,
    haptics: Boolean = true,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> scaleDown
            hovered -> hoverScale
            else -> 1f
        },
        animationSpec = if (pressed) spring(dampingRatio = 1f, stiffness = 900f)
        else spring(dampingRatio = 0.42f, stiffness = 420f),
        label = "press-scale",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .hoverable(source, enabled)
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
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

/** Full-screen placeholder for Album / Playlist while the data arrives: hero square + song rows. */
@Composable
fun HeroListSkeleton(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).shimmer())
        Spacer(Modifier.height(16.dp))
        repeat(rows) { TrackRowSkeleton(Modifier.padding(horizontal = 24.dp)) }
    }
}
