package com.gamut.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Helpers shared by the screens that sit on the flowing cover background (Search, Library, ...).

/** The readable color on the current cover background, at [alpha]. Only valid inside [CoverTheme]. */
@Composable
@ReadOnlyComposable
fun ink(alpha: Float = 1f): Color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

/** Flat translucent surface: an ink tint and one uniform hairline edge. Only valid inside [CoverTheme]. */
@Composable
fun Modifier.glass(
    shape: Shape,
    fill: Float = 0.07f,
    borderColor: Color = ink(0.07f),
): Modifier = this
    .clip(shape)
    .background(ink(fill), shape)
    .border(1.dp, borderColor, shape)

/** Fades scrolled content out at the top and bottom edge instead of cutting it off. */
fun Modifier.fadeEdges(fade: Dp = 18.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        // Built once per size instead of allocating a Brush + array on every scrolled frame.
        val f = (fade.toPx() / size.height).coerceIn(0f, 0.5f)
        val brush = Brush.verticalGradient(
            colorStops = arrayOf(
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

/**
 * Makes the status bar transparent, so the screen's own background runs behind it. Icons are dark when
 * [darkIcons] (light covers) and light otherwise.
 */
@Suppress("DEPRECATION")
@Composable
fun TransparentStatusBar(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    // Keyed: touches the window only when the icon color actually changes, not on every recomposition.
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}