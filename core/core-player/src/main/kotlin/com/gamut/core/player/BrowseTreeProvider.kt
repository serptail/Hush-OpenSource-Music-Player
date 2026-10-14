package com.gamut.core.player

import com.gamut.core.model.Track

/** One row in the car's browse screen. */
sealed interface BrowseEntry {
    val id: String

    data class Folder(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val artUrl: String? = null,
    ) : BrowseEntry

    /** [id] must be enough for [BrowseTreeProvider.queueFor] to rebuild the list this song was picked from. */
    data class Song(override val id: String, val track: Track) : BrowseEntry
}

/** The queue to play when the car taps a song: the whole list it came from, starting at [startIndex]. */
data class BrowseQueue(val tracks: List<Track>, val startIndex: Int)

/**
 * What Android Auto / Assistant can browse and play. core-player must not depend on the feature modules, so (like
 * [TrackSourceResolver]) it only defines the contract; the app module implements it on top of the library + database.
 */
interface BrowseTreeProvider {
    /** Children of a folder id ("root" is the top level). Empty if unknown. */
    suspend fun children(parentId: String): List<BrowseEntry>

    /** A single folder or song by id, or null. */
    suspend fun item(id: String): BrowseEntry?

    /** The queue for a song id from [children] / [search], or null if the id is a folder or no longer exists. */
    suspend fun queueFor(mediaId: String): BrowseQueue?

    /** Voice / search-box search. A blank [query] ("play some music") should return something sensible to play. */
    suspend fun search(query: String): List<BrowseEntry.Song>

    /** What "resume" should play when nothing is loaded (e.g. the last played song's queue). */
    suspend fun resumption(): BrowseQueue?

    companion object {
        const val ROOT = "root"
    }
}
