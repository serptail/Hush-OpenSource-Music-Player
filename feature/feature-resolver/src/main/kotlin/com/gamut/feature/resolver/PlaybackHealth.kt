package com.gamut.feature.resolver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the UI when the audio source itself looks broken (typically: YouTube changed and the extractor needs an update),
 * as opposed to the phone just being offline.
 *
 * Call [reportSuccess] when a stream URL was obtained, and [reportFailure] when EVERY source (NewPipe and the yt-dlp
 * fallback) failed for a track. Do not report cancellations, and do not report failures of a single unavailable video if
 * you can tell them apart. [FAILURES_BEFORE_WARNING] failures in a row, while the network is validated, flip [failing];
 * the first success clears it.
 */
@Singleton
class PlaybackHealth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val consecutiveFailures = AtomicInteger(0)
    private val _failing = MutableStateFlow(false)
    val failing: StateFlow<Boolean> = _failing.asStateFlow()

    fun reportSuccess() {
        consecutiveFailures.set(0)
        _failing.value = false
    }

    fun reportFailure() {
        if (!online()) return // offline is not the source's fault
        if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_WARNING) _failing.value = true
    }

    private fun online(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: SecurityException) {
        true // no ACCESS_NETWORK_STATE: assume online rather than hide a real problem
    }

    private companion object {
        const val FAILURES_BEFORE_WARNING = 3
    }
}
