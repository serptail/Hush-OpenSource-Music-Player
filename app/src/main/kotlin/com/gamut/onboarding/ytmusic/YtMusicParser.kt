package com.gamut.onboarding.ytmusic

import com.gamut.onboarding.importer.ImportTrack

/**
 * Reads YouTube Music's browse responses (already parsed to maps and lists) into songs and playlists.
 *
 * Deliberately forgiving: a row or field that isn't there is skipped, never fatal, because the response layout
 * shifts over time and a partial library is far better than none.
 */
object YtMusicParser {
    data class TrackPage(val tracks: List<ImportTrack>, val continuation: String?)

    data class PlaylistRef(val title: String, val browseId: String)

    data class PlaylistPage(val playlists: List<PlaylistRef>, val continuation: String?)

    /**
     * The songs on a playlist page (Liked Music is one) or on a continuation page, and the token for the next one.
     *
     * A playlist you own can carry a "Suggestions" shelf below the real tracks, so the rows are taken from the
     * playlist shelf itself, not from anywhere on the page.
     */
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

    /**
     * The playlist's name from a browse response's header, or null when the header isn't one of the layouts known.
     * A playlist read from a link has no library row to take its name from.
     */
    fun parsePlaylistTitle(root: Any?): String? {
        for (header in HEADER_RENDERERS) {
            val title = collect(root, header).firstOrNull().obj("title").runs().trim()
            if (title.isNotEmpty()) return title
        }
        return null
    }

    /** The playlists on the library's playlist page (grid or list view), minus Liked Music and episodes. */
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

    // ---- Rows --------------------------------------------------------------------------------

    /** (videoId, track), or null for anything that isn't a playable song row. */
    private fun parseRow(row: Map<*, *>): Pair<String, ImportTrack>? {
        // A greyed-out row is a song that was removed or isn't available here.
        if (row.str("musicItemRendererDisplayPolicy")?.contains("GREY_OUT") == true) return null
        val videoId = row.obj("playlistItemData").str("videoId")
            ?: row.obj("overlay").obj("musicItemThumbnailOverlayRenderer").obj("content")
                .obj("musicPlayButtonRenderer").obj("playNavigationEndpoint").obj("watchEndpoint").str("videoId")
            ?: return null
        // A podcast episode isn't a recording a music catalog can have: it would only be matched to some unrelated song.
        // Told apart by the type its play action states, as BitChord does.
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

    // ---- Continuations -----------------------------------------------------------------------

    /**
     * Token for the next page, or null at the end. Two layouts are in circulation: the command nested directly in
     * the continuation item, and one wrapped in a command executor beside other commands. The older
     * `nextContinuationData` form is read as a last resort.
     */
    fun continuationToken(root: Any?): String? {
        val endpoint = collect(root, "continuationItemRenderer").firstOrNull().obj("continuationEndpoint")
        val command = endpoint.obj("continuationCommand")
            ?: endpoint.obj("commandExecutorCommand").arr("commands")?.firstNotNullOfOrNull { it.obj("continuationCommand") }
        command.str("token")?.let { return it }
        return collect(root, "nextContinuationData").firstOrNull().str("continuation")
    }

    // ---- JSON helpers ------------------------------------------------------------------------

    /** Every object stored under [name] anywhere inside [root]. */
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

    /** The first string stored under [key] anywhere inside [node]. */
    private fun findString(node: Any?, key: String): String? = when (node) {
        is Map<*, *> -> (node[key] as? String) ?: node.values.firstNotNullOfOrNull { findString(it, key) }
        is List<*> -> node.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun Any?.obj(key: String): Map<*, *>? = (this as? Map<*, *>)?.get(key) as? Map<*, *>

    private fun Any?.arr(key: String): List<*>? = (this as? Map<*, *>)?.get(key) as? List<*>

    private fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String

    /** The text of a "runs" value, joined. */
    private fun Any?.runs(): String =
        this.arr("runs")?.joinToString("") { it.str("text").orEmpty() }.orEmpty()

    private val HEADER_RENDERERS = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")
    private const val SEPARATOR = " \u2022 "
    private val DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")
    private val TALLY = Regex("""^[\d.,]+\s*[KMBkmb]?\s+(plays?|views?)\b""", RegexOption.IGNORE_CASE)
    private val TYPE_WORDS = setOf("song", "video", "episode", "single", "ep", "album")
}