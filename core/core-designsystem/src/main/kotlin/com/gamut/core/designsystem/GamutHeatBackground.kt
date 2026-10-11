package com.gamut.core.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

// ======================================================================================
// Tuning
// ======================================================================================

/** Grain size in dp. 1 = fine film grain. */
private const val GrainDp = 1.0f

/** Grain strength at the fading edge of the glow (0..0.2). */
private const val GrainAmount = 0.09f

/** Time multiplier for the pulse. */
private const val HeatSpeed = 0.35f

/** How much the two glows breathe (0 = still). They breathe in opposite phase, so it reads as a slow shimmer. */
private const val PulseAmount = 0.10f

/** How far the glows reach down the screen. Higher = tighter and shorter. */
private const val GlowFalloff = 4.2f

/**
 * Two soft glows bleeding in from the top corners (purple/magenta left, blue/cyan right), mixing into deep blue
 * in the middle, fading into the plain base well before the headline. Heat goes through a colour ramp taken from the
 * Gamut logo, with noise added before the lookup so the edge dissolves into grain. Idea from the Paper Shaders
 * "heatmap" shader (paper-design/shaders, Apache-2.0); written from the technique.
 */
private const val HeatAgsl = """
uniform float2 resolution;
uniform float time;
uniform float3 base;
uniform float intensity;
uniform float grainPx;
uniform float grainAmt;
uniform float falloff;
uniform float pulse;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

const float3 deepBlue = float3(0.000, 0.063, 0.722);   // #0010B8
const float3 indigo   = float3(0.231, 0.169, 1.000);   // #3B2BFF
const float3 purple   = float3(0.541, 0.169, 1.000);   // #8A2BFF
const float3 magenta  = float3(0.910, 0.149, 1.000);   // #E826FF
const float3 blue     = float3(0.000, 0.314, 1.000);   // #0050FF
const float3 cyan     = float3(0.000, 0.847, 1.000);   // #00D8FF
const float3 teal     = float3(0.169, 0.961, 0.835);   // #2BF5D5

float3 rampLeft(float t) {
    float p = clamp(t, 0.0, 1.0) * 3.0;
    float3 c = deepBlue;
    c = mix(c, indigo,  clamp(p, 0.0, 1.0));
    c = mix(c, purple,  clamp(p - 1.0, 0.0, 1.0));
    c = mix(c, magenta, clamp(p - 2.0, 0.0, 1.0));
    return c;
}
float3 rampRight(float t) {
    float p = clamp(t, 0.0, 1.0) * 3.0;
    float3 c = deepBlue;
    c = mix(c, blue, clamp(p, 0.0, 1.0));
    c = mix(c, cyan, clamp(p - 1.0, 0.0, 1.0));
    c = mix(c, teal, clamp(p - 2.0, 0.0, 1.0));
    return c;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / resolution.y;
    float t = time;

    // Height-normalised coordinates, origin at the top-left corner.
    float2 p = float2(uv.x * aspect, uv.y);

    // Two glow centres just outside the top corners, breathing in opposite phase.
    float breathL = sin(t * 2.5);
    float breathR = sin(t * 2.5 + 3.14159);
    float2 cL = float2(-0.05, -0.08);
    float2 cR = float2(aspect + 0.05, -0.04);
    float dL = length((p - cL) * float2(0.9, 1.0)) / (1.0 + pulse * breathL);
    float dR = length((p - cR) * float2(0.9, 1.0)) / (1.0 + pulse * breathR);
    float hL = exp(-dL * dL * falloff);
    float hR = exp(-dR * dR * falloff);

    // Grain: static, and strongest where the glow thins out, so the edge dissolves instead of banding.
    float n = hash(floor(fragCoord / grainPx)) - 0.5;
    float edgeL = clamp(hL * (1.0 - hL) * 4.0, 0.0, 1.0);
    float edgeR = clamp(hR * (1.0 - hR) * 4.0, 0.0, 1.0);
    hL = clamp(hL + n * grainAmt * (0.3 + 0.7 * edgeL), 0.0, 1.0) * intensity;
    hR = clamp(hR + n * grainAmt * (0.3 + 0.7 * edgeR), 0.0, 1.0) * intensity;

    float3 colL = rampLeft(hL);
    float3 colR = rampRight(hR);
    float aL = smoothstep(0.04, 0.55, hL);
    float aR = smoothstep(0.04, 0.55, hR);

    // Weighted mix where the two glows overlap, then fade into the base.
    float3 glow = (colL * aL + colR * aR) / max(aL + aR, 0.0001);
    float a = clamp(aL + aR - aL * aR, 0.0, 1.0);
    return half4(half3(mix(base, glow, a)), 1.0);
}
"""

@Composable
fun GamutHeatBackground(base: Color, isLight: Boolean, modifier: Modifier = Modifier, strength: Float = 1f) {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) clock.floatValue += (now - last) / 1_000_000_000f * HeatSpeed
                last = now
            }
        }
    }
    // Softer in light mode so dark text stays readable.
    val intensity = (if (isLight) 0.6f else 0.85f) * strength

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(HeatAgsl) }
        val brush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier.fillMaxSize()) {
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", clock.floatValue)
            shader.setFloatUniform("base", base.red, base.green, base.blue)
            shader.setFloatUniform("intensity", intensity)
            shader.setFloatUniform("grainPx", GrainDp * density)
            shader.setFloatUniform("grainAmt", GrainAmount)
            shader.setFloatUniform("falloff", GlowFalloff)
            shader.setFloatUniform("pulse", PulseAmount)
            drawRect(brush)
        }
    } else {
        // Android 12 and below have no AGSL: the same two corner glows, static.
        Canvas(modifier.fillMaxSize()) {
            drawRect(base)
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF8A2BFF).copy(alpha = 0.55f * intensity), Color(0xFF3B2BFF).copy(alpha = 0.25f * intensity), Color.Transparent),
                    center = Offset(0f, -size.height * 0.05f), radius = size.height * 0.5f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF00D8FF).copy(alpha = 0.5f * intensity), Color(0xFF0050FF).copy(alpha = 0.25f * intensity), Color.Transparent),
                    center = Offset(size.width, -size.height * 0.03f), radius = size.height * 0.5f,
                ),
            )
        }
    }
}