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
 *
 * [force] is true when the listener asked by hand: remembered answers are ignored and the sources are asked again.
 */
fun interface TrackUpgradeResolver {
    suspend fun upgrade(
        track: Track,
        force: Boolean,
    ): String?
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

    /**
     * Moves the queue entry at [from] to [to] (both positions in the queue as listed in [queue]). The playing
     * track keeps playing and stays the current one wherever it ends up.
     */
    fun moveInQueue(
        from: Int,
        to: Int,
    ) {}

    fun setSourceResolver(resolver: TrackSourceResolver?)

    /** Optional: a slower lookup run while a track plays, whose answer replaces the stream in place. */
    fun setUpgradeResolver(resolver: TrackUpgradeResolver?) {}

    /**
     * Looks for a better stream of the playing track right now, because the listener asked. The outcome shows up in
     * [AudioPipelineInfo.details]: `upgradeSearch` while looking or when nothing better exists, `upgradedFrom` after a swap.
     */
    fun upgradeQuality() {}

    fun warmUp() {}
}