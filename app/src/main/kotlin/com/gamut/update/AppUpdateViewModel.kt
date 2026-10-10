package com.gamut.update

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
)

/**
 * The forced-update gate. On every start of the app it asks GitHub (see [UpdateChecker]) for the latest build. If this
 * build is older than the minimum, the app is blocked behind the update screen until the new APK is installed.
 *
 * Offline is not a lock-out: if GitHub cannot be reached, the last answer seen decides (so an app that already knows it
 * is outdated stays blocked, and one that never learned of an update carries on).
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val checker = UpdateChecker(context)
    private val installer = UpdateInstaller(context)

    private val _state = MutableStateFlow(UpdateUiState(checking = checker.enabled))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var updateJob: Job? = null
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
        if (s.required != null && s.phase == UpdatePhase.NEEDS_PERMISSION && installer.canInstall()) startUpdate()
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
                    required == null -> s.copy(checking = false, required = null, phase = UpdatePhase.READY, error = null)
                    s.required?.versionCode == required.versionCode -> s.copy(checking = false)   // same update: keep its progress
                    else -> s.copy(checking = false, required = required, phase = UpdatePhase.READY, progress = 0f, error = null)
                }
            }
        }
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
        updateJob = viewModelScope.launch {
            _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, error = null) }
            try {
                val apk = installer.download(info) { p -> _state.update { it.copy(progress = p) } }
                installer.signerProblem(apk)?.let { throw IllegalStateException(it) }
                _state.update { it.copy(phase = UpdatePhase.INSTALLING) }
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
