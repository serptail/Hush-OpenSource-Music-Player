package com.gamut.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.LocalCoverPalette

/**
 * Gamut's own colours, one set per setup page. Each set is a single hue in three shades (the accent, a deeper and a lighter one), so the flow stays homogeneous rather than a rainbow. [FlowingBackground] takes three colours:
 * the first is the page's accent (progress dots, cursor, import bar), the other two are its companions.
 */
private val PageGlow: List<List<Color>> =
    listOf(
        // Name: rose red.
        listOf(Color(0xFFFA2D48), Color(0xFFB81F38), Color(0xFFFF6B80)),
        // Look: raspberry pink.
        listOf(Color(0xFFD6336C), Color(0xFF9C1F4E), Color(0xFFF06595)),
        // Folder: deep teal.
        listOf(Color(0xFF12A594), Color(0xFF0B6E63), Color(0xFF4FD1C1)),
        // Import: indigo, no green, it would fight the Spotify logo.
        listOf(Color(0xFF3D5AFE), Color(0xFF2B3FC4), Color(0xFF6F85FF)),
        // Account: warm orange.
        listOf(Color(0xFFFF7A1A), Color(0xFFC4520A), Color(0xFFFFA45C)),
    )

/** The accent of [page]: the fixed colour scheme when one is picked, otherwise the page's own gamut colour. */
internal fun pageAccent(page: Int, tint: Color?): Color = tint ?: PageGlow[page.mod(PageGlow.size)][0]

/**
 * The app's own [FlowingBackground] (so it follows the user's flow style, speed and intensity settings), fed with
 * gamut colours instead of cover art, since nothing is playing during setup. The colours change with [page] and the
 * background cross-fades between them by itself. [tint] replaces them with shades of one colour when a fixed colour
 * scheme is picked on the Look page.
 */
@Composable
internal fun GamutBackground(page: Int, tint: Color?, modifier: Modifier = Modifier) {
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