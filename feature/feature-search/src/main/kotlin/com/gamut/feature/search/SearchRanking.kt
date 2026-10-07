package com.gamut.feature.search

import com.gamut.core.common.TextMatch
import com.gamut.core.model.Track
import kotlin.math.log10
import kotlin.math.min

/**
 * What Deezer knows about the artist that best matches the typed query, for ONE query ([query] is normalized).
 * [name] is null when Deezer had no plausible artist; [fans] is null when the count is unknown.
 */
data class ArtistSignal(val query: String, val name: String?, val fans: Long?)

/** One song competing for a spot in the results, with the signals the ranker needs. */
internal data class Candidate(
    val track: Track,
    /** 0..1, how widely played the song is (Deezer rank, or YouTube views). 0 = unknown. */
    val popularity: Double,
    /** Position in its source's own relevance order (0 = first). */
    val position: Int,
    val penalty: Double = 0.0,
    val boost: Double = 0.0,
)

/**
 * Pure scoring, no I/O, so it is easy to unit test.
 *
 * Two jobs:
 *  1. [rank]: one ordering for iTunes, Deezer and YouTube songs (text relevance + popularity + source position).
 *  2. [artistConfidence] / [showArtistCard]: decides whether "the artist whose name matches" is really the artist the
 *     person meant. A name match alone is not enough: the artist must also be well known, or the song results must
 *     back it up. That is what stops "qm" from surfacing some random account.
 */
internal object SearchRanking {
    /** Artist card is shown from this confidence up. */
    const val ARTIST_SHOW_THRESHOLD = 0.35

    /** The artist's top songs are mixed into the results from this confidence up. */
    const val ARTIST_BOOST_THRESHOLD = 0.60

    const val MAX_RESULTS = 50

    // ------------------------------------------------------------------ artists

    /** 0..1. Exact name = 1. While typing, the last word may be a prefix ("taylor sw") for 3+ character queries. */
    fun nameMatch(query: String, artistName: String): Double {
        val q = TextMatch.norm(query)
        val n = TextMatch.norm(artistName)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        if (q.length < 3) return 0.0
        val qt = q.split(' ')
        val nt = n.split(' ')
        if (qt.size > nt.size) return 0.0
        for (i in 0 until qt.lastIndex) if (qt[i] != nt[i]) return 0.0
        if (!nt[qt.lastIndex].startsWith(qt.last())) return 0.0
        return if (nt.size == qt.size) 0.85 else 0.70
    }

    /** 0..1 on a log scale: 1k fans = 0, 10k = .33, 100k = .67, 1M+ = 1. Unknown counts as 0. */
    fun popularity(fans: Long?): Double {
        if (fans == null || fans <= 0) return 0.0
        return ((log10(fans.toDouble()) - 3.0) / 3.0).coerceIn(0.0, 1.0)
    }

    /** 0..1: how many of the top results are by this artist (3 or more = full evidence). */
    fun artistEvidence(artistName: String, tracks: List<Track>): Double {
        val n = TextMatch.norm(artistName)
        val hits = tracks.take(12).count { TextMatch.primaryArtist(it.artist) == n }
        return min(1.0, hits / 3.0)
    }

    /** True when a song titled exactly like the query, by somebody else, is among the top results. */
    private fun songCollision(query: String, artistName: String, tracks: List<Track>): Boolean {
        val q = TextMatch.norm(query)
        val n = TextMatch.norm(artistName)
        return tracks.take(10).any { TextMatch.norm(it.title) == q && TextMatch.primaryArtist(it.artist) != n }
    }

    /**
     * 0..1 confidence that [artistName] is the artist the person meant by [query].
     *
     *  - 1-2 character queries need a famous artist (about 350k fans): "u2" and "bts" pass, "qm" does not.
     *  - 3 character queries need about 30k fans.
     *  - Longer queries pass on a name match plus popularity and/or songs by that artist in the results.
     *  - A well-known SONG with the same title as a lesser-known artist's name wins (the artist is demoted).
     */
    fun artistConfidence(query: String, artistName: String, fans: Long?, tracks: List<Track>): Double {
        val name = nameMatch(query, artistName)
        if (name == 0.0) return 0.0
        val pop = popularity(fans)
        val length = TextMatch.norm(query).replace(" ", "").length
        val minPopularity = when {
            length <= 2 -> 0.85
            length == 3 -> 0.55
            else -> 0.0
        }
        if (pop < minPopularity) return 0.0
        var score = name * (0.6 * pop + 0.4 * artistEvidence(artistName, tracks))
        if (pop < 0.67 && songCollision(query, artistName, tracks)) score *= 0.5
        return score
    }

    /**
     * Gate for the artist card the Apple lookup produced. Only for the signal that belongs to the CURRENT query; until
     * it arrives the card stays hidden rather than flashing and disappearing.
     */
    fun showArtistCard(query: String, hitName: String, signal: ArtistSignal?, tracks: List<Track>): Boolean {
        if (signal == null || signal.query != TextMatch.norm(query)) return false
        val sameArtist = signal.name?.let { TextMatch.norm(it) == TextMatch.norm(hitName) } == true
        val fans = if (sameArtist) signal.fans else null
        return artistConfidence(query, hitName, fans, tracks) >= ARTIST_SHOW_THRESHOLD
    }

    // ------------------------------------------------------------------ songs

    /** Deezer rank: 1k = 0 ... 1M+ = 1. */
    fun trackPopularity(rank: Long): Double =
        ((log10(rank.coerceAtLeast(1).toDouble()) - 3.0) / 3.0).coerceIn(0.0, 1.0)

    /** YouTube views: 10k = 0 ... 100M+ = 1. */
    fun viewsPopularity(views: Long): Double =
        ((log10(views.coerceAtLeast(1).toDouble()) - 4.0) / 4.0).coerceIn(0.0, 1.0)

    private fun tokenCoverage(queryTokens: List<String>, haystack: Set<String>): Double {
        if (queryTokens.isEmpty()) return 0.0
        val hits = queryTokens.withIndex().count { (i, token) ->
            token in haystack ||
                // The last word may still be mid-typing.
                (i == queryTokens.lastIndex && token.length >= 3 && haystack.any { it.startsWith(token) })
        }
        return hits.toDouble() / queryTokens.size
    }

    private fun Track.dedupeKey() = TextMatch.norm(title) + "|" + TextMatch.primaryArtist(artist)

    private class Scored(val candidate: Candidate, val coverage: Double, val score: Double)

    /**
     * One ranking for every source. Score = text relevance (exact title / artist / "artist title" bonuses, token
     * coverage) + popularity + the source's own ordering, minus junk (covers, karaoke, remixes the person did not ask
     * for) and a small penalty for YouTube-only finds. Exact duplicates collapse to the best-scored copy.
     */
    fun rank(query: String, candidates: List<Candidate>): List<Track> {
        val q = TextMatch.norm(query)
        val queryTokens = q.split(' ').filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return candidates.map { it.track }

        val scored = candidates.map { c ->
            val t = c.track
            val title = TextMatch.norm(t.title)
            val artist = TextMatch.primaryArtist(t.artist)
            val titleTokens = TextMatch.toks(t.title)
            val artistTokens = TextMatch.toks(t.artist)
            val all = titleTokens + artistTokens + TextMatch.toks(t.album.orEmpty())

            val coverage = tokenCoverage(queryTokens, all)
            var score = tokenCoverage(queryTokens, titleTokens) * 30.0 +
                tokenCoverage(queryTokens, artistTokens) * 30.0 +
                coverage * 20.0
            if (title == q) score += 40.0
            if (artist == q) score += 35.0
            if (q == "$artist $title" || q == "$title $artist") score += 50.0

            // Junk words only count against a song when the person did not type them.
            val junk = (all intersect TextMatch.DERIVATIVE) - queryTokens.toSet()
            score -= junk.size * 60.0

            score += c.popularity * 25.0 + 12.0 / (1.0 + 0.2 * c.position) + c.boost - c.penalty
            Scored(c, coverage, score)
        }

        // Drop songs that share almost nothing with the query, unless that would leave the list too thin.
        val relevant = scored.filter { it.coverage >= 0.5 }.takeIf { it.size >= 5 } ?: scored

        return relevant
            .sortedByDescending { it.score }
            .distinctBy { it.candidate.track.dedupeKey() }
            .take(MAX_RESULTS)
            .map { it.candidate.track }
    }
}
