package com.gamut.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.gamut.core.database.GamutDatabase
import com.gamut.core.database.PendingSyncDao
import com.gamut.core.database.SyncStateDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/** Runs [SyncEngine] when signed in + online: at start, after local changes (debounced), on reconnect, every 15 min. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val engine: SyncEngine,
    private val pending: PendingSyncDao,
    private val state: SyncStateDao,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status
    private val _lastSyncedMs = MutableStateFlow<Long?>(null)
    val lastSyncedMs: StateFlow<Long?> = _lastSyncedMs

    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Mutex()

    fun syncNow() { manual.tryEmit(Unit) }

    fun start(scope: CoroutineScope) {
        if (!SyncConfig.enabled) return
        scope.launch(Dispatchers.IO) {
            _lastSyncedMs.value = state.get("last_synced_ms")?.toLongOrNull()
            combine(auth.userId, online()) { user, online -> user != null && online }
                .distinctUntilChanged()
                .flatMapLatest { active ->
                    if (!active) emptyFlow()
                    else merge(
                        flow { while (true) { emit(Unit); delay(PERIOD_MS) } },                       // start + periodic
                        pending.observeCount().filter { it > 0 }.debounce(2_000).map { },             // local changes
                        manual,
                    )
                }
                .conflate()
                .collect { runSync() }
        }
    }

    private suspend fun runSync() = lock.withLock {
        _status.value = SyncStatus.Syncing
        try {
            engine.sync()
            _lastSyncedMs.value = state.get("last_synced_ms")?.toLongOrNull()
            _status.value = SyncStatus.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = SyncStatus.Error(e.message ?: e::class.java.simpleName)   // visible sync-error state
        }
    }

    private fun online() = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(cm.activeNetwork != null) }
        }
        cm.registerDefaultNetworkCallback(callback)
        trySend(cm.activeNetwork != null)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    private companion object { const val PERIOD_MS = 15L * 60 * 1000 }
}

/** "Clear local data" on sign-out / delete account. Wipes the queue and cursors too, so the next sign-in starts clean. */
class LocalDataCleaner @Inject constructor(private val db: GamutDatabase) {
    suspend fun clear() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
