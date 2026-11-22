package com.gamut.feature.resolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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

class DownloadService : Service() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun downloads(): TrackDownloadManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = EntryPointAccessors.fromApplication(applicationContext, Access::class.java).downloads()
        if (intent?.action == ACTION_CANCEL) {
            manager.cancelAll()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val first = notification(manager.batch.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, first)
        }
        if (watcher == null) {
            watcher =
                scope.launch {
                    manager.batch.collect { batch ->
                        if (batch.total == 0) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            notifications.notify(ID, notification(batch))
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

    private fun notification(batch: DownloadBatch): Notification {
        val open =
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val position = minOf(batch.done + 1, batch.total.coerceAtLeast(1))
        val percent = (batch.overall * 100).toInt()
        val title =
            when {
                batch.total <= 1 -> "Downloading"
                else -> "Downloading $position of ${batch.total}"
            }
        val detail =
            when {
                batch.waitingForWifi -> "Waiting for Wi\u2011Fi \u00b7 ${batch.total - batch.done} left"
                batch.total == 0 -> "Starting\u2026"
                else ->
                    buildList {
                        batch.current?.let { add(it) }
                        if (batch.currentProgress > 0f) add("${(batch.currentProgress * 100).toInt()}%")
                        if (batch.bytesPerSec > 0) add(formatSpeed(batch.bytesPerSec))
                    }.joinToString(" \u00b7 ").ifEmpty { "Starting\u2026" }
            }
        val summary =
            buildList {
                add("$percent% overall")
                if (batch.failed > 0) add("${batch.failed} failed")
            }.joinToString(" \u00b7 ")
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(summary)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setProgress(1000, (batch.overall * 1000).toInt(), batch.total == 0 || batch.waitingForWifi)
            .addAction(Notification.Action.Builder(null, "Cancel all", cancel).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val ID = 4107
        const val ACTION_CANCEL = "com.gamut.feature.resolver.CANCEL_DOWNLOADS"
    }
}
