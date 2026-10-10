package com.gamut.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.gamut.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the release workflow publishes next to every APK as `latest.json` (see .github/workflows/release.yml).
 * [minVersionCode]: every build below this must update before it can be used. The workflow sets it to the new build's own
 * code, so every release is mandatory (unless it was started with force_min = false).
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val minVersionCode: Int = 0,
    val notes: String = "",
)

private val json = Json { ignoreUnknownKeys = true }

/** Asks GitHub which build is the latest. The last good answer is kept, so a blocked app stays blocked while offline. */
class UpdateChecker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Off for debug builds and for local builds that were not given a repository. */
    val enabled: Boolean get() = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank() && !BuildConfig.DEBUG

    /** The newest manifest: from GitHub, else the last one seen, else null (never reached GitHub yet: let the user in). */
    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        fetch()?.also { prefs.edit().putString(KEY, json.encodeToString(UpdateInfo.serializer(), it)).apply() } ?: cached()
    }

    private fun fetch(): UpdateInfo? = try {
        val request = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            json.decodeFromString(UpdateInfo.serializer(), response.body?.string() ?: return@use null)
                .takeIf { it.versionCode > 0 && it.apkUrl.startsWith("https://github.com/") && it.sha256.length == 64 }
        }
    } catch (_: Exception) {
        null
    }

    private fun cached(): UpdateInfo? = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(UpdateInfo.serializer(), it) }.getOrNull()
    }

    private companion object { const val KEY = "latest" }
}

/** Downloads the APK, checks it, and hands it to Android's package installer. */
class UpdateInstaller(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Installing from outside the Play Store is a per-app switch the user has to turn on once. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Fetches the APK into the cache (reusing a finished copy), verifying its SHA-256. [onProgress] is 0..1. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (!info.apkUrl.startsWith("https://github.com/")) throw IOException("Unexpected download address")
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "gamut-${info.versionCode}.apk")
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        if (target.exists() && sha256(target).equals(info.sha256, ignoreCase = true)) return@withContext target
        target.delete()

        val part = File(dir, "gamut-${info.versionCode}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent / 100f) }
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The download is damaged. Try again.")
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the update")
        onProgress(1f)
        target
    }

    /**
     * Android refuses an update signed with a different key, with a cryptic error. Catch it first, with a clear message.
     * Null = fine (or not checkable on this Android version).
     */
    fun signerProblem(apk: File): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val incoming = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners ?: return null
            val installed = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners ?: return null
            fun hashes(signatures: Array<android.content.pm.Signature>) =
                signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }.toSet()
            if (hashes(incoming).intersect(hashes(installed)).isEmpty()) {
                "This update is signed with a different key than the installed app. Uninstall Gamut once, then install the latest APK from GitHub."
            } else {
                null
            }
        }.getOrNull()
    }

    /** Streams the APK into a package-installer session. The result arrives at [UpdateInstallReceiver]. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once Gamut has installed an update itself, newer Androids can apply the next ones without a confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("gamut.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_RESULT)
            // The system adds the result to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val ACTION_RESULT = "com.gamut.update.INSTALL_RESULT" }
}

/** Install failures, from the receiver to whoever is showing the update screen. */
object UpdateStatusBus {
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val failures: SharedFlow<String> = _failures

    fun fail(message: String) { _failures.tryEmit(message) }
}

/** Gets the installer's verdict. A success needs no handling: Android replaces and restarts the app. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the user's OK (the first update always does): show its confirmation screen.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                UpdateStatusBus.fail(
                    when {
                        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
                        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                        status == PackageInstaller.STATUS_FAILURE_CONFLICT || detail.contains("INCOMPATIBLE", ignoreCase = true) ->
                            "This update is signed with a different key than the installed app. Uninstall Gamut once, then install the latest APK from GitHub."
                        else -> "The install failed${if (detail.isNotBlank()) ": $detail" else "."}"
                    },
                )
            }
        }
    }
}
