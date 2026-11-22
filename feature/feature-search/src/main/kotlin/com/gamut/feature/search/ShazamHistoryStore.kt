package com.gamut.feature.search

import android.content.Context
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One song the microphone identified, and when. */
data class ShazamHistoryEntry(
    val track: Track,
    val atMs: Long,
)

/**
 * The songs Shazam has identified, newest first. Kept on the device (a small JSON list in preferences), so it
 * survives restarts. Identifying the same song again moves it back to the top with the new time instead of
 * adding a second row.
 */
@Singleton
class ShazamHistoryStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("shazam_history", Context.MODE_PRIVATE)

        private val _entries = MutableStateFlow(read())
        val entries: StateFlow<List<ShazamHistoryEntry>> = _entries.asStateFlow()

        @Synchronized
        fun add(
            track: Track,
            atMs: Long = System.currentTimeMillis(),
        ) {
            val next = (listOf(ShazamHistoryEntry(track, atMs)) + _entries.value.filterNot { it.track.isrc == track.isrc }).take(MAX)
            save(next)
        }

        @Synchronized
        fun remove(entry: ShazamHistoryEntry) = save(_entries.value.filterNot { it.atMs == entry.atMs && it.track.isrc == entry.track.isrc })

        @Synchronized
        fun clear() = save(emptyList())

        private fun save(list: List<ShazamHistoryEntry>) {
            _entries.value = list
            val json = JSONArray()
            list.forEach { json.put(it.toJson()) }
            prefs.edit().putString(KEY, json.toString()).apply()
        }

        private fun read(): List<ShazamHistoryEntry> =
            runCatching {
                val json = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
                (0 until json.length()).mapNotNull { runCatching { json.getJSONObject(it).toEntry() }.getOrNull() }
            }.getOrDefault(emptyList())

        private fun ShazamHistoryEntry.toJson(): JSONObject =
            JSONObject().apply {
                put("at", atMs)
                put("isrc", track.isrc)
                put("title", track.title)
                put("artist", track.artist)
                put("durationMs", track.durationMs)
                put("source", track.source.name)
                track.musicBrainzId?.let { put("mbid", it) }
                track.album?.let { put("album", it) }
                track.albumArtUrl?.let { put("art", it) }
                track.youtubeVideoId?.let { put("yt", it) }
                track.isExplicit?.let { put("explicit", it) }
            }

        private fun JSONObject.toEntry(): ShazamHistoryEntry =
            ShazamHistoryEntry(
                atMs = getLong("at"),
                track =
                    Track(
                        isrc = getString("isrc"),
                        musicBrainzId = optString("mbid").ifEmpty { null },
                        title = getString("title"),
                        artist = getString("artist"),
                        album = optString("album").ifEmpty { null },
                        durationMs = optLong("durationMs"),
                        albumArtUrl = optString("art").ifEmpty { null },
                        source = runCatching { TrackSource.valueOf(getString("source")) }.getOrDefault(TrackSource.ITUNES),
                        youtubeVideoId = optString("yt").ifEmpty { null },
                        isExplicit = if (has("explicit")) getBoolean("explicit") else null,
                    ),
            )

        private companion object {
            const val KEY = "entries"
            const val MAX = 200
        }
    }
