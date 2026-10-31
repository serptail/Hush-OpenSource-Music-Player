package com.gamut.feature.search

import com.gamut.core.model.Track

/**
 * One card or row on Discover (a song, an album, or an artist).
 * [track] is set for playable songs, [artistId] for artist cards, [followers] (fan count) when known.
 */
data class DiscoverItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val track: Track? = null,
    val artistId: Long? = null,
    val followers: Long? = null,
    /** Small third line on chart rows: "Peak #1 \u00B7 12 wks" on Billboard, "3 days ago" on Latest. */
    val meta: String? = null,
    /** Chart movement since last week (Billboard). [trendDelta] is the number of places moved. */
    val trend: Trend? = null,
    val trendDelta: Int = 0,
)

enum class Trend { UP, DOWN, SAME, NEW }

/** A chart tab: [label] on the pill, [title] as the caption under it. */
data class DiscoverTab(
    val label: String,
    val title: String,
    val items: List<DiscoverItem>,
)
// ---- The Discover feed: extra sections that sit between and below the charts ----

/** How a [FeedSection] is drawn. */
enum class FeedKind {
    /** 2-column grid of compact song tiles (cover + title). */
    QUICK_PICKS,
    /** Horizontally paged columns of three song rows. */
    TRACK_RAILS,
    /** Square covers with title and artist, one tap plays. */
    TRACK_CAROUSEL,
    /** Square covers, one tap opens the album. */
    ALBUM_CAROUSEL,
    /** Round portraits, one tap opens the artist. */
    ARTIST_CAROUSEL,
    /** One big album card with a label chip ("New album"). */
    SPOTLIGHT,
}

/** Where a section sits on the page: under the hero, above the charts, or below them (the endless part). */
enum class FeedSlot { TOP, MIDDLE, BOTTOM }

/**
 * One block of the feed. [order] sorts sections inside a [slot]; [id] is stable across loads ("family:detail"), which is
 * how a fresh section replaces last session's copy of itself. [subtitle] is the small "why you are seeing this" line.
 */
data class FeedSection(
    val id: String,
    val kind: FeedKind,
    val slot: FeedSlot,
    val title: String,
    val items: List<DiscoverItem>,
    val subtitle: String? = null,
    val order: Int = 0,
)