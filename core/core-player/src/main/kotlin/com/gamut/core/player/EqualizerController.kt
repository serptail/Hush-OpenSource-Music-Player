package com.gamut.core.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-wide equalizer. It must outlive the player screen (the sound should stay shaped when the screen is closed),
 * so it is a singleton ([get]) that hooks onto the player's audio session: call [attach] with ExoPlayer's
 * audioSessionId whenever it changes (see the integration note in the answer).
 *
 * Uses Android's built-in audio effects: [Equalizer] (the sliders), [BassBoost] and [Virtualizer] (a "wider" sound).
 * Settings are saved, so they survive restarts. Band count / frequencies depend on the device (usually 5 bands).
 */
class EqualizerController private constructor(context: Context) {

    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val preset: String = FLAT,
        val bandFrequenciesHz: List<Int> = emptyList(),
        val bandLevelsMb: List<Int> = emptyList(),   // millibels: 100 = 1 dB
        val minLevelMb: Int = -1500,
        val maxLevelMb: Int = 1500,
        val bassBoostSupported: Boolean = false,
        val bassBoost: Int = 0,                      // 0..1000
        val virtualizerSupported: Boolean = false,
        val virtualizer: Int = 0,                    // 0..1000
    )

    private val prefs = context.applicationContext.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        State(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            preset = prefs.getString(KEY_PRESET, FLAT) ?: FLAT,
            bassBoost = prefs.getInt(KEY_BASS, 0),
            virtualizer = prefs.getInt(KEY_VIRT, 0),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var sessionId = -1
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null

    /** Hook the effects onto an audio session. Safe to call repeatedly; ignores 0 / unset ids. */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (audioSessionId == sessionId && eq != null) return
        releaseEffects()
        sessionId = audioSessionId

        val e = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        eq = e
        bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        virt = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        if (e == null) {
            _state.update { it.copy(available = false) }
            return
        }

        val bands = e.numberOfBands.toInt()
        val minMb = e.bandLevelRange[0].toInt()
        val maxMb = e.bandLevelRange[1].toInt()
        val freqs = List(bands) { e.getCenterFreq(it.toShort()) / 1000 }   // milliHz -> Hz
        val saved = prefs.getString(KEY_LEVELS, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        val current = _state.value
        val levels = when {
            saved != null && saved.size == bands -> saved.map { it.coerceIn(minMb, maxMb) }
            else -> PRESETS[current.preset]?.let { curveToBands(it, bands, minMb, maxMb) } ?: List(bands) { 0 }
        }
        _state.value = current.copy(
            available = true,
            bandFrequenciesHz = freqs,
            bandLevelsMb = levels,
            minLevelMb = minMb,
            maxLevelMb = maxMb,
            bassBoostSupported = bass?.strengthSupported == true,
            virtualizerSupported = virt?.strengthSupported == true,
        )
        applyAll()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        _state.update { it.copy(enabled = on) }
        applyAll()
        persist()
    }

    @Synchronized
    fun setBandLevel(index: Int, levelMb: Int) {
        val s = _state.value
        if (index !in s.bandLevelsMb.indices) return
        val level = levelMb.coerceIn(s.minLevelMb, s.maxLevelMb)
        if (level == s.bandLevelsMb[index]) return
        val levels = s.bandLevelsMb.toMutableList().also { it[index] = level }
        _state.value = s.copy(bandLevelsMb = levels, preset = CUSTOM)
        runCatching { eq?.setBandLevel(index.toShort(), level.toShort()) }
        persist()
    }

    @Synchronized
    fun applyPreset(name: String) {
        val s = _state.value
        val curve = PRESETS[name] ?: return
        if (!s.available) return
        _state.value = s.copy(preset = name, bandLevelsMb = curveToBands(curve, s.bandLevelsMb.size, s.minLevelMb, s.maxLevelMb))
        applyAll()
        persist()
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        _state.update { it.copy(bassBoost = strength.coerceIn(0, 1000)) }
        applyAll()
        persist()
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        _state.update { it.copy(virtualizer = strength.coerceIn(0, 1000)) }
        applyAll()
        persist()
    }

    /** Back to flat, bass boost and surround off. Leaves the on/off switch alone. */
    @Synchronized
    fun reset() {
        val s = _state.value
        val levels = if (s.available) curveToBands(PRESETS.getValue(FLAT), s.bandLevelsMb.size, s.minLevelMb, s.maxLevelMb) else s.bandLevelsMb
        _state.value = s.copy(preset = FLAT, bandLevelsMb = levels, bassBoost = 0, virtualizer = 0)
        applyAll()
        persist()
    }

    /** Frees the native effects (app shutdown / player release). The saved settings stay. */
    @Synchronized
    fun release() {
        releaseEffects()
        sessionId = -1
        _state.update { it.copy(available = false) }
    }

    private fun releaseEffects() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { virt?.release() }
        eq = null; bass = null; virt = null
    }

    private fun applyAll() {
        val s = _state.value
        runCatching {
            eq?.let { e ->
                s.bandLevelsMb.forEachIndexed { i, mb -> e.setBandLevel(i.toShort(), mb.toShort()) }
                e.setEnabled(s.enabled)
            }
        }
        runCatching {
            bass?.takeIf { s.bassBoostSupported }?.let {
                it.setStrength(s.bassBoost.toShort())
                it.setEnabled(s.enabled && s.bassBoost > 0)
            }
        }
        runCatching {
            virt?.takeIf { s.virtualizerSupported }?.let {
                it.setStrength(s.virtualizer.toShort())
                it.setEnabled(s.enabled && s.virtualizer > 0)
            }
        }
    }

    private fun persist() {
        val s = _state.value
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, s.enabled)
            putString(KEY_PRESET, s.preset)
            putInt(KEY_BASS, s.bassBoost)
            putInt(KEY_VIRT, s.virtualizer)
            if (s.bandLevelsMb.isNotEmpty()) putString(KEY_LEVELS, s.bandLevelsMb.joinToString(","))
        }.apply()
    }

    companion object {
        const val FLAT = "Flat"
        const val CUSTOM = "Custom"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_LEVELS = "levels"
        private const val KEY_BASS = "bass"
        private const val KEY_VIRT = "virt"

        /** Preset curves in dB, low -> high frequency (5 points). Stretched to however many bands the device has. */
        private val PRESETS: Map<String, FloatArray> = linkedMapOf(
            FLAT to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Booster" to floatArrayOf(6f, 4f, 0f, 0f, 0f),
            "Bass Reducer" to floatArrayOf(-5f, -3f, 0f, 0f, 0f),
            "Treble Booster" to floatArrayOf(0f, 0f, 0f, 3.5f, 5.5f),
            "Vocal Booster" to floatArrayOf(-2f, 0f, 4f, 3f, -1f),
            "Rock" to floatArrayOf(4.5f, 2.5f, -1.5f, 2.5f, 4.5f),
            "Pop" to floatArrayOf(-1f, 2.5f, 4f, 1.5f, -1.5f),
            "Hip-Hop" to floatArrayOf(5f, 3.5f, 0f, 1.5f, 3f),
            "Electronic" to floatArrayOf(4.5f, 3f, 0f, 3f, 4.5f),
            "Jazz" to floatArrayOf(3f, 1.5f, -1.5f, 1.5f, 3.5f),
            "Classical" to floatArrayOf(3.5f, 2.5f, -1f, 2.5f, 3.5f),
            "Loudness" to floatArrayOf(5f, 2f, -1f, 2f, 4f),
        )

        val presetNames: List<String> = PRESETS.keys.toList()

        private fun curveToBands(curve: FloatArray, bands: Int, minMb: Int, maxMb: Int): List<Int> = List(bands) { i ->
            val pos = if (bands <= 1) 0f else i * (curve.size - 1).toFloat() / (bands - 1)
            val lo = floor(pos).toInt()
            val hi = min(lo + 1, curve.lastIndex)
            val t = pos - lo
            val db = curve[lo] * (1f - t) + curve[hi] * t
            (db * 100f).roundToInt().coerceIn(minMb, maxMb)
        }

        @Volatile private var instance: EqualizerController? = null

        fun get(context: Context): EqualizerController =
            instance ?: synchronized(this) {
                instance ?: EqualizerController(context.applicationContext).also { instance = it }
            }
    }
}
