package com.gamut.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val RestoredGreen = Color(0xFFB9F0C6)
private val RestoredText = Color(0xFF0B3D1E)

/**
 * A light green strip across the top of the app, status bar included, that says the connection is back. Put it last
 * in a full-screen box so it draws over everything. It makes the status bar icons dark while it is shown, so they
 * stay readable on the green.
 */
@Composable
fun ConnectionRestoredBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = "Internet restored",
) {
    val view = LocalView.current
    DisposableEffect(visible) {
        val window = view.context.activityOrNull()?.window
        if (!visible || window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = true
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(RestoredGreen)
                .statusBarsPadding()
                .height(30.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Text(message, color = RestoredText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private tailrec fun Context.activityOrNull(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
