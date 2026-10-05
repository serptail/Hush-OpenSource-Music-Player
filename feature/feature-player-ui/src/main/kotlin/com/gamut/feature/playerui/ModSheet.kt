package com.gamut.feature.playerui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamut.core.designsystem.CoverPalette
import com.gamut.core.player.ModController
import com.gamut.core.player.ModControl
import com.gamut.core.player.ModPreset
import com.gamut.core.player.ModSettings
import com.gamut.feature.playerui.components.bounceTap
import kotlin.math.roundToInt

/** The Mod panel: a 3x3 grid of presets, and below it only the controls that belong to the selected one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModSheet(accent: Color, onDismiss: () -> Unit) {
    val state by ModController.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CoverPalette.Neutral.base,
        contentColor = Color.White,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Mod", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                if (state.preset != ModPreset.ORIGINAL && state.settings != state.preset.defaults()) {
                    Text(
                        "Reset", fontSize = 14.sp, color = accent,
                        modifier = Modifier.bounceTap(onClick = ModController::reset).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModPreset.entries.chunked(3).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowPresets.forEach { preset ->
                            PresetCell(
                                preset = preset,
                                selected = preset == state.preset,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                                onClick = { ModController.select(preset) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            val controls = ModControl.entries.filter { it in state.preset.controls }
            if (controls.isEmpty()) {
                Text("Pick a mod to reshape the sound.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.55f))
            } else {
                controls.forEach { ControlRow(it, state.settings, accent) }
            }
        }
    }
}

@Composable
private fun PresetCell(preset: ModPreset, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .height(68.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
            .border(BorderStroke(1.dp, if (selected) accent else Color.White.copy(alpha = 0.08f)), shape)
            .bounceTap(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(preset.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(presetHint(preset), fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
    }
}

private fun presetHint(preset: ModPreset): String = when (preset) {
    ModPreset.ORIGINAL -> "No effect"
    ModPreset.CUSTOM -> "Everything"
    else -> "%.2fx".format(preset.defaults().speed)
}

// ---- Sliders ----

private class Spec(
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val get: (ModSettings) -> Float,
    val set: (ModSettings, Float) -> ModSettings,
    val format: (Float) -> String,
)

private fun specFor(control: ModControl): Spec? = when (control) {
    ModControl.SPEED -> Spec("Speed", 0.5f, 2f, 0.05f, { it.speed }, { s, v -> s.copy(speed = v) }, { "%.2fx".format(it) })
    ModControl.PITCH -> Spec("Pitch", -12f, 12f, 0.5f, { it.pitchSemitones }, { s, v -> s.copy(pitchSemitones = v) }, { "%+.1f st".format(it) })
    ModControl.BASS -> Spec("Bass", -6f, 10f, 0.5f, { it.bassDb }, { s, v -> s.copy(bassDb = v) }, { "%+.1f dB".format(it) })
    ModControl.REVERB_MIX -> Spec("Reverb", 0f, 1f, 0.05f, { it.reverbMix }, { s, v -> s.copy(reverbMix = v) }, { "${(it * 100).roundToInt()}%" })
    ModControl.DECAY -> Spec("Decay", 0.3f, 8f, 0.1f, { it.reverbDecaySec }, { s, v -> s.copy(reverbDecaySec = v) }, { "%.1f s".format(it) })
    ModControl.PREDELAY -> Spec("Pre-delay", 0f, 100f, 5f, { it.reverbPreDelayMs }, { s, v -> s.copy(reverbPreDelayMs = v) }, { "${it.roundToInt()} ms" })
    ModControl.DAMPING -> Spec("Damping", 0f, 1f, 0.05f, { it.reverbDamping }, { s, v -> s.copy(reverbDamping = v) }, { "${(it * 100).roundToInt()}%" })
    ModControl.LOWPASS -> Spec(
        "Low-pass", 1_500f, 20_000f, 500f, { it.lowPassHz }, { s, v -> s.copy(lowPassHz = v) },
        { if (it >= 20_000f) "Off" else "%.1f kHz".format(it / 1000f) },
    )
    ModControl.WOBBLE -> Spec("Tape wobble", 0f, 40f, 1f, { it.wobbleCents }, { s, v -> s.copy(wobbleCents = v) }, { "${it.roundToInt()} ct" })
    ModControl.VINYL -> Spec("Vinyl noise", 0f, 1f, 0.05f, { it.vinylNoise }, { s, v -> s.copy(vinylNoise = v) }, { "${(it * 100).roundToInt()}%" })
    ModControl.LINK -> null
}

@Composable
private fun ControlRow(control: ModControl, settings: ModSettings, accent: Color) {
    if (control == ModControl.LINK) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pitch follows speed", fontSize = 14.sp, color = Color.White)
                Text("Tape style. Off keeps the original key.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
            }
            Switch(
                checked = settings.pitchFollowsSpeed,
                onCheckedChange = { on -> ModController.edit { it.copy(pitchFollowsSpeed = on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
            )
        }
        return
    }
    val spec = specFor(control) ?: return
    val value = spec.get(settings).coerceIn(spec.min, spec.max)
    val steps = ((spec.max - spec.min) / spec.step).roundToInt() - 1
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(spec.label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text(spec.format(value), fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f))
        }
        Slider(
            value = value,
            onValueChange = { v -> ModController.edit { spec.set(it, v) } },
            valueRange = spec.min..spec.max,
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}
