package com.gamut.onboarding.spotify

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gamut.core.model.Track
import com.gamut.feature.library.LibraryRepository
import com.gamut.feature.resolver.ApplicationScope
import com.gamut.feature.search.SearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportState {
    data object Idle : ImportState

    data object Reading : ImportState

    data class Matching(
        val done: Int,
        val total: Int,
        val current: String?,
    ) : ImportState

    data class Done(
        val playlists: Int,
        val songs: Int,
        val missed: Int,
        val cancelled: Boolean,
    ) : ImportState

    data class Failed(
        val message: String,
    ) : ImportState
}

@Singleton
class SpotifyImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val search: SearchRepository,
        private val library: LibraryRepository,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val mutableState = MutableStateFlow<ImportState>(ImportState.Idle)
        val state: StateFlow<ImportState> = mutableState

        @Volatile private var job: Job? = null

        fun start(uris: List<Uri>) {
            if (uris.isEmpty() || job?.isActive == true) return
            mutableState.value = ImportState.Reading
            job = appScope.launch(Dispatchers.IO) { run(uris) }
        }

        fun cancel() {
            job?.cancel()
        }

        fun acknowledge() {
            mutableState.update { if (it is ImportState.Done || it is ImportState.Failed) ImportState.Idle else it }
        }

        private suspend fun run(uris: List<Uri>) {
            val export =
                try {
                    val files = uris.flatMap { readFiles(it) }
                    withContext(Dispatchers.Default) { SpotifyExportParser.parse(files) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.value = ImportState.Failed("Couldn\u2019t read those files.")
                    return
                }
            if (export.isEmpty) {
                mutableState.value =
                    ImportState.Failed(
                        "No songs found in those files. Pick the Spotify .zip, the Playlist .json files, or Exportify .csv files.",
                    )
                return
            }

            val progress = AtomicInteger()
            val results = ConcurrentHashMap<String, List<Track>>()
            var playlists = 0
            var songs = 0
            var missed = 0
            mutableState.value = ImportState.Matching(0, export.trackCount, null)
            try {
                for (playlist in export.playlists) {
                    val matched = matchAll(playlist.tracks, export.trackCount, progress, results)
                    val found = matched.filterNotNull().distinctBy { it.isrc }
                    missed += matched.count { it == null }
                    if (found.isNotEmpty()) {
                        val id = library.createPlaylist(playlist.name)
                        library.replaceTracks(id, found)
                        playlists++
                        songs += found.size
                    }
                }
                if (export.liked.isNotEmpty()) {
                    val matched = matchAll(export.liked, export.trackCount, progress, results)
                    val now = System.currentTimeMillis()
                    matched.forEachIndexed { index, track ->
                        if (track == null) {
                            missed++
                        } else {
                            library.addFavorite(track, now - index * 1_000L)
                            songs++
                        }
                    }
                }
                mutableState.value = ImportState.Done(playlists, songs, missed, cancelled = false)
            } catch (e: CancellationException) {
                mutableState.value = ImportState.Done(playlists, songs, missed, cancelled = true)
                throw e
            }
        }

        private suspend fun matchAll(
            tracks: List<SpotifyTrack>,
            total: Int,
            progress: AtomicInteger,
            results: ConcurrentHashMap<String, List<Track>>,
        ): List<Track?> =
            coroutineScope {
                val gate = Semaphore(PARALLEL)
                tracks
                    .map { wanted ->
                        async {
                            gate.withPermit {
                                val track = find(wanted, results)
                                val done = progress.incrementAndGet()
                                mutableState.update {
                                    if (it is ImportState.Matching) {
                                        ImportState.Matching(
                                            done,
                                            total,
                                            wanted.title,
                                        )
                                    } else {
                                        it
                                    }
                                }
                                track
                            }
                        }
                    }.awaitAll()
            }

        private suspend fun find(
            wanted: SpotifyTrack,
            results: ConcurrentHashMap<String, List<Track>>,
        ): Track? {
            val query = SpotifyMatcher.query(wanted)
            val candidates =
                results[query.lowercase()] ?: (
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { search.search(query).lastOrNull()?.tracks }.orEmpty()
                ).also { results[query.lowercase()] = it }
            return SpotifyMatcher.best(wanted, candidates.take(CANDIDATES), { it.title }, { it.artist }, { it.durationMs })
        }

        private fun readFiles(uri: Uri): List<ExportFile> {
            val name = displayName(uri)
            val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
            return input.use { stream ->
                if (name.lowercase().endsWith(".zip")) {
                    SpotifyExportParser.unzip(stream)
                } else {
                    listOf(ExportFile(name, readLimited(stream)))
                }
            }
        }

        private fun readLimited(stream: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_FILE_BYTES) {
                val n = stream.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        private fun displayName(uri: Uri): String {
            val fromProvider =
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } catch (_: Exception) {
                    null
                }
            return fromProvider ?: uri.lastPathSegment.orEmpty()
        }

        private companion object {
            const val PARALLEL = 3
            const val CANDIDATES = 15
            const val SEARCH_TIMEOUT_MS = 12_000L
            const val MAX_FILE_BYTES = 64 * 1024 * 1024
        }
    }
