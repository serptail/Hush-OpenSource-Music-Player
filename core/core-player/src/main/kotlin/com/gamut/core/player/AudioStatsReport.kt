package com.gamut.core.player

import java.util.Locale

enum class StatState {
    /** Plain information. */
    INFO,

    /** The feature is working on this stream. */
    ON,

    /** The feature is off or switched off by a setting. */
    OFF,

    /** The pipeline fell back from what was asked for, or a stage was skipped. */
    WARN,
}

data class StatRow(
    val label: String,
    val value: String,
    val state: StatState = StatState.INFO,
)

data class StatsSection(
    val title: String,
    val rows: List<StatRow>,
)

data class StatsReport(
    /** One line for what is playing, such as "Opus · 142 kbps · 48 kHz · Stereo". */
    val headline: String,
    /** The signal path from stream to speaker, in order. */
    val chain: List<String>,
    /** Everything that did not go the way it was asked to, one sentence each. */
    val fallbacks: List<String>,
    val sections: List<StatsSection>,
) {
    /** A plain-text copy of the whole report, for pasting into a bug report. */
    fun asText(): String =
        buildString {
            appendLine(headline.ifBlank { "Nothing playing" })
            if (chain.isNotEmpty()) appendLine(chain.joinToString("  →  "))
            if (fallbacks.isNotEmpty()) {
                appendLine()
                appendLine("Fallbacks and notes")
                fallbacks.forEach { appendLine("  - $it") }
            }
            sections.forEach { section ->
                appendLine()
                appendLine(section.title)
                section.rows.forEach { appendLine("  ${it.label}: ${it.value}") }
            }
        }.trimEnd()
}

/** Every sound setting that can change what reaches the speaker, flattened to plain values. */
data class DspState(
    /** The bit-perfect setting. While it is on, the sound engine and the mod stage are bypassed. */
    val bitPerfectRequested: Boolean = false,
    val speed: Float = 1f,
    val pitchRatio: Float = 1f,
    val bassDb: Float = 0f,
    val reverbMix: Float = 0f,
    val reverbDecaySec: Float = 0f,
    val reverbPreDelayMs: Float = 0f,
    val reverbDamping: Float = 0f,
    val lowPassHz: Float = 20_000f,
    val wobbleCents: Float = 0f,
    val vinylNoise: Float = 0f,
    val mono: Boolean = false,
    val balance: Float = 0f,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "",
    val eqBandsShaped: Int = 0,
    val eqBandCount: Int = 0,
    val autoHeadroom: Boolean = true,
    val limiter: Boolean = true,
    val dither: Boolean = true,
    val crossfeed: String = "Off",
    val widthPercent: Int = 100,
    val spatial: String = "Off",
    val fadeMs: Int = 0,
    val crossfadeMs: Int = 0,
    val skipSilence: Boolean = false,
)

/** What the player knows about the stream and the output beyond the codec itself. */
data class PipelineDetails(
    /** "Network stream" or "Local file". Empty while nothing is loaded. */
    val origin: String = "",
    val qualitySetting: String = "",
    val qualityEffective: String = "",
    val container: String = "",
    val atmos: Boolean = false,
    val decoder: String = "",
    /** The PCM the decoder hands to the sound engine. All zero until the output has opened once. */
    val pcmRateHz: Int = 0,
    val pcmChannels: Int = 0,
    val pcmBits: Int = 0,
    val pcmFloat: Boolean = false,
    /** The rate Android's own mixer runs at, which a stream at any other rate is converted to. */
    val mixerRateHz: Int = 0,
    val androidSdk: Int = 0,
    val bandwidthKbps: Long = 0,
    val bufferAheadMs: Long = -1,
    val underruns: Int = 0,
    val dsp: DspState = DspState(),
)

object AudioStatsReport {
    private const val SOFT = 0.001f

    private const val MIN_BIT_PERFECT_SDK = 34

    fun build(
        path: SignalPath,
        d: PipelineDetails,
    ): StatsReport {
        if (!path.known) {
            return StatsReport(
                headline = "",
                chain = emptyList(),
                fallbacks = emptyList(),
                sections = listOf(StatsSection("Output", outputRows(path, d, Plan.NONE))),
            )
        }
        val plan = Plan.of(path, d)
        return StatsReport(
            headline = headline(path),
            chain = chain(path, d, plan),
            fallbacks = fallbacks(path, d, plan),
            sections =
                buildList {
                    add(StatsSection("Stream", streamRows(path, d)))
                    add(StatsSection("Decoder", decoderRows(path, d, plan)))
                    add(StatsSection("Sound engine", engineRows(d, plan)))
                    add(StatsSection("Mod", modRows(d, plan)))
                    add(StatsSection("Transitions", transitionRows(d)))
                    add(StatsSection("Output", outputRows(path, d, plan)))
                    if (d.origin == NETWORK) add(StatsSection("Network", networkRows(d)))
                },
        )
    }

    /** Which DSP stages can actually run on this stream, decided from the PCM that reaches them. */
    private class Plan(
        val pcmKnown: Boolean,
        val bypassed: Boolean,
        val engineRuns: Boolean,
        val modRuns: Boolean,
    ) {
        companion object {
            val NONE = Plan(pcmKnown = false, bypassed = false, engineRuns = false, modRuns = false)

            fun of(
                path: SignalPath,
                d: PipelineDetails,
            ): Plan {
                val known = d.pcmRateHz > 0
                val bypass = d.dsp.bitPerfectRequested
                val channelsOk = d.pcmChannels in 1..2
                // The sound engine reads 16-bit or float PCM, the mod stage only 16-bit.
                val engine = known && !bypass && channelsOk && (d.pcmFloat || d.pcmBits == 16)
                val mod = known && !bypass && channelsOk && !d.pcmFloat && d.pcmBits == 16
                return Plan(known, bypass, engine, mod)
            }
        }
    }

    private fun headline(path: SignalPath): String =
        listOfNotNull(
            path.codec,
            if (path.lossless) null else path.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" },
            if (path.lossless) path.bitDepth.takeIf { it > 0 }?.let { "$it-bit" } else null,
            path.sampleRateHz.takeIf { it > 0 }?.let(::khz),
            channelLabel(path.channels).takeIf { path.channels > 0 },
        ).joinToString(" · ")

    private fun chain(
        path: SignalPath,
        d: PipelineDetails,
        plan: Plan,
    ): List<String> =
        buildList {
            add(if (d.atmos) "${path.codec} (Dolby Atmos)" else path.summary.replace("  ·  ", " · ").ifBlank { path.codec })
            if (plan.pcmKnown) {
                add("Decode: ${pcmLabel(d)} · ${khz(d.pcmRateHz)}")
                when {
                    plan.bypassed -> add("DSP bypassed")
                    plan.engineRuns -> add("DSP: ${activeStages(d, plan).joinToString(", ").ifBlank { "neutral" }}")
                    else -> add("DSP skipped")
                }
                add(outputFormatLabel(d, plan))
            }
            add(mixerLabel(path, d))
            add(routeLabel(path))
        }

    private fun activeStages(
        d: PipelineDetails,
        plan: Plan,
    ): List<String> {
        val x = d.dsp
        return buildList {
            if (plan.modRuns) {
                if (x.speed != 1f || x.pitchRatio != 1f) add("Speed/Pitch")
                if (x.bassDb != 0f) add("Bass")
                if (x.reverbMix > SOFT) add("Reverb")
                if (x.lowPassHz < 20_000f) add("Low-pass")
                if (x.wobbleCents > SOFT) add("Wobble")
                if (x.vinylNoise > SOFT) add("Vinyl")
                if (x.mono) add("Mono")
                if (x.balance != 0f) add("Balance")
            }
            if (plan.engineRuns) {
                if (x.eqEnabled) add("EQ")
                if (x.crossfeed != "Off") add("Crossfeed")
                if (x.widthPercent != 100) add("Width")
                if (x.spatial != "Off") add("Spatial")
                if (x.limiter) add("Limiter")
                if (x.dither) add("Dither")
            }
        }
    }

    private fun fallbacks(
        path: SignalPath,
        d: PipelineDetails,
        plan: Plan,
    ): List<String> =
        buildList {
            val x = d.dsp

            // Quality
            if (d.origin == NETWORK && d.qualityEffective == "High" && !path.lossless && !d.atmos && path.codec != "Opus") {
                add("High quality asks for Opus, but this track arrived as ${path.codec} ${path.bitrateKbps} kbps.")
            }

            // Bit-perfect
            if (x.bitPerfectRequested) {
                when (path.bitPerfect) {
                    BitPerfectStatus.UNPROCESSED ->
                        add("Bit-perfect is on but Android still mixes the stream. ${path.bitPerfectNote}".trim())
                    BitPerfectStatus.OFF -> add("Bit-perfect is on, but the output has not opened yet.")
                    BitPerfectStatus.ACTIVE -> Unit
                }
                if (d.androidSdk in 1 until MIN_BIT_PERFECT_SDK && path.bitPerfect != BitPerfectStatus.UNPROCESSED) {
                    add("Android 14 or newer is needed for the bit-perfect mixer.")
                }
                if (x.crossfadeMs > 0 || x.fadeMs > 0) add("Fades and crossfade are held off, because they would change the samples.")
                if (x.speed != 1f || x.pitchRatio != 1f) add("Speed and pitch are held at 1.0×, because they resample the stream.")
            }

            // DSP that cannot run on this PCM
            if (plan.pcmKnown && !plan.bypassed) {
                if (!plan.engineRuns) {
                    val format = "${pcmLabel(d)} ${channelLabel(d.pcmChannels)}"
                    add("The sound engine cannot process $format, so EQ, limiter, crossfeed and spatial are skipped.")
                } else if (d.pcmFloat) {
                    val dithered = if (x.dither) "with" else "without"
                    add("Decoded as 32-bit float, but the sound engine outputs 16-bit PCM $dithered dither.")
                }
                if (plan.engineRuns && !plan.modRuns && modWanted(x)) {
                    add("Reverb, bass, vinyl, wobble and speed need 16-bit PCM, so they are skipped for ${pcmLabel(d)}.")
                }
            }

            // Resampling
            if (plan.pcmKnown && d.mixerRateHz > 0 && d.pcmRateHz != d.mixerRateHz && path.bitPerfect != BitPerfectStatus.ACTIVE) {
                add("Android resamples ${khz(d.pcmRateHz)} to ${khz(d.mixerRateHz)}. Bit-perfect output avoids this.")
            }

            // Bit depth lost before the sound engine or the DAC ever sees it
            if (plan.pcmKnown && path.bitDepth > 0 && d.pcmBits in 1 until path.bitDepth && !d.pcmFloat) {
                val dropped = path.bitDepth - d.pcmBits
                add("The source is ${path.bitDepth}-bit, but the decoder outputs ${d.pcmBits}-bit PCM, so $dropped bits are dropped.")
            }

            // Spatial
            if (x.spatial != "Off" && plan.engineRuns) {
                add("Spatial audio is a simulation on stereo, not Dolby Atmos decoding.")
            }
            if (d.atmos && x.spatial != "Off") {
                add("This stream is Dolby Atmos, but the spatial simulation processes the downmix.")
            }

            // Network
            if (d.underruns > 0) add("The output ran dry ${d.underruns} time${if (d.underruns == 1) "" else "s"} on this session.")
        }

    private fun stageOff(
        plan: Plan,
        runs: Boolean,
    ): String =
        when {
            plan.bypassed -> "Bypassed (bit-perfect)"
            plan.pcmKnown && !runs -> "Skipped for this format"
            else -> "Off"
        }

    private fun modWanted(x: DspState): Boolean {
        val tempo = x.speed != 1f || x.pitchRatio != 1f
        val tone = x.bassDb != 0f || x.lowPassHz < 20_000f
        val colour = x.reverbMix > SOFT || x.wobbleCents > SOFT || x.vinylNoise > SOFT
        return tempo || tone || colour
    }

    private fun streamRows(
        path: SignalPath,
        d: PipelineDetails,
    ): List<StatRow> =
        buildList {
            add(StatRow("Source", d.origin.ifBlank { "—" }))
            if (d.origin == NETWORK && d.qualitySetting.isNotBlank()) {
                val value =
                    if (d.qualitySetting == d.qualityEffective || d.qualityEffective.isBlank()) {
                        d.qualitySetting
                    } else {
                        "${d.qualitySetting} → ${d.qualityEffective}"
                    }
                add(StatRow("Quality setting", value))
            }
            add(StatRow("Codec", path.codec))
            if (d.container.isNotBlank()) add(StatRow("Container", d.container))
            add(StatRow("Bitrate", if (path.lossless) "Lossless" else path.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: "—"))
            add(StatRow("Sample rate", path.sampleRateHz.takeIf { it > 0 }?.let(::khz) ?: "—"))
            if (path.bitDepth > 0) add(StatRow("Bit depth", "${path.bitDepth}-bit"))
            add(StatRow("Channels", channelLabel(path.channels).ifBlank { "—" }))
            add(StatRow("Lossless", if (path.lossless) "Yes" else "No", if (path.lossless) StatState.ON else StatState.OFF))
            add(
                StatRow(
                    "Dolby Atmos",
                    if (d.atmos) "Yes — E-AC-3 JOC" else "Not in this stream",
                    if (d.atmos) StatState.ON else StatState.OFF,
                ),
            )
        }

    private fun decoderRows(
        path: SignalPath,
        d: PipelineDetails,
        plan: Plan,
    ): List<StatRow> =
        buildList {
            add(StatRow("Decoder", d.decoder.ifBlank { "—" }))
            if (!plan.pcmKnown) {
                add(StatRow("PCM format", "Not opened yet"))
                return@buildList
            }
            add(StatRow("PCM format", pcmLabel(d)))
            add(StatRow("Rate", khz(d.pcmRateHz)))
            add(StatRow("Channels", channelLabel(d.pcmChannels)))
            if (path.sampleRateHz > 0 && path.sampleRateHz != d.pcmRateHz) {
                add(StatRow("Rate conversion", "${khz(path.sampleRateHz)} → ${khz(d.pcmRateHz)} in the decoder", StatState.WARN))
            }
        }

    private fun engineRows(
        d: PipelineDetails,
        plan: Plan,
    ): List<StatRow> {
        val x = d.dsp
        val off = stageOff(plan, plan.engineRuns)
        val on = plan.engineRuns
        val offState = if (plan.pcmKnown && !on && !plan.bypassed) StatState.WARN else StatState.OFF

        fun row(
            label: String,
            enabled: Boolean,
            value: String,
        ) = if (on && enabled) {
            StatRow(label, value, StatState.ON)
        } else {
            StatRow(label, if (on) "Off" else off, offState)
        }

        return listOf(
            row("Equalizer", x.eqEnabled, "${x.eqPreset.ifBlank { "Custom" }} · ${x.eqBandsShaped} of ${x.eqBandCount} bands shaped"),
            row("Auto headroom", x.autoHeadroom && x.eqEnabled, "On"),
            row("Limiter", x.limiter, "On"),
            row("Dither", x.dither, "On"),
            row("Crossfeed", x.crossfeed != "Off", x.crossfeed),
            row("Stereo width", x.widthPercent != 100, "${x.widthPercent}%"),
            row("Spatial audio", x.spatial != "Off", "${x.spatial} — simulated, not Dolby Atmos"),
        )
    }

    private fun modRows(
        d: PipelineDetails,
        plan: Plan,
    ): List<StatRow> {
        val x = d.dsp
        val off = stageOff(plan, plan.modRuns)
        val on = plan.modRuns
        val reverbOn = x.reverbMix > SOFT
        val rows = mutableListOf<StatRow>()

        fun row(
            label: String,
            enabled: Boolean,
            value: String,
        ) {
            rows +=
                if (on && enabled) {
                    StatRow(label, value, StatState.ON)
                } else {
                    val skipped = plan.pcmKnown && !on && !plan.bypassed && enabled
                    StatRow(label, if (on) "Off" else off, if (skipped) StatState.WARN else StatState.OFF)
                }
        }
        // Speed and pitch run in Media3's own stage, so they are held off only by bit-perfect.
        val tempo = x.speed != 1f || x.pitchRatio != 1f
        rows +=
            if (tempo && !plan.bypassed) {
                StatRow("Speed / pitch", "${fmt(x.speed)}× / ${fmt(x.pitchRatio)}×", StatState.ON)
            } else {
                StatRow("Speed / pitch", if (plan.bypassed) "Held at 1.0× (bit-perfect)" else "1.0× / 1.0×", StatState.OFF)
            }
        row("Bass boost", x.bassDb != 0f, "${signed(x.bassDb)} dB")
        val reverb =
            "${(x.reverbMix * 100).toInt()}% · decay ${fmt(x.reverbDecaySec)} s · " +
                "pre-delay ${x.reverbPreDelayMs.toInt()} ms · damping ${(x.reverbDamping * 100).toInt()}%"
        row("Reverb", reverbOn, reverb)
        row("Low-pass", x.lowPassHz < 20_000f, "${x.lowPassHz.toInt()} Hz")
        row("Wobble", x.wobbleCents > SOFT, "${fmt(x.wobbleCents)} cents")
        row("Vinyl noise", x.vinylNoise > SOFT, "${(x.vinylNoise * 100).toInt()}%")
        row("Mono", x.mono, "On")
        row("Balance", x.balance != 0f, "${if (x.balance < 0) "Left" else "Right"} ${(kotlin.math.abs(x.balance) * 100).toInt()}%")
        return rows
    }

    private fun transitionRows(d: PipelineDetails): List<StatRow> {
        val x = d.dsp
        val held = x.bitPerfectRequested

        fun ms(v: Int) = if (v <= 0) "Off" else "${fmt(v / 1000f)} s"

        fun transition(
            label: String,
            value: Int,
        ) = StatRow(
            label,
            if (held) "Held off (bit-perfect)" else ms(value),
            if (!held && value > 0) StatState.ON else StatState.OFF,
        )

        return listOf(
            transition("Crossfade", x.crossfadeMs),
            transition("Fade in/out", x.fadeMs),
            StatRow("Skip silence", if (x.skipSilence) "On" else "Off", if (x.skipSilence) StatState.ON else StatState.OFF),
        )
    }

    private fun outputRows(
        path: SignalPath,
        d: PipelineDetails,
        plan: Plan,
    ): List<StatRow> =
        buildList {
            add(StatRow("Route", routeLabel(path)))
            val bp =
                when (path.bitPerfect) {
                    BitPerfectStatus.ACTIVE -> StatRow("Bit-perfect", "Active — ${path.bitPerfectNote}".trimEnd(' ', '—'), StatState.ON)
                    BitPerfectStatus.UNPROCESSED -> StatRow("Bit-perfect", "Effects bypassed, Android still mixes", StatState.WARN)
                    BitPerfectStatus.OFF -> StatRow("Bit-perfect", "Off", StatState.OFF)
                }
            add(bp)
            if (plan.pcmKnown) add(StatRow("Sent to the output", outputFormatLabel(d, plan)))
            if (d.mixerRateHz > 0) {
                val state = if (path.bitPerfect == BitPerfectStatus.ACTIVE) StatState.OFF else StatState.INFO
                add(StatRow("Android mixer", if (path.bitPerfect == BitPerfectStatus.ACTIVE) "Bypassed" else khz(d.mixerRateHz), state))
            }
            if (d.androidSdk > 0) {
                val mixer = if (d.androidSdk >= MIN_BIT_PERFECT_SDK) "bit-perfect mixer available" else "no bit-perfect mixer"
                add(StatRow("Android", "API ${d.androidSdk} — $mixer"))
            }
        }

    private fun networkRows(d: PipelineDetails): List<StatRow> =
        buildList {
            add(StatRow("Bandwidth estimate", d.bandwidthKbps.takeIf { it > 0 }?.let { "$it kbps" } ?: "—"))
            add(StatRow("Buffered ahead", d.bufferAheadMs.takeIf { it >= 0 }?.let { "${fmt(it / 1000f)} s" } ?: "—"))
            add(StatRow("Underruns", d.underruns.toString(), if (d.underruns > 0) StatState.WARN else StatState.INFO))
        }

    private fun outputFormatLabel(
        d: PipelineDetails,
        plan: Plan,
    ): String =
        when {
            plan.bypassed -> "${pcmLabel(d)} · ${khz(d.pcmRateHz)} (as decoded)"
            plan.engineRuns -> "16-bit PCM · ${khz(d.pcmRateHz)}"
            else -> "${pcmLabel(d)} · ${khz(d.pcmRateHz)} (DSP skipped)"
        }

    private fun mixerLabel(
        path: SignalPath,
        d: PipelineDetails,
    ): String =
        if (path.bitPerfect == BitPerfectStatus.ACTIVE) {
            "Bit-perfect mixer"
        } else {
            "Android mixer${d.mixerRateHz.takeIf { it > 0 }?.let { " · ${khz(it)}" }.orEmpty()}"
        }

    private fun routeLabel(path: SignalPath): String =
        if (path.routeName.isBlank()) path.routeKind.label else "${path.routeKind.label} (${path.routeName})"

    private fun pcmLabel(d: PipelineDetails): String =
        when {
            d.pcmFloat -> "32-bit float"
            d.pcmBits > 0 -> "${d.pcmBits}-bit PCM"
            else -> "PCM"
        }

    fun channelLabel(channels: Int): String =
        when (channels) {
            0 -> ""
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels ch"
        }

    private fun khz(hz: Int): String = String.format(Locale.US, "%.1f kHz", hz / 1000f).replace(".0 kHz", " kHz")

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun signed(v: Float): String = (if (v > 0) "+" else "") + fmt(v)

    const val NETWORK = "Network stream"
    const val LOCAL = "Local file"
}
