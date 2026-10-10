package com.gamut.core.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
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
import kotlin.math.cos
import kotlin.math.sin
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
 *  - Crossfade (off by default): with it on, a SECOND ExoPlayer prepares the next track a few seconds before the end of
 *    this one, then both play at once while the volumes cross (equal power) and the second becomes the current player.
 *    Without it the first player simply flows into the preloaded next item (gapless), as before.
 *
 * Everything below must be called from the main thread (same as ExoPlayer itself).
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3MusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PlaybackSettings,
) : MusicPlayer {

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

    private val sound = SoundSettings.get(context)

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /**
     * An ExoPlayer with its own audio chain: [mod] (reverb / low-pass / wobble / vinyl / bass / mono / balance), then
     * [engine] (EQ / headroom / crossfeed / width / limiter / dither). Both sit before ExoPlayer's speed stage (see
     * applyMod). Processors keep state, so each player needs its own.
     *
     * There are two players so a crossfade can overlap the end of one track with the start of the next. Only
     * [activeSlot] is "the player": the queue, the state flows and the media session all follow it. [standbySlot] is
     * the idle second one (it holds the next track, prepared and silent, shortly before a crossfade); [outgoingSlot] is
     * the one whose track is fading out.
     */
    private class Slot(val mod: ModAudioProcessor, val engine: SoundEngineProcessor, val exo: ExoPlayer)

    private var activeSlot: Slot? = null
    private var standbySlot: Slot? = null
    private var outgoingSlot: Slot? = null

    // Lazy: ExoPlayer is no longer built on the main thread inside onCreate. It is created by warmUp() once the
    // loading screen is gone, or by the first playback call if that comes first.
    private val active: Slot get() = activeSlot ?: createPrimary()
    private val player: ExoPlayer get() = active.exo

    private fun createPrimary(): Slot {
        val slot = newSlot(handleFocus = true)
        activeSlot = slot
        startSettingsObservers()
        return slot
    }

    private fun allSlots(): List<Slot> = listOfNotNull(activeSlot, standbySlot, outgoingSlot)

    /** [handleFocus]: only the current player may hold audio focus, a second request from this app would pause the first. */
    private fun newSlot(handleFocus: Boolean): Slot {
        val mod = ModAudioProcessor()
        val engine = SoundEngineProcessor()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(mod, engine))
                    .build()
        }
        val exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
                // Downloaded songs (file:) play straight from disk; copying them into the stream cache would only waste space.
                SchemeRoutingDataSource(local = DefaultDataSource.Factory(context).createDataSource(), remote = cacheFactory.createDataSource())
            }))
            // Start quickly, but download in long bursts rather than a constant trickle: fewer radio wake-ups.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 90_000, BUFFER_FOR_PLAYBACK_MS, 3_000)
                    .build(),
            )
            .setAudioAttributes(musicAttributes, handleFocus)
            .setHandleAudioBecomingNoisy(true)   // pause when the headphones are unplugged
            // Without this the CPU and Wi-Fi are allowed to sleep with the screen off, which stalls the stream and the
            // player then pauses / stops by itself on the lock screen. NETWORK also holds the Wi-Fi lock for streams.
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
        return Slot(mod, engine, exo).also { slot ->
            attachPlayerListener(slot)
            applyMod(slot, ModController.state.value.settings)
            applyPlaybackSettings(slot, settings.state.value)
            engine.params = sound.state.value.toParams()
        }
    }

    /**
     * Mod, Playback & Audio settings and the sound engine, applied live to every player that exists. Started once, with
     * the first player. A player built later (the standby) takes the current values in [newSlot].
     */
    private fun startSettingsObservers() {
        // Mod: speed + pitch go through ExoPlayer's playback parameters, the rest into the mod processor.
        scope.launch { ModController.state.collect { st -> allSlots().forEach { applyMod(it, st.settings) } } }
        // Playback & Audio settings: skip silence, unplug behaviour, fade, crossfade, mono / balance.
        scope.launch {
            settings.state.collect { st ->
                allSlots().forEach { applyPlaybackSettings(it, st) }
                onCrossfadeChanged()
            }
        }
        scope.launch {
            sound.state.collect { st ->
                val params = st.toParams()
                allSlots().forEach { it.engine.params = params }
            }
        }
    }

    override fun warmUp() { player.playbackState }

    internal val sessionPlayer: ExoPlayer get() = player

    /**
     * Use THIS (instead of [sessionPlayer]) when building the MediaSession, so the notification /
     * lock-screen / Bluetooth next + previous buttons go through the queue logic below.
     */
    internal val mediaSessionPlayer: Player get() = queueForwardingPlayer(player)

    /** Set by the media service: called with a fresh wrapper when a crossfade hands over to the other ExoPlayer. */
    internal var onSessionPlayerChanged: ((Player) -> Unit)? = null

    private fun queueForwardingPlayer(exo: ExoPlayer): Player = object : ForwardingPlayer(exo) {
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
    }

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
        set(value) { field = value; updateTransitionLock(value); updateAwaiting() }

    // Resolving the next URL (yt-dlp) can take seconds. ExoPlayer's own wake lock is only held while it plays, so with
    // the screen off the CPU slept in that gap and the track never started. Held only while moving between tracks.
    private val transitionLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gamut:transition").apply { setReferenceCounted(false) }
    private fun updateTransitionLock(on: Boolean) {
        runCatching { if (on) transitionLock.acquire(TRANSITION_LOCK_MS) else if (transitionLock.isHeld) transitionLock.release() }
    }

    /** True while moving between tracks: the media service must stay foreground even though ExoPlayer is paused. */
    internal val keepForeground: Boolean get() = transitioning || loadJob?.isActive == true

    private val streamDuration = MutableStateFlow(0L)
    override val streamDurationMs: StateFlow<Long> = streamDuration

    private fun publishStreamDuration() {
        val d = player.duration
        val ok = d != C.TIME_UNSET && d > 0 && player.currentMediaItem?.mediaId == currentTrack?.isrc
        streamDuration.value = if (ok) d else 0L
    }

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

    /**
     * Every ExoPlayer reports here, but only the active one counts: the other (the standby, or the track fading out in a
     * crossfade) must not touch the queue, the state flows or the progress clock.
     */
    private fun attachPlayerListener(slot: Slot) {
        slot.exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (slot !== activeSlot) return
                if (isPlaying) {
                    startPositionUpdates()
                    startCrossfadeWatcher()
                } else {
                    positionJob?.cancel()
                    crossfadeJob?.cancel()
                }
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (slot === activeSlot) updateAwaiting() }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (slot !== activeSlot) return
                updateAwaiting()
                when (playbackState) {
                    Player.STATE_ENDED -> onTrackEnded()
                    // Paused while it was still buffering: leave the Loading state.
                    Player.STATE_READY -> {
                        learnDuration()
                        publishStreamDuration()
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
                if (slot !== activeSlot) return
                publishStreamDuration()
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
                if (slot !== activeSlot) return
                // Network-type failure (expired or IP-bound stream URL, connection switch): re-resolve and resume.
                val failing = player.currentMediaItem?.mediaId?.let { queuedTrack(it) } ?: currentTrack
                if (failing != null && error.errorCode / 1000 == 2 && recover(failing, player.currentPosition)) return
                transitioning = false
                state.value = PlaybackState.Error("Playback could not start")
            }
        })
    }

    private fun applyMod(slot: Slot, s: ModSettings) {
        val exo = slot.exo
        // speed == pitch ratio is the tape / 45-vs-33 RPM sound (nightcore, slowed). Unlinked keeps the key.
        exo.playbackParameters = PlaybackParameters(s.speed, s.pitchRatio)
        // The processor runs BEFORE the speed/pitch stage, so what it produces is then played `speed`x as fast and
        // `pitchRatio`x as high. Pre-compensate times and cutoffs so the panel's numbers are what you hear.
        // The sound engine sits before the speed stage too: tell it, so a band set to 1 kHz is heard at 1 kHz.
        slot.engine.pitchRatio = s.pitchRatio
        slot.mod.apply {
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

    private var fadeMs = 0
    private var fadeJob: Job? = null

    private fun applyPlaybackSettings(slot: Slot, s: PlaybackSettings.State) {
        fadeMs = s.fadeMs
        crossfadeMs = s.crossfadeMs
        slot.exo.setSkipSilenceEnabled(s.skipSilence)
        slot.exo.setHandleAudioBecomingNoisy(s.pauseOnUnplug)
        slot.mod.mono = s.mono
        slot.mod.balance = s.balance
    }

    /** Back to full volume and no fade running. Called wherever a new item starts, so a half-faded pause can't leak. */
    private fun resetVolume() {
        fadeJob?.cancel()
        abortCrossfade()
        disarm()
        player.volume = 1f
    }

    private suspend fun rampVolume(from: Float, to: Float, ms: Int) {
        val steps = (ms / 20).coerceAtLeast(1)
        for (i in 1..steps) {
            player.volume = from + (to - from) * i / steps
            delay(20L)
        }
    }

    /** Pause, fading out first when the fade setting is on. Only a pause of audio that is really playing fades. */
    private fun pausePlayer() {
        fadeJob?.cancel()
        abortCrossfade(keepVolume = true)   // the track that was fading out stops; the new one carries on from where its fade was
        if (fadeMs <= 0 || !player.isPlaying) {
            player.volume = 1f
            player.pause()
            return
        }
        val from = player.volume
        fadeJob = scope.launch {
            rampVolume(from, 0f, fadeMs)
            player.pause()
            player.volume = 1f
        }
    }

    private fun resumePlayer() {
        fadeJob?.cancel()
        if (fadeMs <= 0) {
            player.volume = 1f
            player.play()
            return
        }
        // Resuming in the middle of a fade-out continues from where the volume is, instead of jumping.
        val from = if (player.isPlaying) player.volume else 0f
        player.volume = from
        player.play()
        fadeJob = scope.launch { rampVolume(from, 1f, fadeMs) }
    }

    // ---- Transport ----

    // Nothing else binds to GamutMediaSessionService, so without this the service (and its foreground notification)
    // never starts and Android is free to freeze / kill the app once the screen is off: playback then stops by itself.
    // A controller connection binds the service and keeps it foreground while audio plays.
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    private fun ensureSessionService() {
        if (controllerFuture != null) return
        runCatching {
            val token = androidx.media3.session.SessionToken(context, ComponentName(context, GamutMediaSessionService::class.java))
            val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            // A failed connection must not block every later attempt: the service would then never start.
            future.addListener(
                { if (runCatching { future.get() }.isFailure && controllerFuture === future) controllerFuture = null },
                androidx.core.content.ContextCompat.getMainExecutor(context),
            )
        }
    }

    override fun play(track: Track, sourceUrl: String) {
        ensureSessionService()
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
        pausePlayer()
        updateAwaiting()
    }

    override fun resume() {
        val track = currentTrack ?: return
        if (loadedIsrc == track.isrc) resumePlayer() else loadAtPos(pos)   // not loaded (e.g. failed earlier): retry
    }

    override fun seekTo(positionMs: Long) { if (!transitioning) player.seekTo(positionMs) }   // ExoPlayer still holds the old track meanwhile

    override fun skipNext() {
        val target = nextPos() ?: return
        val track = trackAt(target) ?: return
        // Close to the end of a track with crossfade on, the next one is already waiting, prepared: a short crossfade.
        if (crossfadeActive() && !transitioning && player.isPlaying && target == peekNextPos() &&
            armedIsrc == track.isrc && standbyReady() && beginCrossfade(minOf(crossfadeMs, MANUAL_CROSSFADE_MS))
        ) return
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
            streamDuration.value = 0L
            publishQueue()
            state.value = PlaybackState.Playing(0L, track)
            player.seekToNextMediaItem()
            resetVolume()
            player.play()
            publishStreamDuration()
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
        // Crossfade is on and the next track is already waiting, prepared: start it now (gapless, nothing left to overlap).
        if (crossfadeActive() && standbyReady() && beginCrossfade(0)) return
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
        // A track waiting on the standby player that is no longer the next one (or crossfade was turned off).
        val nextIsrc = peekNextPos()?.let { trackAt(it)?.isrc }
        if (armedIsrc != null && (armedIsrc != nextIsrc || !crossfadeActive())) disarm()
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
        streamDuration.value = 0L
        // Show the new track right away as playing; its audio follows as soon as it is ready.
        transitioning = true
        state.value = PlaybackState.Playing(startPositionMs, track)
        resetVolume()
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
        // One failed resolve (slow yt-dlp, flaky network) used to leave the player paused on the track: in the
        // background that is "playback stopped by itself". Try again, later attempts ignoring any cached URL.
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            val url = try {
                withContext(Dispatchers.Default) { active.resolve(track, forceRefresh || attempt > 0, onProgress) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (url != null) return url
            if (attempt < RESOLVE_ATTEMPTS - 1) delay(800L * (attempt + 1))
        }
        return null
    }

    /** [soft] = a queue transition: the UI keeps showing the track as playing instead of the loading screen. */
    private fun startMedia(track: Track, url: String, soft: Boolean = false, startPositionMs: Long = 0L) {
        currentTrack = track
        loadedIsrc = track.isrc
        streamDuration.value = 0L
        if (!soft) {
            transitioning = false
            state.value = PlaybackState.Loading
        }
        resetVolume()
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
            // Runs for every kind of wait (the loading screen AND the "playing but still silent" queue transitions)
            // and only reports full once audio is really coming out. Before, soft transitions skipped the loop and
            // the bar jumped to 100% at once.
            while (isActive && (state.value is PlaybackState.Loading || transitioning)) {
                val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                raiseProgress(bufferBase + (1f - bufferBase) * (ahead.toFloat() / BUFFER_FOR_PLAYBACK_MS).coerceIn(0f, 1f))
                delay(100)
            }
            if (isActive) raiseProgress(1f)
        }
    }

    // ---- Crossfade ----

    private var crossfadeMs = 0
    private var armedIsrc: String? = null     // the track the standby player holds: prepared, silent, waiting for its moment
    private var armedPos = -1                 // ... and its place in the play order
    private var armTried: String? = null      // "<current>><next>": arming is tried once per pair, a failure just means a normal gap
    private var armingJob: Job? = null
    private var crossfadeJob: Job? = null     // the watcher that decides when it is time
    private var rampJob: Job? = null          // the volume ramp of a crossfade in progress

    private fun crossfadeActive() = crossfadeMs > 0 && repeat.value != RepeatMode.ONE   // repeat-one loops gaplessly instead
    private fun crossfading() = outgoingSlot != null
    private fun standbyReady() = armedIsrc != null && standbySlot?.exo?.playbackState == Player.STATE_READY

    private fun onCrossfadeChanged() {
        if (!crossfadeActive()) {
            crossfadeJob?.cancel()
            if (armedIsrc != null || armingJob?.isActive == true) disarm()
        } else if (activeSlot?.exo?.isPlaying == true) {
            startCrossfadeWatcher()
        }
    }

    private fun startCrossfadeWatcher() {
        if (!crossfadeActive() || crossfadeJob?.isActive == true) return
        crossfadeJob = scope.launch { while (isActive) delay(watchCrossfade()) }
    }

    /** One look at the clock. Returns how long to sleep before the next one. */
    private fun watchCrossfade(): Long {
        val track = currentTrack
        val exo = player
        if (!crossfadeActive() || track == null || transitioning || crossfading() || !exo.isPlaying ||
            exo.currentMediaItem?.mediaId != track.isrc
        ) return WATCH_IDLE_MS
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return WATCH_IDLE_MS
        val nextPos = peekNextPos() ?: return WATCH_IDLE_MS   // end of the queue (or a fresh shuffle is due): a normal ending
        val speed = exo.playbackParameters.speed.coerceAtLeast(0.1f)
        val remaining = ((duration - exo.currentPosition) / speed).toLong()   // wall-clock ms until this track ends
        val fade = minOf(crossfadeMs.toLong(), (duration / speed).toLong() / 2)  // never more than half the track
        val armAt = fade + ARM_LEAD_MS
        if (remaining > armAt) return (remaining - armAt).coerceIn(250L, 2_000L)
        arm(nextPos, track)
        if (remaining > fade) return (remaining - fade).coerceIn(20L, 250L)
        if (standbyReady()) {
            beginCrossfade(minOf(fade, remaining).toInt())
            return WATCH_IDLE_MS
        }
        // Not ready yet: keep looking until the track is about to end (then onTrackEnded takes whatever is ready).
        return if (remaining > 300L) 100L else WATCH_IDLE_MS
    }

    /** Resolves the next track and prepares it, silent, on the standby player. Honours the user's crossfade choice over battery saver. */
    private fun arm(nextPos: Int, current: Track) {
        val next = trackAt(nextPos) ?: return
        val pair = "${current.isrc}>${next.isrc}"
        if (armedIsrc == next.isrc || armTried == pair) return
        armTried = pair
        armingJob?.cancel()
        armingJob = scope.launch {
            val url = resolveUrl(next, forceRefresh = false) ?: return@launch
            // The queue may have been edited while the URL was being resolved.
            if (currentTrack?.isrc != current.isrc || peekNextPos()?.let { trackAt(it)?.isrc } != next.isrc) return@launch
            val slot = standbySlot ?: newSlot(handleFocus = false).also { standbySlot = it }
            // A gapless next item would switch over without overlapping: take it out.
            val i = player.currentMediaItemIndex
            if (player.mediaItemCount > i + 1) player.removeMediaItem(i + 1)
            slot.exo.volume = 0f
            slot.exo.playWhenReady = false
            slot.exo.setMediaItem(buildMediaItem(next, url))
            slot.exo.prepare()
            armedIsrc = next.isrc
            armedPos = nextPos
        }
    }

    /** Forget the track waiting on the standby player (the queue or the plan changed). */
    private fun disarm() {
        armingJob?.cancel()
        armedIsrc = null
        armedPos = -1
        armTried = null
        standbySlot?.exo?.let { runCatching { it.stop(); it.clearMediaItems() } }
    }

    /**
     * Makes the standby player (holding the next track) the current one and fades the old one out over [fadeMs]; 0 =
     * switch at once. Bookkeeping is the same as for a skip into a preloaded item. False = it could not start.
     */
    private fun beginCrossfade(fadeMs: Int): Boolean {
        val incoming = standbySlot ?: return false
        val outgoing = activeSlot ?: return false
        val isrc = armedIsrc ?: return false
        val target = armedPos
        val track = trackAt(target)?.takeIf { it.isrc == isrc } ?: return false
        if (target != peekNextPos() || incoming.exo.playbackState != Player.STATE_READY) return false

        positionJob?.cancel()
        fadeJob?.cancel()
        rampJob?.cancel()
        pos = target
        currentTrack = track
        loadedIsrc = track.isrc
        streamDuration.value = 0L
        publishQueue()
        armedIsrc = null
        armedPos = -1
        armTried = null
        standbySlot = null
        outgoingSlot?.let { retire(it) }   // a fade that is still running (should not happen) is cut
        outgoingSlot = outgoing
        activeSlot = incoming

        // Audio focus belongs to the current player. Handed over in this order, the two never compete for it.
        outgoing.exo.setAudioAttributes(musicAttributes, false)
        incoming.exo.setAudioAttributes(musicAttributes, true)
        incoming.exo.volume = if (fadeMs > 0) 0f else 1f
        incoming.exo.play()
        state.value = PlaybackState.Playing(0L, track)
        learnDuration()
        publishStreamDuration()
        updateAwaiting()
        onSessionPlayerChanged?.invoke(queueForwardingPlayer(incoming.exo))   // notification / lock screen follow the new player
        prefetchedFor = track.isrc
        schedulePrefetch(PREFETCH_DELAY_MS)

        if (fadeMs <= 0) {
            finishCrossfade()
            return true
        }
        rampJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            while (isActive) {
                val t = ((SystemClock.elapsedRealtime() - started).toFloat() / fadeMs).coerceIn(0f, 1f)
                // Equal power: sin^2 + cos^2 = 1, so the loudness holds steady through the middle of the fade.
                incoming.exo.volume = sin(t * HALF_PI)
                outgoing.exo.volume = cos(t * HALF_PI)
                if (t >= 1f) break
                delay(20L)
            }
            finishCrossfade()
        }
        return true
    }

    private fun finishCrossfade() {
        outgoingSlot?.let { retire(it) }
        outgoingSlot = null
        activeSlot?.exo?.volume = 1f
    }

    /** A track change or a pause in the middle of a crossfade: the old track stops at once. */
    private fun abortCrossfade(keepVolume: Boolean = false) {
        rampJob?.cancel()
        val out = outgoingSlot ?: return
        retire(out)
        outgoingSlot = null
        if (!keepVolume) activeSlot?.exo?.volume = 1f
    }

    /** The player that just faded out becomes the standby, ready to be reused for the next crossfade. */
    private fun retire(slot: Slot) {
        runCatching {
            slot.exo.pause()
            slot.exo.stop()
            slot.exo.clearMediaItems()
            slot.exo.volume = 1f
        }
        if (standbySlot == null) standbySlot = slot else runCatching { slot.exo.release() }
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
        if (crossfadeActive()) return   // crossfade starts the next track itself, on the second player
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
        if (url.startsWith("file:")) return@withContext   // already on disk
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
        val s = settings.state.value
        if (!s.preload) return false
        if (s.preloadUnmeteredOnly && !settings.onUnmeteredNetwork()) return false
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

        const val RESOLVE_ATTEMPTS = 3
        const val TRANSITION_LOCK_MS = 90_000L

        /** The next track is prepared on the standby player this long before the crossfade starts. */
        const val ARM_LEAD_MS = 6_000L

        /** How often the crossfade watcher looks when there is nothing to do. */
        const val WATCH_IDLE_MS = 500L

        /** A skip near the end of a track crossfades for at most this long. */
        const val MANUAL_CROSSFADE_MS = 1_000

        const val HALF_PI = 1.5707964f
    }
}

/** Sends `file:` URIs to a plain file source and everything else (network streams) through the disk cache. */
@UnstableApi
private class SchemeRoutingDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var active: DataSource = remote

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == "file") local else remote
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
    override fun close() = active.close()
}