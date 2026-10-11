package com.gamut.update

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gamut.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase { READY, NEEDS_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

data class UpdateUiState(
    /** The first check is still running (for at most [AppUpdateViewModel.GRACE_MS]): hold the app back a moment. */
    val checking: Boolean = true,
    /** Non-null = this build is too old to be used. */
    val required: UpdateInfo? = null,
    val phase: UpdatePhase = UpdatePhase.READY,
    val progress: Float = 0f,
    val error: String? = null,
    /** The user chose "Skip for now": let them into the app until it is closed. The screen returns on the next start. */
    val skipped: Boolean = false,
)

/**
 * The forced-update gate. On every start of the app it asks GitHub (see [UpdateChecker]) for the latest build. If this
 * build is older than the minimum, the app is blocked behind the update screen until the new APK is installed, or the user skips it for this session
 * (e.g. to reach music they downloaded). The next start asks again.
 *
 * Offline is not a lock-out: if GitHub cannot be reached, the last answer seen decides (so an app that already knows it
 * is outdated stays blocked, and one that never learned of an update carries on).
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val checker = UpdateChecker(context)
    private val installer = UpdateInstaller(context)
    private val workManager = WorkManager.getInstance(context)

    private val _state = MutableStateFlow(UpdateUiState(checking = checker.enabled))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var watchJob: Job? = null
    private var watchedCode = 0
    /** The download this session started (null = none yet: then whatever is already running or done is picked up). */
    private var expectedWork: UUID? = null
    private var installedWork: UUID? = null
    private var lastCheckAt = 0L

    init {
        // A slow or unreachable GitHub must not hold the app hostage: after the grace period, carry on with what is known.
        viewModelScope.launch {
            delay(GRACE_MS)
            _state.update { if (it.checking) it.copy(checking = false) else it }
        }
        viewModelScope.launch {
            UpdateStatusBus.failures.collect { message ->
                updateJob?.cancel()
                _state.update { it.copy(phase = UpdatePhase.FAILED, error = message) }
            }
        }
    }

    /** Called whenever the app comes to the foreground. */
    fun onStart() {
        check()
        // Back from the "allow installs" screen: carry on where the user left off.
        val s = _state.value
        if (s.required != null && !s.skipped && s.phase == UpdatePhase.NEEDS_PERMISSION && installer.canInstall()) startUpdate()
    }

    private fun check() {
        if (!checker.enabled) {
            _state.update { it.copy(checking = false) }
            return
        }
        if (checkJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (lastCheckAt != 0L && now - lastCheckAt < RECHECK_MS && _state.value.required == null) return
        checkJob = viewModelScope.launch {
            val latest = checker.latest()
            lastCheckAt = SystemClock.elapsedRealtime()
            val required = latest?.takeIf { BuildConfig.VERSION_CODE < it.minVersionCode }
            Log.i("AppUpdate", "installed ${BuildConfig.VERSION_CODE}, latest ${latest?.versionCode}, minimum ${latest?.minVersionCode} -> ${if (required != null) "UPDATE REQUIRED" else "ok"}")
            _state.update { s ->
                when {
                    required == null -> s.copy(checking = false, required = null, phase = UpdatePhase.READY, error = null, skipped = false)
                    s.required?.versionCode == required.versionCode -> s.copy(checking = false)   // same update: keep its progress
                    else -> s.copy(checking = false, required = required, phase = UpdatePhase.READY, progress = 0f, error = null, skipped = false)   // a newer update: ask again
                }
            }
            if (required != null && watchedCode != required.versionCode) {
                watchedCode = required.versionCode
                expectedWork = null
                watch(required)
            }
        }
    }

    /** Lets the user into the app without updating, until the app is closed. */
    fun skip() {
        _state.update { it.copy(skipped = true) }
    }

    fun startUpdate() {
        val info = _state.value.required ?: return
        val phase = _state.value.phase
        if (phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.INSTALLING) return
        if (!installer.canInstall()) {
            _state.update { it.copy(phase = UpdatePhase.NEEDS_PERMISSION, error = null) }
            installer.openInstallSettings()
            return
        }
        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, error = null) }
        // WorkManager runs the download, so it carries on (and resumes) when the app is closed.
        expectedWork = UpdateDownloadWorker.enqueue(context, info)
    }

    /**
     * Follows the download job for [info]: shows its progress, installs when it finishes while the app is on screen.
     * A job that already finished before this session (app closed meanwhile) is not installed behind the user's back:
     * the screen just offers "Update now", which then takes no time because the file is already on disk.
     */
    private fun watch(info: UpdateInfo) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            var sawRunning = false
            workManager.getWorkInfosForUniqueWorkFlow(UpdateDownloadWorker.nameFor(info)).collect { list ->
                val work = list.firstOrNull { expectedWork == null || it.id == expectedWork } ?: return@collect
                when (work.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                        sawRunning = true
                        val p = work.progress.getFloat(UpdateDownloadWorker.K_PROGRESS, -1f)
                        _state.update {
                            if (it.phase == UpdatePhase.INSTALLING) it
                            else it.copy(phase = UpdatePhase.DOWNLOADING, error = null, progress = if (p >= 0f) p else it.progress)
                        }
                    }
                    WorkInfo.State.SUCCEEDED ->
                        if ((sawRunning || expectedWork != null) && installedWork != work.id) {
                            installedWork = work.id
                            install(info)
                        }
                    WorkInfo.State.FAILED ->
                        if (sawRunning || expectedWork != null) {
                            val message = work.outputData.getString(UpdateDownloadWorker.K_ERROR) ?: "The download failed."
                            _state.update { it.copy(phase = UpdatePhase.FAILED, error = message) }
                        }
                    WorkInfo.State.CANCELLED -> Unit
                }
            }
        }
    }

    private fun install(info: UpdateInfo) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _state.update { it.copy(phase = UpdatePhase.INSTALLING, progress = 1f, error = null) }
            try {
                val apk = installer.download(info) { }   // already on disk and checked by the worker: returns at once
                installer.signerProblem(apk)?.let { throw IllegalStateException(it) }
                installer.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = UpdatePhase.FAILED, error = e.message ?: "The update failed.") }
            }
        }
    }

    companion object {
        const val GRACE_MS = 3_500L
        private const val RECHECK_MS = 15 * 60_000L
    }
}