package com.gamut.feature.resolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MigrationService : Service() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun migrator(): LibraryMigrator
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val migrator = EntryPointAccessors.fromApplication(applicationContext, Access::class.java).migrator()
        if (intent?.action == ACTION_CANCEL) {
            migrator.cancel()
            if (migrator.state.value !is MigrationState.Running) stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Library updates", NotificationManager.IMPORTANCE_LOW))
        val first = notification(migrator.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, first)
        }
        if (watcher == null) {
            watcher =
                scope.launch {
                    migrator.state.collect { state ->
                        if (state is MigrationState.Running) {
                            notifications.notify(ID, notification(state))
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: MigrationState): Notification {
        val open =
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, MigrationService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val running = state as? MigrationState.Running
        val title = if (running?.moving == false) "Adding covers & info" else "Moving your songs"
        val text =
            when {
                running == null -> "Starting\u2026"
                else -> "${minOf(running.done + 1, running.total)} of ${running.total}" + (running.current?.let { " \u00b7 $it" } ?: "")
            }
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(running?.total?.coerceAtLeast(1) ?: 1, running?.done ?: 0, running == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action
                    .Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Cancel",
                        cancel,
                    ).build(),
            ).build()
    }

    private companion object {
        const val CHANNEL = "library_updates"
        const val ID = 4108
        const val ACTION_CANCEL = "com.gamut.feature.resolver.CANCEL_MIGRATION"
    }
}
