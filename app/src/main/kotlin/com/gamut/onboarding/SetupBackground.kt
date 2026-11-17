package com.gamut.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.LocalCoverPalette

private val PageGlow: List<List<Color>> =
    listOf(
        listOf(Color(0xFFFA2D48), Color(0xFFB81F38), Color(0xFFFF6B80)),
        listOf(Color(0xFFD6336C), Color(0xFF9C1F4E), Color(0xFFF06595)),
        listOf(Color(0xFF12A594), Color(0xFF0B6E63), Color(0xFF4FD1C1)),
        listOf(Color(0xFF3D5AFE), Color(0xFF2B3FC4), Color(0xFF6F85FF)),
        listOf(Color(0xFFFF7A1A), Color(0xFFC4520A), Color(0xFFFFA45C)),
    )

internal fun pageAccent(
    page: Int,
    tint: Color?,
): Color = tint ?: PageGlow[page.mod(PageGlow.size)][0]

@Composable
internal fun GamutBackground(
    page: Int,
    tint: Color?,
    modifier: Modifier = Modifier,
) {
    val current = LocalCoverPalette.current
    val palette =
        remember(page, tint, current) {
            val colors =
                if (tint != null) {
                    listOf(tint, lerp(tint, current.onBase, 0.25f), lerp(tint, current.base, 0.35f))
                } else {
                    PageGlow[page.mod(PageGlow.size)]
                }
            current.copy(primary = colors[0], secondary = colors[1], tertiary = colors[2], flows = true)
        }
    FlowingBackground(palette = palette, isPlaying = true, visible = true, modifier = modifier)
}
