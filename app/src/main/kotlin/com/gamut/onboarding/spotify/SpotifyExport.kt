package com.gamut.onboarding.spotify

import com.gamut.onboarding.importer.ImportLibrary
import com.gamut.onboarding.importer.ImportPlaylist
import com.gamut.onboarding.importer.ImportTrack
import com.gamut.onboarding.importer.MiniJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** One file the user picked: a name (for the playlist title) and its bytes. */
class ExportFile(val name: String, val bytes: ByteArray)

/**
 * Reads what Spotify hands out: the official data download (a .zip, or its Playlist*.json and YourLibrary.json files)
 * and Exportify's per-playlist .csv files. Anything it doesn't understand is skipped, never fatal.
 */
object SpotifyExportParser {
    fun parse(files: List<ExportFile>): ImportLibrary {
        val playlists = ArrayList<ImportPlaylist>()
        val liked = LinkedHashMap<String, ImportTrack>()

        fun addLiked(track: ImportTrack) {
            liked.putIfAbsent(keyOf(track), track)
        }

        fun handle(path: String, bytes: ByteArray, depth: Int) {
            val base = path.substringAfterLast('/').substringAfterLast('\\')
            val lower = base.lowercase()
            when {
                lower.endsWith(".zip") && depth < 2 -> unzip(bytes.inputStream()).forEach { handle(it.name, it.bytes, depth + 1) }
                lower.endsWith(".csv") -> {
                    val tracks = parseCsv(String(bytes, Charsets.UTF_8))
                    val title = base.substringBeforeLast('.').replace('_', ' ').trim()
                    when {
                        tracks.isEmpty() -> Unit
                        LIKED_NAME.containsMatchIn(title) -> tracks.forEach(::addLiked)
                        else -> playlists += ImportPlaylist(title.ifBlank { DEFAULT_PLAYLIST }, tracks)
                    }
                }
                lower.endsWith(".json") && !IGNORED_JSON.containsMatchIn(lower) -> {
                    val root = try {
                        MiniJson.parse(String(bytes, Charsets.UTF_8))
                    } catch (_: Exception) {
                        return
                    }
                    if (root is Map<*, *>) readJson(root, { playlists += it }, ::addLiked)
                }
            }
        }

        files.forEach { handle(it.name, it.bytes, 0) }
        return ImportLibrary(playlists.filter { it.tracks.isNotEmpty() }, liked.values.toList())
    }

    // ---- JSON (the official export) ----------------------------------------------------------

    private fun readJson(root: Map<*, *>, onPlaylist: (ImportPlaylist) -> Unit, onLiked: (ImportTrack) -> Unit) {
        (root["playlists"] as? List<*>)?.forEach { entry ->
            val playlist = entry as? Map<*, *> ?: return@forEach
            val name = (playlist["name"] as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYLIST
            val tracks = (playlist["items"] as? List<*>).orEmpty().mapNotNull { item ->
                val track = (item as? Map<*, *>)?.get("track") as? Map<*, *> ?: return@mapNotNull null
                val title = (track["trackName"] ?: track["name"]) as? String
                val artist = (track["artistName"] ?: track["artist"]) as? String
                makeTrack(title, artist, track["albumName"] as? String, null)
            }
            onPlaylist(ImportPlaylist(name, tracks))
        }
        (root["tracks"] as? List<*>)?.forEach { entry ->
            val track = entry as? Map<*, *> ?: return@forEach
            makeTrack(track["track"] as? String, track["artist"] as? String, track["album"] as? String, null)?.let(onLiked)
        }
    }

    // ---- CSV (Exportify and similar) ---------------------------------------------------------

    private val TITLE_COLUMNS = listOf("trackname", "name", "title", "songname", "song", "track")
    private val ARTIST_COLUMNS = listOf("artistnames", "artistname", "artists", "artist")
    private val ALBUM_COLUMNS = listOf("albumname", "album")
    private val DURATION_COLUMNS = listOf("trackdurationms", "durationms", "duration")

    private fun parseCsv(text: String): List<ImportTrack> {
        val rows = csvRows(text.removePrefix("\uFEFF"))
        if (rows.size < 2) return emptyList()
        val header = rows[0].map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        fun column(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val title = column(TITLE_COLUMNS) ?: return emptyList()
        val artist = column(ARTIST_COLUMNS)
        val album = column(ALBUM_COLUMNS)
        val duration = column(DURATION_COLUMNS)
        return rows.drop(1).mapNotNull { row ->
            makeTrack(
                title = row.getOrNull(title),
                artist = artist?.let { row.getOrNull(it) },
                album = album?.let { row.getOrNull(it) },
                durationMs = duration?.let { row.getOrNull(it)?.trim()?.toDoubleOrNull()?.toLong() },
            )
        }
    }

    /** RFC 4180: quoted fields may contain commas, doubled quotes and line breaks. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString()
                    field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }

    // ---- Zip ---------------------------------------------------------------------------------

    /**
     * Pulls the json, csv and nested zip entries out of a zip, one at a time, so a big data download is never held
     * in memory whole. Streaming history and other large files that have nothing to import are skipped.
     */
    fun unzip(stream: InputStream): List<ExportFile> {
        val out = ArrayList<ExportFile>()
        try {
            ZipInputStream(stream).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val lower = name.lowercase()
                    if (!(lower.endsWith(".json") || lower.endsWith(".csv") || lower.endsWith(".zip")) || IGNORED_JSON.containsMatchIn(lower)) continue
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                        total += n
                        if (buffer.size() > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) return out
                    }
                    out += ExportFile(name, buffer.toByteArray())
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun makeTrack(title: String?, artist: String?, album: String?, durationMs: Long?): ImportTrack? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        return ImportTrack(
            title = t,
            artist = artist.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            durationMs = durationMs?.takeIf { it > 0 },
        )
    }

    private fun keyOf(track: ImportTrack) = (track.title + "|" + track.artist).lowercase()

    private val LIKED_NAME = Regex("liked|saved songs|favou?rite", RegexOption.IGNORE_CASE)
    private val IGNORED_JSON = Regex("streaming_?history|endsong|searchqueries|userdata|inferences|identity|payments|follow|marquee|wrapped|read_?me", RegexOption.IGNORE_CASE)
    private const val DEFAULT_PLAYLIST = "Spotify playlist"
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
}