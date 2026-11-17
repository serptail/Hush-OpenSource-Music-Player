package com.gamut.onboarding.importer

data class ImportTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
)

data class ImportPlaylist(
    val name: String,
    val tracks: List<ImportTrack>,
)

data class ImportLibrary(
    val playlists: List<ImportPlaylist>,
    val liked: List<ImportTrack>,
) {
    val trackCount: Int get() = playlists.sumOf { it.tracks.size } + liked.size
    val isEmpty: Boolean get() = trackCount == 0
}
