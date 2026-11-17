package com.gamut.onboarding.applemusic

import com.gamut.onboarding.importer.ImportPlaylist
import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson

object AppleMusicLinks {
    enum class Kind(
        val path: String,
    ) {
        Playlist("playlist"),
        Album("album"),
    }

    data class Ref(
        val kind: Kind,
        val id: String,
        val storefront: String,
        val slug: String,
    ) {
        val pageUrl: String get() = "https://music.apple.com/$storefront/${kind.path}/$slug/$id"
    }

    private val URL = Regex("""https?://(?:geo\.|embed\.)?music\.apple\.com/[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val PATH = Regex("""/([a-z]{2})/(playlist|album)/(?:([^/?#\s]+)/)?(pl\.[A-Za-z0-9_-]+|\d+)""", RegexOption.IGNORE_CASE)
    private val SINGLE_SONG = Regex("""[?&]i=\d+""")

    fun parse(text: String): List<Ref> {
        val found = LinkedHashMap<String, Ref>()
        URL.findAll(text).forEach { match ->
            val url = match.value
            if (SINGLE_SONG.containsMatchIn(url)) return@forEach
            val m = PATH.find(url) ?: return@forEach
            val kind = if (m.groupValues[2].equals("album", ignoreCase = true)) Kind.Album else Kind.Playlist
            val id = m.groupValues[4]
            if ((kind == Kind.Playlist) != id.startsWith("pl.", ignoreCase = true)) return@forEach
            val slug = m.groupValues[3].ifEmpty { kind.path }
            found.putIfAbsent(kind.path + id, Ref(kind, id, m.groupValues[1].lowercase(), slug))
        }
        return found.values.toList()
    }

    private class Found(
        val name: String?,
        val tracks: List<ImportTrack>,
    )

    fun parsePage(
        html: String,
        kind: Kind,
        fallbackName: String? = null,
    ): ImportPlaylist? {
        val found = fromJsonLd(html) ?: fromServerData(html) ?: return null
        val default = fallbackName ?: if (kind == Kind.Album) "Apple Music album" else "Apple Music playlist"
        val name = found.name?.takeIf { it.isNotBlank() } ?: pageName(html)?.takeIf { it.isNotBlank() } ?: default
        return ImportPlaylist(name, found.tracks)
    }

    private val LD_JSON =
        Regex(
            """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    private val SERVER_DATA = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private fun fromJsonLd(html: String): Found? {
        for (script in LD_JSON.findAll(html)) {
            val root = parseJson(script.groupValues[1]) ?: continue
            val entity = findEntity(root, 0) ?: continue
            val isAlbum = (entity["@type"] as? String)?.contains("Album", ignoreCase = true) == true
            val entityName = (entity["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val entityArtist = artistNames(entity["byArtist"] ?: entity["author"])
            val tracks =
                (entity["track"] as? List<*>).orEmpty().mapNotNull { row ->
                    val outer = row as? Map<*, *> ?: return@mapNotNull null
                    val track = outer["item"] as? Map<*, *> ?: outer
                    val title = (track["name"] as? String)?.trim().orEmpty()
                    if (title.isEmpty()) return@mapNotNull null
                    val album =
                        ((track["inAlbum"] as? Map<*, *>)?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: entityName.takeIf { isAlbum }
                    ImportTrack(
                        title = title,
                        artist = artistNames(track["byArtist"]).ifEmpty { entityArtist },
                        album = album,
                        durationMs = isoDurationMs(track["duration"] as? String),
                    )
                }
            if (tracks.isNotEmpty()) return Found(entityName, tracks)
        }
        return null
    }

    private fun fromServerData(html: String): Found? {
        val root = parseJson(SERVER_DATA.find(html)?.groupValues?.get(1) ?: return null) ?: return null
        val out = ArrayList<ImportTrack>()
        val seen = HashSet<String>()

        fun walk(
            node: Any?,
            depth: Int,
        ) {
            if (depth > 40) return
            when (node) {
                is Map<*, *> -> {
                    if (isTrackRow(node)) {
                        val id = node["id"] as? String
                        if (id != null && !seen.add(id)) return
                        val title = (node["title"] as? String)?.trim().orEmpty()
                        val artist =
                            (node["artistName"] as? String)?.trim()
                                ?: firstLinkTitle(node["subtitleLinks"]).orEmpty()
                        val album = firstLinkTitle(node["tertiaryLinks"])
                        val duration = (node["duration"] as? Number)?.toLong()?.takeIf { it > 0 }
                        if (title.isNotEmpty()) out += ImportTrack(title, artist, album, duration)
                        return
                    }
                    node.values.forEach { walk(it, depth + 1) }
                }

                is List<*> -> {
                    node.forEach { walk(it, depth + 1) }
                }
            }
        }
        walk(root, 0)
        return if (out.isEmpty()) null else Found(null, out)
    }

    private fun isTrackRow(node: Map<*, *>): Boolean {
        if (node["title"] !is String) return false
        val kind = (node["itemKind"] ?: node["\$kind"]) as? String
        if (kind != null) return kind == "trackLockup"
        return node["artistName"] is String && node["duration"] is Number
    }

    private fun firstLinkTitle(node: Any?): String? =
        ((node as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.get("title")
            ?.let { it as? String }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun artistNames(node: Any?): String =
        when (node) {
            is String -> node.trim()
            is Map<*, *> -> (node["name"] as? String).orEmpty().trim()
            is List<*> -> node.map { artistNames(it) }.filter { it.isNotEmpty() }.joinToString(", ")
            else -> ""
        }

    private fun isoDurationMs(text: String?): Long? {
        val m = ISO_DURATION.matchEntire(text?.trim().orEmpty()) ?: return null
        val hours = m.groupValues[1].toLongOrNull() ?: 0
        val minutes = m.groupValues[2].toLongOrNull() ?: 0
        val seconds = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val ms = ((hours * 60 + minutes) * 60 * 1000) + (seconds * 1000).toLong()
        return ms.takeIf { it > 0 }
    }

    private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""", RegexOption.IGNORE_CASE)

    private val OG_TITLE = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun pageName(html: String): String? {
        val raw = OG_TITLE.find(html)?.groupValues?.get(1) ?: TITLE_TAG.find(html)?.groupValues?.get(1) ?: return null
        return unescape(raw)
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace(Regex("""\s+on Apple Music\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+[-\u2013\u2014]\s+(?:playlist|album|single|ep)\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun unescape(s: String) =
        s
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    fun pageTitle(
        html: String,
        kind: Kind,
    ): String = pageName(html) ?: if (kind == Kind.Album) "Apple Music album" else "Apple Music playlist"

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val META_SONG = Regex("""(?:property|name)\s*=\s*["']music:song["']""", RegexOption.IGNORE_CASE)
    private val META_CONTENT = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SONG_ID = Regex("""/(\d+)(?:[?#].*)?$""")

    fun songIds(html: String): List<String> =
        META_TAG
            .findAll(html)
            .mapNotNull { tag ->
                val text = tag.value
                if (!META_SONG.containsMatchIn(text)) return@mapNotNull null
                val url = META_CONTENT.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                SONG_ID.find(unescape(url))?.groupValues?.get(1)
            }.toList()

    fun lookupUrl(
        ids: List<String>,
        storefront: String,
    ): String = "https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=$storefront"

    fun parseLookup(json: String): List<Pair<String, ImportTrack>> {
        val root = parseJson(json) as? Map<*, *> ?: return emptyList()
        return (root["results"] as? List<*>).orEmpty().mapNotNull { row ->
            val item = row as? Map<*, *> ?: return@mapNotNull null
            if (item["wrapperType"] != "track") return@mapNotNull null
            val id = (item["trackId"] as? Number)?.toLong()?.toString() ?: return@mapNotNull null
            val title = (item["trackName"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            id to
                ImportTrack(
                    title = title,
                    artist = (item["artistName"] as? String).orEmpty().trim(),
                    album = (item["collectionName"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    durationMs = (item["trackTimeMillis"] as? Number)?.toLong()?.takeIf { it > 0 },
                )
        }
    }

    private fun parseJson(text: String): Any? =
        try {
            MiniJson.parse(text)
        } catch (_: Exception) {
            null
        }

    private fun findEntity(
        node: Any?,
        depth: Int,
    ): Map<*, *>? {
        if (depth > 24) return null
        when (node) {
            is Map<*, *> -> {
                if (node["track"] is List<*>) return node
                node.values.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
            }

            is List<*> -> {
                node.forEach { child -> findEntity(child, depth + 1)?.let { return it } }
            }
        }
        return null
    }
}
