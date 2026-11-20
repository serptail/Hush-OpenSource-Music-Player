package com.gamut.core.player

import android.content.Context
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * A silent, video-only ExoPlayer that follows [MusicPlayer]'s audio clock.
 *
 * Audio is the master: this never drives playback and never pauses the music. When the video
 * buffers, the music keeps going and the video catches up. Small drift is absorbed by nudging
 * the playback rate (invisible); large drift, or a user seek, is a hard seek.
 *
 * Main-thread only, like any ExoPlayer.
 */
@UnstableApi
class VideoSyncPlayer(
    context: Context,
) {
    private val exo: ExoPlayer =
        ExoPlayer
            .Builder(
                context,
                DefaultRenderersFactory(context).setEnableDecoderFallback(true),
).setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
                    // High-resolution frames are big; cap the buffer by bytes as well as time.
                    .setTargetBufferBytes(MAX_BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            ).build()
            .apply {
                volume = 0f
                setSeekParameters(SeekParameters.EXACT)
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
            }

    /** Headers of the stream being loaded; read each time a data source is created. */
    @Volatile
    private var headers: Map<String, String> = emptyMap()

    private val mediaSourceFactory =
        ProgressiveMediaSource.Factory(
            RangedDataSource.Factory(
                DataSource.Factory {
                    val h = headers
                    DefaultHttpDataSource
                        .Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(15_000)
                        .setUserAgent(h["User-Agent"])
                        .setDefaultRequestProperties(h.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
                        .createDataSource()
                },
            ),
        )

    private val aspect = MutableStateFlow(0f)

    /** Display aspect (width / height) of the decoded video; 0 until the decoder reports it. */
    val videoAspect: StateFlow<Float> = aspect

    private val renderedFlow = MutableStateFlow(false)

    /** True once a frame is on the current surface. */
    val rendered: StateFlow<Boolean> = renderedFlow

    private val failedFlow = MutableStateFlow(false)

    /** True after a playback error (usually an expired URL): the owner should re-resolve once. */
    val failed: StateFlow<Boolean> = failedFlow

    private var lastSeekAtMs = 0L
    private var lastStatusLogAt = 0L
    private var loadedAtMs = 0L
    private var lastSpeed = 1f
    private var attached: TextureView? = null
    private var released = false

    private val listener =
        object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                aspect.value = if (w.isFinite() && w > 0f && videoSize.height > 0) w / videoSize.height else 0f
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name =
                    when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        else -> "ENDED"
                    }
                Log.d(TAG, "state $name ${nowMs() - loadedAtMs}ms after load")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "isPlaying=$isPlaying")
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                Log.d(TAG, "playWhenReady=$playWhenReady reason=$reason")
            }

            override fun onRenderedFirstFrame() {
                Log.d(TAG, "first frame rendered ${nowMs() - loadedAtMs}ms after load")
                renderedFlow.value = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "video playback failed: ${error.errorCodeName} ${error.cause?.message}")
                renderedFlow.value = false
                failedFlow.value = true
            }
        }

    init {
        exo.addListener(listener)
        exo.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    Log.d(TAG, "decoder $decoderName ready in ${initializationDurationMs}ms")
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    Log.d(TAG, "dropped $droppedFrames frames in ${elapsedMs}ms")
                }
            },
        )
    }

    fun attach(view: TextureView) {
        if (released) return
        attached = view
        exo.setVideoTextureView(view)
    }

    fun detach(view: TextureView) {
        if (released || attached !== view) return
        attached = null
        exo.clearVideoTextureView(view)
        renderedFlow.value = false
    }

    /** Called when the surface was taken away and a new one handed back: repaint at the current frame. */
    fun repaint() {
        if (released || exo.playbackState == Player.STATE_IDLE) return
        renderedFlow.value = false
        exo.seekTo(exo.currentPosition)
    }

    fun markSurfaceLost() {
        renderedFlow.value = false
    }

    fun markRendered() {
        renderedFlow.value = true
    }

    fun load(
        stream: VideoStream,
        startMs: Long,
    ) {
        if (released) return
        aspect.value = 0f
        renderedFlow.value = false
        failedFlow.value = false
        headers = stream.headers
        Log.d(TAG, "loading ${stream.width}x${stream.height} ${stream.codec} at ${startMs}ms, ua=${stream.headers["User-Agent"]?.take(40)}")
        exo.setMediaSource(
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(stream.url)),
            startMs.coerceAtLeast(0L),
        )
        exo.playbackParameters = PlaybackParameters.DEFAULT
        lastSpeed = 1f
        exo.prepare()
        lastSeekAtMs = nowMs()
        loadedAtMs = lastSeekAtMs
    }

    fun clear() {
        if (released) return
        exo.stop()
        exo.clearMediaItems()
        aspect.value = 0f
        renderedFlow.value = false
        failedFlow.value = false
    }

    /**
     * Follows the audio clock. Call every [SYNC_INTERVAL_MS] or so.
     *
     * @param targetMs audio position, or a negative value when unknown (then only play state is applied)
     * @param audioPlaying whether audio is audibly progressing
     * @param baseSpeed the audio's playback speed, so a sped-up track doesn't fight the drift loop
     */
    fun sync(
        targetMs: Long,
        audioPlaying: Boolean,
        baseSpeed: Float,
        allowPlay: Boolean,
    ) {
        if (released || exo.playbackState == Player.STATE_IDLE) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pastEnd = duration != null && targetMs >= duration - END_GUARD_MS
        val wantPlay = allowPlay && audioPlaying && targetMs >= 0 && !pastEnd
        if (exo.playWhenReady != wantPlay) exo.playWhenReady = wantPlay
        if (targetMs < 0) return

        val target = if (duration != null) targetMs.coerceIn(0L, duration - 1) else targetMs
        val drift = exo.currentPosition - target // positive = video ahead of audio
        val magnitude = abs(drift)
        val now = nowMs()
        val settling = exo.playbackState == Player.STATE_BUFFERING || now - lastSeekAtMs < SEEK_COOLDOWN_MS
        if (now - lastStatusLogAt >= STATUS_LOG_MS) {
            lastStatusLogAt = now
            Log.d(
                TAG,
                "sync pos=${exo.currentPosition} target=$target drift=$drift state=${exo.playbackState} " +
                    "playWhenReady=${exo.playWhenReady} isPlaying=${exo.isPlaying} wantPlay=$wantPlay allowPlay=$allowPlay " +
                    "audioPlaying=$audioPlaying size=${exo.videoSize.width}x${exo.videoSize.height} " +
                    "rendered=${renderedFlow.value} attached=${attached != null}",
            )
        }

        val speed =
            when {
                !wantPlay -> baseSpeed
                magnitude > HARD_RESYNC_MS -> baseSpeed
                magnitude > SOFT_DRIFT_MS ->
                    baseSpeed * (1f - (drift / NUDGE_WINDOW_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE))
                else -> baseSpeed
            }
        if (abs(speed - lastSpeed) > 0.002f) {
            exo.playbackParameters = PlaybackParameters(speed)
            lastSpeed = speed
        }

        val needsSeek =
            if (wantPlay) magnitude > HARD_RESYNC_MS else magnitude > PAUSED_TOLERANCE_MS
        if (needsSeek && !settling) {
            exo.seekTo(target)
            lastSeekAtMs = now
        }
    }

    fun release() {
        if (released) return
        released = true
        exo.removeListener(listener)
        attached?.let { exo.clearVideoTextureView(it) }
        attached = null
        exo.release()
    }

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "GamutVideo"
        const val SYNC_INTERVAL_MS = 200L
        private const val STATUS_LOG_MS = 2_000L
        private const val HARD_RESYNC_MS = 600L
        private const val SOFT_DRIFT_MS = 60L
        private const val PAUSED_TOLERANCE_MS = 120L
        private const val NUDGE_WINDOW_MS = 4_000f
        private const val MAX_NUDGE = 0.06f
        private const val SEEK_COOLDOWN_MS = 1_500L
        private const val END_GUARD_MS = 100L
        private const val MIN_BUFFER_MS = 5_000
        private const val MAX_BUFFER_MS = 20_000
        private const val PLAYBACK_BUFFER_MS = 1_000
        private const val REBUFFER_MS = 2_000
        private const val MAX_BUFFER_BYTES = 48 * 1024 * 1024
    }
}