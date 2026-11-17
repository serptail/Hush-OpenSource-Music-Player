package com.gamut.onboarding.importer

/** One song from another service's library: what it's called and who made it. Nothing Gamut-specific. */
data class ImportTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
)

data class ImportPlaylist(val name: String, val tracks: List<ImportTrack>)

/** Everything found to import: named playlists plus the liked songs. */
data class ImportLibrary(val playlists: List<ImportPlaylist>, val liked: List<ImportTrack>) {
    val trackCount: Int get() = playlists.sumOf { it.tracks.size } + liked.size
    val isEmpty: Boolean get() = trackCount == 0
}
