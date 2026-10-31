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
            // The feed is optional: a file without it (older app) or with a bad section still gives the charts.
            val feed = runCatching { root.optJSONArray("feed")?.objects()?.mapNotNull { it.toSection() }.orEmpty() }.getOrDefault(emptyList())
            DiscoverState(
                albums = root.getJSONArray("albums").objects().map { it.toItem() },
                artists = root.getJSONArray("artists").objects().map { it.toItem() },
                tabs = tabs,
                isLoading = false,
                feed = feed,
            )
        }.getOrNull()
    }

    /** Size of the cached Discover on disk (0 when there is none). */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        file.length() + File(file.parentFile, "discover.json.tmp").length()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete(); File(file.parentFile, "discover.json.tmp").delete() }
    }

    suspend fun write(state: DiscoverState) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
                .put("v", VERSION)
                .put("albums", JSONArray(state.albums.map { it.toJson() }))
                .put("artists", JSONArray(state.artists.map { it.toJson() }))
                .put("feed", JSONArray(state.feed.map { it.toJson() }))
                .put("tabs", JSONArray(state.tabs.map { tab ->
                    JSONObject().put("label", tab.label).put("title", tab.title)
                        .put("items", JSONArray(tab.items.map { it.toJson() }))
                }))
            // Write-then-rename: a crash mid-write must not leave a half file behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    private fun FeedSection.toJson() = JSONObject()
        .put("id", id).put("kind", kind.name).put("slot", slot.name).put("order", order)
        .put("title", title).put("subtitle", subtitle ?: JSONObject.NULL)
        .put("items", JSONArray(items.map { it.toJson() }))

    /** Null for a section kind this build does not know (a newer app wrote it): it is dropped, not fatal. */
    private fun JSONObject.toSection(): FeedSection? {
        val kind = runCatching { FeedKind.valueOf(getString("kind")) }.getOrNull() ?: return null
        val slot = runCatching { FeedSlot.valueOf(getString("slot")) }.getOrNull() ?: return null
        return FeedSection(
            id = getString("id"), kind = kind, slot = slot, order = optInt("order"),
            title = getString("title"), subtitle = str("subtitle"),
            items = getJSONArray("items").objects().map { it.toItem() },
        )
    }

    private fun DiscoverItem.toJson() = JSONObject()
        .put("id", id).put("title", title).put("subtitle", subtitle).put("imageUrl", imageUrl)
        .put("artistId", artistId ?: JSONObject.NULL)
        .put("track", track?.toJson() ?: JSONObject.NULL)
        .put("followers", followers ?: JSONObject.NULL)
        .put("meta", meta ?: JSONObject.NULL)
        .put("trend", trend?.name ?: JSONObject.NULL)
        .put("trendDelta", trendDelta)

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
        followers = if (isNull("followers")) null else optLong("followers"),
        meta = str("meta"),
        trend = str("trend")?.let { runCatching { Trend.valueOf(it) }.getOrNull() },
        trendDelta = optInt("trendDelta"),
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
    // 4: items carry meta / trend (Billboard movement, release age) and followers.
    // The optional "feed" array (sections of the Discover feed) was added WITHOUT a bump: it is read leniently, so v4 files
    // stay valid and simply have no feed.
    private companion object { const val VERSION = 4 }
}