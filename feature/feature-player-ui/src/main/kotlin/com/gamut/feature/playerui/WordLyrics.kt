package com.gamut.feature.playerui

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object WordLyrics {
    private const val BASE = "https://lyrics-api.boidu.dev/getLyrics"
    private const val USER_AGENT = "Gamut/1.0 (Android)"
    private val SKIPPED_ROLES = setOf("x-translation", "x-roman", "x-bg")

    fun fetch(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricsLine>? =
        runCatching {
            val query =
                buildString {
                    append("?s=").append(URLEncoder.encode(title, "UTF-8"))
                    append("&a=").append(URLEncoder.encode(artist, "UTF-8"))
                    val seconds = durationMs / 1000
                    if (seconds > 0) append("&d=").append(seconds)
                    if (!album.isNullOrBlank()) append("&al=").append(URLEncoder.encode(album, "UTF-8"))
                }
            val body = get(BASE + query) ?: return null
            val content = unwrap(body) ?: return null
            if (!content.contains("<tt", ignoreCase = true)) return null
            parse(content).takeIf { lines -> lines.any { it.exact && it.words.isNotEmpty() } }
        }.getOrNull()

    private fun get(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 4000
            connection.readTimeout = 7000
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            connection.disconnect()
        }
    }

    private fun unwrap(raw: String): String? {
        val value = raw.replace("\uFEFF", "").trim()
        if (value.isEmpty()) return null
        val parsed: Any =
            runCatching { if (value.startsWith("[")) JSONArray(value) else JSONObject(value) }.getOrNull()
                ?: return unescape(value)
        return extract(parsed)?.trim()?.takeIf { it.isNotEmpty() }?.let(::unescape)
    }

    private val KEYS = listOf("ttml", "ttmlContent", "lyrics", "content", "text", "data", "result", "response")

    private fun extract(node: Any?): String? =
        when (node) {
            is String -> {
                val nested = runCatching { if (node.trimStart().startsWith("{")) JSONObject(node) else null }.getOrNull()
                if (nested != null) extract(nested) else node
            }

            is JSONArray -> {
                (0 until node.length()).mapNotNull { extract(node.opt(it)) }.joinToString("\n").ifBlank { null }
            }

            is JSONObject -> {
                KEYS.firstNotNullOfOrNull { key -> if (node.has(key)) extract(node.opt(key)) else null }
            }

            else -> {
                null
            }
        }

    private fun unescape(value: String): String =
        if (value.contains("&lt;tt", ignoreCase = true)) {
            value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        } else {
            value
        }

    fun parse(ttml: String): List<LyricsLine> =
        runCatching {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    harden("http://apache.org/xml/features/disallow-doctype-decl")
                    harden("http://xml.org/sax/features/external-general-entities", false)
                    harden("http://xml.org/sax/features/external-parameter-entities", false)
                    harden(XMLConstants.FEATURE_SECURE_PROCESSING)
                    runCatching { isExpandEntityReferences = false }
                }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
            val paragraphs = document.getElementsByTagName("p")
            val lines = ArrayList<LyricsLine>(paragraphs.length)
            for (i in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(i) as? Element ?: continue
                lineFrom(paragraph)?.let { lines += it }
            }
            lines.sortBy { it.timestampMs }
            lines
        }.getOrDefault(emptyList())

    private fun DocumentBuilderFactory.harden(
        feature: String,
        value: Boolean = true,
    ) {
        runCatching { setFeature(feature, value) }
    }

    private fun Element.qualified(name: String): String {
        getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val found = attributes ?: return ""
        for (i in 0 until found.length) {
            val attribute = found.item(i) ?: continue
            if (attribute.nodeName == name || attribute.nodeName == local || attribute.localName == local) {
                return attribute.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun lineFrom(paragraph: Element): LyricsLine? {
        val pieces = mutableListOf<Piece>()
        collect(paragraph, pieces)
        val words = mergeIntoWords(pieces)
        val paragraphBegin = time(paragraph.getAttribute("begin"))
        val paragraphEnd = time(paragraph.getAttribute("end"))

        if (words.isEmpty()) {
            val text = paragraph.textContent?.trim().orEmpty()
            val begin = paragraphBegin ?: return null
            if (text.isEmpty()) return null
            return LyricsLine(timestampMs = begin, text = text, endMs = paragraphEnd?.takeIf { it > begin } ?: begin)
        }

        val begin = paragraphBegin ?: words.first().startMs
        return LyricsLine(
            timestampMs = minOf(begin, words.first().startMs),
            text = words.joinToString(" ") { it.text },
            endMs = maxOf(words.last().endMs, paragraphEnd ?: 0L),
            words = words,
            exact = true,
        )
    }

    private fun collect(
        node: Node,
        out: MutableList<Piece>,
    ) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    if (child.qualified("ttm:role") in SKIPPED_ROLES) continue
                    val begin = time(child.getAttribute("begin"))
                    val end = time(child.getAttribute("end"))
                    if (begin != null && end != null && !hasTimedChild(child)) {
                        out += Piece.Timed(child.textContent.orEmpty(), begin, end)
                    } else {
                        collect(child, out)
                    }
                }

                else -> {
                    if (child.nodeType == Node.TEXT_NODE) {
                        val text = child.textContent.orEmpty()
                        if (text.isNotEmpty()) out += Piece.Text(text)
                    }
                }
            }
        }
    }

    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin").isNotEmpty() || hasTimedChild(child)) return true
        }
        return false
    }

    private fun mergeIntoWords(pieces: List<Piece>): List<LyricsWord> {
        val words = mutableListOf<LyricsWord>()
        val current = StringBuilder()
        val syllables = mutableListOf<LyricsSyllable>()
        val spans = pieces.count { it is Piece.Timed && it.text.isNotBlank() }
        val hasBreak =
            pieces.any { p ->
                when (p) {
                    is Piece.Text -> p.text.any { it.isWhitespace() }
                    is Piece.Timed -> p.text.first().isWhitespace() || p.text.last().isWhitespace()
                }
            }
        val glued = !hasBreak && spans >= 4
        var start = 0L
        var end = 0L
        var timed = false

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) {
                words += LyricsWord(start, end, text, LyricsSyllable.normalized(syllables, text.length))
            }
            syllables.clear()
            timed = false
        }

        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> {
                    when {
                        piece.text.isBlank() -> {
                            flush()
                        }

                        timed -> {
                            val leading = piece.text.first().isWhitespace()
                            if (leading) flush() else current.append(piece.text)
                            if (!leading && piece.text.last().isWhitespace()) flush()
                        }

                        else -> {
                            Unit
                        }
                    }
                }

                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    if (piece.text.first().isWhitespace() || (glued && current.isNotEmpty())) flush()
                    if (current.isEmpty()) start = piece.start
                    val from = current.length
                    current.append(piece.text.trim())
                    syllables += LyricsSyllable(piece.start, piece.end, from, current.length)
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        return words
    }

    internal fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val parts = raw.removeSuffix("s").split(':')
        val seconds =
            when (parts.size) {
                1 -> {
                    parts[0].toDoubleOrNull()
                }

                2 -> {
                    parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
                }

                3 -> {
                    parts[0].toDoubleOrNull()?.let { h ->
                        parts[1].toDoubleOrNull()?.let { m -> parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it } }
                    }
                }

                else -> {
                    null
                }
            } ?: return null
        return (seconds * 1000).toLong()
    }

    private sealed interface Piece {
        data class Text(
            val text: String,
        ) : Piece

        data class Timed(
            val text: String,
            val start: Long,
            val end: Long,
        ) : Piece
    }
}
