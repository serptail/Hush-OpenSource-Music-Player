package com.gamut.core.common

import java.text.Normalizer

/**
 * Decides whether two catalogue entries (Apple, Deezer, YouTube...) describe the same recording.
 *
 * Services agree on the recording but disagree on packaging: the film a song is "From", an
 * "Official Audio" tag, which credited singers made it into the artist field. A title is therefore
 * split into three parts with different strictness:
 *
 *  - words: the title proper. Must be equal.
 *  - versions: markers of a different take (remix, live, acoustic, "Taylor's Version"). Must be equal
 *    in both directions, so "Song (Live)" never merges with "Song".
 *  - extras: everything else found in brackets or after a dash (feat., From, Remastered, Official
 *    Video). Never a veto.
 *
 * Artist overlap and runtime are then checked on top of that.
 */
object TrackIdentity {
    class Fingerprint(
        val words: String,
        val versions: Set<String>,
        val artists: List<String>,
        val durationMs: Long,
    )

    private val VERSION_WORDS =
        setOf(
            "remix", "live", "acoustic", "unplugged", "instrumental", "karaoke", "cover", "slowed",
            "sped", "reverb", "nightcore", "8d", "demo", "mashup", "extended", "edit", "stripped",
            "reprise", "acapella", "cappella",
        )

    private val VERSION_ALIASES = mapOf("rmx" to "remix", "speed" to "sped", "cappella" to "acapella")

    /** Words that describe packaging, so a "(... Version)" made only of them is not a different take. */
    private val PACKAGING =
        setOf(
            "album", "single", "original", "explicit", "clean", "deluxe", "remaster", "remastered", "mono",
            "stereo", "bonus", "track", "official", "audio", "video", "lyrics", "lyric", "hd", "hq",
            "visualizer", "from", "feat", "ft", "featuring", "with", "prod", "version", "soundtrack",
            "motion", "picture", "music", "theme", "film", "movie", "season", "ost", "radio", "the", "of",
            "and", "a", "in", "to",
        )

    private val BRACKETS = Regex("""[(\[]([^)\]]*)[)\]]""")
    private val DASH = Regex("""\s[-\u2013\u2014]\s""")
    private val TRAILING_FEAT = Regex("""\s(feat\.?|ft\.?|featuring)\s.+$""", RegexOption.IGNORE_CASE)
    private val LATIN_MARKS = Regex("""(?<=\p{IsLatin})\p{M}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTIST_SPLIT =
        Regex(
            """\s*(?:,|&|/|;|\sx\s|\u00D7|\+|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\swith\s|\svs\.?\s|\sand\s)\s*""",
            RegexOption.IGNORE_CASE,
        )
    private val TOPIC_SUFFIX = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)

    /** Case, Latin accents and punctuation are not identity. Non-Latin scripts are kept as they are. */
    fun fold(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .replace(LATIN_MARKS, "")
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .lowercase()
            .replace(NON_WORD, " ")
            .trim()

    /** Credited artists in order, split on the usual separators. A trailing " - Topic" is dropped. */
    fun artists(value: String): List<String> =
        value
            .replace(TOPIC_SUFFIX, "")
            .split(ARTIST_SPLIT)
            .map(::fold)
            .filter { it.isNotBlank() }
            .distinct()

    private fun canonical(token: String): String = VERSION_ALIASES[token] ?: token

    private fun versionsAndExtras(
        text: String,
        versions: MutableSet<String>,
        extras: MutableSet<String>,
    ) {
        val tokens = fold(text).split(' ').filter { it.isNotBlank() }.map(::canonical)
        val markers = tokens.filter { it in VERSION_WORDS }
        versions += markers
        // "(Taylor's Version)": a version made of more than packaging words is a different recording.
        if ("version" in tokens && markers.isEmpty()) {
            val special = tokens.filter { it !in PACKAGING }
            if (special.isNotEmpty()) versions += "v:" + special.sorted().joinToString("_")
        }
        extras += tokens.filter { it !in VERSION_WORDS }
    }

    private class Split(
        val main: String,
        val versions: Set<String>,
    )

    private fun split(
        rawTitle: String,
        artist: String,
    ): Split {
        val versions = linkedSetOf<String>()
        val extras = linkedSetOf<String>()

        var main =
            BRACKETS.replace(rawTitle) { match ->
                versionsAndExtras(match.groupValues[1], versions, extras)
                " "
            }
        main = TRAILING_FEAT.replace(main, "")

        val segments = main.split(DASH).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size > 1) {
            val credited = artists(artist)
            val head = fold(segments.first())
            // "Artist - Song": YouTube uploads put the artist in the title; the song is what follows.
            val artistPrefix = credited.any { head == it || (head.isNotEmpty() && " $head ".contains(" $it ")) }
            val songIndex = if (artistPrefix) 1 else 0
            main = segments[songIndex]
            segments.drop(songIndex + 1).forEach { versionsAndExtras(it, versions, extras) }
        }

        return Split(main.ifBlank { rawTitle }, versions)
    }

    fun fingerprint(
        title: String,
        artist: String,
        durationMs: Long = 0L,
    ): Fingerprint {
        val parts = split(title, artist)
        return Fingerprint(
            words = fold(parts.main).ifBlank { fold(title) },
            versions = parts.versions,
            artists = artists(artist),
            durationMs = durationMs,
        )
    }

    private fun artistsOverlap(
        a: List<String>,
        b: List<String>,
    ): Boolean {
        if (a.isEmpty() || b.isEmpty()) return true
        if (a.any { it in b }) return true
        val joinedA = a.joinToString(" ", prefix = " ", postfix = " ")
        val joinedB = b.joinToString(" ", prefix = " ", postfix = " ")
        return a.any { joinedB.contains(" $it ") } || b.any { joinedA.contains(" $it ") }
    }

    fun sameRecording(
        a: Fingerprint,
        b: Fingerprint,
        toleranceMs: Long = 6_000L,
    ): Boolean {
        if (a.words.isEmpty() || a.words != b.words) return false
        if (a.versions != b.versions) return false
        if (!artistsOverlap(a.artists, b.artists)) return false
        if (a.durationMs > 0 && b.durationMs > 0 && kotlin.math.abs(a.durationMs - b.durationMs) > toleranceMs) return false
        return true
    }

    fun sameRecording(
        titleA: String,
        artistA: String,
        durationMsA: Long,
        titleB: String,
        artistB: String,
        durationMsB: Long,
    ): Boolean = sameRecording(fingerprint(titleA, artistA, durationMsA), fingerprint(titleB, artistB, durationMsB))

    /** A stable key for "same song by the same lead artist", without duration. */
    fun key(
        title: String,
        artist: String,
    ): String {
        val print = fingerprint(title, artist)
        return print.words + "|" + print.versions.sorted().joinToString(",") + "|" + print.artists.firstOrNull().orEmpty()
    }

    /** The song title with a leading "Artist - " (YouTube style) removed; unchanged otherwise. */
    fun displayTitle(
        title: String,
        artist: String,
    ): String {
        val segments = title.split(DASH)
        if (segments.size < 2) return title
        val head = fold(segments.first())
        val credited = artists(artist)
        val artistPrefix = credited.any { head == it || (head.isNotEmpty() && " $head ".contains(" $it ")) }
        return if (artistPrefix) segments.drop(1).joinToString(" - ").trim().ifBlank { title } else title
    }
}
