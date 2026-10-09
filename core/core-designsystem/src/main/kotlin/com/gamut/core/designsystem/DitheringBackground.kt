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

// ======================================================================================
// Tuning. Everything you are likely to want to change is here.
// ======================================================================================

/** Size of one dither cell in dp. 1.5 = fine, 3 = chunky retro, 6 = very coarse. */
private const val DitheringCellDp = 2f

/** How visible the dots are. 0 = a smooth gradient with no dots (fully blurred), 1 = hard dots. */
private const val DitheringCrispness = 0.3f

/** Overall color amount (0..1). Lower keeps more of the screen on the calm base color so text stays readable. */
private const val DitheringAmount = 0.7f

/** Animation speed multiplier on top of the shared clock. */
private const val DitheringSpeed = 0.5f

/** Where the color starts to appear (0..1). Higher = more of the screen stays the plain base color. */
private const val DitheringBias = 0.30f

/** Size of the folds in the underlying field. Higher = more, smaller shapes. */
private const val DitheringScale = 1.6f

/**
 * Ordered dithering over the whole screen, in the style of the Paper Shaders "dithering" shader
 * (paper-design/shaders, Apache-2.0): a continuous flowing field is compared against a Bayer 4x4 threshold
 * matrix, so tone becomes dot density. Instead of two colors it uses four levels, built from the cover:
 * base, then the three accents. Written from the technique, not copied line by line.
 *
 * [strength] scales the field, so a lower intensity (and the light-mode damping) leaves fewer lit pixels and the
 * base stays calm.
 */
private const val DitheringAgsl = """
uniform float2 resolution;
uniform float time;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 base;
uniform float strength;
uniform float cell;
uniform float bias;
uniform float scale;
uniform float crisp;
uniform float amount;

// Bayer matrices without array lookups: the 2x2 pattern, then built up recursively.
float bayer2(float2 a) {
    a = floor(a);
    return fract(a.x / 2.0 + a.y * a.y * 0.75);
}
float bayer4(float2 a) {
    return bayer2(0.5 * a) * 0.25 + bayer2(a);
}

float fieldAt(float2 p, float t) {
    float2 q = p * scale;
    q += 0.45 * float2(sin(q.y * 1.7 + t * 0.70), cos(q.x * 1.5 - t * 0.60));
    q += 0.25 * float2(sin(q.y * 3.1 - t * 0.50 + 1.3), cos(q.x * 2.7 + t * 0.60 + 0.4));
    float v = 0.50 * sin(q.x * 2.0 + q.y * 1.3)
            + 0.30 * sin(q.y * 2.7 - q.x * 0.8)
            + 0.20 * sin((q.x + q.y) * 3.5);
    return 0.5 + 0.5 * v;
}

half4 main(float2 fragCoord) {
    float t = time;
    // Sample the field at the center of the dither cell, so each cell is one flat tone.
    float2 cellId = floor(fragCoord / cell);
    float2 pc = (cellId + 0.5) * cell / resolution.y;

    float f = fieldAt(pc, t);
    f = clamp((f - bias) / (1.0 - bias), 0.0, 1.0);
    f *= clamp(strength, 0.0, 1.0) * amount;

    // 4 levels: base, c1, c2, c3. Compare against the Bayer threshold to decide which side of a step we are on.
    float v = f * 3.0;
    float level = clamp(floor(v + bayer4(cellId)), 0.0, 3.0);

    float3 col = base;
    col = mix(col, c1, step(0.5, level));
    col = mix(col, c2, step(1.5, level));
    col = mix(col, c3, step(2.5, level));

    // The same ramp without the threshold: a smooth gradient. Blending the two "blurs" the dots away.
    float3 smoothCol = mix(base, c1, clamp(v, 0.0, 1.0));
    smoothCol = mix(smoothCol, c2, clamp(v - 1.0, 0.0, 1.0));
    smoothCol = mix(smoothCol, c3, clamp(v - 2.0, 0.0, 1.0));
    col = mix(smoothCol, col, crisp);
    return half4(half3(col), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DitheringBackground(
    base: Color, c1: Color, c2: Color, c3: Color,
    strength: Float,
    isLight: Boolean,
    clock: FloatState,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(DitheringAgsl) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", clock.floatValue * DitheringSpeed)
        shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
        shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
        shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
        shader.setFloatUniform("base", base.red, base.green, base.blue)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("cell", DitheringCellDp * density)
        shader.setFloatUniform("bias", DitheringBias)
        shader.setFloatUniform("scale", DitheringScale)
        shader.setFloatUniform("crisp", DitheringCrispness)
        shader.setFloatUniform("amount", DitheringAmount)
        drawRect(brush)
    }
}