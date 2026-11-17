package com.gamut.onboarding.spotify

import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a public playlist past the embed page's 100-song cut-off. Ported from BitChord's `fetchRemainingTracks`:
 * the web player's own `fetchPlaylist` query, called with the anonymous token the embed page hands out
 * ([SpotifyLinks.embedToken]), a page of 100 at a time.
 *
 * This is an undocumented Spotify endpoint, so it can stop working. Callers treat any failure as "keep the 100
 * already read" and never as an import failure.
 */
object SpotifyPaging {
    /** How many songs the embed page lists before it stops. */
    const val EMBED_LIMIT = 100

    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 50
    private const val URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"

    /** Songs from [start] to the end of the playlist. Blocking; call off the main thread. Throws on any HTTP error. */
    fun fetchRemaining(http: OkHttpClient, playlistId: String, token: String, start: Int, userAgent: String): List<ImportTrack> {
        val out = ArrayList<ImportTrack>()
        var offset = start
        repeat(MAX_PAGES) {
            val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
            val url = URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build()
            val body = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val content = dig(MiniJson.parse(body), "data", "playlistV2", "content") as? Map<*, *> ?: return out
            val items = content["items"] as? List<*> ?: return out
            for (item in items) {
                val data = dig(item, "itemV2", "data") as? Map<*, *> ?: continue
                val title = (data["name"] as? String)?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = (dig(data, "artists", "items") as? List<*>).orEmpty()
                    .mapNotNull { dig(it, "profile", "name") as? String }
                    .joinToString(", ")
                out += ImportTrack(title = title, artist = artist)
            }
            val total = (content["totalCount"] as? Number)?.toInt() ?: return out
            offset += items.size
            if (items.isEmpty() || offset >= total) return out
        }
        return out
    }

    private fun dig(node: Any?, vararg keys: String): Any? {
        var current = node
        for (key in keys) current = (current as? Map<*, *>)?.get(key) ?: return null
        return current
    }
}
