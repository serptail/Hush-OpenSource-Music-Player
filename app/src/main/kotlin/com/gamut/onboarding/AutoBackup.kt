package com.gamut.onboarding

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamut.core.sync.DataExporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Daily JSON backup of favorites + playlists, kept on the device (no cloud). The Profile screen can switch it off. */
object AutoBackup {
    private const val WORK = "gamut-auto-backup"
    private const val KEEP = 10

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        // KEEP: calling this on every launch is harmless, it does not reset the timer.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Call this (with ProfileStore.autoBackup.value) wherever schedule() is called at launch, so "off" stays off. */
    fun apply(context: Context, enabled: Boolean) = if (enabled) schedule(context) else cancel(context)

    fun backupDir(context: Context) = File(context.filesDir, "backups")

    /** Writes today's backup (same-day reruns overwrite) and prunes to the newest [KEEP]. Used by the worker and "Back up now". */
    suspend fun write(context: Context, exporter: DataExporter): File {
        val bytes = exporter.export(password = null)
        return withContext(Dispatchers.IO) {
            val dir = backupDir(context).apply { mkdirs() }
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "gamut-backup-$day.json").apply { writeBytes(bytes) }
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            file
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun exporter(): DataExporter }

    override suspend fun doWork(): Result = runCatching {
        val exporter = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).exporter()
        AutoBackup.write(applicationContext, exporter)
        Result.success()
    }.getOrElse { Result.retry() }
}