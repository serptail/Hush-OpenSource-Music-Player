package com.gamut.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gamut.core.designsystem.ink
import com.gamut.core.player.BandType
import com.gamut.core.player.Crossfeed
import com.gamut.core.player.EqBand
import com.gamut.core.player.PlaybackSettings
import com.gamut.core.player.SoundSettings
import com.gamut.core.player.StreamQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Both stores are app-wide singletons that outlive this screen; the ViewModel only hands them to the UI. */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    val settings: PlaybackSettings,
    @ApplicationContext context: Context,
) : ViewModel() {
    val sound: SoundSettings = SoundSettings.get(context)
}

private enum class PlaybackDialog { QUALITY, FADE, PRESET, BANDS, CROSSFEED, RESET }

private val FadeChoices = listOf(
    Choice(0, "Off"),
    Choice(300, "Short", "0.3 seconds"),
    Choice(600, "Medium", "0.6 seconds"),
    Choice(1_000, "Long", "1 second"),
)

private fun fadeLabel(ms: Int) = FadeChoices.firstOrNull { it.value == ms }?.let { c ->
    if (c.hint == null) c.label else "${c.label} (${c.hint})"
} ?: "${ms} ms"

private fun hzLabel(hz: Int) =
    if (hz >= 1_000) String.format(Locale.US, "%.1f kHz", hz / 1_000f).replace(".0 ", " ") else "$hz Hz"

private fun dbLabel(mb: Int) = String.format(Locale.US, "%+.1f dB", mb / 100f)

private fun bandTitle(b: EqBand): String {
    val f = hzLabel(b.freqHz.roundToInt())
    return when (b.type) {
        BandType.LOW_SHELF -> "$f  ·  low shelf"
        BandType.HIGH_SHELF -> "$f  ·  high shelf"
        BandType.PEAK -> f
    }
}

private val LnMinHz = ln(20f)
private val LnMaxHz = ln(20_000f)

/** Every band's frequency and width. Frequency is a log slider: equal steps are equal musical intervals. */
@Composable
private fun BandEditorDialog(bands: List<EqBand>, sound: SoundSettings, onDismiss: () -> Unit) {
    GamutDialog(
        title = "Frequency and Q",
        body = "Move each band along the spectrum and set how wide it is. A lower Q is wider.",
        confirmLabel = "Done",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null,
    ) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            bands.forEachIndexed { i, b ->
                Text(
                    "Band ${i + 1}", style = MaterialTheme.typography.titleMedium, color = ink(),
                    modifier = Modifier.padding(top = 14.dp),
                )
                SliderCell(
                    title = "Frequency",
                    valueLabel = hzLabel(b.freqHz.roundToInt()),
                    value = ln(b.freqHz),
                    range = LnMinHz..LnMaxHz,
                    onChange = { sound.setBandFreq(i, exp(it).roundToInt().toFloat()) },
                )
                SliderCell(
                    title = if (b.type == BandType.PEAK) "Q" else "Steepness",
                    valueLabel = String.format(Locale.US, "%.2f", b.q),
                    value = b.q,
                    range = 0.3f..8f,
                    onChange = { sound.setBandQ(i, (it * 20).roundToInt() / 20f) },
                )
            }
        }
    }
}

private fun balanceLabel(b: Float): String {
    val pct = (abs(b) * 100).roundToInt()
    return when {
        pct == 0 -> "Centre"
        b < 0 -> "Left $pct%"
        else -> "Right $pct%"
    }
}

/** Settings > Playback & Audio. Every change is saved and applied to the player straight away. */
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    nowPlaying: @Composable () -> Unit,
    vm: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.state.collectAsState()
    val snd by vm.sound.state.collectAsState()
    var dialog by remember { mutableStateOf<PlaybackDialog?>(null) }
    val settings = vm.settings
    val sound = vm.sound

    GamutPage(title = "Playback & Audio", onBack = onBack, nowPlaying = nowPlaying) {

        GroupLabel("Streaming")
        Cell(
            "Streaming quality",
            subtitle = if (s.streamQuality == StreamQuality.AUTO) "Auto: ${s.streamQuality.hint}" else "${s.streamQuality.label}: ${s.streamQuality.hint}",
            onClick = { dialog = PlaybackDialog.QUALITY },
        )
        SwitchCell(
            "Preload next track",
            checked = s.preload,
            onChange = { v -> settings.update { it.copy(preload = v) } },
            subtitle = "Gapless transitions and instant skips. Uses a little extra data.",
        )
        SwitchCell(
            "Preload on Wi-Fi only",
            checked = s.preloadUnmeteredOnly,
            enabled = s.preload,
            onChange = { v -> settings.update { it.copy(preloadUnmeteredOnly = v) } },
            subtitle = "On mobile data the next track loads when you get to it.",
        )

        GroupLabel("Playback")
        SliderCell(
            title = "Crossfade",
            valueLabel = if (s.crossfadeMs == 0) "Off" else "${s.crossfadeMs / 1000} s",
            value = s.crossfadeMs / 1000f,
            range = 0f..12f,
            steps = 11,
            onChange = { v -> settings.update { it.copy(crossfadeMs = v.roundToInt() * 1000) } },
            subtitle = "Songs overlap as one ends and the next begins. Off keeps them gapless.",
        )
        Cell(
            "Fade on pause and resume",
            subtitle = fadeLabel(s.fadeMs),
            onClick = { dialog = PlaybackDialog.FADE },
        )
        SwitchCell(
            "Skip silence",
            checked = s.skipSilence,
            onChange = { v -> settings.update { it.copy(skipSilence = v) } },
            subtitle = "Cut long silent gaps inside tracks, like in live recordings.",
        )
        SwitchCell(
            "Pause when headphones disconnect",
            checked = s.pauseOnUnplug,
            onChange = { v -> settings.update { it.copy(pauseOnUnplug = v) } },
        )

        GroupLabel("Equalizer")
        SwitchCell(
            "10-band parametric EQ",
            checked = snd.eqEnabled,
            onChange = sound::setEqEnabled,
            subtitle = "${snd.preset}. 64-bit filters, tuned band by band.",
        )
        Cell("Preset", subtitle = snd.preset, onClick = { dialog = PlaybackDialog.PRESET })
        snd.bands.forEachIndexed { i, band ->
            SliderCell(
                title = bandTitle(band),
                valueLabel = dbLabel((band.gainDb * 100).roundToInt()),
                value = band.gainDb,
                range = -SoundSettings.MAX_GAIN_DB..SoundSettings.MAX_GAIN_DB,
                onChange = { sound.setBandGain(i, (it * 10).roundToInt() / 10f) },
                enabled = snd.eqEnabled,
            )
        }
        Cell("Frequency and Q", subtitle = "Move and reshape each band", onClick = { dialog = PlaybackDialog.BANDS })

        GroupLabel("Sound quality")
        SwitchCell(
            "Automatic headroom",
            checked = snd.autoHeadroom,
            onChange = sound::setAutoHeadroom,
            subtitle = "Lowers the level by exactly the EQ's biggest boost, so boosted bands cannot clip.",
        )
        SwitchCell(
            "Soft limiter",
            checked = snd.limiter,
            onChange = sound::setLimiter,
            subtitle = "Catches peaks just below full scale before they distort.",
        )
        SwitchCell(
            "Dither",
            checked = snd.dither,
            onChange = sound::setDither,
            subtitle = "Adds a whisper of triangular noise when the sound is processed, so quiet detail is not rounded away.",
        )
        Cell(
            "Headphone crossfeed",
            subtitle = if (snd.crossfeed == Crossfeed.OFF) "Off" else "${snd.crossfeed.label}: ${snd.crossfeed.hint}",
            onClick = { dialog = PlaybackDialog.CROSSFEED },
        )
        SliderCell(
            title = "Stereo width",
            valueLabel = "${snd.widthPercent}%",
            value = snd.widthPercent.toFloat(),
            range = 0f..200f,
            // Snap to the middle so "as recorded" is easy to hit.
            onChange = { v -> sound.setWidth(if (abs(v - 100f) < 4f) 100 else v.roundToInt()) },
            subtitle = "0% is mono, 100% is the recording as it is.",
        )

        GroupLabel("Output")
        SwitchCell(
            "Mono audio",
            checked = s.mono,
            onChange = { v -> settings.update { it.copy(mono = v) } },
            subtitle = "Play both channels as one. Helps with a single earbud or hearing differences.",
        )
        SliderCell(
            title = "Balance",
            valueLabel = balanceLabel(s.balance),
            value = s.balance,
            range = -1f..1f,
            // Snap to the middle so "centred" is easy to hit.
            onChange = { v -> settings.update { it.copy(balance = if (abs(v) < 0.04f) 0f else v) } },
        )

        Spacer(Modifier.height(24.dp))
        Cell("Reset audio settings", destructive = true, chevron = false, onClick = { dialog = PlaybackDialog.RESET })
    }

    when (dialog) {
        PlaybackDialog.QUALITY -> GamutChoiceDialog(
            title = "Streaming quality",
            choices = StreamQuality.entries.map { Choice(it, it.label, it.hint) },
            selected = s.streamQuality,
            onSelect = { q -> settings.update { it.copy(streamQuality = q) } },
            onDismiss = { dialog = null },
            note = "Applies from the next track you play. Downloads always use the best quality.",
        )
        PlaybackDialog.FADE -> GamutChoiceDialog(
            title = "Fade on pause and resume",
            choices = FadeChoices,
            selected = s.fadeMs,
            onSelect = { ms -> settings.update { it.copy(fadeMs = ms) } },
            onDismiss = { dialog = null },
        )
        PlaybackDialog.PRESET -> GamutChoiceDialog(
            title = "Equalizer preset",
            choices = buildList {
                addAll(SoundSettings.presetNames.map { Choice(it, it) })
                if (snd.preset == SoundSettings.CUSTOM) add(Choice(SoundSettings.CUSTOM, "Custom"))
            },
            selected = snd.preset,
            onSelect = sound::applyPreset,
            onDismiss = { dialog = null },
        )
        PlaybackDialog.BANDS -> BandEditorDialog(snd.bands, sound) { dialog = null }
        PlaybackDialog.CROSSFEED -> GamutChoiceDialog(
            title = "Headphone crossfeed",
            choices = Crossfeed.entries.map { Choice(it, it.label, it.hint) },
            selected = snd.crossfeed,
            onSelect = sound::setCrossfeed,
            onDismiss = { dialog = null },
            note = "On headphones each ear hears only one channel, which is not how music is mixed. Crossfeed lets a little of the lows cross over, like speakers do.",
        )
        PlaybackDialog.RESET -> GamutDialog(
            title = "Reset audio settings?",
            body = "Streaming, playback, output and sound-quality options go back to their defaults, and the equalizer returns to Flat.",
            confirmLabel = "Reset",
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                settings.reset()
                sound.reset()
                dialog = null
            },
        )
        null -> Unit
    }
}