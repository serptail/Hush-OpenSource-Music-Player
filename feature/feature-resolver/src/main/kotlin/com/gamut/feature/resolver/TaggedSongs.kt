package com.gamut.feature.resolver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaggedSongs
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("tagged_songs", Context.MODE_PRIVATE)
        private val tagged: MutableSet<String> =
            ConcurrentHashMap.newKeySet<String>().also {
                it.addAll(prefs.getStringSet(KEY, emptySet()).orEmpty())
            }

        fun contains(isrc: String) = isrc in tagged

        fun add(isrc: String) {
            if (tagged.add(isrc)) save()
        }

        fun remove(isrc: String) {
            if (tagged.remove(isrc)) save()
        }

        @Synchronized
        private fun save() {
            prefs.edit().putStringSet(KEY, HashSet(tagged)).apply()
        }

        private companion object {
            const val KEY = "isrcs"
        }
    }
