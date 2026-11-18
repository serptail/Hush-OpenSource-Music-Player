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
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.ArrowLineDown
import com.adamglin.phosphoricons.regular.ArrowsOutLineHorizontal
import com.adamglin.phosphoricons.regular.CloudArrowDown
import com.adamglin.phosphoricons.regular.Cube
import com.adamglin.phosphoricons.regular.Disc
import com.adamglin.phosphoricons.regular.DotsNine
import com.adamglin.phosphoricons.regular.Ear
import com.adamglin.phosphoricons.regular.Equalizer
import com.adamglin.phosphoricons.regular.Headphones
import com.adamglin.phosphoricons.regular.Intersect
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.phosphoricons.regular.Plugs
import com.adamglin.phosphoricons.regular.Scales
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SpeakerSimpleSlash
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.WaveSine
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.WifiHigh
import com.gamut.core.designsystem.ink
import com.gamut.core.player.AudioPipelineInfo
import com.gamut.core.player.BandType
import com.gamut.core.player.BitPerfectStatus
import com.gamut.core.player.Crossfeed
import com.gamut.core.player.EqBand
import com.gamut.core.player.PlaybackSettings
import com.gamut.core.player.SoundSettings
import com.gamut.core.player.SpatialMode
import com.gamut.core.player.StreamQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@HiltViewModel
class PlaybackSettingsViewModel
    @Inject
    constructor(
        val settings: PlaybackSettings,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        val sound: SoundSettings = SoundSettings.get(context)
    }

private enum class PlaybackDialog { QUALITY, PRESET, BANDS, RESET }

private val FadeChoices =
    listOf(
        Choice(0, "Off"),
        Choice(300, "Short", "0.3 seconds"),
        Choice(600, "Medium", "0.6 seconds"),
        Choice(1_000, "Long", "1 second"),
    )

private fun hzLabel(hz: Int) = if (hz >= 1_000) String.format(Locale.US, "%.1f kHz", hz / 1_000f).replace(".0 ", " ") else "$hz Hz"

private fun hzShort(hz: Int) = if (hz >= 1_000) String.format(Locale.US, "%.1fk", hz / 1_000f).replace(".0k", "k") else "$hz"

private fun dbLabel(mb: Int) = String.format(Locale.US, "%+.1f dB", mb / 100f)

private val LnMinHz = ln(20f)
private val LnMaxHz = ln(20_000f)

@Composable
private fun BandEditorDialog(
    bands: List<EqBand>,
    sound: SoundSettings,
    onDismiss: () -> Unit,
) {
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
                Text("Band ${i + 1}", style = MaterialTheme.typography.titleMedium, color = ink(), modifier = Modifier.padding(top = 14.dp))
                Column(Modifier.padding(top = 4.dp)) {
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
}

private fun balanceLabel(b: Float): String {
    val pct = (abs(b) * 100).roundToInt()
    return when {
        pct == 0 -> "Centre"
        b < 0 -> "Left $pct%"
        else -> "Right $pct%"
    }
}

@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    nowPlaying: @Composable () -> Unit,
    vm: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.state.collectAsState()
    val snd by vm.sound.state.collectAsState()
    val path by AudioPipelineInfo.state.collectAsState()
    var dialog by remember { mutableStateOf<PlaybackDialog?>(null) }
    var activeBand by remember { mutableStateOf<Int?>(null) }
    val settings = vm.settings
    val sound = vm.sound
    val icons = PhosphorIcons.Regular

    GamutPage(title = "", onBack = onBack, nowPlaying = nowPlaying) {
        Text(
            "Playback & Audio",
            style = MaterialTheme.typography.headlineLarge,
            color = ink(),
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )

        SettingsBlock {
            GroupedHeader("Streaming")
            GroupedRow(
                "Streaming quality",
                icons.Waveform,
                value = s.streamQuality.label,
                subtitle = s.streamQuality.hint,
                onClick = { dialog = PlaybackDialog.QUALITY },
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Preload next track",
                icons.CloudArrowDown,
                checked = s.preload,
                onChange = { v -> settings.update { it.copy(preload = v) } },
                subtitle = "Gapless transitions and instant skips",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Preload on Wi-Fi only",
                icons.WifiHigh,
                checked = s.preloadUnmeteredOnly,
                enabled = s.preload,
                onChange = { v -> settings.update { it.copy(preloadUnmeteredOnly = v) } },
                subtitle = "On mobile data, load it when needed",
            )
            GroupedFooter("Preloading uses a little extra data.")

            GroupedHeader("Playback")
            GroupedSliderRow(
                title = "Crossfade",
                icon = icons.Intersect,
                valueLabel = if (s.crossfadeMs == 0) "Off" else "${s.crossfadeMs / 1000} s",
                value = s.crossfadeMs / 1000f,
                range = 0f..12f,
                steps = 11,
                onChange = { v -> settings.update { it.copy(crossfadeMs = v.roundToInt() * 1000) } },
            )
            GroupedDivider()
            GroupedSegmentedRow(
                title = "Fade on pause and resume",
                icon = icons.Timer,
                options = FadeChoices,
                selected = s.fadeMs,
                onSelect = { ms -> settings.update { it.copy(fadeMs = ms) } },
                hint = FadeChoices.firstOrNull { it.value == s.fadeMs }?.hint,
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Skip silence",
                icons.SpeakerSimpleSlash,
                checked = s.skipSilence,
                onChange = { v -> settings.update { it.copy(skipSilence = v) } },
                subtitle = "Trim long quiet gaps inside tracks",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Pause on unplug",
                icons.Plugs,
                checked = s.pauseOnUnplug,
                onChange = { v -> settings.update { it.copy(pauseOnUnplug = v) } },
                subtitle = "When headphones disconnect",
            )
            GroupedFooter("With crossfade off, songs stay gapless.")

            GroupedHeader("Equalizer")
            GroupedSwitchRow(
                "10-band EQ",
                icons.Equalizer,
                checked = snd.eqEnabled,
                onChange = sound::setEqEnabled,
                subtitle = "64-bit parametric filters",
            )
            Column(Modifier.padding(horizontal = GroupedRowInset).padding(top = 2.dp, bottom = 10.dp)) {
                val band = activeBand?.let { snd.bands.getOrNull(it) }
                Text(
                    if (band != null) {
                        "${hzLabel(band.freqHz.roundToInt())}  ·  ${dbLabel((band.gainDb * 100).roundToInt())}"
                    } else {
                        snd.preset
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink(0.6f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                EqCurve(snd.bands, SoundSettings.MAX_GAIN_DB, snd.eqEnabled, LnMinHz, LnMaxHz)
                Spacer(Modifier.height(12.dp))
                EqBandSliders(
                    bands = snd.bands,
                    maxDb = SoundSettings.MAX_GAIN_DB,
                    enabled = snd.eqEnabled,
                    labelFor = { hzShort(it.freqHz.roundToInt()) },
                    onGain = { i, g -> sound.setBandGain(i, g) },
                    onActive = { activeBand = it },
                )
            }
            GroupedDivider(start = GroupedRowInset)
            GroupedRow("Preset", icons.SlidersHorizontal, value = snd.preset, onClick = { dialog = PlaybackDialog.PRESET })
            GroupedDivider()
            GroupedRow(
                "Frequency and Q",
                icons.WaveSine,
                subtitle = "Move and reshape each band",
                onClick = { dialog = PlaybackDialog.BANDS },
            )

            GroupedHeader("Sound quality")
            GroupedSwitchRow(
                "Automatic headroom",
                icons.ArrowLineDown,
                checked = snd.autoHeadroom,
                onChange = sound::setAutoHeadroom,
                subtitle = "Lowers level by the EQ\u2019s biggest boost",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Soft limiter",
                icons.ShieldCheck,
                checked = snd.limiter,
                onChange = sound::setLimiter,
                subtitle = "Catches peaks before they distort",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Dither",
                icons.DotsNine,
                checked = snd.dither,
                onChange = sound::setDither,
                subtitle = "Keeps quiet detail from rounding away",
            )
            GroupedDivider()
            GroupedSegmentedRow(
                title = "Headphone crossfeed",
                icon = icons.Headphones,
                options = Crossfeed.entries.map { Choice(it, it.label, it.hint) },
                selected = snd.crossfeed,
                onSelect = sound::setCrossfeed,
                subtitle = "Lets some lows cross between ears",
                hint = snd.crossfeed.hint.takeIf { snd.crossfeed != Crossfeed.OFF },
            )
            GroupedDivider()
            GroupedSegmentedRow(
                title = "Spatial audio",
                icon = icons.Cube,
                options = SpatialMode.entries.map { Choice(it, it.label, it.hint) },
                selected = snd.spatial,
                onSelect = sound::setSpatial,
                subtitle = "Simulated surround. Not Dolby Atmos.",
                hint =
                    if (snd.spatial != SpatialMode.OFF) {
                        snd.spatial.hint + if (snd.crossfeed != Crossfeed.OFF) ". Replaces crossfeed while it is on." else ""
                    } else {
                        null
                    },
            )
            GroupedDivider()
            GroupedSliderRow(
                title = "Stereo width",
                icon = icons.ArrowsOutLineHorizontal,
                valueLabel = "${snd.widthPercent}%",
                value = snd.widthPercent.toFloat(),
                range = 0f..200f,
                onChange = { v -> sound.setWidth(if (abs(v - 100f) < 4f) 100 else v.roundToInt()) },
                subtitle = "0% is mono, 100% is as recorded",
                centered = true,
            )
            GroupedFooter(
                "Headroom keeps boosted bands from clipping. Dither adds a whisper of noise when sound is processed.",
            )

            GroupedHeader("Hi-Fi output")
            GroupedSwitchRow(
                "Bit-perfect output",
                icons.Disc,
                checked = s.bitPerfect,
                onChange = { v -> settings.update { it.copy(bitPerfect = v) } },
                subtitle = "Sends the track untouched",
            )
            if (s.bitPerfect && path.bitPerfectNote.isNotEmpty()) {
                GroupedNote(
                    when (path.bitPerfect) {
                        BitPerfectStatus.ACTIVE -> "Bit-perfect  ·  ${path.bitPerfectNote}"
                        else -> path.bitPerfectNote
                    },
                )
            }
            if (path.known) {
                GroupedDivider()
                GroupedRow(
                    "Now playing",
                    icons.MusicNotes,
                    subtitle = path.summary,
                    value = path.tierLabel,
                    chevron = false,
                )
                GroupedDivider()
                GroupedRow(
                    "Output",
                    icons.SpeakerHigh,
                    subtitle = path.routeName.ifEmpty { null },
                    value = path.routeKind.label,
                    chevron = false,
                )
            }
            GroupedFooter(
                "Bit-perfect bypasses the equalizer, spatial audio, speed, crossfade and fades. " +
                    "Plug in a USB DAC on Android 14 or newer to skip the system mixer too.",
            )

            GroupedHeader("Output")
            GroupedSwitchRow(
                "Mono audio",
                icons.Ear,
                checked = s.mono,
                onChange = { v -> settings.update { it.copy(mono = v) } },
                subtitle = "Play both channels as one",
            )
            GroupedDivider()
            GroupedSliderRow(
                title = "Balance",
                icon = icons.Scales,
                valueLabel = balanceLabel(s.balance),
                value = s.balance,
                range = -1f..1f,
                onChange = { v -> settings.update { it.copy(balance = if (abs(v) < 0.04f) 0f else v) } },
                centered = true,
            )
            GroupedFooter("Mono helps with a single earbud or hearing differences.")

            GroupedHeader("Reset")
            GroupedRow(
                "Reset audio settings",
                icons.ArrowCounterClockwise,
                destructive = true,
                onClick = { dialog = PlaybackDialog.RESET },
            )
            GroupedFooter("Puts every option on this page back to its default and sets the equalizer to Flat.")
            Spacer(Modifier.height(8.dp))
        }
    }

    when (dialog) {
        PlaybackDialog.QUALITY -> {
            GamutChoiceDialog(
                title = "Streaming quality",
                choices = StreamQuality.entries.map { Choice(it, it.label, it.hint) },
                selected = s.streamQuality,
                onSelect = { q -> settings.update { it.copy(streamQuality = q) } },
                onDismiss = { dialog = null },
                note = "Applies from the next track you play. Downloads have their own quality setting in Storage & Downloads.",
            )
        }

        PlaybackDialog.PRESET -> {
            GamutChoiceDialog(
                title = "Equalizer preset",
                choices =
                    buildList {
                        addAll(SoundSettings.presetNames.map { Choice(it, it) })
                        if (snd.preset == SoundSettings.CUSTOM) add(Choice(SoundSettings.CUSTOM, "Custom"))
                    },
                selected = snd.preset,
                onSelect = sound::applyPreset,
                onDismiss = { dialog = null },
            )
        }

        PlaybackDialog.BANDS -> {
            BandEditorDialog(snd.bands, sound) { dialog = null }
        }

        PlaybackDialog.RESET -> {
            GamutDialog(
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
        }

        null -> {
            Unit
        }
    }
}