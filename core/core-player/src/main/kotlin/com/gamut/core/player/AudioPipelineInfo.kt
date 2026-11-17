package com.gamut.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class RouteKind(
    val label: String,
) {
    SPEAKER("Phone speaker"),
    WIRED("Wired headphones"),
    USB_DAC("USB DAC"),
    BLUETOOTH("Bluetooth"),
    OTHER("Audio output"),
}

enum class BitPerfectStatus {
    /** The setting is off. */
    OFF,

    /** The OS mixer is bypassed for this stream. */
    ACTIVE,

    /** Every effect is bypassed, but the system mixer still handles the stream. */
    UNPROCESSED,
}

data class SignalPath(
    val codec: String = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val lossless: Boolean = false,
    val routeName: String = "",
    val routeKind: RouteKind = RouteKind.SPEAKER,
    val bitPerfect: BitPerfectStatus = BitPerfectStatus.OFF,
    val bitPerfectNote: String = "",
) {
    val known: Boolean get() = codec.isNotEmpty()

    val tierLabel: String
        get() =
            when {
                !known -> ""
                lossless -> "Lossless"
                bitrateKbps >= 224 -> "High quality"
                bitrateKbps >= 112 -> "Standard"
                bitrateKbps > 0 -> "Data saver"
                else -> "Lossy"
            }

    /** For example "FLAC  ·  24-bit / 96 kHz" or "Opus  ·  160 kbps". */
    val summary: String
        get() {
            if (!known) return ""
            val parts = mutableListOf(codec)
            if (lossless) {
                val rate = sampleRateHz.takeIf { it > 0 }?.let { khz(it) }
                parts += listOfNotNull(bitDepth.takeIf { it > 0 }?.let { "$it-bit" }, rate).joinToString(" / ")
            } else if (bitrateKbps > 0) {
                parts += "$bitrateKbps kbps"
            }
            return parts.filter { it.isNotBlank() }.joinToString("  ·  ")
        }

    private fun khz(hz: Int): String = String.format(Locale.US, "%.1f kHz", hz / 1000f).replace(".0 kHz", " kHz")
}

@OptIn(UnstableApi::class)
object AudioPipelineInfo {
    private val _state = MutableStateFlow(SignalPath())
    val state: StateFlow<SignalPath> = _state.asStateFlow()

    private val losslessMimes =
        setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_TRUEHD)

    internal fun onSource(format: Format?) {
        if (format == null) {
            _state.update { it.copy(codec = "", bitrateKbps = 0, sampleRateHz = 0, bitDepth = 0, channels = 0, lossless = false) }
            return
        }
        val mime = format.sampleMimeType.orEmpty()
        val bitrate =
            listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
                .firstOrNull { it != Format.NO_VALUE && it > 0 }
                ?.let { (it + 500) / 1000 } ?: 0
        val depth =
            when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }
        _state.update {
            it.copy(
                codec = codecLabel(mime),
                bitrateKbps = bitrate,
                sampleRateHz = format.sampleRate.takeIf { r -> r != Format.NO_VALUE } ?: 0,
                bitDepth = depth,
                channels = format.channelCount.takeIf { c -> c != Format.NO_VALUE } ?: 0,
                lossless = mime in losslessMimes,
            )
        }
    }

    internal fun onRoute(
        name: String,
        kind: RouteKind,
    ) {
        _state.update { it.copy(routeName = name, routeKind = kind) }
    }

    internal fun onBitPerfect(
        status: BitPerfectStatus,
        note: String,
    ) {
        _state.update { it.copy(bitPerfect = status, bitPerfectNote = note) }
    }

    private fun codecLabel(mime: String): String =
        when (mime) {
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            "" -> ""
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }
}
