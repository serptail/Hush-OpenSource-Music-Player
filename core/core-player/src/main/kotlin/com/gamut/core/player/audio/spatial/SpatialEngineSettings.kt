package com.gamut.core.player.audio.spatial

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which spatial renderer runs when a spatial mode other than Off is selected. */
enum class SpatialEngine(
    val label: String,
    val hint: String,
) {
    GAMUT("Gamut", "Head shadow, interaural delay, early reflections and a height cue"),
    ATMOS_SIM("Atmos style", "A simple stereo widener, like BitChord's. Not Dolby Atmos decoding"),
}

/** Persists the [SpatialEngine] choice. The default keeps Gamut's own virtualizer. */
class SpatialEngineSettings private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("spatial_engine", Context.MODE_PRIVATE)
    private val _engine = MutableStateFlow(load())
    val engine: StateFlow<SpatialEngine> = _engine.asStateFlow()

    fun setEngine(value: SpatialEngine) {
        if (value == _engine.value) return
        _engine.value = value
        prefs.edit().putString(KEY, value.name).apply()
    }

    private fun load() = SpatialEngine.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: SpatialEngine.GAMUT

    companion object {
        private const val KEY = "engine"

        @Volatile private var instance: SpatialEngineSettings? = null

        fun get(context: Context): SpatialEngineSettings =
            instance ?: synchronized(this) {
                instance ?: SpatialEngineSettings(context.applicationContext).also { instance = it }
            }
    }
}
