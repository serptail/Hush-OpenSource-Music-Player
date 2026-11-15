package com.gamut.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val SetupPalettes: List<List<Color>> =
    listOf(
        listOf(Color(0xFFFA2D48), Color(0xFF7B2FF7), Color(0xFFFF7A45), Color(0xFF2E1A8A)),
        listOf(Color(0xFFFF4E8A), Color(0xFFFF9A3C), Color(0xFFE0245E), Color(0xFF6A1B9A)),
        listOf(Color(0xFF1E88E5), Color(0xFF00BFA5), Color(0xFF5E35B1), Color(0xFF0D47A1)),
        listOf(Color(0xFF00C853), Color(0xFF00B8D4), Color(0xFF7C4DFF), Color(0xFF1A237E)),
        listOf(Color(0xFFD500F9), Color(0xFFFF4081), Color(0xFF651FFF), Color(0xFF4A148C)),
        listOf(Color(0xFFFFB300), Color(0xFFFF5252), Color(0xFFF06292), Color(0xFF6D2E46)),
    )

@Composable
internal fun SetupMesh(
    colors: List<Color>,
    animated: Boolean,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    val four = (colors + colors + colors + colors).take(4)
    val fade = tween<Color>(2_400)
    val c0 by animateColorAsState(four[0], fade, label = "setupMesh0")
    val c1 by animateColorAsState(four[1], fade, label = "setupMesh1")
    val c2 by animateColorAsState(four[2], fade, label = "setupMesh2")
    val c3 by animateColorAsState(four[3], fade, label = "setupMesh3")

    val phase = remember { Animatable(0f) }
    LaunchedEffect(animated) {
        if (animated) {
            while (isActive) {
                phase.animateTo(phase.value + (2 * PI).toFloat(), tween(28_000, easing = LinearEasing))
            }
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }.blur(64.dp),
    ) {
        val anchors = listOf(Offset(0.20f, 0.25f), Offset(0.80f, 0.20f), Offset(0.75f, 0.80f), Offset(0.25f, 0.75f))
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value
        listOf(c0, c1, c2, c3).forEachIndexed { index, color ->
            val center =
                Offset(
                    x = (anchors[index].x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                    y = (anchors[index].y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
                )
            val radius = size.maxDimension * 0.62f

            fun at(alpha: Float) = color.copy(alpha = intensity * alpha)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colorStops =
                            arrayOf(
                                0f to at(1f),
                                0.25f to at(0.8965f),
                                0.5f to at(0.5f),
                                0.75f to at(0.1035f),
                                1f to at(0f),
                            ),
                        center = center,
                        radius = radius,
                    ),
                radius = radius,
                center = center,
            )
        }
    }
}
