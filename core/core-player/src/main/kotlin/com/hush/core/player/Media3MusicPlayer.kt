package com.hush.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hush.core.model.PlaybackState
import com.hush.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class Media3MusicPlayer @Inject constructor(@ApplicationContext context: Context) : MusicPlayer {
    private val player = ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true).build()
    internal val sessionPlayer: ExoPlayer get() = player
    private val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playbackState: StateFlow<PlaybackState> = state
    private var currentTrack: Track? = null
    private var positionJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPositionUpdates() else positionJob?.cancel()
                currentTrack?.let { state.value = if (isPlaying) PlaybackState.Playing(player.currentPosition, it) else PlaybackState.Paused(player.currentPosition, it) }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { state.value = PlaybackState.Error("Playback could not start") }
        })
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            while (isActive) {
                currentTrack?.let { state.value = PlaybackState.Playing(player.currentPosition, it) }
                delay(500)
            }
        }
    }

    override fun play(track: Track, sourceUrl: String) { currentTrack = track; state.value = PlaybackState.Loading; player.setMediaItem(MediaItem.fromUri(sourceUrl)); player.prepare(); player.play() }
    override fun pause() { player.pause() }
    override fun resume() { player.play() }
    override fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    override fun skipNext() { player.seekToNextMediaItem() }
    override fun skipPrevious() { player.seekToPreviousMediaItem() }
    override fun setQueue(tracks: List<Track>, startIndex: Int) { player.setMediaItems(tracks.map { MediaItem.Builder().setMediaId(it.isrc).build() }, startIndex, 0L); player.prepare() }
}