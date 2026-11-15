package com.gamut.onboarding.spotify

import com.gamut.core.common.TextMatch

object SpotifyMatcher {
    const val THRESHOLD = 0.72

    fun query(track: SpotifyTrack): String {
        val artist =
            track.artist
                .split(',')
                .firstOrNull()
                .orEmpty()
                .trim()
        return "${cleanTitle(track.title)} $artist".trim().take(120)
    }

    fun score(
        wanted: SpotifyTrack,
        title: String,
        artist: String,
        durationMs: Long,
    ): Double {
        val wantedTitle = tokens(wanted.title)
        val foundTitle = tokens(title)
        if (wantedTitle.isEmpty() || foundTitle.isEmpty()) return 0.0

        val titleScore = jaccard(wantedTitle, foundTitle)
        val artistScore = artistScore(wanted.artist, artist)
        val durationScore =
            when {
                wanted.durationMs == null || durationMs <= 0 -> {
                    0.5
                }

                else -> {
                    val diff = kotlin.math.abs(wanted.durationMs - durationMs)
                    when {
                        diff <= 3_000 -> 1.0
                        diff <= 8_000 -> 0.5
                        else -> 0.0
                    }
                }
            }
        val unwanted = (foundTitle intersect TextMatch.DERIVATIVE) - wantedTitle
        val penalty = if (unwanted.isNotEmpty()) 0.4 else 0.0

        return 0.55 * titleScore + 0.35 * artistScore + 0.10 * durationScore - penalty
    }

    fun <T> best(
        wanted: SpotifyTrack,
        candidates: List<T>,
        title: (T) -> String,
        artist: (T) -> String,
        durationMs: (T) -> Long,
    ): T? =
        candidates
            .map { it to score(wanted, title(it), artist(it), durationMs(it)) }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first

    private fun artistScore(
        wanted: String,
        found: String,
    ): Double {
        val wantedFirst = TextMatch.primaryArtist(wanted.split(',').firstOrNull().orEmpty())
        val foundNorm = TextMatch.norm(found)
        if (wantedFirst.isEmpty()) return 0.5
        if (foundNorm.isEmpty()) return 0.0
        if (TextMatch.primaryArtist(found) == wantedFirst) return 1.0
        if (" $foundNorm ".contains(" $wantedFirst ")) return 1.0
        return jaccard(TextMatch.toks(wantedFirst), TextMatch.toks(foundNorm))
    }

    private fun tokens(title: String): Set<String> = TextMatch.toks(cleanTitle(title))

    private fun cleanTitle(title: String): String =
        title
            .replace(Regex("""\s*[(\[]\s*(feat|ft|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex(
                    """\s+-\s+(\d{4}\s+)?(remaster(ed)?|single version|radio edit|mono|stereo|deluxe|bonus track)\b.*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).trim()

    private fun jaccard(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = (a intersect b).size
        return common.toDouble() / (a.size + b.size - common)
    }
}
