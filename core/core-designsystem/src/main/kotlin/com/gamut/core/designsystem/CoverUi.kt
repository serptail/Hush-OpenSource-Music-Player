package com.gamut.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
@ReadOnlyComposable
fun ink(alpha: Float = 1f): Color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

@Composable
fun Modifier.glass(
    shape: Shape,
    fill: Float = 0.07f,
    borderColor: Color = ink(0.07f),
): Modifier =
    this
        .clip(shape)
        .background(ink(fill), shape)
        .border(1.dp, borderColor, shape)

fun Modifier.fadeEdges(fade: Dp = 18.dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val f = (fade.toPx() / size.height).coerceIn(0f, 0.5f)
            val brush =
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to Color.Transparent,
                            f to Color.Black,
                            1f - f to Color.Black,
                            1f to Color.Transparent,
                        ),
                )
            onDrawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.DstIn)
            }
        }

@Suppress("DEPRECATION")
@Composable
fun TransparentStatusBar(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Rounded square with a capital E punched out of it, on a 12x12 grid. */
private const val EXPLICIT_BADGE_PATH =
    "M2.5 0h7A2.5 2.5 0 0 1 12 2.5v7A2.5 2.5 0 0 1 9.5 12h-7A2.5 2.5 0 0 1 0 9.5v-7A2.5 2.5 0 0 1 2.5 0Z" +
        "M3.9 3v6h4.2V7.75H5.35v-1.15H7.8V5.4H5.35V4.25H8.1V3Z"

/**
 * The boxed "E" that marks explicit content. Drawn as one even-odd path so the letter is a real
 * hole (the cover or background shows through) and stays centred at any [size].
 */
@Composable
fun ExplicitBadge(
    modifier: Modifier = Modifier,
    color: Color = ink(0.6f),
    size: Dp = 13.dp,
) {
    val path =
        remember {
            PathParser().parsePathString(EXPLICIT_BADGE_PATH).toPath().apply { fillType = PathFillType.EvenOdd }
        }
    Canvas(modifier.size(size).semantics { contentDescription = "Explicit" }) {
        scale(scaleX = this.size.width / 12f, scaleY = this.size.height / 12f, pivot = Offset.Zero) {
            drawPath(path, color)
        }
    }
}

/**
 * Leading badge for a title row: draws the badge and its gap when [explicit] is true, nothing
 * otherwise (null = the catalogue didn't say, so it is never badged). Place inside a Row with
 * `verticalAlignment = Alignment.CenterVertically`, ahead of the title.
 */
@Composable
fun ExplicitMark(
    explicit: Boolean?,
    color: Color = ink(0.6f),
    size: Dp = 13.dp,
) {
    if (explicit == true) {
        ExplicitBadge(color = color, size = size)
        Spacer(Modifier.width(6.dp))
    }
}