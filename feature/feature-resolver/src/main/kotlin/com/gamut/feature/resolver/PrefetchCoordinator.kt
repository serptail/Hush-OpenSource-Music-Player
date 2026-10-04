package com.gamut.feature.resolver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.gamut.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Warms up the first few of [prefetch]'s tracks (typically the top search results), so tapping one starts fast.
 *
 * Gentle on purpose, because resolving can mean a search plus a yt-dlp run per track:
 *  - it waits [DEBOUNCE_MS] after the LAST call, so typing / quick re-searches start nothing;
 *  - it resolves one track at a time (never in parallel);
 *  - it does nothing in battery saver, at low battery, or when the phone is hot.
 */
@Singleton
class PrefetchCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolve: ResolveTrackSourceUseCase,
    private val audio: AudioSourceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var activeJob: Job? = null

    fun prefetch(tracks: List<Track>) {
        activeJob?.cancel()
        activeJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (!allowed()) return@launch
            for (track in tracks.take(PREFETCH_COUNT)) {
                ensureActive()   // resolve() swallows cancellation (runCatching), so stop explicitly
                resolve(track).onSuccess { source -> audio.audioUrl(source.youtubeVideoId) }
            }
        }
    }

    private fun allowed(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isPowerSaveMode) return false
        if (Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return false
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return charging || percent > LOW_BATTERY_PERCENT
    }

    private companion object {
        const val PREFETCH_COUNT = 2
        const val DEBOUNCE_MS = 1_500L
        const val LOW_BATTERY_PERCENT = 15
    }
}