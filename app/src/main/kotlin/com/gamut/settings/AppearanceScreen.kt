package com.gamut.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gamut.core.designsystem.AcrylicHost
import com.gamut.core.designsystem.AcrylicPill
import com.gamut.core.designsystem.AppColorPresets
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.AppearanceLevel
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.designsystem.FlowStyle
import com.gamut.core.designsystem.FlowingBackground
import com.gamut.core.designsystem.LocalCoverPalette
import com.gamut.core.designsystem.acrylicSource
import com.gamut.core.designsystem.ink
import com.gamut.core.designsystem.pressScale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: AppearanceStore) : ViewModel() {
    val settings: StateFlow<AppearanceSettings> = store.settings
    fun update(transform: (AppearanceSettings) -> AppearanceSettings) = store.update(transform)
    fun reset() = store.update { AppearanceSettings() }
}

/** Appearance: theme mode, adaptive background (or a fixed color preset), and the glass (acrylic) effect. */
@Composable
fun AppearanceScreen(onBack: () -> Unit, nowPlaying: @Composable () -> Unit, vm: AppearanceViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val showDark = s.resolvedDark(systemDark) ?: !LocalCoverPalette.current.isLight

    GamutPage(title = "Appearance", onBack = onBack, nowPlaying = nowPlaying) {
        Spacer(Modifier.height(8.dp))
        Preview()

        // ---- theme ----
        GroupLabel("Theme")
        val modes = buildList {
            if (s.adaptive) add(AppThemeMode.Auto to "Auto")
            add(AppThemeMode.System to "System")
            add(AppThemeMode.Light to "Light")
            add(AppThemeMode.Dark to "Dark")
        }
        GamutSegmented(modes, s.effectiveMode) { m -> vm.update { it.copy(mode = m) } }
        Caption(
            when (s.effectiveMode) {
                AppThemeMode.Auto -> "Each album decides: light covers give a light app, dark covers a dark one."
                AppThemeMode.System -> "Follows your phone's light / dark setting."
                AppThemeMode.Light -> "Light everywhere, including sheets and pop-ups."
                AppThemeMode.Dark -> "Dark everywhere, including sheets and pop-ups."
            },
        )

        // ---- background ----
        GroupLabel("Background")
        Cell(
            title = "Adaptive background", subtitle = "Colors flow from the album you're playing",
            chevron = false, onClick = { vm.update { it.copy(adaptive = !it.adaptive) } },
            trailing = { GamutSwitch(s.adaptive) { v -> vm.update { it.copy(adaptive = v) } } },
        )
        if (s.adaptive) {
            OptionRow("Style") {
                GamutSegmented(
                    listOf(
                        FlowStyle.Flow to "Flow",
                        FlowStyle.Mercury to "Mercury",
                        FlowStyle.Metaballs to "Blobs",
                        FlowStyle.Dithering to "Dither",
                    ),
                    s.flowStyle,
                ) { v -> vm.update { it.copy(flowStyle = v) } }
            }
            Cell(
                title = "Animate", subtitle = "Slowly drifting color", chevron = false,
                onClick = { vm.update { it.copy(flowAnimated = !it.flowAnimated) } },
                trailing = { GamutSwitch(s.flowAnimated) { v -> vm.update { it.copy(flowAnimated = v) } } },
            )
            if (s.flowAnimated) {
                OptionRow("Speed") {
                    GamutSegmented(levels("Slow", "Normal", "Fast"), s.flowSpeed) { v -> vm.update { it.copy(flowSpeed = v) } }
                }
                Cell(
                    title = "Freeze when paused", subtitle = "Stops the motion while nothing is playing", chevron = false,
                    onClick = { vm.update { it.copy(freezeWhenPaused = !it.freezeWhenPaused) } },
                    trailing = { GamutSwitch(s.freezeWhenPaused) { v -> vm.update { it.copy(freezeWhenPaused = v) } } },
                )
            }
            OptionRow("Intensity") {
                GamutSegmented(levels("Calm", "Balanced", "Vivid"), s.flowIntensity) { v -> vm.update { it.copy(flowIntensity = v) } }
            }
        } else {
            OptionRow("Color") {
                PresetGrid(selectedId = s.preset, dark = showDark) { id -> vm.update { it.copy(preset = id) } }
            }
            Caption("Fixed colors in light and dark. They don't change with the music.")
        }

        // ---- glass ----
        GroupLabel("Glass")
        Cell(
            title = "Frosted glass", subtitle = "Blur behind the mini player, bars and buttons. Off is lighter on battery.",
            chevron = false, onClick = { vm.update { it.copy(glass = !it.glass) } },
            trailing = { GamutSwitch(s.glass) { v -> vm.update { it.copy(glass = v) } } },
        )
        if (s.glass) {
            OptionRow("Density") {
                GamutSegmented(levels("Light", "Balanced", "Dense"), s.glassDensity) { v -> vm.update { it.copy(glassDensity = v) } }
            }
        }

        GamutButton("Reset to defaults", onClick = vm::reset, filled = false, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
    }
}

private fun levels(low: String, mid: String, high: String) =
    listOf(AppearanceLevel.Low to low, AppearanceLevel.Medium to mid, AppearanceLevel.High to high)

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ink(0.55f), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun OptionRow(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ink(), modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** Live preview: the real background + a real glass pill, so every change is visible right away. */
@Composable
private fun Preview() {
    val palette = LocalCoverPalette.current
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, ink(0.12f), RoundedCornerShape(26.dp))) {
        AcrylicHost(Modifier.fillMaxSize()) {
            FlowingBackground(palette = palette, isPlaying = true, modifier = Modifier.fillMaxSize().acrylicSource())
            AcrylicPill(Modifier.align(Alignment.Center).padding(horizontal = 28.dp)) {
                Text("Now playing", style = MaterialTheme.typography.titleMedium, color = ink())
            }
        }
    }
}

@Composable
private fun PresetGrid(selectedId: String, dark: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppColorPresets.all.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    val pal = if (dark) p.dark else p.light
                    val selected = p.id == selectedId
                    Column(Modifier.weight(1f).pressScale(onClick = { onPick(p.id) }, scaleDown = 0.95f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(pal.base)
                                .border(if (selected) 2.dp else 1.dp, if (selected) ink(0.95f) else ink(0.2f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(24.dp).background(pal.accent, CircleShape)) }
                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = ink(if (selected) 0.95f else 0.6f), modifier = Modifier.padding(top = 6.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}