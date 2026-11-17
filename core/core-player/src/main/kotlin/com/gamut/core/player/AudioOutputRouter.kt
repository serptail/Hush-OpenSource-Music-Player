package com.gamut.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/**
 * Sends audio to a USB DAC when bit-perfect output is on and, on Android 14 and newer, asks the system for
 * its bit-perfect mixer so the stream reaches the DAC without being resampled or mixed.
 */
internal class AudioOutputRouter(
    context: Context,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private val routed = HashSet<ExoPlayer>()
    @Volatile private var mixerDevice: AudioDeviceInfo? = null
    private var callback: AudioDeviceCallback? = null

    fun start(onDevicesChanged: () -> Unit) {
        if (callback != null) return
        val cb =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
            }
        callback = cb
        runCatching { audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
        publishRoute()
    }

    fun usbDac(): AudioDeviceInfo? =
        runCatching {
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        }.getOrNull()

    fun publishRoute() {
        val devices = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val wired =
            devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        val bluetooth =
            devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        when {
            usb != null -> AudioPipelineInfo.onRoute(usb.productName?.toString().orEmpty(), RouteKind.USB_DAC)
            wired != null -> AudioPipelineInfo.onRoute(wired.productName?.toString().orEmpty(), RouteKind.WIRED)
            bluetooth != null -> AudioPipelineInfo.onRoute(bluetooth.productName?.toString().orEmpty(), RouteKind.BLUETOOTH)
            else -> AudioPipelineInfo.onRoute("", RouteKind.SPEAKER)
        }
    }

    /** Points the player at the USB DAC while bit-perfect is on, and lets go of it again afterwards. */
    fun applyDevice(
        exo: ExoPlayer,
        bitPerfect: Boolean,
    ) {
        val usb = if (bitPerfect) usbDac() else null
        if (usb != null) {
            if (runCatching { exo.setPreferredAudioDevice(usb) }.isSuccess) routed.add(exo)
        } else if (routed.remove(exo)) {
            runCatching { exo.setPreferredAudioDevice(null) }
        }
    }

    fun forget(exo: ExoPlayer) {
        routed.remove(exo)
    }

    /**
     * Called right before the output opens for a new stream format, so the mixer matches it. This runs on
     * the playback thread, so it is synchronised with the main-thread calls that also touch the mixer.
     */
    @Synchronized
    fun onSinkConfigure(
        bitPerfect: Boolean,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        if (!bitPerfect) {
            releaseMixer()
            AudioPipelineInfo.onBitPerfect(BitPerfectStatus.OFF, "")
            return
        }
        val khz = String.format(Locale.US, "%.1f kHz", sampleRate / 1000f).replace(".0 kHz", " kHz")
        val bits = bitDepthOf(encoding)
        val usb = usbDac()
        if (usb == null) {
            releaseMixer()
            AudioPipelineInfo.onBitPerfect(
                BitPerfectStatus.UNPROCESSED,
                "Effects are bypassed. Connect a USB DAC so Android can skip its own mixer too.",
            )
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AudioPipelineInfo.onBitPerfect(
                BitPerfectStatus.UNPROCESSED,
                "Effects are bypassed. Android 14 or newer is needed for the bit-perfect mixer.",
            )
            return
        }
        if (applyMixer(usb, sampleRate, channelCount, encoding)) {
            AudioPipelineInfo.onBitPerfect(BitPerfectStatus.ACTIVE, "Straight to the DAC at $khz, $bits-bit.")
        } else {
            releaseMixer()
            AudioPipelineInfo.onBitPerfect(
                BitPerfectStatus.UNPROCESSED,
                "Effects are bypassed, but this DAC has no bit-perfect mode for $khz / $bits-bit.",
            )
        }
    }

    @Synchronized
    fun release() {
        releaseMixer()
        callback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        callback = null
    }

    private fun applyMixer(
        device: AudioDeviceInfo,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching { applyMixerApi34(device, sampleRate, channelCount, encoding) }.getOrDefault(false)
    }

    private fun bitDepthOf(encoding: Int): Int =
        when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

    private fun platformEncoding(encoding: Int): Int =
        when (encoding) {
            C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyMixerApi34(
        device: AudioDeviceInfo,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int,
    ): Boolean {
        val wanted = platformEncoding(encoding)
        val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val match =
            audioManager.getSupportedMixerAttributes(device).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == sampleRate &&
                    it.format.encoding == wanted &&
                    it.format.channelMask == mask
            } ?: return false
        val ok = audioManager.setPreferredMixerAttributes(attributes, device, match)
        if (ok) mixerDevice = device
        return ok
    }

    private fun releaseMixer() {
        val device = mixerDevice ?: return
        mixerDevice = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { clearMixerApi34(device) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearMixerApi34(device: AudioDeviceInfo) {
        audioManager.clearPreferredMixerAttributes(attributes, device)
    }
}
