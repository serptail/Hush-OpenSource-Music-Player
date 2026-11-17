package com.gamut.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class OnboardingStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("gamut_onboarding", Context.MODE_PRIVATE)
    private val _localMode = MutableStateFlow(prefs.getBoolean(KEY_LOCAL, false))
    val localMode: StateFlow<Boolean> = _localMode

    private val _setupDone = MutableStateFlow(prefs.getBoolean(KEY_SETUP, false))

    /** True once the first-run setup (name, look, music folder, Spotify import) has been finished or skipped. */
    val setupDone: StateFlow<Boolean> = _setupDone

    private val _setupStarted = MutableStateFlow(prefs.getBoolean(KEY_SETUP_STARTED, false))

    /** True once the setup has been shown, so signing in on its last page can't end it early. */
    val setupStarted: StateFlow<Boolean> = _setupStarted

    fun markSetupStarted() {
        if (_setupStarted.value) return
        prefs.edit().putBoolean(KEY_SETUP_STARTED, true).apply()
        _setupStarted.value = true
    }

    fun completeSetup() {
        prefs.edit().putBoolean(KEY_SETUP, true).apply()
        _setupDone.value = true
    }

    fun chooseLocal() {
        prefs.edit().putBoolean(KEY_LOCAL, true).apply()
        _localMode.value = true
    }

    private companion object {
        const val KEY_LOCAL = "local_mode"
        const val KEY_SETUP = "setup_done"
        const val KEY_SETUP_STARTED = "setup_started"
    }
}