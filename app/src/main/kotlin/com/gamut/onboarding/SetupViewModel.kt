package com.gamut.onboarding

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.account.ProfileStore
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.core.sync.AuthRepository
import com.gamut.core.sync.SyncConfig
import com.gamut.feature.resolver.DownloadStorage
import com.gamut.feature.resolver.StorageSettings
import com.gamut.onboarding.importer.ImportState
import com.gamut.onboarding.importer.LibraryImporter
import com.gamut.onboarding.ytmusic.YtSession
import com.gamut.settings.AppearanceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val store: OnboardingStore,
        private val profile: ProfileStore,
        private val appearanceStore: AppearanceStore,
        private val storage: DownloadStorage,
        private val storageSettings: StorageSettings,
        private val importer: LibraryImporter,
        private val auth: AuthRepository,
    ) : ViewModel() {
        init {
            store.markSetupStarted()
        }

        val syncAvailable: Boolean = SyncConfig.enabled

        val initialName: String get() = profile.localName().orEmpty()

        val appearance: StateFlow<AppearanceSettings> = appearanceStore.settings

        val folder: StateFlow<String?> =
            storageSettings.state
                .map { it.musicFolderUri?.let(storage::folderLabel) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, storageSettings.current.musicFolderUri?.let(storage::folderLabel))

        val importState: StateFlow<ImportState> = importer.state

        private val mutableAccount = MutableStateFlow(WelcomeUi())
        val account: StateFlow<WelcomeUi> = mutableAccount

        fun saveName(name: String) = profile.setName(null, name.trim())

        fun setMode(mode: AppThemeMode) = appearanceStore.update { it.copy(mode = mode) }

        fun useAdaptive() = appearanceStore.update { it.copy(adaptive = true) }

        fun usePreset(id: String) =
            appearanceStore.update {
                it.copy(adaptive = false, preset = id, mode = if (it.mode == AppThemeMode.Auto) AppThemeMode.System else it.mode)
            }

        fun chooseFolder(tree: Uri): Boolean = storage.adopt(tree)

        fun useAppStorage() = storageSettings.update { it.copy(musicFolderUri = null) }

        fun startFileImport(uris: List<Uri>) = importer.startFromFiles(uris)

        fun startYouTubeImport(session: YtSession) = importer.startFromYouTubeMusic(session)

        fun startSpotifyLinks(text: String) = importer.startFromSpotifyLinks(text)

        fun startAppleMusicLinks(text: String) = importer.startFromAppleMusicLinks(text)

        fun cancelImport() = importer.cancel()

        fun signIn(activityContext: Context) {
            if (mutableAccount.value.busy) return
            viewModelScope.launch {
                mutableAccount.value = WelcomeUi(busy = true)
                auth.signInWithGoogle(activityContext).fold(
                    onSuccess = {
                        withTimeoutOrNull(SESSION_WAIT_MS) { auth.authState.first { it == true } }
                        profile.adoptLocalName(auth.currentUserId())
                        mutableAccount.value = WelcomeUi()
                        store.completeSetup()
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Google sign-in failed: ${error::class.java.name}: ${error.message}", error)
                        mutableAccount.value = WelcomeUi(error = signInMessage(error))
                    },
                )
            }
        }

        private fun signInMessage(error: Throwable): String? =
            when (error) {
                is GetCredentialCancellationException -> null
                is NoCredentialException -> "No Google account was available on this device."
                else -> "Couldn\u2019t sign in. Check your connection and try again."
            }

        fun continueWithoutAccount() {
            store.chooseLocal()
            store.completeSetup()
        }

        fun finish() = store.completeSetup()

        private companion object {
            const val TAG = "GamutSetup"
            const val SESSION_WAIT_MS = 5_000L
        }
    }
