package com.gamut.core.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which exclusive output, if any, a track is sent to unchanged. Persisted by name.
 *
 * Bit-perfect output bypasses the equalizer, sound engine, crossfade and software volume, since any of them
 * would change the samples. Volume belongs to the DAC.
 */
enum class BitPerfectMode(
    val label: String,
    val hint: String,
) {
    OFF("Off", "Normal output through Android's mixer"),
    USB_DIRECT("Direct USB", "Gamut's own USB Audio Class driver. Any Android version, DAC hardware volume"),
    DAP_EXCLUSIVE("Built-in DAC", "AAudio exclusive stream to a built-in DAC, for audio players"),
    USB_ANDROID("USB (Android)", "Android 14's bit-perfect mixer for a USB DAC"),
}

/** The PCM format the normal (non bit-perfect) output hands to the system. */
enum class OutputPcmMode(
    val label: String,
) {
    PCM_16("16-bit PCM"),
    FLOAT_32("32-bit float"),
}

/**
 * The audio-output options the ported bit-perfect, USB, float and Atmos code reads. It mirrors the matching
 * fields of [com.gamut.core.player.PlaybackSettings] so that code which runs on the playback thread, or
 * before a Hilt graph exists, can read them without injection.
 *
 * [com.gamut.core.player.PlaybackSettings] is the only writer.
 */
object AudioConfig {
    private val _bitPerfectMode = MutableStateFlow(BitPerfectMode.OFF)
    val bitPerfectMode: StateFlow<BitPerfectMode> = _bitPerfectMode.asStateFlow()

    private val _convertForLimitedDac = MutableStateFlow(false)

    /** Convert a track to the best format a limited DAC offers instead of refusing it. Never bit-exact. */
    val convertForLimitedDac: StateFlow<Boolean> = _convertForLimitedDac.asStateFlow()

    private val _usbAllowFixedVolume = MutableStateFlow(false)

    /** Stream to a USB DAC that has no hardware volume control, leaving volume to the amplifier. */
    val usbAllowFixedVolume: StateFlow<Boolean> = _usbAllowFixedVolume.asStateFlow()

    private val _dolbyAtmos = MutableStateFlow(true)

    /** Whether a source offering a Dolby Atmos (E-AC-3 JOC) rendition may serve it. */
    val dolbyAtmos: StateFlow<Boolean> = _dolbyAtmos.asStateFlow()

    private val _outputPcmMode = MutableStateFlow(OutputPcmMode.FLOAT_32)
    val outputPcmMode: StateFlow<OutputPcmMode> = _outputPcmMode.asStateFlow()

    internal fun publish(
        bitPerfectMode: BitPerfectMode,
        convertForLimitedDac: Boolean,
        usbAllowFixedVolume: Boolean,
        dolbyAtmos: Boolean,
        outputPcmMode: OutputPcmMode,
    ) {
        _bitPerfectMode.value = bitPerfectMode
        _convertForLimitedDac.value = convertForLimitedDac
        _usbAllowFixedVolume.value = usbAllowFixedVolume
        _dolbyAtmos.value = dolbyAtmos
        _outputPcmMode.value = outputPcmMode
    }
}
