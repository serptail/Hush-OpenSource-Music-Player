package com.gamut.feature.search

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a catalogue clip (~30s) on its own, for auditioning a result.
 *
 * It is deliberately separate from the music player: it never touches the queue or the media
 * session. It takes transient audio focus instead, so whatever is playing pauses for the clip and
 * resumes by itself when the clip ends or is stopped. Starting real playback elsewhere takes focus
 * back, which stops the clip.
 */
@Singleton
class PreviewPlayer
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        data class State(
            val key: String? = null,
            val loading: Boolean = false,
        )

        private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private var player: MediaPlayer? = null
        private var focus: AudioFocusRequest? = null

        private val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        /** Starts [url] under [key], or stops it if [key] is already the one playing. */
        fun toggle(
            key: String,
            url: String,
        ) {
            if (_state.value.key == key) stop() else start(key, url)
        }

        private fun start(
            key: String,
            url: String,
        ) {
            stop()
            val request =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                    }.build()
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
            focus = request

            val mp = MediaPlayer()
            player = mp
            _state.value = State(key, loading = true)
            try {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(url.replaceFirst("http://", "https://"))
                mp.setOnPreparedListener {
                    if (player === it) {
                        it.start()
                        _state.value = State(key, loading = false)
                    }
                }
                mp.setOnCompletionListener { if (player === it) stop() }
                mp.setOnErrorListener { p, _, _ ->
                    if (player === p) stop()
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                stop()
            }
        }

        fun stop() {
            val current = player
            player = null
            current?.let { runCatching { it.release() } }
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
            if (_state.value.key != null) _state.value = State()
        }
    }
