package com.gamut.core.player

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
