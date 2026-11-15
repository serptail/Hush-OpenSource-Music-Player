package com.gamut.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.account.ProfileStore
import com.gamut.core.designsystem.AppThemeMode
import com.gamut.core.designsystem.AppearanceSettings
import com.gamut.feature.resolver.DownloadStorage
import com.gamut.feature.resolver.StorageSettings
import com.gamut.onboarding.spotify.ImportState
import com.gamut.onboarding.spotify.SpotifyImporter
import com.gamut.settings.AppearanceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        private val importer: SpotifyImporter,
    ) : ViewModel() {
        val initialName: String get() = profile.localName().orEmpty()

        val appearance: StateFlow<AppearanceSettings> = appearanceStore.settings

        val folder: StateFlow<String?> =
            storageSettings.state
                .map { it.musicFolderUri?.let(storage::folderLabel) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, storageSettings.current.musicFolderUri?.let(storage::folderLabel))

        val importState: StateFlow<ImportState> = importer.state

        fun saveName(name: String) = profile.setName(null, name.trim())

        fun setMode(mode: AppThemeMode) = appearanceStore.update { it.copy(mode = mode) }

        fun useAdaptive() = appearanceStore.update { it.copy(adaptive = true) }

        fun usePreset(id: String) =
            appearanceStore.update {
                it.copy(adaptive = false, preset = id, mode = if (it.mode == AppThemeMode.Auto) AppThemeMode.System else it.mode)
            }

        fun chooseFolder(tree: Uri): Boolean = storage.adopt(tree)

        fun useAppStorage() = storageSettings.update { it.copy(musicFolderUri = null) }

        fun startImport(uris: List<Uri>) = importer.start(uris)

        fun cancelImport() = importer.cancel()

        fun importSeen() = importer.acknowledge()

        fun finish() = store.completeSetup()
    }
