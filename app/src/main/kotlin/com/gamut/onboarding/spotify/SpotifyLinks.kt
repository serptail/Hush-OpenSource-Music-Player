package com.gamut.onboarding.spotify

import com.gamut.onboarding.importer.ImportPlaylist
import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson

/**
 * Importing straight from Spotify links: finds playlist and album links in whatever text was pasted, and reads the
 * songs from Spotify's public embed page for each one, so no Spotify account or key is involved.
 *
 * Only what a signed-out visitor can see is readable: public playlists and albums. The embed page lists the first
 * 100 songs; longer playlists are read on with [SpotifyPaging]. Private playlists need the data download instead.
 */
object SpotifyLinks {
    enum class Kind(val path: String) { Playlist("playlist"), Album("album") }

    data class Ref(val kind: Kind, val id: String) {
        /** The page Spotify serves for embedding a playlist or album on another site. */
        val embedUrl: String get() = "https://open.spotify.com/embed/${kind.path}/$id"
    }

    private const val ID = "[A-Za-z0-9]{22}"

    private val WEB_LINK = Regex(
        """https?://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(?:user/[^/\s]+/)?(playlist|album)/($ID)""",
        RegexOption.IGNORE_CASE,
    )
    private val URI = Regex("""spotify:(?:user:[^:\s]+:)?(playlist|album):($ID)""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK = Regex("""https?://(?:spotify\.link|spoti\.fi)/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)

    /** Every playlist and album in [text], in order, without repeats. */
    fun parse(text: String): List<Ref> {
        val found = LinkedHashMap<String, Ref>()
        fun add(kind: String, id: String) {
            val ref = Ref(if (kind.equals("album", ignoreCase = true)) Kind.Album else Kind.Playlist, id)
            found.putIfAbsent(ref.kind.path + id, ref)
        }
        // One pass over the text, in order, so a mix of links and URIs keeps the order they were pasted in.
        val matches = (WEB_LINK.findAll(text) + URI.findAll(text)).sortedBy { it.range.first }
        matches.forEach { add(it.groupValues[1], it.groupValues[2]) }
        return found.values.toList()
    }

    /** Short share links ("spotify.link/…") that must be followed to learn what they point at. */
    fun shortLinks(text: String): List<String> = SHORT_LINK.findAll(text).map { it.value }.distinct().toList()

    // ---- Reading the embed page --------------------------------------------------------------

    /**
     * The songs on a playlist or album embed page, or null when the page doesn't hold a track list (a private
     * playlist, or a page layout this doesn't know).
     *
     * The page ships its data as JSON in a `__NEXT_DATA__` script. Rather than trust one exact path through it, the
     * track list is found by shape, so a rearranged wrapper around it doesn't break the import.
     */
    fun parseEmbedPage(html: String, fallbackName: String = "Spotify playlist"): ImportPlaylist? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = try {
            MiniJson.parse(json)
        } catch (_: Exception) {
            return null
        }
        val entity = findEntity(root) ?: return null
        val tracks = (entity["trackList"] as? List<*>).orEmpty().mapNotNull { row ->
            val track = row as? Map<*, *> ?: return@mapNotNull null
            val title = (track["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = (track["subtitle"] as? String).orEmpty().replace('\u00A0', ' ').trim()
            val duration = (track["duration"] as? Number)?.toLong()?.takeIf { it > 0 }
            ImportTrack(title = title, artist = artist, album = null, durationMs = duration)
        }
        if (tracks.isEmpty()) return null
        val name = ((entity["name"] ?: entity["title"]) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
        return ImportPlaylist(name, tracks)
    }

    /**
     * The anonymous web-player token the embed page hands out, or null. It is what lets [SpotifyPaging] read past the
     * first 100 songs, which the embed page itself stops at. Same path BitChord reads it from.
     */
    fun embedToken(html: String): String? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = try {
            MiniJson.parse(json)
        } catch (_: Exception) {
            return null
        }
        var node: Any? = root
        for (key in listOf("props", "pageProps", "state", "settings", "session", "accessToken")) {
            node = (node as? Map<*, *>)?.get(key) ?: return null
        }
        return (node as? String)?.takeIf { it.isNotBlank() }
    }

    private val NEXT_DATA = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** The first object anywhere in [node] that carries a "trackList" array. */
    private fun findEntity(node: Any?, depth: Int = 0): Map<*, *>? {
        if (depth > 24) return null
        when (node) {
            is Map<*, *> -> {
                if (node["trackList"] is List<*>) return node
                node.values.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
            }
            is List<*> -> node.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
        }
        return null
    }
}