package com.gamut.onboarding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("gamut_onboarding", Context.MODE_PRIVATE)
        private val _localMode = MutableStateFlow(prefs.getBoolean(KEY_LOCAL, false))
        val localMode: StateFlow<Boolean> = _localMode

        private val _setupDone = MutableStateFlow(prefs.getBoolean(KEY_SETUP, false))

        val setupDone: StateFlow<Boolean> = _setupDone

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
        }
    }
