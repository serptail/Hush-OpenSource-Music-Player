package com.gamut.feature.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How search picks the cover for a song that lives on more than one release. Kept here, next to the search that reads
 * it, and shown in Appearance settings.
 */
@Singleton
class CoverArtPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("gamut_cover_art", Context.MODE_PRIVATE)
        private val _preferSingleCovers = MutableStateFlow(prefs.getBoolean(KEY_PREFER_SINGLE, DEFAULT_PREFER_SINGLE))

        /** When a song is on a single and on an album, show the single's cover in search results. */
        val preferSingleCovers: StateFlow<Boolean> = _preferSingleCovers

        fun setPreferSingleCovers(value: Boolean) {
            _preferSingleCovers.value = value
            prefs.edit().putBoolean(KEY_PREFER_SINGLE, value).apply()
        }

        companion object {
            private const val KEY_PREFER_SINGLE = "prefer_single_covers"
            const val DEFAULT_PREFER_SINGLE = true
        }
    }
