package com.gamut.onboarding.spotify

import com.gamut.onboarding.importer.ImportPlaylist
import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson

object SpotifyLinks {
    enum class Kind(
        val path: String,
    ) {
        Playlist("playlist"),
        Album("album"),
    }

    data class Ref(
        val kind: Kind,
        val id: String,
    ) {
        val embedUrl: String get() = "https://open.spotify.com/embed/${kind.path}/$id"
    }

    private const val ID = "[A-Za-z0-9]{22}"

    private val WEB_LINK =
        Regex(
            """https?://open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(?:user/[^/\s]+/)?(playlist|album)/($ID)""",
            RegexOption.IGNORE_CASE,
        )
    private val URI = Regex("""spotify:(?:user:[^:\s]+:)?(playlist|album):($ID)""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK = Regex("""https?://(?:spotify\.link|spoti\.fi)/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Ref> {
        val found = LinkedHashMap<String, Ref>()

        fun add(
            kind: String,
            id: String,
        ) {
            val ref = Ref(if (kind.equals("album", ignoreCase = true)) Kind.Album else Kind.Playlist, id)
            found.putIfAbsent(ref.kind.path + id, ref)
        }
        val matches = (WEB_LINK.findAll(text) + URI.findAll(text)).sortedBy { it.range.first }
        matches.forEach { add(it.groupValues[1], it.groupValues[2]) }
        return found.values.toList()
    }

    fun shortLinks(text: String): List<String> =
        SHORT_LINK
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()

    fun parseEmbedPage(
        html: String,
        fallbackName: String = "Spotify playlist",
    ): ImportPlaylist? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root =
            try {
                MiniJson.parse(json)
            } catch (_: Exception) {
                return null
            }
        val entity = findEntity(root) ?: return null
        val tracks =
            (entity["trackList"] as? List<*>).orEmpty().mapNotNull { row ->
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

    fun embedToken(html: String): String? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root =
            try {
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

    private fun findEntity(
        node: Any?,
        depth: Int = 0,
    ): Map<*, *>? {
        if (depth > 24) return null
        when (node) {
            is Map<*, *> -> {
                if (node["trackList"] is List<*>) return node
                node.values.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
            }

            is List<*> -> {
                node.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
            }
        }
        return null
    }
}
