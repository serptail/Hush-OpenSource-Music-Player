package com.gamut.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun GamutLogo(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Image(painterResource(R.drawable.gamut_logo), contentDescription = "Gamut", modifier = modifier.size(size))
}

/** Fills to ~90% over [minDurationMs]; completes and calls [onFinished] once the time has passed AND [dataReady]. */
@Composable
fun GamutLoadingScreen(dataReady: Boolean, onFinished: () -> Unit, minDurationMs: Int = 3000) {
    // Follows Appearance: light / dark / system, or the fixed colour preset. Nothing is playing yet, so the solid palette.
    val palette = appNeutralPalette()
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
    Box(
        Modifier.fillMaxSize().background(palette.base).pointerInput(Unit) {}, // swallow touches
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GamutLogo(size = 115.dp)
            Spacer(Modifier.height(36.dp))
            Box(Modifier.width(120.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(palette.onBase.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(palette.onBase.copy(alpha = 0.85f)))
            }
        }
    }
}