package com.gamut.feature.resolver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.gamut.core.database.DownloadEntity
import com.gamut.core.database.DownloadIndex
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toEntity
import com.gamut.core.model.Track
import com.gamut.core.player.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the UI shows for one track. A track with no entry in [TrackDownloadManager.states] is simply not downloaded. */
sealed interface DownloadState {
    data object Queued : DownloadState
    /** [progress] is 0..1. */
    data class Active(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Overall progress of the current run of downloads (drives the notification). total == 0 means idle. */
/** [waitingForWifi] is true while "Download on Wi-Fi only" is on and the queue is held back by a metered / missing network. */
data class DownloadBatch(val done: Int = 0, val total: Int = 0, val current: String? = null, val waitingForWifi: Boolean = false)

/**
 * What a finished run of downloads came to (emitted once per run, see [TrackDownloadManager.summaries]).
 * [title] is the song when exactly one finished; [label] is the playlist name when the whole run was one playlist.
 */
data class DownloadSummary(val succeeded: Int, val failed: Int, val title: String?, val label: String?)

/**
 * Downloads tracks to the app's private storage, ONE AT A TIME, at the best quality YouTube offers and without
 * re-encoding (the original stream is saved as it is: Opus/WebM ~160 kbps when available, else AAC/m4a).
 *
 * Each track: resolve the video id (same resolver as playback) -> pick the highest-bitrate audio stream -> fetch it in
 * 1 MiB byte ranges into a .part file (resuming after a dropped connection or an expired URL) -> rename -> record it
 * in the `downloads` table. The player then plays it from disk (see LocalAudioIndex).
 */
@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolveTrackSource: ResolveTrackSourceUseCase,
    private val audioSources: AudioSourceRepository,
    private val newPipe: NewPipeYoutubeSource,
    @Named("youtube") private val client: OkHttpClient,
    private val index: DownloadIndex,
    private val trackDao: TrackDao,
    private val storageSettings: StorageSettings,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "downloads")

    /** Where finished (and half-written) downloads live. The Storage screen measures and tidies it. */
    val downloadsDir: File get() = dir

    /** Queued / active / failed tracks (finished ones are read from [index]). */
    private val transient = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** isrc -> state, for every track that is downloaded or in progress. Missing = not downloaded. */
    val states: StateFlow<Map<String, DownloadState>> = combine(transient, index.downloads) { live, saved ->
        buildMap {
            saved.keys.forEach { put(it, DownloadState.Done) }
            putAll(live)
        }
    }.stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    private val batchState = MutableStateFlow(DownloadBatch())
    val batch: StateFlow<DownloadBatch> = batchState

    // One toast per RUN (a single song, or a whole playlist), not per song: results are counted here and handed out
    // by emitSummary() when the last queued track of the run is done.
    private val summaryEvents = MutableSharedFlow<DownloadSummary>(extraBufferCapacity = 4)
    val summaries: SharedFlow<DownloadSummary> = summaryEvents
    private val summaryLock = Any()
    private var okCount = 0
    private var failCount = 0
    private var firstTitle: String? = null
    private var runLabel: String? = null

    private val queue = Channel<Track>(Channel.UNLIMITED)
    /** Tracks cancelled while still waiting in [queue]; skipped when their turn comes. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running: Pair<String, Job>? = null
    /** Tracks that failed in this session, so "Retry failed" has something to re-queue. */
    private val failedTracks = ConcurrentHashMap<String, Track>()

    init {
        appScope.launch(Dispatchers.IO) {
            // Half-written files from a previous run can never be resumed (the queue is not persisted).
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
        appScope.launch {
            for (track in queue) {
                awaitNetworkAllowed(track.isrc)
                if (cancelled.remove(track.isrc)) continue
                val job = launch { process(track) }
                running = track.isrc to job
                job.join()
                running = null
            }
        }
    }

    /** Queues every track that is not already downloaded or in progress. Failed ones are retried. */
    fun download(tracks: List<Track>, label: String? = null) {
        val fresh = tracks.distinctBy { it.isrc }.filter { track ->
            val live = transient.value[track.isrc]
            !index.downloads.value.containsKey(track.isrc) && (live == null || live is DownloadState.Failed)
        }
        if (fresh.isEmpty()) return
        synchronized(summaryLock) {
            // A new run takes the label as is; joining a run in progress keeps it only if both agree.
            runLabel = if (batchState.value.total == 0 || runLabel == label) label else null
        }
        fresh.forEach { failedTracks.remove(it.isrc) }
        transient.update { it + fresh.associate { track -> track.isrc to DownloadState.Queued } }
        batchState.update { it.copy(total = it.total + fresh.size) }
        fresh.forEach { queue.trySend(it) }
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
    }

    /** Stops these tracks if they are queued or downloading. Finished downloads are untouched (see [remove]). */
    fun cancel(isrcs: Collection<String>) {
        val ids = isrcs.toSet()
        val waiting = ids.filter { transient.value[it] is DownloadState.Queued }
        waiting.forEach { cancelled.add(it) }
        transient.update { current -> current.filterKeys { it !in waiting.toSet() } }
        repeat(waiting.size) { finishOne() }
        running?.takeIf { it.first in ids }?.second?.cancel()
    }

    /** Stops everything that is queued or downloading. Finished downloads are untouched. */
    fun cancelAll() {
        cancel(transient.value.filterValues { it is DownloadState.Queued || it is DownloadState.Active }.keys.toList())
    }

    /** Re-queues every track that failed in this session. */
    fun retryFailed() {
        val again = failedTracks.values.toList()
        if (again.isNotEmpty()) download(again)
    }

    /** Forgets failed downloads (they disappear from [states]). */
    fun clearFailed() {
        failedTracks.clear()
        transient.update { current -> current.filterValues { it !is DownloadState.Failed } }
    }

    /**
     * Deletes the downloaded files (and stops anything in progress for them). Everything else follows from [index]:
     * [states] drops the "Done" entry, so every badge / playlist / library screen updates, and playback stops finding
     * a local file and streams instead.
     */
    suspend fun remove(isrcs: Collection<String>) = withContext(Dispatchers.IO) {
        isrcs.forEach { failedTracks.remove(it) }
        cancel(isrcs)
        isrcs.forEach { id -> index.remove(id)?.let { File(it).delete() } }
        val ids = isrcs.toSet()
        transient.update { current -> current.filterKeys { it !in ids } }
    }

    // ---- one track ----

    private suspend fun process(track: Track) {
        val id = track.isrc
        batchState.update { it.copy(current = track.title) }
        setState(id, DownloadState.Active(0f))
        try {
            val file = fetch(track)
            saveMetadata(track)   // before the index row, so the Storage screen can show title / artist at once
            index.save(DownloadEntity(id, file.absolutePath, System.currentTimeMillis(), file.length()))
            setState(id, DownloadState.Done)
            recordResult(track.title, ok = true)
        } catch (e: CancellationException) {
            transient.update { it - id }
            throw e
        } catch (e: Exception) {
            failedTracks[id] = track
            setState(id, DownloadState.Failed(e.message ?: "Download failed"))
            recordResult(track.title, ok = false)
        } finally {
            finishOne()
        }
    }

    private suspend fun fetch(track: Track): File = withContext(Dispatchers.IO) {
        val id = track.isrc
        dir.mkdirs()
        if (dir.usableSpace < MIN_FREE_BYTES) throw IOException("Not enough storage")
        val videoId = resolveTrackSource(track).getOrThrow().youtubeVideoId
        val base = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val part = File(dir, "$base.part").also { it.delete() }

        var url = bestUrl(videoId, refresh = false)
        var stream = streamKey(url)
        var attempts = 0
        var lastEmit = 0L
        while (true) {
            try {
                pump(url, part) { done, all ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= PROGRESS_EVERY_MS || done >= all) {
                        lastEmit = now
                        setState(id, DownloadState.Active(done.toFloat() / all))
                    }
                }
                break
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()   // a cancelled call also surfaces as an IOException
                if (++attempts > MAX_ATTEMPTS) throw e
                delay(500L * attempts)
                // The URL expired or the connection dropped: get a fresh one and carry on from what is already saved,
                // unless it is a different stream (other codec), whose bytes cannot be appended.
                url = bestUrl(videoId, refresh = true)
                if (streamKey(url) != stream) { part.delete(); stream = streamKey(url) }
            }
        }
        if (part.length() < MIN_VALID_BYTES) throw IOException("Download incomplete")
        val target = File(dir, "$base.${extensionOf(url)}")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the file")
        target
    }

    /**
     * "Best": highest-bitrate stream (any codec); falls back to the playback resolver (NewPipe m4a, then yt-dlp).
     * "Standard" (Storage settings): AAC / m4a straight from the playback resolver, smaller files.
     */
    private suspend fun bestUrl(videoId: String, refresh: Boolean): String {
        val best = storageSettings.current.downloadQuality == DownloadQuality.BEST
        if (best) newPipe.bestAudioUrl(videoId).getOrNull()?.let { return it }
        return audioSources.audioUrl(
            videoId, forceRefresh = refresh,
            quality = if (best) StreamQuality.HIGH else StreamQuality.NORMAL,
        ).getOrThrow()
    }

    /**
     * Fetches [url] into [part] in byte ranges (googlevideo throttles one long request), appending after what the file
     * already holds. [onProgress] gets (bytes so far, total bytes) once the total is known.
     */
    private suspend fun pump(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        var offset = part.length()
        var total = -1L
        FileOutputStream(part, true).use { out ->
            val buffer = ByteArray(32 * 1024)
            while (total < 0 || offset < total) {
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + CHUNK_BYTES - 1}").build()
                val call = client.newCall(request)
                val hook = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
                try {
                    call.execute().use { response ->
                        if (response.code == 416) { total = offset; return@use }   // asked past the end: already complete
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty response")
                        val ranged = response.code == 206
                        if (!ranged && offset > 0) { out.channel.truncate(0); offset = 0 }   // server ignored Range: start over
                        if (ranged) {
                            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { total = it }
                        } else if (body.contentLength() > 0) {
                            total = body.contentLength()
                        }
                        var chunk = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                offset += n
                                chunk += n
                                if (total > 0) onProgress(offset, total)
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        // No usable total: a short chunk (or a full 200 body) means that was the end.
                        if (total < 0 && (!ranged || chunk < CHUNK_BYTES)) total = offset
                        if (chunk == 0L && total < 0) throw IOException("Empty response")
                    }
                } finally {
                    hook.dispose()
                }
            }
        }
    }

    // ---- helpers ----

    /** Keeps title / artist / duration of what was downloaded, so the Storage screen can list it by name. */
    private suspend fun saveMetadata(track: Track) {
        try { trackDao.upsert(track.toEntity()) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    /** With "Download on Wi-Fi only", the queue waits here (the track stays Queued) until an unmetered network is up. */
    private suspend fun awaitNetworkAllowed(isrc: String) {
        var waited = false
        while (!networkAllowed() && !cancelled.contains(isrc)) {
            if (!waited) { waited = true; batchState.update { it.copy(waitingForWifi = true) } }
            delay(2_000)
        }
        if (waited) batchState.update { it.copy(waitingForWifi = false) }
    }

    private fun networkAllowed(): Boolean {
        if (!storageSettings.current.wifiOnly) return true
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            if (cm.activeNetwork == null) return false
            !cm.isActiveNetworkMetered
        } catch (_: SecurityException) {
            true   // ACCESS_NETWORK_STATE missing: do not block downloads over it
        }
    }

    private fun setState(id: String, state: DownloadState) = transient.update { it + (id to state) }

    /** One track finished (done, failed or cancelled): when the whole run is over the batch resets to idle. */
    private fun finishOne() {
        var finished = false
        batchState.update { b ->
            val done = b.done + 1
            finished = done >= b.total
            if (finished) DownloadBatch() else b.copy(done = done)
        }
        if (finished) emitSummary()
    }

    private fun recordResult(title: String, ok: Boolean) = synchronized(summaryLock) {
        if (ok) { okCount++; if (firstTitle == null) firstTitle = title } else failCount++
    }

    /** The run is over: hand out its result (nothing if everything was cancelled) and start counting afresh. */
    private fun emitSummary() {
        val summary = synchronized(summaryLock) {
            DownloadSummary(okCount, failCount, firstTitle, runLabel).also {
                okCount = 0; failCount = 0; firstTitle = null; runLabel = null
            }
        }
        if (summary.succeeded + summary.failed > 0) summaryEvents.tryEmit(summary)
    }

    private fun streamKey(url: String) = Uri.parse(url).getQueryParameter("itag").orEmpty()

    /** googlevideo URLs carry the real format (mime=audio/webm or audio/mp4); Opus lives in WebM, AAC in MP4. */
    private fun extensionOf(url: String): String {
        val mime = Uri.parse(url).getQueryParameter("mime").orEmpty()
        return if ("webm" in mime) "webm" else "m4a"
    }

    private companion object {
        const val CHUNK_BYTES = 1L shl 20
        const val MAX_ATTEMPTS = 3
        const val PROGRESS_EVERY_MS = 120L
        const val MIN_FREE_BYTES = 100L * 1024 * 1024
        const val MIN_VALID_BYTES = 32L * 1024
    }
}