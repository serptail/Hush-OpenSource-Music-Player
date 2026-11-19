package com.gamut.core.player

import com.gamut.core.model.PlaybackState
import com.gamut.core.model.QueueState
import com.gamut.core.model.RepeatMode
import com.gamut.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NeverAwaiting = MutableStateFlow(false)
private val NoStreamDuration = MutableStateFlow(0L)
private val NeverLoadingTrack = MutableStateFlow(false)

fun interface TrackSourceResolver {
    suspend fun resolve(
        track: Track,
        forceRefresh: Boolean,
        onProgress: (Float) -> Unit,
    ): String?
}

/**
 * Looks for a better stream of a track that is already playing. Returns its URL, or null to keep the current
 * one. The player swaps it in at the current position.
 */
fun interface TrackUpgradeResolver {
    suspend fun upgrade(track: Track): String?
}

interface MusicPlayer {
    val playbackState: StateFlow<PlaybackState>
    val shuffleEnabled: StateFlow<Boolean>
    val repeatMode: StateFlow<RepeatMode>
    val queue: StateFlow<QueueState>
    val loadProgress: StateFlow<Float>

    val awaitingAudio: StateFlow<Boolean> get() = NeverAwaiting

    val streamDurationMs: StateFlow<Long> get() = NoStreamDuration

    val loadingTrack: StateFlow<Boolean> get() = NeverLoadingTrack

    fun sourceUrlFor(isrc: String): String? = null

    fun currentPositionMs(): Long = -1L

    fun play(
        track: Track,
        sourceUrl: String,
    )

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun skipNext()

    fun skipPrevious()

    fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    )

    fun setShuffle(enabled: Boolean)

    fun setRepeatMode(mode: RepeatMode)

    fun skipTo(index: Int)

    fun addToQueue(
        track: Track,
        playNext: Boolean = false,
    )

    fun removeFromQueue(index: Int)

    fun setSourceResolver(resolver: TrackSourceResolver?)

    /** Optional: a slower lookup run while a track plays, whose answer replaces the stream in place. */
    fun setUpgradeResolver(resolver: TrackUpgradeResolver?) {}

    fun warmUp() {}
}
