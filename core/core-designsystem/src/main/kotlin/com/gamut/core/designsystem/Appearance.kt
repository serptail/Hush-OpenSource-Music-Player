package com.gamut.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/* -------------------------------------------------------------------------- */
/*  Appearance settings                                                        */
/*                                                                             */
/*  One immutable value describes every look-and-feel choice. It is persisted   */
/*  by AppearanceStore (app module) and provided to the whole tree through      */
/*  LocalAppearance, so FlowingBackground, the acrylic surfaces, GamutTheme     */
/*  and the palette resolver below all read the same source of truth.           */
/* -------------------------------------------------------------------------- */

/** Auto = adaptive only: each cover decides light or dark (the original behaviour). */
enum class AppThemeMode { Auto, System, Light, Dark }

/** Three-step scale shared by the intensity / speed / glass-density controls. */
enum class AppearanceLevel { Low, Medium, High }

@Immutable
data class AppearanceSettings(
    val mode: AppThemeMode = AppThemeMode.Auto,
    /** true = colors come from the playing cover. false = the fixed [preset] palette (no adaptive colors at all). */
    val adaptive: Boolean = true,
    val preset: String = "graphite",
    // adaptive background
    val flowStyle: FlowStyle = FlowStyle.Flow,
    val flowAnimated: Boolean = true,
    val flowIntensity: AppearanceLevel = AppearanceLevel.Medium,
    val flowSpeed: AppearanceLevel = AppearanceLevel.Medium,
    val freezeWhenPaused: Boolean = false,
    // acrylic
    val glass: Boolean = true,
    val glassDensity: AppearanceLevel = AppearanceLevel.Medium,
) {
    /** "Auto" only makes sense while colors adapt; without that it behaves like System. */
    val effectiveMode: AppThemeMode get() = if (mode == AppThemeMode.Auto && !adaptive) AppThemeMode.System else mode

    /** true = dark, false = light, null = let the cover decide (Auto). */
    fun resolvedDark(systemDark: Boolean): Boolean? = when (effectiveMode) {
        AppThemeMode.Auto -> null
        AppThemeMode.System -> systemDark
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

    /** The fixed palette of the chosen preset in light or dark. */
    fun staticPalette(dark: Boolean): CoverPalette = AppColorPresets.byId(preset).let { if (dark) it.dark else it.light }

    /** Solid palette used when nothing is playing, before the first frame (window background) and behind opaque screens. */
    fun neutralPalette(systemDark: Boolean): CoverPalette {
        val dark = resolvedDark(systemDark)
        return when {
            !adaptive -> staticPalette(dark ?: systemDark)
            dark == false -> CoverPalette.NeutralLight
            else -> CoverPalette.Neutral
        }
    }
}

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

// ---- how the levels map to numbers ----
val AppearanceLevel.flowStrength: Float get() = when (this) { AppearanceLevel.Low -> 0.65f; AppearanceLevel.Medium -> 1f; AppearanceLevel.High -> 1.25f }
val AppearanceLevel.flowSpeed: Float get() = when (this) { AppearanceLevel.Low -> 0.5f; AppearanceLevel.Medium -> 1f; AppearanceLevel.High -> 1.7f }
val AppearanceLevel.blurScale: Float get() = when (this) { AppearanceLevel.Low -> 0.7f; AppearanceLevel.Medium -> 1f; AppearanceLevel.High -> 1.35f }
val AppearanceLevel.tintScale: Float get() = when (this) { AppearanceLevel.Low -> 0.8f; AppearanceLevel.Medium -> 1f; AppearanceLevel.High -> 1.3f }

// ---- presets: solid colors with a light and a dark variant; they never follow the cover ----

@Immutable
class AppColorPreset(val id: String, val name: String, val dark: CoverPalette, val light: CoverPalette)

private fun solid(base: Long, accent: Long, light: Boolean): CoverPalette {
    val b = Color(base)
    return CoverPalette(
        primary = b, secondary = b, tertiary = b,
        accent = Color(accent),
        base = b,
        onBase = if (light) Color(0xFF141414) else Color.White,
        isLight = light,
        flows = false,
    )
}

private fun preset(id: String, name: String, darkBase: Long, darkAccent: Long, lightBase: Long, lightAccent: Long) =
    AppColorPreset(id, name, solid(darkBase, darkAccent, false), solid(lightBase, lightAccent, true))

object AppColorPresets {
    val all = listOf(
        preset("graphite", "Graphite", 0xFF0B0B0D, 0xFFFFFFFF, 0xFFF4F4F6, 0xFF141414),
        preset("rose", "Rose", 0xFF1E1016, 0xFFFF8FB1, 0xFFFFF1F5, 0xFFC2185B),
        preset("ocean", "Ocean", 0xFF0D1620, 0xFF7DB9FF, 0xFFEFF6FF, 0xFF1565C0),
        preset("forest", "Forest", 0xFF0E1A14, 0xFF6FD6A0, 0xFFF0F9F3, 0xFF2E7D4F),
        preset("crimson", "Crimson", 0xFF1F0E10, 0xFFFF7A7A, 0xFFFFF1F1, 0xFFC62828),
        preset("violet", "Violet", 0xFF15121F, 0xFFB8A6F5, 0xFFF5F2FF, 0xFF5E4BC2),
        preset("amber", "Amber", 0xFF1F1608, 0xFFFFC266, 0xFFFFF7E8, 0xFFB26A00),
        preset("teal", "Teal", 0xFF0C1A1B, 0xFF5FD4D6, 0xFFEEF9F9, 0xFF00767A),
    )

    fun byId(id: String): AppColorPreset = all.firstOrNull { it.id == id } ?: all[0]
}

// ---- resolvers used by the app root ----

/**
 * THE palette for the whole app. Adaptive: extracted from the cover, forced into the chosen light/dark mode
 * (Auto lets the cover decide). Otherwise: the fixed preset. Provide the result through LocalCoverPalette / CoverTheme.
 */
@Composable
fun rememberAppPalette(artUrl: String?, settings: AppearanceSettings = LocalAppearance.current): CoverPalette {
    val systemDark = isSystemInDarkTheme()
    val dark = settings.resolvedDark(systemDark)
    // No cover is requested when adaptive is off, so nothing is downloaded or analysed for nothing.
    val cover = rememberCoverPalette(if (settings.adaptive) artUrl else null, forceLight = dark?.not())
    return if (settings.adaptive) cover else settings.staticPalette(dark ?: systemDark)
}

/** Solid palette for "nothing playing" and for screens that draw their own opaque background. */
@Composable
fun appNeutralPalette(settings: AppearanceSettings = LocalAppearance.current): CoverPalette =
    settings.neutralPalette(isSystemInDarkTheme())