package com.gamut.feature.search

import com.gamut.core.model.Track

data class DiscoverItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val track: Track? = null,
    val artistId: Long? = null,
    val followers: Long? = null,
    val meta: String? = null,
    val trend: Trend? = null,
    val trendDelta: Int = 0,
)

enum class Trend { UP, DOWN, SAME, NEW }

data class DiscoverTab(
    val label: String,
    val title: String,
    val items: List<DiscoverItem>,
)

enum class FeedKind {
    /** The lead row of tall station cards: latest release, mixes, radio, a mood. */
    TOP_PICKS,

    /** Tiles for every mood, the ones that suit the hour first. */
    MOODS,
    QUICK_PICKS,
    TRACK_RAILS,
    TRACK_CAROUSEL,
    ALBUM_CAROUSEL,
    ARTIST_CAROUSEL,
    SPOTLIGHT,
}

enum class FeedSlot { TOP, MIDDLE, BOTTOM }

data class FeedSection(
    val id: String,
    val kind: FeedKind,
    val slot: FeedSlot,
    val title: String,
    val items: List<DiscoverItem>,
    val subtitle: String? = null,
    val order: Int = 0,
)
