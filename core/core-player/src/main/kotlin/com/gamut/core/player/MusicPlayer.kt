package com.gamut.core.player

import com.gamut.core.model.PlaybackState
import com.gamut.core.model.Track
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