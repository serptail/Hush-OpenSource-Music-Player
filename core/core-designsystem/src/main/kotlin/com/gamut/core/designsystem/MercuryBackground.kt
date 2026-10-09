package com.gamut.core.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/** Which look the adaptive background has. Everything except [Flow] needs Android 13+; older phones keep the flow look. */
enum class FlowStyle { Flow, Mercury, Metaballs, Dithering }

// ======================================================================================
// Tuning. Everything you are likely to want to change is here.
// ======================================================================================

/** How much the field is pushed around (0..1+). Higher = more folded, flowing shapes. */
private const val MercuryDistortion = 0.8f

/** How much the field twists around the center (0..1). */
private const val MercurySwirl = 0.35f

/** Animation speed multiplier on top of the shared clock. */
private const val MercurySpeed = 0.6f

/**
 * Weight of the two base-colored points relative to each accent point (1 = same).
 * Higher = the cover's majority color covers more of the screen and the accents stay smaller.
 */
private const val MercuryBaseWeight = 0.9f

/**
 * Standalone copy of the Paper Shaders "mesh-gradient" (paper-design/shaders, Apache-2.0, via the AGSL port
 * paper-shaders-android by André Frélicot). Shepard interpolation: every pixel averages the color points,
 * weighted by 1 / distance^3.5, while the points drift along sin/cos paths and the space is warped and twisted.
 * Nothing is drawn, the softness is the average itself. The grain options are dropped (unused by default).
 *
 * The color points are the cover palette: three accents plus two points of the base color, so the base stays
 * the majority color. [strength] then fades between the plain base and the full mesh, which is how the intensity
 * setting and the light-mode damping from the flowing background carry over.
 */
private const val MercuryAgsl = """
uniform float2 resolution;
uniform float time;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 base;
uniform float strength;
uniform float distortion;
uniform float swirl;
uniform float baseWeight;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float2 getPosition(float i, float t) {
    float a = i * 0.37;
    float b = 0.6 + fract(i / 3.0) * 0.9;
    float c = 0.8 + fract((i + 1.0) / 4.0);
    float x = sin(t * b + a);
    float y = cos(t * c + a * 1.5);
    return 0.5 + 0.5 * float2(x, y);
}

void accumulate(inout float3 color, inout float totalWeight, float3 col, float i, float2 uv, float t, float mul) {
    float dist = length(uv - getPosition(i, t));
    dist = pow(max(dist, 0.0001), 3.5);
    float weight = mul / (dist + 0.001);
    color += col * weight;
    totalWeight += weight;
}

half4 main(float2 fragCoord) {
    // Stretched to the screen so every color point stays visible.
    float2 uv = fragCoord / resolution;
    float t = 0.5 * (time + 41.5);

    float radius = smoothstep(0.0, 1.0, length(uv - 0.5));
    float center = 1.0 - radius;
    for (int k = 1; k <= 2; k++) {
        float i = float(k);
        uv.x += distortion * center / i * sin(t + i * 0.4 * smoothstep(0.0, 1.0, uv.y))
                * cos(0.2 * t + i * 2.4 * smoothstep(0.0, 1.0, uv.y));
        uv.y += distortion * center / i * cos(t + i * 2.0 * smoothstep(0.0, 1.0, uv.x));
    }

    float2 rotated = uv - 0.5;
    float angle = -3.0 * swirl * radius;
    float ca = cos(angle);
    float sa = sin(angle);
    rotated = float2(rotated.x * ca - rotated.y * sa, rotated.x * sa + rotated.y * ca) + 0.5;

    float3 color = float3(0.0);
    float totalWeight = 0.0;
    accumulate(color, totalWeight, c1, 0.0, rotated, t, 1.0);
    accumulate(color, totalWeight, c2, 1.0, rotated, t, 1.0);
    accumulate(color, totalWeight, c3, 2.0, rotated, t, 1.0);
    accumulate(color, totalWeight, base, 3.0, rotated, t, baseWeight);
    accumulate(color, totalWeight, base, 4.0, rotated, t, baseWeight);
    color /= max(totalWeight, 0.0001);

    // Intensity: 0 = plain base, 1 = full mesh.
    float3 outCol = mix(base, color, clamp(strength, 0.0, 1.0));

    // One-level dither (about +-0.5/255): breaks up banding in the soft gradient without visible grain.
    outCol += (hash(fragCoord) - 0.5) * 0.004;
    return half4(half3(clamp(outCol, 0.0, 1.0)), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun MercuryBackground(
    base: Color, c1: Color, c2: Color, c3: Color,
    strength: Float,
    isLight: Boolean,
    clock: FloatState,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(MercuryAgsl) }
    val brush = remember(shader) { ShaderBrush(shader) }
    // Colors, strength and the light/dark flip already arrive animated from FlowingBackground.
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", clock.floatValue * MercurySpeed)
        shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
        shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
        shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
        shader.setFloatUniform("base", base.red, base.green, base.blue)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("distortion", MercuryDistortion)
        shader.setFloatUniform("swirl", MercurySwirl)
        shader.setFloatUniform("baseWeight", MercuryBaseWeight)
        drawRect(brush)
    }
}