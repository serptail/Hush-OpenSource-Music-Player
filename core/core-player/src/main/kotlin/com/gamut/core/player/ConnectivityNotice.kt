package com.gamut.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that the connection was lost, so the UI can say "Internet restored" once it is back. It is tracked here
 * rather than in the UI so a drop that happens while the app is in the background is still announced when the
 * person returns to it. Short blips (a wifi to mobile handover) are ignored.
 */
@Singleton
class ConnectivityNotice
    @Inject
    constructor(
        private val network: NetworkMonitor,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val lost = MutableStateFlow(false)

        /** True while the connection is back after a real outage that has not been announced yet. */
        val restoredPending: StateFlow<Boolean> =
            combine(lost, network.online) { wasLost, online -> wasLost && online }
                .stateIn(scope, SharingStarted.Eagerly, false)

        init {
            scope.launch {
                network.online.collectLatest { online ->
                    if (!online) {
                        delay(OFFLINE_GRACE_MS)
                        lost.value = true
                    }
                }
            }
        }

        /** Call once the notice has been shown. */
        fun consume() {
            lost.value = false
        }

        private companion object {
            const val OFFLINE_GRACE_MS = 1_500L
        }
    }
