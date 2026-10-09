package com.gamut.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ---------------------------------------------------------------------------
 *  GAMUT — light, minimal design system ("acrylic bottle" look)
 *  Thin single-weight outlines, soft shadows, gentle spring-based press
 *  feedback, and a gradient that adapts to whatever's actually playing.
 *  No blur, no dark theme — clean and light, with just enough shadow/gloss
 *  to feel like polished acrylic rather than flat paper.
 * ---------------------------------------------------------------------------
 */

// ---- Neutrals ----
val GamutInk = Color(0xFF1B1B1F)
val GamutMist = Color(0xFF7A7680)
val GamutPaper = Color(0xFFFAFAFC)
val GamutCard = Color(0xFFFFFFFF)
val GamutHairline = Color(0xFFE9E7EC)
val GamutShadow = Color(0x1F1B1B1F) // soft, low-alpha ink shadow — never pure black

// ---- Default accent trio (before any artwork has been analyzed) ----
val GamutAccentA = Color(0xFF8E8CF0) // soft violet
val GamutAccentB = Color(0xFF6FC8E8) // soft sky
val GamutAccentC = Color(0xFFB8A6F5) // soft lilac

private val GamutColors = lightColorScheme(
    primary = GamutAccentA,
    onPrimary = Color.White,
    secondary = GamutAccentB,
    background = GamutPaper,
    surface = GamutCard,
    onBackground = GamutInk,
    onSurface = GamutInk,
    error = Color(0xFFE0526B),
)

/**
 * Fonts, bundled as .ttf under THIS module's res/font/
 * (core/core-designsystem/src/main/res/font/). File names must be lowercase a-z, 0-9 and underscores,
 * so CodeNext-ExtraBold.ttf has to be named codenext_extrabold.ttf, and so on.
 *
 * CodeNext ExtraBold (and its italic, via fontStyle = FontStyle.Italic) is the display face for headings.
 */
val GamutDisplayFont = FontFamily(
    Font(R.font.codenext_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(R.font.codenext_extrabolditalic, FontWeight.ExtraBold, FontStyle.Italic),
)

private val GamutBodyFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val GamutTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
        displayMedium = displayMedium.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
        headlineLarge = headlineLarge.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp),
        headlineMedium = headlineMedium.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 26.sp),
        titleLarge = titleLarge.copy(fontFamily = GamutDisplayFont, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 23.sp),
        titleMedium = titleMedium.copy(fontFamily = GamutBodyFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = bodyLarge.copy(fontFamily = GamutBodyFont, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontFamily = GamutBodyFont, fontSize = 14.sp, lineHeight = 19.sp),
        labelMedium = labelMedium.copy(fontFamily = GamutBodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    )
}

private val GamutDarkColors = darkColorScheme(
    primary = GamutAccentA,
    onPrimary = Color.White,
    secondary = GamutAccentB,
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF16161A),
    onBackground = Color(0xFFF2F2F5),
    onSurface = Color(0xFFF2F2F5),
    error = Color(0xFFFF7A8E),
)

/** [dark] = the resolved light/dark of the whole app (Appearance settings, or the cover in Auto mode). */
@Composable
fun GamutTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) GamutDarkColors else GamutColors, typography = GamutTypography, content = content)
}

@Composable
fun GamutBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(modifier = Modifier.background(GamutPaper), content = content)
}

val CardShape = RoundedCornerShape(18.dp)
val PillShape = RoundedCornerShape(50)
val ArtworkShape = RoundedCornerShape(16.dp)
val ArtworkShapeLarge = RoundedCornerShape(24.dp)

/** Thin, single-weight 1dp outline — the recurring "container" look across the app. */
fun Modifier.hairlineBorder(shape: Shape = CardShape, color: Color = GamutHairline): Modifier =
    this.border(width = 1.dp, color = color, shape = shape)

/** Flat white card with a hairline outline and a soft, low ambient shadow — reads as gently raised acrylic, not flat paper. */
fun Modifier.thinCard(shape: Shape = CardShape, elevation: androidx.compose.ui.unit.Dp = 6.dp): Modifier = this
    .shadow(elevation = elevation, shape = shape, ambientColor = GamutShadow, spotColor = GamutShadow, clip = false)
    .clip(shape)
    .background(GamutCard, shape)
    .hairlineBorder(shape)

/**
 * The "acrylic" frame for album artwork: a thin bright outline that lifts it
 * off the background (the "pop" the person asked for), plus a soft shadow
 * underneath. [strength] scales both — use 1f for the full player's big
 * artwork, ~0.4f for small thumbnails in lists so they only lift a little.
 */
fun Modifier.acrylicFrame(shape: Shape = ArtworkShape, strength: Float = 1f, outlineColor: Color = Color.White): Modifier = this
    .shadow(
        elevation = (10f * strength).dp,
        shape = shape,
        ambientColor = GamutShadow,
        spotColor = GamutShadow,
        clip = false,
    )
    .clip(shape)
    .border(width = (1.2f * strength).dp.coerceAtLeast(0.5.dp), color = outlineColor.copy(alpha = 0.55f * strength + 0.15f), shape = shape)

/**
 * Diagonal gloss highlight — a faint white sheen across the top-left corner
 * of a surface, the detail that sells "acrylic/glass" over "flat sticker."
 * Draw this as the LAST element inside a Box wrapping the artwork/card.
 */
@Composable
fun BoxScope.AcrylicGloss(shape: Shape = ArtworkShape, alpha: Float = 0.16f) {
    Box(
        Modifier
            .matchParentSize()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(400f, 500f),
                ),
            ),
    )
}

/**
 * Three-stop adaptive gradient behind the full-screen player — colors are
 * expected to already be pre-animated (see rememberDynamicAccent), so this
 * itself needs no animation logic. Diagonal rather than vertical reads
 * cleaner with three stops instead of muddying in the middle.
 */
fun adaptivePlayerGradient(top: Color, mid: Color, bottom: Color): Brush = Brush.linearGradient(
    colors = listOf(top, mid, bottom),
    start = Offset(0f, 0f),
    end = Offset(600f, 1200f),
)