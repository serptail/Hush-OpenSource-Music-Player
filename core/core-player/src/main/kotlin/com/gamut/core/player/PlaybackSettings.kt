package com.gamut.core.player

import android.content.Context
import android.net.ConnectivityManager
import com.gamut.core.player.audio.AudioConfig
import com.gamut.core.player.audio.BitPerfectMode
import com.gamut.core.player.audio.OutputPcmMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class StreamQuality(
    val label: String,
    val hint: String,
) {
    AUTO("Auto", "High on Wi-Fi, Normal on mobile data"),
    LOW("Data saver", "Smallest files, about 48 kbps"),
    NORMAL("Normal", "AAC, about 128 kbps"),
    HIGH("High", "Best stream available, up to about 160 kbps Opus"),
}

@Singleton
class PlaybackSettings
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        data class State(
            val streamQuality: StreamQuality = StreamQuality.HIGH,
            val preload: Boolean = true,
            val preloadUnmeteredOnly: Boolean = false,
            val fadeMs: Int = 0,
            val crossfadeMs: Int = 0,
            val skipSilence: Boolean = false,
            val pauseOnUnplug: Boolean = true,
            val mono: Boolean = false,
            val balance: Float = 0f,
            val bitPerfectMode: BitPerfectMode = BitPerfectMode.OFF,
            val convertForLimitedDac: Boolean = false,
            val usbAllowFixedVolume: Boolean = false,
            val dolbyAtmos: Boolean = true,
            val outputPcmMode: OutputPcmMode = OutputPcmMode.FLOAT_32,
        ) {
            /** True when any exclusive output is selected. */
            val bitPerfect: Boolean get() = bitPerfectMode != BitPerfectMode.OFF
        }

        private val prefs = context.applicationContext.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
        private val _state = MutableStateFlow(load())
        val state: StateFlow<State> = _state.asStateFlow()

        init {
            publishConfig(_state.value)
        }

        @Synchronized
        fun update(transform: (State) -> State) {
            val next =
                transform(_state.value).let {
                    it.copy(
                        fadeMs = it.fadeMs.coerceIn(0, 2_000),
                        crossfadeMs = it.crossfadeMs.coerceIn(0, 12_000),
                        balance = it.balance.coerceIn(-1f, 1f),
                    )
                }
            if (next == _state.value) return
            _state.value = next
            save(next)
            publishConfig(next)
        }

        private fun publishConfig(s: State) =
            AudioConfig.publish(s.bitPerfectMode, s.convertForLimitedDac, s.usbAllowFixedVolume, s.dolbyAtmos, s.outputPcmMode)

        fun reset() = update { State() }

        fun effectiveQuality(): StreamQuality =
            when (val q = _state.value.streamQuality) {
                StreamQuality.AUTO -> if (onUnmeteredNetwork()) StreamQuality.HIGH else StreamQuality.NORMAL
                else -> q
            }

        fun onUnmeteredNetwork(): Boolean =
            runCatching {
                !context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
            }.getOrDefault(false)

        private fun load() =
            State(
                streamQuality = StreamQuality.entries.firstOrNull { it.name == prefs.getString(K_QUALITY, null) } ?: StreamQuality.HIGH,
                preload = prefs.getBoolean(K_PRELOAD, true),
                preloadUnmeteredOnly = prefs.getBoolean(K_PRELOAD_WIFI, false),
                fadeMs = prefs.getInt(K_FADE, 0),
                crossfadeMs = prefs.getInt(K_CROSSFADE, 0),
                skipSilence = prefs.getBoolean(K_SKIP_SILENCE, false),
                pauseOnUnplug = prefs.getBoolean(K_UNPLUG, true),
                mono = prefs.getBoolean(K_MONO, false),
                balance = prefs.getFloat(K_BALANCE, 0f),
                bitPerfectMode = loadMode(),
                convertForLimitedDac = prefs.getBoolean(K_CONVERT_DAC, false),
                usbAllowFixedVolume = prefs.getBoolean(K_USB_FIXED_VOLUME, false),
                dolbyAtmos = prefs.getBoolean(K_DOLBY_ATMOS, true),
                outputPcmMode = OutputPcmMode.entries.firstOrNull { it.name == prefs.getString(K_PCM_MODE, null) } ?: OutputPcmMode.FLOAT_32,
            )

        /** The old on/off toggle drove Android 14's bit-perfect mixer, so an old "on" becomes that mode. */
        private fun loadMode(): BitPerfectMode {
            prefs.getString(K_BIT_PERFECT_MODE, null)?.let { name ->
                return BitPerfectMode.entries.firstOrNull { it.name == name } ?: BitPerfectMode.OFF
            }
            return if (prefs.getBoolean(K_BIT_PERFECT, false)) BitPerfectMode.USB_ANDROID else BitPerfectMode.OFF
        }

        private fun save(s: State) {
            prefs
                .edit()
                .putString(K_QUALITY, s.streamQuality.name)
                .putBoolean(K_PRELOAD, s.preload)
                .putBoolean(K_PRELOAD_WIFI, s.preloadUnmeteredOnly)
                .putInt(K_FADE, s.fadeMs)
                .putInt(K_CROSSFADE, s.crossfadeMs)
                .putBoolean(K_SKIP_SILENCE, s.skipSilence)
                .putBoolean(K_UNPLUG, s.pauseOnUnplug)
                .putBoolean(K_MONO, s.mono)
                .putFloat(K_BALANCE, s.balance)
                .putBoolean(K_BIT_PERFECT, s.bitPerfect)
                .putString(K_BIT_PERFECT_MODE, s.bitPerfectMode.name)
                .putBoolean(K_CONVERT_DAC, s.convertForLimitedDac)
                .putBoolean(K_USB_FIXED_VOLUME, s.usbAllowFixedVolume)
                .putBoolean(K_DOLBY_ATMOS, s.dolbyAtmos)
                .putString(K_PCM_MODE, s.outputPcmMode.name)
                .apply()
        }

        private companion object {
            const val K_QUALITY = "quality"
            const val K_PRELOAD = "preload"
            const val K_PRELOAD_WIFI = "preload_unmetered_only"
            const val K_FADE = "fade_ms"
            const val K_CROSSFADE = "crossfade_ms"
            const val K_SKIP_SILENCE = "skip_silence"
            const val K_UNPLUG = "pause_on_unplug"
            const val K_MONO = "mono"
            const val K_BALANCE = "balance"
            const val K_BIT_PERFECT = "bit_perfect"
            const val K_BIT_PERFECT_MODE = "bit_perfect_mode"
            const val K_CONVERT_DAC = "convert_for_limited_dac"
            const val K_USB_FIXED_VOLUME = "usb_allow_fixed_volume"
            const val K_DOLBY_ATMOS = "dolby_atmos"
            const val K_PCM_MODE = "output_pcm_mode"
        }
    }