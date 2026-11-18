package com.gamut.core.player

import com.gamut.core.model.Track

/**
 * One resolved, video-only rendition of a track's music video.
 *
 * Video-only on purpose: audio always comes from [MusicPlayer], which stays the single
 * clock. The video player follows it (see [VideoSyncPlayer]).
 */
data class VideoStream(
    val videoId: String,
    val url: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String?,
    val expiresAtMs: Long,
    /** Headers the media fetch must carry: googlevideo wants the agent of the client that minted the URL. */
    val headers: Map<String, String> = emptyMap(),
    /** InnerTubeX's id for the client that minted [url]; what a refusal skips on the next resolve. Null for NewPipe. */
    val profileId: String? = null,
)

/** Finds the highest-quality decodable music-video stream for a track, or null if none stays in sync. */
fun interface VideoStreamProvider {
    suspend fun resolve(
        track: Track,
        forceRefresh: Boolean,
    ): VideoStream?
}