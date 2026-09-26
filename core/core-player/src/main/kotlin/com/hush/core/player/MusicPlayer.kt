package com.hush.core.player

import com.hush.core.model.PlaybackState
import com.hush.core.model.Track
import kotlinx.coroutines.flow.StateFlow

interface MusicPlayer {
    val playbackState: StateFlow<PlaybackState>
    fun play(track: Track, sourceUrl: String)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun setQueue(tracks: List<Track>, startIndex: Int)
}