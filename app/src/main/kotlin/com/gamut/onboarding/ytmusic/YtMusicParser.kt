package com.gamut.onboarding.ytmusic

import com.gamut.onboarding.importer.ImportTrack

object YtMusicParser {
    data class TrackPage(val tracks: List<ImportTrack>, val continuation: String?)

    data class PlaylistRef(val title: String, val browseId: String)

    data class PlaylistPage(val playlists: List<PlaylistRef>, val continuation: String?)

    fun parseTrackPage(root: Any?): TrackPage {
        val scope: Any? = root.obj("continuationContents")?.obj("musicPlaylistShelfContinuation")
            ?: collect(root.obj("contents"), "musicPlaylistShelfRenderer").firstOrNull()
            ?: root.arr("onResponseReceivedActions")?.firstNotNullOfOrNull { it.obj("appendContinuationItemsAction") }
            ?: root.obj("continuationContents")
        val rows = collect(scope ?: root, "musicResponsiveListItemRenderer")
        val seen = HashSet<String>()
        val tracks = rows.mapNotNull { row ->
            val parsed = parseRow(row) ?: return@mapNotNull null
            if (parsed.first in seen) null else parsed.also { seen += it.first }.second
        }
        return TrackPage(tracks, scope?.let(::continuationToken))
    }

    fun parsePlaylistTitle(root: Any?): String? {
        for (header in HEADER_RENDERERS) {
            val title = collect(root, header).firstOrNull().obj("title").runs().trim()
            if (title.isNotEmpty()) return title
        }
        return null
    }

    fun parsePlaylistPage(root: Any?): PlaylistPage {
        val out = LinkedHashMap<String, PlaylistRef>()
        fun add(title: String, browseId: String?) {
            if (browseId == null || title.isBlank()) return
            if (!browseId.startsWith("VL") || browseId == "VLLM" || browseId == "VLSE") return
            out.putIfAbsent(browseId, PlaylistRef(title.trim(), browseId))
        }
        collect(root, "musicTwoRowItemRenderer").forEach { card ->
            add(card.obj("title").runs(), card.obj("navigationEndpoint").obj("browseEndpoint").str("browseId"))
        }
        collect(root, "musicResponsiveListItemRenderer").forEach { row ->
            val title = row.arr("flexColumns")?.firstOrNull().obj("musicResponsiveListItemFlexColumnRenderer").obj("text").runs()
            add(title, row.obj("navigationEndpoint").obj("browseEndpoint").str("browseId"))
        }
        return PlaylistPage(out.values.toList(), continuationToken(root))
    }

    private fun parseRow(row: Map<*, *>): Pair<String, ImportTrack>? {
        if (row.str("musicItemRendererDisplayPolicy")?.contains("GREY_OUT") == true) return null
        val videoId = row.obj("playlistItemData").str("videoId")
            ?: row.obj("overlay").obj("musicItemThumbnailOverlayRenderer").obj("content")
                .obj("musicPlayButtonRenderer").obj("playNavigationEndpoint").obj("watchEndpoint").str("videoId")
            ?: return null
        if (findString(row, "musicVideoType") == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") return null

        val columns = row.arr("flexColumns").orEmpty()
        fun column(index: Int): String = columns.getOrNull(index)
            .obj("musicResponsiveListItemFlexColumnRenderer").obj("text").runs()

        val title = column(0).trim()
        if (title.isEmpty()) return null

        val parts = column(1).split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        val artist = parts.firstOrNull { !DURATION.matches(it) && it.lowercase() !in TYPE_WORDS && !TALLY.containsMatchIn(it) }.orEmpty()
        val album = column(2).trim().ifEmpty { null }
            ?: parts.drop(1).firstOrNull { !DURATION.matches(it) && it.lowercase() !in TYPE_WORDS && !TALLY.containsMatchIn(it) }
        val durationText = parts.lastOrNull { DURATION.matches(it) }
            ?: row.arr("fixedColumns").orEmpty().firstNotNullOfOrNull { fixed ->
                fixed.obj("musicResponsiveListItemFixedColumnRenderer").obj("text").runs().trim().takeIf { DURATION.matches(it) }
            }
        return videoId to ImportTrack(title, artist, album, durationText?.let(::durationMs))
    }

    private fun durationMs(text: String): Long? {
        val parts = text.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size !in 2..3 || parts.size != text.split(':').size) return null
        return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    fun continuationToken(root: Any?): String? {
        val endpoint = collect(root, "continuationItemRenderer").firstOrNull().obj("continuationEndpoint")
        val command = endpoint.obj("continuationCommand")
            ?: endpoint.obj("commandExecutorCommand").arr("commands")?.firstNotNullOfOrNull { it.obj("continuationCommand") }
        command.str("token")?.let { return it }
        return collect(root, "nextContinuationData").firstOrNull().str("continuation")
    }

    private fun collect(root: Any?, name: String): List<Map<*, *>> {
        val out = ArrayList<Map<*, *>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    (node[name] as? Map<*, *>)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(root)
        return out
    }

    private fun findString(node: Any?, key: String): String? = when (node) {
        is Map<*, *> -> (node[key] as? String) ?: node.values.firstNotNullOfOrNull { findString(it, key) }
        is List<*> -> node.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun Any?.obj(key: String): Map<*, *>? = (this as? Map<*, *>)?.get(key) as? Map<*, *>

    private fun Any?.arr(key: String): List<*>? = (this as? Map<*, *>)?.get(key) as? List<*>

    private fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

    private fun Any?.runs(): String =
        this.arr("runs")?.joinToString("") { it.str("text").orEmpty() }.orEmpty()

    private val HEADER_RENDERERS = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")
    private const val SEPARATOR = " \u2022 "
    private val DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")
    private val TALLY = Regex("""^[\d.,]+\s*[KMBkmb]?\s+(plays?|views?)\b""", RegexOption.IGNORE_CASE)
    private val TYPE_WORDS = setOf("song", "video", "episode", "single", "ep", "album")
}
