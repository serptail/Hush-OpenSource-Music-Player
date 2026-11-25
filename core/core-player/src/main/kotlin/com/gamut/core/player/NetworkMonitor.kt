package com.gamut.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether the device currently has a usable internet connection, so playback can wait for the network to
 * come back and pick up where it left off instead of failing.
 *
 * [online] is true only for a validated connection, so a wifi network with no internet counts as offline.
 */
@Singleton
class NetworkMonitor
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        private val _online = MutableStateFlow(currentlyOnline())
        val online: StateFlow<Boolean> = _online.asStateFlow()

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()

                override fun onLost(network: Network) = refresh()

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = refresh()
            }

        init {
            try {
                cm?.registerDefaultNetworkCallback(callback)
            } catch (_: SecurityException) {
                // No ACCESS_NETWORK_STATE: assume online and let the normal error paths handle failures.
                _online.value = true
            } catch (_: RuntimeException) {
                _online.value = true
            }
        }

        private fun refresh() {
            _online.value = currentlyOnline()
        }

        private fun currentlyOnline(): Boolean =
            try {
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                caps == null && cm == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            } catch (_: SecurityException) {
                true
            }
    }
