package com.gamut.core.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.gamut.core.model.PlaybackState
import com.gamut.core.model.QueueState
import com.gamut.core.model.RepeatMode
import com.gamut.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player, built so that skipping is instant and cheap on battery:
 *
 *  - The queue, shuffle order and repeat logic live here. ExoPlayer holds at most TWO items: the current track and,
 *    once it is ready, the next one. Because the next item is already in ExoPlayer's playlist, the end of a track
 *    flows straight into the next (gapless) and a manual "next" is just a seek to an item that is already loaded.
 *  - Everything that is played is written to a disk cache ([CACHE_MAX_BYTES], least-recently-used), and the first
 *    few seconds of the upcoming track are downloaded into it ahead of time ([WARM_BYTES]). So the next track
 *    starts from disk, with no network wait, and replays cost no data.
 *  - Looking ahead (resolving the next track, warming its cache) is deliberately gentle: it waits a few seconds
 *    after a track starts (rapid skipping triggers nothing), runs one step at a time off the main thread, looks one
 *    track ahead (two when charging), and does nothing in battery saver, at low battery or when the phone is hot.
 *  - A stream URL that stops working (expired, or the network changed) is re-resolved and playback resumes where
 *    it stopped, instead of ending in an error.
 *
 * Everything below must be called from the main thread (same as ExoPlayer itself).
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3MusicPlayer @Inject constructor(@ApplicationContext private val context: Context) : MusicPlayer {

    // Created on first use (on ExoPlayer's playback thread), so opening the cache folder never blocks app startup.
    private val cacheFactory: CacheDataSource.Factory by lazy {
        val cache = SimpleCache(
            File(context.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)   // a broken cache must never break playback
    }

    // Reverb / low-pass / wobble / vinyl / bass. Sits before ExoPlayer's speed stage (see applyMod).
    private val modProcessor = ModAudioProcessor()

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(modProcessor))
                .build()
    }

    // Lazy: ExoPlayer is no longer built on the main thread inside onCreate. It is created by warmUp() once the
    // loading screen is gone, or by the first playback call if that comes first.
    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { cacheFactory.createDataSource() }))
        // Start quickly, but download in long bursts rather than a constant trickle: fewer radio wake-ups.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 90_000, BUFFER_FOR_PLAYBACK_MS, 3_000)
                .build(),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)   // pause when the headphones are unplugged
        .build()
        .apply {
            // This is a music player -- some yt-dlp-resolved sources are muxed video+audio
            // (YouTube doesn't always expose an audio-only format for a given video without
            // a PO token). Disabling the video track type means ExoPlayer never allocates a
            // video decoder or renders frames for those, regardless of what format yt-dlp
            // picked -- a guaranteed fix at the player layer, independent of the "-f" selector.
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
        .also(::attachPlayerListener)
    }

    override fun warmUp() { player.playbackState }

    internal val sessionPlayer: ExoPlayer get() = player

    /**
     * Use THIS (instead of [sessionPlayer]) when building the MediaSession, so the notification /
     * lock-screen / Bluetooth next + previous buttons go through the queue logic below.
     */
    internal val mediaSessionPlayer: Player by lazy { object : ForwardingPlayer(player) {
        private val queueCommands = intArrayOf(
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
            queueCommands.forEach { builder.add(it) }
            return builder.build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            command in queueCommands || super.isCommandAvailable(command)

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()
        override fun play() = resume()
        override fun pause() = this@Media3MusicPlayer.pause()
    } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playbackState: StateFlow<PlaybackState> = state

    private val shuffle = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = shuffle

    private val repeat = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = repeat

    private val queueFlow = MutableStateFlow(QueueState())
    override val queue: StateFlow<QueueState> = queueFlow

    // Load progress = real milestones, not a timer: [RESOLVE_START]..[RESOLVE_END] is the resolver reporting its
    // steps, the rest is ExoPlayer's actual buffer filling up to what it needs to start playing.
    private val progress = MutableStateFlow(0f)
    override val loadProgress: StateFlow<Float> = progress
    private var bufferBase = RESOLVE_END
    private var progressJob: Job? = null

    // Queue model: [items] is the queue in the order it was set; [order] is the play order, a
    // permutation of indices into [items] (identity when shuffle is off); [pos] is the current
    // position INSIDE [order] (-1 = nothing current yet).
    private val items = mutableListOf<Track>()
    private var order = mutableListOf<Int>()
    private var pos = -1

    private var currentTrack: Track? = null
    private var loadedIsrc: String? = null   // the track ExoPlayer actually holds as its current item
    private var positionJob: Job? = null
    private var loadJob: Job? = null
    private var resolver: TrackSourceResolver? = null

    // True while moving to a track that ExoPlayer does not hold yet (skip without a preloaded next item, queue tap,
    // recovery): the UI already shows the new track as playing while its audio loads, instead of the loading screen.
    private var transitioning = false
        set(value) { field = value; updateAwaiting() }

    private val awaiting = MutableStateFlow(false)
    override val awaitingAudio: StateFlow<Boolean> = awaiting

    /** "Playing" on screen but silent: moving to a track ExoPlayer doesn't hold yet, loading, or re-buffering. */
    private fun updateAwaiting() {
        awaiting.value = transitioning || state.value is PlaybackState.Loading ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
    }
    private var prefetchJob: Job? = null
    private var prefetchedFor: String? = null
    private var recoveringIsrc: String? = null
    private var recoveries = 0

    private fun attachPlayerListener(exo: ExoPlayer) {
        // Equalizer: the effects live on ExoPlayer's audio session, which can change (e.g. audio sink re-created).
        val equalizer = EqualizerController.get(context)
        equalizer.attach(exo.audioSessionId)
        // Mod: speed + pitch go through ExoPlayer's playback parameters, the rest into the mod processor. Takes `exo`
        // (not `player`): this runs inside player's lazy initializer, and Main.immediate starts collecting right away.
        scope.launch { ModController.state.collect { applyMod(exo, it.settings) } }
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { equalizer.attach(audioSessionId) }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPositionUpdates() else positionJob?.cancel()
                val track = currentTrack ?: return
                // While the next track is loading, ExoPlayer reporting "not playing" must not flash a Paused state.
                if (!isPlaying && (transitioning || state.value is PlaybackState.Loading)) return
                if (isPlaying) {
                    // First time this track plays: start looking ahead (after a pause, see PREFETCH_DELAY_MS).
                    if (prefetchedFor != track.isrc) {
                        prefetchedFor = track.isrc
                        schedulePrefetch(PREFETCH_DELAY_MS)
                    }
                }
                state.value = if (isPlaying) PlaybackState.Playing(player.currentPosition, track)
                else PlaybackState.Paused(player.currentPosition, track)
                if (isPlaying) transitioning = false   // after the real position is published, so the UI re-anchors to it
                updateAwaiting()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateAwaiting()

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateAwaiting()
                when (playbackState) {
                    Player.STATE_ENDED -> onTrackEnded()
                    // Paused while it was still buffering: leave the Loading state.
                    Player.STATE_READY -> {
                        learnDuration()
                        val track = currentTrack
                        if (!player.playWhenReady && track != null) {
                            val loadedThis = loadedIsrc == track.isrc && player.currentMediaItem?.mediaId == track.isrc
                            if (state.value is PlaybackState.Loading || (transitioning && loadedThis)) {
                                transitioning = false
                                state.value = PlaybackState.Paused(player.currentPosition, track)
                            }
                        }
                    }
                }
            }

            /** ExoPlayer moved on by itself (the track ended) into the preloaded next item. */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
                val id = mediaItem?.mediaId ?: return
                if (id != currentTrack?.isrc) {
                    val newPos = positionOfTransition(id) ?: return
                    val track = trackAt(newPos) ?: return
                    pos = newPos
                    currentTrack = track
                    loadedIsrc = track.isrc
                    publishQueue()
                    state.value = if (player.isPlaying) PlaybackState.Playing(player.currentPosition, track)
                    else PlaybackState.Paused(player.currentPosition, track)
                }
                // Keep ExoPlayer's playlist at [current, next]: drop what already played, then look ahead again.
                while (player.currentMediaItemIndex > 0) player.removeMediaItem(0)
                prefetchedFor = id
                schedulePrefetch(PREFETCH_DELAY_MS)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Network-type failure (expired or IP-bound stream URL, connection switch): re-resolve and resume.
                val failing = player.currentMediaItem?.mediaId?.let { queuedTrack(it) } ?: currentTrack
                if (failing != null && error.errorCode / 1000 == 2 && recover(failing, player.currentPosition)) return
                transitioning = false
                state.value = PlaybackState.Error("Playback could not start")
            }
        })
    }

    private fun applyMod(exo: ExoPlayer, s: ModSettings) {
        // speed == pitch ratio is the tape / 45-vs-33 RPM sound (nightcore, slowed). Unlinked keeps the key.
        exo.playbackParameters = PlaybackParameters(s.speed, s.pitchRatio)
        // The processor runs BEFORE the speed/pitch stage, so what it produces is then played `speed`x as fast and
        // `pitchRatio`x as high. Pre-compensate times and cutoffs so the panel's numbers are what you hear.
        modProcessor.apply {
            reverbMix = s.reverbMix
            reverbDecaySec = s.reverbDecaySec * s.speed
            reverbPreDelayMs = s.reverbPreDelayMs * s.speed
            reverbDamping = s.reverbDamping
            lowPassHz = if (s.lowPassHz >= 20_000f) 20_000f else s.lowPassHz / s.pitchRatio
            vinyl = s.vinylNoise
            bassDb = s.bassDb
            wobbleCents = s.wobbleCents
        }
    }

    // ---- Transport ----

    override fun play(track: Track, sourceUrl: String) {
        loadJob?.cancel()
        positionJob?.cancel()   // else it keeps publishing the OLD item's position for the new track until ExoPlayer reports not-playing
        syncQueuePositionTo(track)
        beginLoad(startAt = 0f, bufferFrom = 0f)   // the URL is already resolved: the whole bar is buffering
        startMedia(track, sourceUrl)
    }

    override fun pause() {
        // Paused while the next track is still resolving (drop the load, resume() retries it) or buffering.
        val resolving = loadJob?.isActive == true
        if (resolving) loadJob?.cancel()
        if (resolving || transitioning) {
            transitioning = false
            currentTrack?.let { state.value = PlaybackState.Paused(0L, it) }
        }
        player.pause()
        updateAwaiting()
    }

    override fun resume() {
        val track = currentTrack ?: return
        if (loadedIsrc == track.isrc) player.play() else loadAtPos(pos)   // not loaded (e.g. failed earlier): retry
    }

    override fun seekTo(positionMs: Long) { if (!transitioning) player.seekTo(positionMs) }   // ExoPlayer still holds the old track meanwhile

    override fun skipNext() {
        val target = nextPos() ?: return
        val track = trackAt(target) ?: return
        val preloaded = !transitioning &&
            target == peekNextPos() &&
            player.currentMediaItem?.mediaId == currentTrack?.isrc &&
            attachedNextId() == track.isrc
        if (preloaded) {
            // Already in ExoPlayer's playlist (and likely buffered): switch instantly. Bookkeeping is done right
            // here, so a second quick tap on "next" already builds on the new position.
            pos = target
            currentTrack = track
            loadedIsrc = track.isrc
            publishQueue()
            state.value = PlaybackState.Playing(0L, track)
            player.seekToNextMediaItem()
            player.play()
        } else {
            loadAtPos(target)
        }
    }

    override fun skipPrevious() {
        // Like every music app: past the first seconds, "previous" restarts the track instead.
        if (!transitioning && player.currentPosition > RESTART_THRESHOLD_MS) {
            player.seekTo(0L)
            return
        }
        val previous = prevPos()
        if (previous != null) loadAtPos(previous) else if (!transitioning) player.seekTo(0L)
    }

    // ---- Queue / shuffle / repeat ----

    override fun setQueue(tracks: List<Track>, startIndex: Int) {
        items.clear()
        items.addAll(tracks)
        rebuildOrder(if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex))
        publishQueue()
        queueChanged()
    }

    override fun setShuffle(enabled: Boolean) {
        if (shuffle.value == enabled) return
        shuffle.value = enabled
        rebuildOrder(order.getOrNull(pos) ?: -1)   // keep the current track current
        publishQueue()
        queueChanged()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeat.value = mode
        // ONE is handled natively (gapless loop, no re-resolving). OFF / ALL are handled by the playlist + onTrackEnded.
        player.repeatMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        queueChanged()
    }

    override fun skipTo(index: Int) {
        if (index in order.indices) loadAtPos(index)
    }

    override fun addToQueue(track: Track, playNext: Boolean) {
        items.add(track)
        val at = if (playNext) (pos + 1).coerceAtMost(order.size) else order.size
        order.add(at, items.lastIndex)
        publishQueue()
        queueChanged()
    }

    override fun removeFromQueue(index: Int) {
        if (index !in order.indices || index == pos) return
        val itemIndex = order.removeAt(index)
        items.removeAt(itemIndex)
        for (i in order.indices) if (order[i] > itemIndex) order[i] = order[i] - 1
        if (index < pos) pos--
        publishQueue()
        queueChanged()
    }

    override fun setSourceResolver(resolver: TrackSourceResolver?) { this.resolver = resolver }

    // ---- Queue navigation ----

    private fun trackAt(p: Int): Track? = order.getOrNull(p)?.let { items.getOrNull(it) }

    private fun queuedTrack(isrc: String): Track? = items.firstOrNull { it.isrc == isrc }

    /** Where "next" would go, without changing anything. Null at the end of the queue, or when a fresh shuffle is needed. */
    private fun peekNextPos(): Int? {
        if (order.isEmpty()) return null
        val next = pos + 1
        if (next < order.size) return next
        if (repeat.value != RepeatMode.ALL) return null
        if (shuffle.value && order.size > 1) return null   // the wrap-around deals a fresh order, see nextPos()
        return 0
    }

    private fun nextPos(): Int? {
        peekNextPos()?.let { return it }
        if (order.isEmpty() || repeat.value != RepeatMode.ALL) return null
        // Wrapped around with shuffle on: deal a fresh order, but never start with the track that just played.
        val current = order.getOrNull(pos)
        order = order.shuffled().toMutableList()
        if (order.size > 1 && order[0] == current) order.add(order.removeAt(0))
        return 0
    }

    private fun prevPos(): Int? = when {
        pos > 0 -> pos - 1
        repeat.value == RepeatMode.ALL && order.isNotEmpty() -> order.lastIndex
        else -> null
    }

    private fun positionOfTransition(isrc: String): Int? {
        val expected = peekNextPos()
        if (expected != null && trackAt(expected)?.isrc == isrc) return expected
        return order.indexOfFirst { items[it].isrc == isrc }.takeIf { it >= 0 }
    }

    private fun onTrackEnded() {
        val next = nextPos()
        if (next != null) {
            loadAtPos(next)
        } else {
            // End of the queue: rewind and wait, so the user can hit play to start over.
            player.seekTo(0L)
            player.pause()
            currentTrack?.let { state.value = PlaybackState.Paused(0L, it) }
        }
    }

    /** The queue changed: drop a preloaded next item that is no longer the right one, and look ahead again. */
    private fun queueChanged() {
        val i = player.currentMediaItemIndex
        if (player.mediaItemCount > i + 1) {
            val wanted = peekNextPos()?.let { trackAt(it)?.isrc }
            if (player.getMediaItemAt(i + 1).mediaId != wanted) player.removeMediaItem(i + 1)
        }
        if (currentTrack != null) schedulePrefetch(QUEUE_EDIT_PREFETCH_DELAY_MS)
    }

    private fun attachedNextId(): String? {
        val i = player.currentMediaItemIndex
        return if (player.mediaItemCount > i + 1) player.getMediaItemAt(i + 1).mediaId else null
    }

    // ---- Loading ----

    /** Makes the track at [p] (play-order position) current, resolves its source and plays it. */
    private fun loadAtPos(p: Int, startPositionMs: Long = 0L, forceRefresh: Boolean = false) {
        val track = trackAt(p) ?: return
        loadJob?.cancel()
        positionJob?.cancel()
        beginLoad(startAt = RESOLVE_START, bufferFrom = RESOLVE_END)
        pos = p
        currentTrack = track
        publishQueue()
        // Show the new track right away as playing; its audio follows as soon as it is ready.
        transitioning = true
        state.value = PlaybackState.Playing(startPositionMs, track)
        player.pause()   // don't keep playing the old track while the new source is resolved

        if (resolver == null) {
            transitioning = false
            state.value = PlaybackState.Paused(0L, track)
            return
        }
        loadJob = scope.launch {
            val url = resolveUrl(track, forceRefresh) { done ->
                raiseProgress(RESOLVE_START + (RESOLVE_END - RESOLVE_START) * done.coerceIn(0f, 1f))
            }
            // Couldn't resolve: stay on this track, paused. Play retries it, skip moves on.
            if (url == null) {
                transitioning = false
                state.value = PlaybackState.Paused(0L, track)
            } else {
                raiseProgress(RESOLVE_END)
                startMedia(track, url, soft = true, startPositionMs = startPositionMs)
            }
        }
    }

    /** Resolves off the main thread; null if it could not be resolved. */
    private suspend fun resolveUrl(track: Track, forceRefresh: Boolean, onProgress: (Float) -> Unit = {}): String? {
        val active = resolver ?: return null
        return try {
            withContext(Dispatchers.Default) { active.resolve(track, forceRefresh, onProgress) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** [soft] = a queue transition: the UI keeps showing the track as playing instead of the loading screen. */
    private fun startMedia(track: Track, url: String, soft: Boolean = false, startPositionMs: Long = 0L) {
        currentTrack = track
        loadedIsrc = track.isrc
        if (!soft) {
            transitioning = false
            state.value = PlaybackState.Loading
        }
        player.setMediaItem(buildMediaItem(track, url), startPositionMs)   // also drops any preloaded next item
        player.prepare()
        player.play()
        trackBuffering()
        updateAwaiting()
    }

    /** The stream stopped working: fetch a fresh URL and carry on from where it stopped. False = give up. */
    private fun recover(track: Track, resumeAtMs: Long): Boolean {
        if (resolver == null) return false
        if (recoveringIsrc != track.isrc) { recoveringIsrc = track.isrc; recoveries = 0 }
        if (recoveries >= MAX_RECOVERIES) return false
        recoveries++
        // An error on a preloaded next item surfaces here as well: make sure the queue points at the failing track.
        if (trackAt(pos)?.isrc != track.isrc) {
            val p = order.indexOfFirst { items[it].isrc == track.isrc }
            if (p < 0) return false
            pos = p
        }
        loadAtPos(pos, startPositionMs = resumeAtMs, forceRefresh = true)
        return true
    }

    private fun beginLoad(startAt: Float, bufferFrom: Float) {
        progressJob?.cancel()
        bufferBase = bufferFrom
        progress.value = startAt
    }

    /** Progress only ever moves forward within one load. */
    private fun raiseProgress(value: Float) {
        if (value > progress.value) progress.value = value.coerceAtMost(1f)
    }

    /** While Loading, the bar follows how much audio ExoPlayer has buffered ahead, up to what it needs to start. */
    private fun trackBuffering() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && state.value is PlaybackState.Loading) {
                val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                raiseProgress(bufferBase + (1f - bufferBase) * (ahead.toFloat() / BUFFER_FOR_PLAYBACK_MS).coerceIn(0f, 1f))
                delay(100)
            }
            raiseProgress(1f)
        }
    }

    // ---- Looking ahead ----

    /**
     * After [delayMs] (so rapid skipping starts nothing): resolves the next track, downloads its first seconds into
     * the disk cache and puts it into ExoPlayer's playlist. One step at a time, off the main thread.
     */
    private fun schedulePrefetch(delayMs: Long) {
        prefetchJob?.cancel()
        if (resolver == null || currentTrack == null) return
        prefetchJob = scope.launch {
            delay(delayMs)
            if (!prefetchAllowed()) return@launch
            val upcoming = upcomingTracks(if (isCharging()) 2 else 1)
            upcoming.forEachIndexed { index, track ->
                val url = resolveUrl(track, forceRefresh = false) ?: return@forEachIndexed
                if (index == 0) attachNext(track, url)   // in the playlist first: "next" is instant as soon as possible
                warmCache(url)
            }
        }
    }

    private fun upcomingTracks(count: Int): List<Track> {
        val result = mutableListOf<Track>()
        for (k in 1..count) {
            val at = pos + k
            val itemIndex = when {
                at < order.size -> order[at]
                repeat.value == RepeatMode.ALL && !shuffle.value && order.isNotEmpty() -> order[at % order.size]
                else -> null
            } ?: break
            val track = items.getOrNull(itemIndex) ?: break
            if (track.isrc != currentTrack?.isrc && result.none { it.isrc == track.isrc }) result += track
        }
        return result
    }

    /** Puts [track] after the current item in ExoPlayer's playlist, if it is still the right next track. */
    private fun attachNext(track: Track, url: String) {
        if (transitioning || player.currentMediaItem?.mediaId != currentTrack?.isrc) return
        val wanted = peekNextPos()?.let { trackAt(it) } ?: return
        if (wanted.isrc != track.isrc) return   // the queue changed while this was being resolved
        val i = player.currentMediaItemIndex
        if (player.mediaItemCount > i + 1) {
            if (player.getMediaItemAt(i + 1).mediaId == track.isrc) return
            player.removeMediaItem(i + 1)
        }
        player.addMediaItem(buildMediaItem(track, url))
    }

    /** Downloads the first [WARM_BYTES] of the stream into the disk cache (nothing if it is already there). */
    private suspend fun warmCache(url: String) = withContext(Dispatchers.IO) {
        try {
            val spec = DataSpec.Builder()
                .setUri(url)
                .setKey(cacheKeyFor(url))
                .setPosition(0)
                .setLength(WARM_BYTES)
                .build()
            val writer = CacheWriter(cacheFactory.createDataSource(), spec, null, null)
            val onCancel = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) writer.cancel() }
            try {
                writer.cache()
            } finally {
                onCancel.dispose()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: without the warm-up the track simply streams when it is actually played.
        }
    }

    /** Battery saver, low battery or a hot phone: no looking ahead, the next track just loads when it is needed. */
    private fun prefetchAllowed(): Boolean {
        if (powerManager.isPowerSaveMode) return false
        if (Build.VERSION.SDK_INT >= 29 && powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return false
        val (percent, charging) = batteryState()
        return charging || percent > LOW_BATTERY_PERCENT
    }

    private fun isCharging(): Boolean = batteryState().second

    /** (level in percent, charging). Reads the sticky battery broadcast: no receiver to register or leak. */
    private fun batteryState(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    // ---- Helpers ----

    /** A track from outside the catalog (e.g. a recognized song) can arrive without a length: take it from the stream. */
    private fun learnDuration() {
        val track = currentTrack ?: return
        if (track.durationMs > 0 || player.currentMediaItem?.mediaId != track.isrc) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val fixed = track.copy(durationMs = duration)
        currentTrack = fixed
        val i = items.indexOfFirst { it.isrc == track.isrc }
        if (i >= 0) items[i] = fixed
        publishQueue()
        state.value = when (val s = state.value) {
            is PlaybackState.Playing -> s.copy(track = fixed)
            is PlaybackState.Paused -> s.copy(track = fixed)
            else -> s
        }
    }

    /**
     * Cache key for a stream. A YouTube stream URL changes every time it is resolved, so the URL itself can't be the
     * key; the file id + format + length in it identify the actual bytes. Null (= the URL is the key) if it has none.
     */
    private fun cacheKeyFor(url: String): String? {
        val uri = Uri.parse(url)
        val id = uri.getQueryParameter("id") ?: return null
        val itag = uri.getQueryParameter("itag") ?: return null
        val length = uri.getQueryParameter("clen") ?: return null
        return "yt:$id:$itag:$length"
    }

    private fun buildMediaItem(track: Track, url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMediaId(track.isrc)
        .setCustomCacheKey(cacheKeyFor(url))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.albumArtUrl?.let(Uri::parse))
                .build(),
        )
        .build()

    /** Called by [play]: points the queue at [track], or makes it a one-track queue if it isn't in it. */
    private fun syncQueuePositionTo(track: Track) {
        val itemAtPos = trackAt(pos)
        if (itemAtPos?.isrc == track.isrc) return
        val itemIndex = items.indexOfFirst { it.isrc == track.isrc }
        val newPos = if (itemIndex >= 0) order.indexOf(itemIndex) else -1
        if (newPos >= 0) {
            pos = newPos
        } else {
            items.clear()
            items.add(track)
            rebuildOrder(0)
        }
        publishQueue()
    }

    /** Rebuilds [order] around the item at [currentItem] (an index into [items], or -1 for none). */
    private fun rebuildOrder(currentItem: Int) {
        val indices = items.indices.toMutableList()
        if (shuffle.value) {
            indices.remove(currentItem)
            indices.shuffle()
            if (currentItem >= 0) indices.add(0, currentItem)
            order = indices
            pos = if (currentItem >= 0) 0 else -1
        } else {
            order = indices
            pos = currentItem
        }
    }

    private fun publishQueue() {
        queueFlow.value = QueueState(order.map { items[it] }, pos)
    }

    /** Ticks while playing. Twice a second with the screen on, every two seconds with it off (nobody is watching). */
    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                val track = currentTrack
                // Never publish a position while the sound isn't actually running for THIS track.
                if (track != null && !transitioning && state.value !is PlaybackState.Loading &&
                    player.isPlaying && player.currentMediaItem?.mediaId == track.isrc
                ) {
                    state.value = PlaybackState.Playing(player.currentPosition, track)
                }
                delay(if (powerManager.isInteractive) 500L else 2_000L)
            }
        }
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val RESOLVE_START = 0.05f
        const val RESOLVE_END = 0.7f

        /** Playback starts once this much audio is buffered; the loading bar is measured against it too. */
        const val BUFFER_FOR_PLAYBACK_MS = 1_500

        /** Disk cache of played audio, least recently used goes first. It lives in the cache dir: Android may clear it. */
        const val CACHE_MAX_BYTES = 200L * 1024 * 1024

        /** How much of the next track is downloaded ahead of time: roughly 15 seconds of audio. */
        const val WARM_BYTES = 256L * 1024

        /** After a track starts, wait this long before looking ahead, so skipping through tracks costs nothing. */
        const val PREFETCH_DELAY_MS = 2_000L
        const val QUEUE_EDIT_PREFETCH_DELAY_MS = 500L

        /** Below this (and not charging) nothing is fetched ahead. */
        const val LOW_BATTERY_PERCENT = 15

        /** How often one track may be re-resolved after its stream URL failed, before showing an error. */
        const val MAX_RECOVERIES = 3
    }
}