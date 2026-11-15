package com.gamut.feature.resolver

import android.content.Context
import android.content.Intent
import com.gamut.core.database.DownloadEntity
import com.gamut.core.database.DownloadIndex
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toDomain
import com.gamut.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MigrationState {
    data object Idle : MigrationState

    data class Running(
        val done: Int,
        val total: Int,
        val current: String?,
        val moving: Boolean,
    ) : MigrationState

    data class Finished(
        val moved: Int,
        val tagged: Int,
        val failed: Int,
        val cancelled: Boolean,
        val moving: Boolean,
    ) : MigrationState
}

@Singleton
class LibraryMigrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val index: DownloadIndex,
        private val trackDao: TrackDao,
        private val storage: DownloadStorage,
        private val tagger: TrackTagger,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val mutableState = MutableStateFlow<MigrationState>(MigrationState.Idle)
        val state: StateFlow<MigrationState> = mutableState

        @Volatile private var job: Job? = null

        fun countToMove(): Int =
            index.downloads.value.values
                .count { !storage.isAtDestination(it.localFilePath) }

        fun countToTag(): Int =
            index.downloads.value.values.count { row ->
                tagger.needsTags(row.isrc, storage.extensionOf(row.localFilePath)) && canTagInPlace(row.localFilePath)
            }

        fun start(moveToDestination: Boolean) {
            if (job?.isActive == true) return
            val rows =
                index.downloads.value.values
                    .filter { if (moveToDestination) true else canTagInPlace(it.localFilePath) }
                    .filter { needsWork(it, moveToDestination) }
                    .sortedBy { it.downloadedAtEpochMs }
            if (rows.isEmpty()) return
            mutableState.value = MigrationState.Running(done = 0, total = rows.size, current = null, moving = moveToDestination)
            runCatching { context.startForegroundService(Intent(context, MigrationService::class.java)) }
            job = appScope.launch(Dispatchers.IO) { run(rows, moveToDestination) }
        }

        fun cancel() {
            job?.cancel()
        }

        fun acknowledge() {
            mutableState.update { if (it is MigrationState.Finished) MigrationState.Idle else it }
        }

        private fun canTagInPlace(location: String) = !storage.isUri(location) || storage.isAtDestination(location)

        private fun needsWork(
            row: DownloadEntity,
            moving: Boolean,
        ): Boolean {
            val location = row.localFilePath
            val needsMove = moving && !storage.isAtDestination(location)
            return needsMove || tagger.needsTags(row.isrc, storage.extensionOf(location))
        }

        private suspend fun run(
            rows: List<DownloadEntity>,
            moving: Boolean,
        ) {
            val done = AtomicInteger()
            val moved = AtomicInteger()
            val tagged = AtomicInteger()
            val failed = AtomicInteger()
            val tracks = loadTracks(rows.map { it.isrc })

            fun finish(cancelled: Boolean) {
                mutableState.value = MigrationState.Finished(moved.get(), tagged.get(), failed.get(), cancelled, moving)
            }

            try {
                coroutineScope {
                    val gate = Semaphore(PARALLEL)
                    rows
                        .map { row ->
                            async {
                                gate.withPermit {
                                    val track = tracks[row.isrc]
                                    mutableState.update {
                                        if (it is MigrationState.Running) {
                                            it.copy(
                                                current = track?.title ?: row.isrc,
                                            )
                                        } else {
                                            it
                                        }
                                    }
                                    try {
                                        migrate(row, track, moving)?.let { outcome ->
                                            if (outcome.moved) moved.incrementAndGet()
                                            if (outcome.tagged) tagged.incrementAndGet()
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        failed.incrementAndGet()
                                    }
                                    val finished = done.incrementAndGet()
                                    mutableState.update { if (it is MigrationState.Running) it.copy(done = finished) else it }
                                }
                            }
                        }.awaitAll()
                }
                finish(cancelled = false)
            } catch (e: CancellationException) {
                finish(cancelled = true)
                throw e
            }
        }

        private class Outcome(
            val moved: Boolean,
            val tagged: Boolean,
        )

        private suspend fun migrate(
            row: DownloadEntity,
            track: Track?,
            moving: Boolean,
        ): Outcome? {
            val source = row.localFilePath
            val extension = storage.extensionOf(source)
            val needsMove = moving && !storage.isAtDestination(source)
            val needsTag = track != null && tagger.needsTags(row.isrc, extension)
            if (!needsMove && !needsTag) return null
            if (storage.exists(source) != true) return null

            val dir = storage.privateDir.also { it.mkdirs() }
            if (dir.usableSpace < MIN_FREE_BYTES) throw IOException("Not enough storage")
            val base = storage.privateBaseFor(row.isrc)
            val staged = File(dir, "$base.mig.part").also { it.delete() }
            var result: TaggedAudio? = null
            try {
                storage.copyOut(source, staged)
                result =
                    if (needsTag && track != null) {
                        val extras = withTimeoutOrNull(EXTRAS_WAIT_MS) { tagger.prepare(track) } ?: PreparedExtras.NONE
                        tagger.apply(track, staged, extension, extras)
                    } else {
                        TaggedAudio(staged, extension)
                    }
                val size = result.file.length()
                val keepPrivate = !moving && !storage.isUri(source)
                val location =
                    storage.store(
                        part = result.file,
                        privateBase = base,
                        displayBase = storage.fileNameFor(track?.artist.orEmpty(), track?.title.orEmpty(), fallback = base),
                        extension = result.extension,
                        forcePrivate = keepPrivate,
                    )
                withContext(NonCancellable) {
                    index.save(DownloadEntity(row.isrc, location, row.downloadedAtEpochMs, size))
                    if (location != source) storage.delete(source)
                }
                return Outcome(moved = needsMove, tagged = result.tagged)
            } finally {
                staged.delete()
                result?.file?.delete()
            }
        }

        private suspend fun loadTracks(isrcs: List<String>): Map<String, Track> =
            try {
                isrcs
                    .chunked(400)
                    .flatMap { trackDao.getByIsrcs(it) }
                    .map { it.toDomain() }
                    .associateBy { it.isrc }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }

        private companion object {
            const val PARALLEL = 3
            const val MIN_FREE_BYTES = 50L * 1024 * 1024
            const val EXTRAS_WAIT_MS = 20_000L
        }
    }
