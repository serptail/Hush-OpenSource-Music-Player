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