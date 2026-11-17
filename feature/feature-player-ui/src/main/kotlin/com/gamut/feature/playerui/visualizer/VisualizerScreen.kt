package com.gamut.feature.playerui.visualizer

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.X
import com.gamut.core.player.AudioAnalysis
import com.gamut.core.player.AudioAnalyzer
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_MS = 30_000L

/**
 * Full-screen, music-reactive visualizer. Tap to change preset. It draws the audio that is actually reaching
 * the output, and switches the analyzer tap on only while it is open.
 */
@Composable
fun VisualizerScreen(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        VisualizerContent(onDismiss)
    }
}

@Composable
private fun VisualizerContent(onDismiss: () -> Unit) {
    val analysis = remember { AudioAnalysis() }
    val renderer = remember { VisualizerRenderer(analysis) }
    var index by remember { mutableIntStateOf(0) }
    var showName by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(true) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner, glView) {
        AudioAnalyzer.enabled = true
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        AudioAnalyzer.enabled = true
                        glView?.onResume()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        AudioAnalyzer.enabled = false
                        glView?.onPause()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            AudioAnalyzer.enabled = false
        }
    }

    LaunchedEffect(index) {
        renderer.presetIndex = index
        showName = true
        delay(2_200)
        showName = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AUTO_ADVANCE_MS)
            index = (index + 1) % Presets.all.size
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            live = renderer.live
            delay(400)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                index = (index + 1) % Presets.all.size
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    keepScreenOn = true
                    glView = this
                }
            },
        )
        AnimatedVisibility(
            visible = showName || !live,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
        ) {
            Text(
                if (!live) "Play something to see it move" else Presets.all[index].name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Icon(
            PhosphorIcons.Bold.X,
            contentDescription = "Close visualizer",
            tint = Color.White.copy(alpha = 0.8f),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(28.dp)
                    .clickable(onClick = onDismiss),
        )
    }
}
