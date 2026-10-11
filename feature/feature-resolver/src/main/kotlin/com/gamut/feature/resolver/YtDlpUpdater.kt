package com.gamut.feature.resolver

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the yt-dlp inside the app current. YouTube changes its protection every few days and the fix ships in a new
 * yt-dlp, so this is what keeps playback alive between app releases.
 *
 * Three triggers, one code path ([run]):
 *  - app start ([updateIfDue]): when the last successful update check is more than [CHECK_INTERVAL_MS] old;
 *  - in the background ([YtDlpUpdateWorker]): about every 12 hours, on a network connection, even if the app is not used;
 *  - on failure ([recover]): a search or a stream lookup just failed, which is often a YouTube change. Updates right away
 *    and tells the caller whether a newer build was installed, so it knows a retry is worth it.
 *
 * Channel: the NIGHTLY builds first (YouTube fixes land there within hours, the stable release can take weeks), STABLE
 * if the nightly channel cannot be reached. Attempts are rate limited and survive restarts (a failing network must not
 * mean an update attempt on every single playback error). Concurrent callers share one update.
 *
 * NewPipe Extractor, the other half, is compiled into the app and cannot be updated here: it is kept current by the
 * release pipeline (.github/workflows/extractor-bump.yml). Whenever it breaks, resolving falls back to yt-dlp.
 */
@Singleton
class YtDlpUpdater @Inject constructor(@ApplicationContext private val context: Context) {

    data class Status(
        val version: String? = null,
        val lastAttemptMs: Long = 0L,
        val lastSuccessMs: Long = 0L,
        val lastInstallMs: Long = 0L,
        val lastError: String? = null,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** App start: updates if the last successful check is old. True = a newer yt-dlp was installed. */
    suspend fun updateIfDue(): Boolean {
        if (System.currentTimeMillis() - prefs.getLong(K_LAST_SUCCESS, 0L) < CHECK_INTERVAL_MS) return false
        return run(System.currentTimeMillis(), RETRY_GAP_MS)
    }

    /** The background job: the schedule is WorkManager's, so no interval check here. */
    suspend fun refresh(): Boolean = run(System.currentTimeMillis(), RETRY_GAP_MS)

    /** Something just failed: update now (rate limited). True = a newer yt-dlp is in place, so retrying is worthwhile. */
    suspend fun recover(): Boolean = run(System.currentTimeMillis(), RECOVER_GAP_MS)

    private suspend fun run(requestedAt: Long, minGapMs: Long): Boolean = lock.withLock {
        // A caller ahead of us installed a newer build while we waited: that is what this one wanted as well.
        if (prefs.getLong(K_LAST_INSTALL, 0L) >= requestedAt) return@withLock true
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(K_LAST_ATTEMPT, 0L) < minGapMs) return@withLock false
        prefs.edit().putLong(K_LAST_ATTEMPT, now).apply()
        withContext(Dispatchers.IO) { attempt() }
    }

    private fun attempt(): Boolean {
        val ytdl = YoutubeDL.getInstance()
        // Does nothing when the engine already did it. A failure here would make every update fail, so log it.
        runCatching { ytdl.init(context) }.onFailure { Log.w(TAG, "yt-dlp init failed", it) }
        val before = installedVersion(ytdl)
        Log.i(TAG, "checking for a newer yt-dlp (installed: $before)")
        var error: String? = null
        for ((name, channel) in CHANNELS) {
            val result = runCatching { ytdl.updateYoutubeDL(context, channel) }
            // Full stack trace: the library's own message ("failed to update youtube-dl") hides the real cause.
            result.exceptionOrNull()?.let { Log.w(TAG, "update on $name channel threw", it) }
            if (result.isSuccess) {
                val after = installedVersion(ytdl)
                val changed = after != null && after != before
                val done = System.currentTimeMillis()
                prefs.edit().apply {
                    putLong(K_LAST_SUCCESS, done)
                    putString(K_ERROR, null)
                    if (after != null) putString(K_VERSION, after)
                    if (changed) putLong(K_LAST_INSTALL, done)
                }.apply()
                _status.value = readStatus()
                Log.i(TAG, "$name channel: ${if (changed) "updated $before -> $after" else "already current ($after)"}")
                return changed
            }
            val e = result.exceptionOrNull()
            error = "$name: ${e?.message ?: "update failed"}${e?.cause?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()}"
            Log.w(TAG, "update failed on $error")
        }
        prefs.edit().putString(K_ERROR, error).apply()
        _status.value = readStatus()
        return false
    }

    private fun installedVersion(ytdl: YoutubeDL): String? = runCatching { ytdl.version(context) }.getOrNull()

    private fun readStatus() = Status(
        version = prefs.getString(K_VERSION, null),
        lastAttemptMs = prefs.getLong(K_LAST_ATTEMPT, 0L),
        lastSuccessMs = prefs.getLong(K_LAST_SUCCESS, 0L),
        lastInstallMs = prefs.getLong(K_LAST_INSTALL, 0L),
        lastError = prefs.getString(K_ERROR, null),
    )

    private companion object {
        const val TAG = "YtDlpUpdater"
        const val PREFS = "yt_dlp_updater"
        const val K_VERSION = "version"
        const val K_LAST_ATTEMPT = "last_attempt_ms"
        const val K_LAST_SUCCESS = "last_success_ms"
        const val K_LAST_INSTALL = "last_install_ms"
        const val K_ERROR = "last_error"

        const val CHECK_INTERVAL_MS = 12 * 3_600_000L
        const val RETRY_GAP_MS = 30 * 60_000L
        const val RECOVER_GAP_MS = 15 * 60_000L

        val CHANNELS = listOf(
            "nightly" to YoutubeDL.UpdateChannel.NIGHTLY,
            "stable" to YoutubeDL.UpdateChannel.STABLE,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface YtDlpUpdaterEntryPoint {
    fun updater(): YtDlpUpdater
}

/** WorkManager has no Hilt factory in this project, so the worker reaches the singleton through an entry point. */
class YtDlpUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, YtDlpUpdaterEntryPoint::class.java).updater().refresh()
        return Result.success()   // a failed attempt is simply tried again at the next run
    }

    companion object {
        private const val NAME = "yt-dlp-update"

        /** Safe to call on every start: KEEP leaves an existing schedule alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}