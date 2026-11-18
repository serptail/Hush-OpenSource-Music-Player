package com.gamut.core.designsystem

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Overrides the base color ink() tints from. Null means "use the normal theme / cover-palette color".
 * SettingsBlock sets it when it draws a solid fill, so text and icons on that fill stay readable.
 *
 * In ink(), change the base color to:   val base = LocalInkBase.current ?: <your existing base color>
 */
val LocalInkBase = compositionLocalOf<Color?> { null }
