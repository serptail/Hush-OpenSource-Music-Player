package com.gamut.core.designsystem

import android.content.pm.ApplicationInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GamutLogo(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Image(painterResource(R.drawable.gamut_logo), contentDescription = "Gamut", modifier = modifier.size(size))
}

/**
 * Fills to ~90% over [minDurationMs]; completes and calls [onFinished] once the time has passed AND [dataReady].
 *
 * Just the logo on one soft corner glow, a thin progress line, and a quiet build tag at the bottom. [buildLabel] overrides the tag; by default it is read
 * from the installed package ("gamut-release-build-1.2.0", or "debug" for debuggable builds).
 */
@Composable
fun GamutLoadingScreen(
    dataReady: Boolean,
    onFinished: () -> Unit,
    minDurationMs: Int = 3000,
    buildLabel: String? = null,
) {
    // Follows Appearance: light / dark / system, or the fixed colour preset. Nothing is playing yet, so the solid palette.
    val palette = appNeutralPalette()
    val context = LocalContext.current
    val label = buildLabel ?: remember {
        runCatching {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            "gamut-${if (debug) "debug" else "release"}-build-$version"
        }.getOrDefault("gamut")
    }

    val progress = remember { Animatable(0f) }
    var minTimePassed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(minDurationMs.toLong()); minTimePassed = true }
    LaunchedEffect(Unit) { progress.animateTo(0.9f, tween(minDurationMs, easing = FastOutSlowInEasing)) }
    LaunchedEffect(dataReady, minTimePassed) {
        if (dataReady && minTimePassed) {
            progress.animateTo(1f, tween(250))
            delay(150)
            onFinished()
        }
    }

    // Entrance: the logo pops in, then the line and the footer fade up after it.
    val logoScale = remember { Animatable(0.82f) }
    val logoAlpha = remember { Animatable(0f) }
    val barAlpha = remember { Animatable(0f) }
    val footerAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(450)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        launch { delay(350); barAlpha.animateTo(1f, tween(500)) }
        launch { delay(700); footerAlpha.animateTo(1f, tween(700)) }
    }

    val barWidth = 120.dp

    Box(
        Modifier.fillMaxSize().background(palette.base).pointerInput(Unit) {}, // swallow touches
        contentAlignment = Alignment.Center,
    ) {
        // One soft glow from the top-left corner, like the Flick screen. A plain gradient: no shader, no animation.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = logoAlpha.value }.drawBehind {
                val a = if (palette.isLight) 0.16f else 0.30f
                drawRect(
                    Brush.radialGradient(
                        0f to Color(0xFF3B4BFF).copy(alpha = a),
                        0.45f to Color(0xFF3B2BFF).copy(alpha = a * 0.4f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.05f, -size.height * 0.02f),
                        radius = size.height * 0.62f,
                    ),
                )
            },
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Black mark on a light background, white on a dark one (follows the Appearance palette).
            Image(
                painterResource(if (palette.isLight) R.drawable.ic_gamut_mark_black else R.drawable.ic_gamut_mark_white),
                contentDescription = "Gamut",
                modifier = Modifier.size(115.dp).graphicsLayer {
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                    alpha = logoAlpha.value
                },
            )
            Spacer(Modifier.height(40.dp))
            // Thin line in the palette's text colour (white on dark, black on light).
            Box(
                Modifier
                    .graphicsLayer { alpha = barAlpha.value }
                    .width(barWidth)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(palette.onBase.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .background(palette.onBase.copy(alpha = 0.85f)),
                )
            }
        }

        // Footer: one quiet build tag, nothing else.
        Text(
            label,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.4.sp, textAlign = TextAlign.Center),
            color = palette.onBase.copy(alpha = 0.38f),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
                .graphicsLayer { alpha = footerAlpha.value },
        )
    }
}