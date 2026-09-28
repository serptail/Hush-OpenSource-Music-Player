package com.hush.core.network

data class YoutubeCandidate(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelIsTopicChannel: Boolean,
    val durationMs: Long,
    val viewCount: Long,
    val category: String,
    // True when the candidate came from YouTube Music's own "Songs" result type
    // (music.youtube.com search), i.e. YouTube itself classified it as a music track.
    // False for candidates from a broad/regular YouTube search fallback.
    val isMusicCatalog: Boolean = false,
)

/**
 * Defined in core-network (a dependency both feature-search and feature-resolver already have)
 * so SearchRepositoryImpl can fan out to YouTube during a live search WITHOUT feature-search
 * gaining a gradle dependency on feature-resolver.
 *
 * feature-resolver's AndroidYtDlpEngine implements this; ResolverRepositoryModule (Hilt)
 * binds it. feature-search only ever sees the interface.
 */
interface YoutubeMusicSearchSource {
    /** Scoped to YouTube Music's "Songs" catalog. Fast, clean, but misses fan edits/bootlegs. */
    suspend fun searchSongs(query: String): List<YoutubeCandidate>

    /** Regular YouTube search, unrestricted. Slower/noisier; use as a fallback only. */
    suspend fun searchBroad(query: String): List<YoutubeCandidate>
}
