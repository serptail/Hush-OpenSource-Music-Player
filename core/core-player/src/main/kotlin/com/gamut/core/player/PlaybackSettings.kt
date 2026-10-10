package com.gamut.core.player

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much audio quality (and data) a streamed track uses. Downloads always take the best stream, whatever this says. */
enum class StreamQuality(val label: String, val hint: String) {
    AUTO("Auto", "High on Wi-Fi, Normal on mobile data"),
    LOW("Data saver", "Smallest files, about 48 kbps"),
    NORMAL("Normal", "AAC, about 128 kbps"),
    HIGH("High", "Best stream available, up to about 160 kbps Opus"),
}

/**
 * App-wide playback / audio preferences (everything on the "Playback & Audio" screen except the equalizer, which has
 * its own [EqualizerController]). Saved to SharedPreferences, so they survive restarts.
 *
 * Readers: [Media3MusicPlayer] observes [state] and applies it live; the resolver calls [effectiveQuality] each time it
 * picks a stream.
 */
@Singleton
class PlaybackSettings @Inject constructor(@ApplicationContext private val context: Context) {

    data class State(
        val streamQuality: StreamQuality = StreamQuality.AUTO,
        /** Resolve + warm up the next track while this one plays (gapless end, instant skip). */
        val preload: Boolean = true,
        /** Only look ahead on an unmetered connection (Wi-Fi). */
        val preloadUnmeteredOnly: Boolean = false,
        /** Volume ramp on pause / resume, in ms. 0 = off. */
        val fadeMs: Int = 0,
        /** How long the end of one track and the start of the next overlap, in ms. 0 = off (gapless instead). */
        val crossfadeMs: Int = 0,
        val skipSilence: Boolean = false,
        val pauseOnUnplug: Boolean = true,
        /** Fold both channels into one. */
        val mono: Boolean = false,
        /** -1 = all left, 0 = centre, +1 = all right. */
        val balance: Float = 0f,
    )

    private val prefs = context.applicationContext.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    @Synchronized
    fun update(transform: (State) -> State) {
        val next = transform(_state.value).let {
            it.copy(
                fadeMs = it.fadeMs.coerceIn(0, 2_000),
                crossfadeMs = it.crossfadeMs.coerceIn(0, 12_000),
                balance = it.balance.coerceIn(-1f, 1f),
            )
        }
        if (next == _state.value) return
        _state.value = next
        save(next)
    }

    fun reset() = update { State() }

    /** What a stream lookup should use right now: [StreamQuality.AUTO] is turned into a concrete level. */
    fun effectiveQuality(): StreamQuality = when (val q = _state.value.streamQuality) {
        StreamQuality.AUTO -> if (onUnmeteredNetwork()) StreamQuality.HIGH else StreamQuality.NORMAL
        else -> q
    }

    /**
     * True on Wi-Fi / ethernet. Needs ACCESS_NETWORK_STATE; if that is missing or the query fails we answer false
     * (the careful choice: treat it as mobile data).
     */
    fun onUnmeteredNetwork(): Boolean = runCatching {
        !context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    private fun load() = State(
        streamQuality = StreamQuality.entries.firstOrNull { it.name == prefs.getString(K_QUALITY, null) } ?: StreamQuality.AUTO,
        preload = prefs.getBoolean(K_PRELOAD, true),
        preloadUnmeteredOnly = prefs.getBoolean(K_PRELOAD_WIFI, false),
        fadeMs = prefs.getInt(K_FADE, 0),
        crossfadeMs = prefs.getInt(K_CROSSFADE, 0),
        skipSilence = prefs.getBoolean(K_SKIP_SILENCE, false),
        pauseOnUnplug = prefs.getBoolean(K_UNPLUG, true),
        mono = prefs.getBoolean(K_MONO, false),
        balance = prefs.getFloat(K_BALANCE, 0f),
    )

    private fun save(s: State) {
        prefs.edit()
            .putString(K_QUALITY, s.streamQuality.name)
            .putBoolean(K_PRELOAD, s.preload)
            .putBoolean(K_PRELOAD_WIFI, s.preloadUnmeteredOnly)
            .putInt(K_FADE, s.fadeMs)
            .putInt(K_CROSSFADE, s.crossfadeMs)
            .putBoolean(K_SKIP_SILENCE, s.skipSilence)
            .putBoolean(K_UNPLUG, s.pauseOnUnplug)
            .putBoolean(K_MONO, s.mono)
            .putFloat(K_BALANCE, s.balance)
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
    }
}