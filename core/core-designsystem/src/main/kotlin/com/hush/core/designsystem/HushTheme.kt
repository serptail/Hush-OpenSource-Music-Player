package com.hush.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ---------------------------------------------------------------------------
 *  HUSH — light, minimal design system (Samsung Music style)
 *  Flat, airy, thin single-weight outlines, soft pastel gradients that ease
 *  toward the current track's dominant color. No blur, no dark theme, no
 *  glow — calm and static except for gentle color crossfades.
 * ---------------------------------------------------------------------------
 */

// ---- Neutrals ----
val HushInk = Color(0xFF1B1B1F)
val HushMist = Color(0xFF7A7680)
val HushPaper = Color(0xFFFAFAFC)
val HushCard = Color(0xFFFFFFFF)
val HushHairline = Color(0xFFE9E7EC)

// ---- Default accent pair (before any artwork has been analyzed) ----
val HushAccentA = Color(0xFFFF8FB1) // soft rose
val HushAccentB = Color(0xFFFFC2A8) // soft peach

private val HushColors = lightColorScheme(
    primary = HushAccentA,
    onPrimary = Color.White,
    secondary = HushAccentB,
    background = HushPaper,
    surface = HushCard,
    onBackground = HushInk,
    onSurface = HushInk,
    error = Color(0xFFE0526B),
)

/**
 * Fonts, bundled as .ttf under THIS module's res/font/
 * (core/core-designsystem/src/main/res/font/). "Manrope" for headlines reads
 * clean and geometric without feeling cold; "Inter" for body copy.
 * Missing a weight? Delete that Font(...) line — FontFamily falls back
 * gracefully, it won't break the build.
 */
private val HushDisplayFont = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
)

private val HushBodyFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val HushTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = HushDisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
        headlineLarge = headlineLarge.copy(fontFamily = HushDisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp),
        headlineMedium = headlineMedium.copy(fontFamily = HushDisplayFont, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 26.sp),
        titleLarge = titleLarge.copy(fontFamily = HushDisplayFont, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 23.sp),
        titleMedium = titleMedium.copy(fontFamily = HushBodyFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = bodyLarge.copy(fontFamily = HushBodyFont, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontFamily = HushBodyFont, fontSize = 14.sp, lineHeight = 19.sp),
        labelMedium = labelMedium.copy(fontFamily = HushBodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HushColors, typography = HushTypography, content = content)
}

@Composable
fun HushBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.background(HushPaper), content = content)
}

val CardShape = RoundedCornerShape(18.dp)
val PillShape = RoundedCornerShape(50)
val ArtworkShape = RoundedCornerShape(16.dp)
val ArtworkShapeLarge = RoundedCornerShape(24.dp)

/** Thin, single-weight 1dp outline — the one recurring "container" look across the app. */
fun Modifier.hairlineBorder(shape: Shape = CardShape): Modifier =
    this.border(width = 1.dp, color = HushHairline, shape = shape)

/** Flat white card with a hairline outline, no shadow, no blur. */
fun Modifier.thinCard(shape: Shape = CardShape): Modifier = this
    .clip(shape)
    .background(HushCard, shape)
    .hairlineBorder(shape)

/**
 * The soft two-tone wash behind the full-screen player — a plain diagonal
 * gradient, no blur, no motion. Pass already-animated colors (see
 * rememberDynamicAccent); this itself needs no animation logic.
 */
fun softPlayerGradient(top: Color, bottom: Color): Brush = Brush.verticalGradient(listOf(top, bottom))