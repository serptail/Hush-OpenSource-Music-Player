package com.gamut.feature.search

import android.content.Context
import com.gamut.core.model.Track
import com.gamut.core.model.TrackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last session's Discover on disk, so the next launch can show it at once while fresh data loads.
 * Plain org.json (part of Android) instead of kotlinx.serialization: [DiscoverItem] and [DiscoverTab] are not
 * @Serializable and this module may not apply the serialization plugin. A missing or unreadable file is just "no cache".
 */
@Singleton
class DiscoverDiskCache @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.cacheDir, "discover.json")

    suspend fun read(): DiscoverState? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val root = JSONObject(file.readText())
            if (root.optInt("v") != VERSION) return@runCatching null
            val tabs = root.getJSONArray("tabs").objects().map { tab ->
                DiscoverTab(tab.getString("label"), tab.getString("title"), tab.getJSONArray("items").objects().map { it.toItem() })
            }
            if (tabs.isEmpty()) return@runCatching null
            DiscoverState(
                albums = root.getJSONArray("albums").objects().map { it.toItem() },
                artists = root.getJSONArray("artists").objects().map { it.toItem() },
                tabs = tabs,
                isLoading = false,
            )
        }.getOrNull()
    }

    suspend fun write(state: DiscoverState) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
                .put("v", VERSION)
                .put("albums", JSONArray(state.albums.map { it.toJson() }))
                .put("artists", JSONArray(state.artists.map { it.toJson() }))
                .put("tabs", JSONArray(state.tabs.map { tab ->
                    JSONObject().put("label", tab.label).put("title", tab.title)
                        .put("items", JSONArray(tab.items.map { it.toJson() }))
                }))
            // Write-then-rename: a crash mid-write must not leave a half file behind.
            val tmp = File(file.parentFile, "discover.json.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    private fun DiscoverItem.toJson() = JSONObject()
        .put("id", id).put("title", title).put("subtitle", subtitle).put("imageUrl", imageUrl)
        .put("artistId", artistId ?: JSONObject.NULL)
        .put("track", track?.toJson() ?: JSONObject.NULL)

    private fun Track.toJson() = JSONObject()
        .put("isrc", isrc).put("musicBrainzId", musicBrainzId ?: JSONObject.NULL)
        .put("title", title).put("artist", artist).put("album", album ?: JSONObject.NULL)
        .put("durationMs", durationMs).put("albumArtUrl", albumArtUrl ?: JSONObject.NULL)
        .put("source", source.name).put("youtubeVideoId", youtubeVideoId ?: JSONObject.NULL)

    private fun JSONObject.toItem() = DiscoverItem(
        id = getString("id"),
        title = getString("title"),
        subtitle = getString("subtitle"),
        imageUrl = getString("imageUrl"),
        track = optJSONObject("track")?.toTrack(),
        artistId = if (isNull("artistId")) null else getLong("artistId"),
    )

    private fun JSONObject.toTrack() = Track(
        isrc = getString("isrc"),
        musicBrainzId = str("musicBrainzId"),
        title = getString("title"),
        artist = getString("artist"),
        album = str("album"),
        durationMs = getLong("durationMs"),
        albumArtUrl = str("albumArtUrl"),
        source = runCatching { TrackSource.valueOf(getString("source")) }.getOrDefault(TrackSource.ITUNES),
        youtubeVideoId = str("youtubeVideoId"),
    )

    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    // 3: drops copies saved while Billboard art was unresolved (Deezer quota errors) so they are not shown as "stale".
    private companion object { const val VERSION = 3 }
}