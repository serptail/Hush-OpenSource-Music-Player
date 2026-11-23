package com.gamut.feature.search

import com.gamut.core.common.TrackIdentity
import com.gamut.core.network.YoutubeMusicSearchSource
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Typeahead completions for the search field. Cheap enough to call per (debounced) keystroke. */
@Singleton
class SearchSuggestionsRepository
    @Inject
    constructor(
        private val youtube: YoutubeMusicSearchSource,
    ) {
        private class Cached(
            val values: List<String>,
            val atMs: Long,
        )

        private val cache = ConcurrentHashMap<String, Cached>()

        @Volatile private var lastGood: List<String> = emptyList()

        /** What the previous completions still say about [input]: shown instantly while a fresh fetch runs. */
        fun localFor(input: String): List<String> {
            val key = TrackIdentity.fold(input)
            if (key.length < MIN_LENGTH) return emptyList()
            return lastGood.filter { TrackIdentity.fold(it).startsWith(key) }
        }

        suspend fun suggest(input: String): List<String> {
            val key = TrackIdentity.fold(input)
            if (key.length < MIN_LENGTH) return emptyList()
            cache[key]?.takeIf { System.currentTimeMillis() - it.atMs < TTL_MS }?.let {
                if (it.values.isNotEmpty()) lastGood = it.values
                return it.values
            }

            val fetched = withTimeoutOrNull(TIMEOUT_MS) { runCatching { youtube.suggestions(input.trim()) }.getOrNull() }
            // A timeout or failure is not cached, so the next keystroke tries again, and the last good
            // completions that still fit are kept rather than blanking the row.
            val values = fetched ?: return localFor(input)
            if (cache.size >= MAX_ENTRIES) cache.clear()
            cache[key] = Cached(values, System.currentTimeMillis())
            if (values.isNotEmpty()) lastGood = values
            return values
        }

        private companion object {
            const val MIN_LENGTH = 2
            const val TIMEOUT_MS = 1_500L
            const val TTL_MS = 5 * 60 * 1_000L
            const val MAX_ENTRIES = 80
        }
    }
