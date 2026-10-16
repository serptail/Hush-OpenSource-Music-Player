package com.gamut.core.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

// ======================================================================================
// Tuning. Everything you are likely to want to change is here.
// ======================================================================================

/**
 * false: mint (#1CF2C0) is the hot spot at the top edge and magenta (#EE22FF) fades out at the bottom (the reference
 *        image flipped upside down).
 * true:  magenta at the top, mint at the bottom (the stops read literally from offset 0 at the focal point).
 */
private const val MagentaAtBottom = false

/** How close to the bottom edge the hot spot sits (0 = centre of the blob, 0.99 = on the bottom edge). */
private const val BlobFocalDistance = 0.97f

/** How much of the outer edge fades out (0 = hard edge, 1 = fades from the hot spot). Top softness, mostly. */
private const val BlobFalloff = 0.55f

/** Your stops. Offsets are baked into the shader's ramp(): 0, .20, .40, .55, .72, .86, 1. */
private val BlobColors = listOf(
    Color(0xFFEE22FF),
    Color(0xFFA02CFF),
    Color(0xFF3340FF),
    Color(0xFF0A4CF0),
    Color(0xFF00A8FF),
    Color(0xFF00DCE8),
    Color(0xFF1CF2C0),
)
private val BlobOffsets = floatArrayOf(0f, 0.2f, 0.4f, 0.55f, 0.72f, 0.86f, 1f)

/**
 * Focal radial gradient inside a circle, in the style of the Paper Shaders "static-radial-gradient"
 * (paper-design/shaders, Apache-2.0). The gradient's focal point sits near the bottom edge of the circle, so the colour
 * starts there and spreads downward. Written from the technique, not copied line by line.
 *
 * For each pixel we cast a ray from the focal point through it, find where that ray leaves the circle, and use
 * (distance from focal point) / (distance to the edge) as the gradient position. That is what makes the bottom edge
 * crisp and the far edge long and soft.
 */
private const val BlobAgsl = """
uniform float2 resolution;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 c4;
uniform float3 c5;
uniform float3 c6;
uniform float focalDistance;
uniform float falloff;
uniform float intensity;
uniform float reverse;

float3 ramp(float u) {
    float3 c = c0;
    c = mix(c, c1, clamp((u - 0.00) / 0.20, 0.0, 1.0));
    c = mix(c, c2, clamp((u - 0.20) / 0.20, 0.0, 1.0));
    c = mix(c, c3, clamp((u - 0.40) / 0.15, 0.0, 1.0));
    c = mix(c, c4, clamp((u - 0.55) / 0.17, 0.0, 1.0));
    c = mix(c, c5, clamp((u - 0.72) / 0.14, 0.0, 1.0));
    c = mix(c, c6, clamp((u - 0.86) / 0.14, 0.0, 1.0));
    return c;
}

half4 main(float2 fc) {
    // Work in a unit circle: the box is stretched to an ellipse, so the blob fills any width and height.
    float2 p = (fc - resolution * 0.5) / (resolution * 0.5);
    float2 F = float2(0.0, -focalDistance);     // focal point, near the TOP edge

    float2 dv = p - F;
    float len = length(dv);
    float2 d = dv / max(len, 0.0001);

    // Distance from F along d to the circle boundary (radius 1).
    float b = dot(F, d);
    float s = -b + sqrt(max(b * b - (dot(F, F) - 1.0), 0.0));
    float t = len / max(s, 0.0001);             // 0 at the focal point, 1 on the edge

    float u = clamp(reverse > 0.5 ? t : 1.0 - t, 0.0, 1.0);
    float3 col = ramp(u);

    float alpha = 1.0 - smoothstep(1.0 - falloff, 1.0, t);

    // A touch of noise so the long gradient does not band on 8-bit screens.
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453);
    col += (n - 0.5) / 128.0;

    float a = alpha * intensity;
    return half4(half3(col * a), half(a));      // premultiplied
}
"""

/**
 * The welcome-screen blob. The shape is an ellipse that fills the box, so size it to the width and height you want;  its bottom edge sits at the
 * bottom of the box. [intensity] scales the opacity (lower it in light mode so text stays readable).
 */
@Composable
fun GradientBlob(modifier: Modifier = Modifier, intensity: Float = 1f) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderBlob(modifier, intensity)
    } else {
        GradientBlobFallback(modifier, intensity)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderBlob(modifier: Modifier, intensity: Float) {
    val shader = remember { RuntimeShader(BlobAgsl) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        shader.setFloatUniform("resolution", size.width, size.height)
        BlobColors.forEachIndexed { i, c -> shader.setFloatUniform("c$i", c.red, c.green, c.blue) }
        shader.setFloatUniform("focalDistance", BlobFocalDistance)
        shader.setFloatUniform("falloff", BlobFalloff)
        shader.setFloatUniform("intensity", intensity)
        shader.setFloatUniform("reverse", if (MagentaAtBottom) 1f else 0f)
        drawRect(brush)
    }
}

/** Below Android 13 there is no AGSL: a plain radial gradient from the bottom centre gives the same feel. */
@Composable
private fun GradientBlobFallback(modifier: Modifier, intensity: Float) {
    Canvas(modifier) {
        val stops = BlobColors.indices
            .map { i -> (if (MagentaAtBottom) BlobOffsets[i] else 1f - BlobOffsets[i]) to BlobColors[i] }
            .sortedBy { it.first }
            .let { it.dropLast(1) + (it.last().first to it.last().second.copy(alpha = 0f)) }
        drawRect(
            brush = Brush.radialGradient(
                colorStops = stops.toTypedArray(),
                center = Offset(size.width / 2f, 0f),
                radius = size.height * 0.98f,
            ),
            alpha = intensity,
        )
    }
}