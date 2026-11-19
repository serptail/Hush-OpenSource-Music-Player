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
import com.adamglin.phosphoricons.regular.ArrowsOutLineHorizontal
import com.adamglin.phosphoricons.regular.CloudArrowDown
import com.adamglin.phosphoricons.regular.Cube
import com.adamglin.phosphoricons.regular.DotsNine
import com.adamglin.phosphoricons.regular.Ear
import com.adamglin.phosphoricons.regular.Equalizer
import com.adamglin.phosphoricons.regular.FlowArrow
import com.adamglin.phosphoricons.regular.Intersect
import com.adamglin.phosphoricons.regular.Plugs
import com.adamglin.phosphoricons.regular.Scales
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.SpeakerHifi
import com.adamglin.phosphoricons.regular.SpeakerSimpleSlash
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.WaveSine
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
import com.gamut.core.player.audio.BitPerfectMode
import com.gamut.core.player.audio.LosslessOutput
import com.gamut.core.player.audio.OutputPcmMode
import com.gamut.core.player.audio.spatial.SpatialEngine
import com.gamut.core.player.audio.spatial.SpatialEngineSettings
import com.gamut.feature.resolver.lossless.LosslessSettings
import com.gamut.feature.resolver.lossless.SourceKind
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
        val lossless: LosslessSettings,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        val sound: SoundSettings = SoundSettings.get(context)
        val spatialEngine: SpatialEngineSettings = SpatialEngineSettings.get(context)
    }

private enum class PlaybackDialog {
    QUALITY, PRESET, BANDS, RESET, PRELOAD, CROSSFADE, SPATIAL_ENGINE, BIT_PERFECT, PIPELINE, ADD_SOURCE
}

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
    var openSourceId by remember { mutableStateOf<Any?>(null) }
    var activeBand by remember { mutableStateOf<Int?>(null) }
    val settings = vm.settings
    val sound = vm.sound
    val icons = PhosphorIcons.Regular
    val engine by vm.spatialEngine.engine.collectAsState()
    val ll by vm.lossless.state.collectAsState()
    val llOutput by LosslessOutput.state.collectAsState()
    val customSources = ll.sources.filter { it.kind != SourceKind.JIOSAAVN }

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
                GamutIcons.Stream,
                value = s.streamQuality.label,
                subtitle = s.streamQuality.hint,
                onClick = { dialog = PlaybackDialog.QUALITY },
            )
            GroupedDivider()
            GroupedRow(
                "Preloading",
                icons.CloudArrowDown,
                value = if (!s.preload) "Off" else if (s.preloadUnmeteredOnly) "Wi-Fi only" else "On",
                subtitle = "Gapless transitions and instant skips",
                onClick = { dialog = PlaybackDialog.PRELOAD },
            )

            GroupedHeader("Playback")
            GroupedRow(
                "Crossfade",
                icons.Intersect,
                value = if (s.crossfadeMs == 0) "Off" else "${s.crossfadeMs / 1000} s",
                subtitle = "Blend the end of a track into the next",
                onClick = { dialog = PlaybackDialog.CROSSFADE },
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
                        "${hzLabel(band.freqHz.roundToInt())}  \u00b7  ${dbLabel((band.gainDb * 100).roundToInt())}"
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
                icons.FlowArrow,
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
                icon = GamutIcons.Crossfeed,
                options = Crossfeed.entries.map { Choice(it, it.label, it.hint) },
                selected = snd.crossfeed,
                onSelect = sound::setCrossfeed,
                subtitle = "Lets some lows cross between ears",
                hint = snd.crossfeed.hint.takeIf { snd.crossfeed != Crossfeed.OFF },
            )
            GroupedDivider()
            GroupedSliderRow(
                title = "Spatial audio",
                icon = icons.Cube,
                valueLabel = snd.spatial.label,
                value = snd.spatial.ordinal.toFloat(),
                range = 0f..(SpatialMode.entries.size - 1).toFloat(),
                onChange = { v ->
                    val mode = SpatialMode.entries[v.roundToInt().coerceIn(0, SpatialMode.entries.size - 1)]
                    if (mode != snd.spatial) sound.setSpatial(mode)
                },
            )
            if (snd.spatial != SpatialMode.OFF) {
                GroupedDivider()
                GroupedRow(
                    "Spatial engine",
                    icons.Cube,
                    value = engine.label,
                    subtitle = "Simulated surround. Not Dolby Atmos.",
                    onClick = { dialog = PlaybackDialog.SPATIAL_ENGINE },
                )
            }
            GroupedDivider()
            GroupedSliderRow(
                title = "Stereo width",
                icon = icons.ArrowsOutLineHorizontal,
                valueLabel = when (snd.widthPercent) {
                    0 -> "Mono"
                    100 -> "As recorded"
                    else -> "${snd.widthPercent}%"
                },
                value = snd.widthPercent.toFloat(),
                range = 0f..200f,
                onChange = { v -> sound.setWidth(if (abs(v - 100f) < 4f) 100 else v.roundToInt()) },
                centered = true,
            )
            GroupedFooter("Headroom keeps boosted bands from clipping. Dither adds a whisper of noise when sound is processed.")

            GroupedHeader("Bit-perfect output")
            GroupedRow(
                "Bit-perfect mode",
                bitPerfectIcon(s.bitPerfectMode),
                value = s.bitPerfectMode.label,
                subtitle = s.bitPerfectMode.hint,
                onClick = { dialog = PlaybackDialog.BIT_PERFECT },
            )
            if (s.bitPerfectMode != BitPerfectMode.OFF && path.bitPerfectNote.isNotEmpty()) {
                GroupedNote(
                    when (path.bitPerfect) {
                        BitPerfectStatus.ACTIVE -> "Bit-perfect  \u00b7  ${path.bitPerfectNote}"
                        else -> path.bitPerfectNote
                    },
                )
            }
            GroupedFooter("Bit-perfect bypasses the equalizer, spatial audio, speed, crossfade and fades, and leaves volume to the DAC.")

            GroupedHeader("Format")
            GroupedSwitchRow(
                "32-bit float output",
                GamutIcons.Float32,
                checked = s.outputPcmMode == OutputPcmMode.FLOAT_32,
                onChange = { v -> settings.update { it.copy(outputPcmMode = if (v) OutputPcmMode.FLOAT_32 else OutputPcmMode.PCM_16) } },
                subtitle = "Keeps 24-bit sources intact to the mixer. Applies on the next app start.",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "Dolby Atmos",
                GamutIcons.DolbyAtmos,
                checked = s.dolbyAtmos,
                onChange = { v -> settings.update { it.copy(dolbyAtmos = v) } },
                subtitle = "Ask sources for Atmos when this phone can decode it",
            )
            GroupedFooter("Atmos is immersive, not lossless, and plays through the phone\u2019s own mixer rather than bit-perfect output.")

            if (path.known) {
                GroupedHeader("Now playing")
                GroupedRow(
                    "Stream",
                    GamutIcons.Stream,
                    subtitle = path.summary,
                    value = path.tierLabel,
                    onClick = { dialog = PlaybackDialog.PIPELINE },
                )
                GroupedDivider()
                GroupedRow(
                    "Output",
                    icons.SpeakerHifi,
                    subtitle = path.routeName.ifEmpty { null },
                    value = path.routeKind.label,
                    onClick = { dialog = PlaybackDialog.PIPELINE },
                )
            }

            GroupedHeader("Sources")
            GroupedSwitchRow(
                "Prefer lossless",
                GamutIcons.Lossless,
                checked = ll.preferLossless,
                onChange = vm.lossless::setPreferLossless,
                subtitle =
                    if (llOutput.capable) "Current output can carry lossless audio" else "Current output cannot carry lossless audio",
            )
            GroupedDivider()
            GroupedSwitchRow(
                "JioSaavn",
                GamutIcons.JioSaavn,
                checked = ll.sources.any { it.kind == SourceKind.JIOSAAVN },
                onChange = vm.lossless::setJioSaavn,
                subtitle = "Up to 320 kbps AAC, no account. Used when it beats YouTube.",
            )
            customSources.forEach { src ->
                GroupedDivider()
                GroupedRow(
                    src.displayName,
                    if (src.kind.label.contains("module", ignoreCase = true)) GamutIcons.JavaScript else GamutIcons.Addons,
                    subtitle = "${src.kind.label}  \u00b7  ${src.baseUrl}",
                    onClick = { openSourceId = src.id },
                )
            }
            GroupedDivider()
            GroupedRow(
                "Add a source",
                GamutIcons.Addons,
                subtitle = "An addon server or a module index",
                onClick = { dialog = PlaybackDialog.ADD_SOURCE },
            )
            GroupedFooter(
                "Sources are asked in this order before YouTube. A stream is used only if it is lossless, Dolby Atmos or " +
                    "at least 256 kbps; otherwise YouTube plays, and the better stream is swapped in while the track plays. " +
                    "Gamut ships no sources. You are responsible for what you add.",
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

    val dismiss = { dialog = null }
    when (dialog) {
        PlaybackDialog.QUALITY ->
            GamutChoiceDialog(
                title = "Streaming quality",
                choices = StreamQuality.entries.map { Choice(it, it.label, it.hint) },
                selected = s.streamQuality,
                onSelect = { q -> settings.update { it.copy(streamQuality = q) } },
                onDismiss = dismiss,
                note = "Applies from the next track you play. Downloads have their own quality setting in Storage & Downloads.",
            )

        PlaybackDialog.PRESET ->
            GamutChoiceDialog(
                title = "Equalizer preset",
                choices =
                    buildList {
                        addAll(SoundSettings.presetNames.map { Choice(it, it) })
                        if (snd.preset == SoundSettings.CUSTOM) add(Choice(SoundSettings.CUSTOM, "Custom"))
                    },
                selected = snd.preset,
                onSelect = sound::applyPreset,
                onDismiss = dismiss,
            )

        PlaybackDialog.BANDS -> BandEditorDialog(snd.bands, sound, dismiss)

        PlaybackDialog.PRELOAD ->
            PreloadDialog(
                preload = s.preload,
                wifiOnly = s.preloadUnmeteredOnly,
                onPreload = { v -> settings.update { it.copy(preload = v) } },
                onWifiOnly = { v -> settings.update { it.copy(preloadUnmeteredOnly = v) } },
                onDismiss = dismiss,
            )

        PlaybackDialog.CROSSFADE ->
            CrossfadeDialog(
                seconds = s.crossfadeMs / 1000,
                onChange = { sec -> settings.update { it.copy(crossfadeMs = sec * 1000) } },
                onDismiss = dismiss,
            )

        PlaybackDialog.SPATIAL_ENGINE ->
            GamutChoiceDialog(
                title = "Spatial engine",
                choices = SpatialEngine.entries.map { Choice(it, it.label, it.hint) },
                selected = engine,
                onSelect = vm.spatialEngine::setEngine,
                onDismiss = dismiss,
                note = "Renders the simulated surround. This is not Dolby Atmos.",
            )

        PlaybackDialog.BIT_PERFECT ->
            BitPerfectDialog(
                mode = s.bitPerfectMode,
                onMode = { m -> settings.update { it.copy(bitPerfectMode = m) } },
                allowFixedVolume = s.usbAllowFixedVolume,
                onAllowFixedVolume = { v -> settings.update { it.copy(usbAllowFixedVolume = v) } },
                convertForLimitedDac = s.convertForLimitedDac,
                onConvertForLimitedDac = { v -> settings.update { it.copy(convertForLimitedDac = v) } },
                status = path.bitPerfectNote.takeIf { it.isNotEmpty() },
                onDismiss = dismiss,
            )

        PlaybackDialog.PIPELINE ->
            PipelineDialog(
                rows =
                    buildList {
                        add("Stream" to path.summary)
                        add("Quality" to path.tierLabel)
                        add("Output" to path.routeKind.label)
                        if (path.routeName.isNotEmpty()) add("Device" to path.routeName)
                        add("Bit-perfect" to if (s.bitPerfectMode == BitPerfectMode.OFF) "Off" else path.bitPerfectNote.ifEmpty { s.bitPerfectMode.label })
                    },
                onDismiss = dismiss,
            )

        PlaybackDialog.ADD_SOURCE ->
            AddSourceDialog(
                onAddAddon = vm.lossless::addAddon,
                onAddModuleIndex = vm.lossless::addModuleIndex,
                onDismiss = dismiss,
            )

        PlaybackDialog.RESET ->
            GamutDialog(
                title = "Reset audio settings?",
                body = "Streaming, playback, output and sound-quality options go back to their defaults, and the equalizer returns to Flat.",
                confirmLabel = "Reset",
                destructive = true,
                onDismiss = dismiss,
                onConfirm = {
                    settings.reset()
                    sound.reset()
                    dialog = null
                },
            )

        null -> Unit
    }

    val open = openSourceId?.let { id -> customSources.firstOrNull { it.id == id } }
    if (openSourceId != null && open == null) openSourceId = null
    if (open != null) {
        SourceDialog(
            name = open.displayName,
            kindLabel = open.kind.label,
            url = open.baseUrl,
            position = customSources.indexOf(open),
            total = customSources.size,
            onEarlier = { vm.lossless.move(open.id, -1) },
            onRemove = {
                vm.lossless.removeSource(open.id)
                openSourceId = null
            },
            onDismiss = { openSourceId = null },
        )
    }
}