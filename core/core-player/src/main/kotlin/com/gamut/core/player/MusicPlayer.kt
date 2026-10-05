package com.gamut.core.player

import com.gamut.core.model.PlaybackState
import com.gamut.core.model.QueueState
import com.gamut.core.model.RepeatMode
import com.gamut.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NeverAwaiting = MutableStateFlow(false)

/**
 * Turns a [Track] into a playable stream URL. The player owns the queue, so when the user skips
 * (or a track ends) it needs a way to get the URL of the *next* track itself. Implemented by the
 * resolver feature and handed over once at startup via [MusicPlayer.setSourceResolver].
 * Return null if the track cannot be resolved.
 *
 * [onProgress] is for the loading bar: call it with how far along the resolving is (0..1) each time a real
 * step finishes (e.g. 0.5f once the track is identified and only the audio URL is left).
 */
fun interface TrackSourceResolver {
    /** [forceRefresh]: ignore anything cached, the URL the player already has stopped working. */
    suspend fun resolve(track: Track, forceRefresh: Boolean, onProgress: (Float) -> Unit): String?
}

interface MusicPlayer {
    val playbackState: StateFlow<PlaybackState>
    val shuffleEnabled: StateFlow<Boolean>
    val repeatMode: StateFlow<RepeatMode>
    /** The queue in play order (see [QueueState]). */
    val queue: StateFlow<QueueState>
    /**
     * How far along the current load is (0..1), for a loading bar. Only meaningful while [playbackState] is
     * Loading; it never goes backwards within one load and restarts at the next one.
     */
    val loadProgress: StateFlow<Float>

    /**
     * True while [playbackState] says Playing but no audio is actually coming out yet: the next track is still being
     * resolved / buffered, or the stream is re-buffering. The UI must NOT advance its progress clock while this is
     * true, otherwise the timer runs ahead of the sound and then jumps back when the audio really starts.
     */
    val awaitingAudio: StateFlow<Boolean> get() = NeverAwaiting

    /** Plays [track] from [sourceUrl]. If the track is part of the queue the queue position follows it. */
    fun play(track: Track, sourceUrl: String)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()

    /** Replaces the queue. Does not start playback; call [play] (or [skipTo]) for that. */
    fun setQueue(tracks: List<Track>, startIndex: Int)

    fun setShuffle(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    /** Jumps to the track at [index] of the play-order queue. */
    fun skipTo(index: Int)
    fun addToQueue(track: Track, playNext: Boolean = false)
    /** Removes the track at [index] of the play-order queue. The current track cannot be removed. */
    fun removeFromQueue(index: Int)

    fun setSourceResolver(resolver: TrackSourceResolver?)

    /** Builds the underlying engine ahead of the first [play], so the first tap does not pay for it. Optional. */
    fun warmUp() {}
}