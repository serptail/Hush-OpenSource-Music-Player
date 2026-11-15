package com.gamut.feature.resolver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadQuality(
    val label: String,
    val hint: String,
) {
    BEST("Best available", "The original stream, Opus about 160 kbps. Best sound, but many file managers won't show its cover."),
    STANDARD("Standard", "AAC / m4a, about 128 kbps. Plays everywhere and shows cover art in file managers."),
}

data class StorageSettingsState(
    val wifiOnly: Boolean = false,
    val downloadQuality: DownloadQuality = DownloadQuality.STANDARD,
    val cacheLimitMb: Int = 0,
    val musicFolderUri: String? = null,
)

@Singleton
class StorageSettings
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE)
        private val flow = MutableStateFlow(read())

        val state: StateFlow<StorageSettingsState> = flow.asStateFlow()
        val current: StorageSettingsState get() = flow.value

        @Synchronized
        fun update(block: (StorageSettingsState) -> StorageSettingsState) {
            val next = block(flow.value)
            flow.value = next
            prefs
                .edit()
                .putBoolean("wifiOnly", next.wifiOnly)
                .putString("downloadQuality", next.downloadQuality.name)
                .putInt("cacheLimitMb", next.cacheLimitMb)
                .putString("musicFolderUri", next.musicFolderUri)
                .apply()
        }

        private fun read() =
            StorageSettingsState(
                wifiOnly = prefs.getBoolean("wifiOnly", false),
                downloadQuality =
                    runCatching { DownloadQuality.valueOf(prefs.getString("downloadQuality", null).orEmpty()) }
                        .getOrDefault(DownloadQuality.STANDARD),
                cacheLimitMb = prefs.getInt("cacheLimitMb", 0),
                musicFolderUri = prefs.getString("musicFolderUri", null),
            )
    }
