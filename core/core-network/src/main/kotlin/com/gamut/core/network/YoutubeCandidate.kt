package com.gamut.core.network

private val YoutubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")

data class YoutubeCandidate(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelIsTopicChannel: Boolean,
    val durationMs: Long,
    val viewCount: Long,
    val category: String,
    val isMusicCatalog: Boolean = false,
    /** The artwork the source returned for this entry; square for YouTube Music catalogue songs. */
    val thumbnailUrl: String? = null,
)

fun isValidYoutubeVideoId(value: String): Boolean = YoutubeVideoId.matches(value)

interface YoutubeMusicSearchSource {
    suspend fun searchSongs(query: String): List<YoutubeCandidate>

    suspend fun searchBroad(query: String): List<YoutubeCandidate>

    /** Query completions for a half-typed [query]; empty when the source cannot offer any. */
    suspend fun suggestions(query: String): List<String> = emptyList()
}
