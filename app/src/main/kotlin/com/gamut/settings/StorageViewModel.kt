package com.gamut.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamut.core.database.DownloadEntity
import com.gamut.core.database.DownloadIndex
import com.gamut.core.model.Track
import com.gamut.feature.resolver.DownloadStorage
import com.gamut.feature.resolver.LibraryMigrator
import com.gamut.feature.resolver.MigrationState
import com.gamut.feature.resolver.StorageSettings
import com.gamut.feature.resolver.TrackDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class DownloadedSong(
    val isrc: String,
    val title: String,
    val artist: String,
    val bytes: Long,
    val addedMs: Long,
    val durationMs: Long,
    val format: String,
    val artUrl: String? = null,
)

data class DownloadStats(
    val count: Int = 0,
    val bytes: Long = 0L,
    val playtimeMs: Long = 0L,
    val averageBytes: Long = 0L,
    val largest: DownloadedSong? = null,
    val newestMs: Long = 0L,
    val opus: Int = 0,
    val aac: Int = 0,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class StorageViewModel
    @Inject
    constructor(
        private val manager: StorageManager,
        val downloads: TrackDownloadManager,
        val settings: StorageSettings,
        private val index: DownloadIndex,
        private val storage: DownloadStorage,
        private val migrator: LibraryMigrator,
    ) : ViewModel() {
        private val refresh = MutableStateFlow(0)
        private val metadata = ConcurrentHashMap<String, Track>()

        val songs: StateFlow<List<DownloadedSong>> =
            index.downloads
                .mapLatest { rows -> buildSongs(rows) }
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val stats: StateFlow<DownloadStats> =
            songs
                .map { computeStats(it) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadStats())

        val snapshot: StateFlow<StorageSnapshot?> =
            combine(
                index.downloads,
                refresh,
                downloads.batch.map { it.total == 0 }.distinctUntilChanged(),
            ) { _, _, _ -> }
                .debounce(150)
                .mapLatest { manager.measure() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val musicFolder: StateFlow<String?> =
            settings.state
                .map { it.musicFolderUri?.let(storage::folderLabel) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.musicFolderUri?.let(storage::folderLabel))

        private val folderOk = MutableStateFlow(true)
        val folderAvailable: StateFlow<Boolean> = folderOk

        init {
            viewModelScope.launch(Dispatchers.IO) {
                delay(5_000)
                manager.trimCacheIfNeeded()
            }
        }

        val migration: StateFlow<MigrationState> = migrator.state

        fun songsToMove(): Int = migrator.countToMove()

        fun songsToTag(): Int = migrator.countToTag()

        fun startMove() = migrator.start(moveToDestination = true)

        fun startTag() = migrator.start(moveToDestination = false)

        fun cancelMigration() = migrator.cancel()

        fun migrationSeen() = migrator.acknowledge()

        fun chooseFolder(tree: Uri): Boolean {
            val ok = storage.adopt(tree)
            checkFolder()
            return ok
        }

        fun useAppStorage() {
            settings.update { it.copy(musicFolderUri = null) }
            checkFolder()
        }

        private fun checkFolder() {
            viewModelScope.launch(Dispatchers.IO) { folderOk.value = storage.folderAvailable() ?: true }
        }

        fun onOpened() {
            checkFolder()
            viewModelScope.launch {
                manager.repair()
                manager.trimCacheIfNeeded()
                refresh.value++
            }
        }

        fun refreshNow() {
            refresh.value++
        }

        fun delete(isrcs: Collection<String>) {
            val ids = isrcs.toList()
            viewModelScope.launch { downloads.remove(ids) }
        }

        fun deleteAll() {
            viewModelScope.launch {
                downloads.cancelAll()
                downloads.clearFailed()
                downloads.remove(
                    index.downloads.value.keys
                        .toList(),
                )
            }
        }

        fun clear(
            target: CacheTarget,
            onDone: (Long) -> Unit,
        ) {
            viewModelScope.launch {
                val freed = manager.clear(target)
                refresh.value++
                onDone(freed)
            }
        }

        fun cleanLeftovers(onDone: (Long) -> Unit) {
            viewModelScope.launch {
                val freed = manager.cleanLeftovers()
                refresh.value++
                onDone(freed)
            }
        }

        private suspend fun buildSongs(rows: Map<String, DownloadEntity>): List<DownloadedSong> {
            val missing = rows.keys.filter { !metadata.containsKey(it) }
            if (missing.isNotEmpty()) metadata.putAll(manager.trackInfo(missing))
            return rows.values.mapNotNull { row ->
                val track = metadata[row.isrc]
                val format = if (storage.extensionOf(row.localFilePath) in OPUS_EXTENSIONS) "Opus" else "AAC"
                val art = storage.coverFile(row.isrc).takeIf { it.isFile }?.let { Uri.fromFile(it).toString() } ?: track?.albumArtUrl
                if (storage.isUri(row.localFilePath)) {
                    return@mapNotNull DownloadedSong(
                        isrc = row.isrc,
                        title = track?.title ?: storage.baseNameOf(row.localFilePath),
                        artist = track?.artist.orEmpty(),
                        bytes = row.fileSizeBytes,
                        addedMs = row.downloadedAtEpochMs,
                        durationMs = track?.durationMs ?: 0L,
                        format = format,
                        artUrl = art,
                    )
                }
                val file = File(row.localFilePath)
                if (!file.exists()) return@mapNotNull null
                DownloadedSong(
                    isrc = row.isrc,
                    title = track?.title ?: file.nameWithoutExtension,
                    artist = track?.artist.orEmpty(),
                    bytes = file.length(),
                    addedMs = file.lastModified(),
                    durationMs = track?.durationMs ?: 0L,
                    format = format,
                    artUrl = art,
                )
            }
        }

        private fun computeStats(list: List<DownloadedSong>): DownloadStats {
            if (list.isEmpty()) return DownloadStats()
            val bytes = list.sumOf { it.bytes }
            val playtime = list.sumOf { if (it.durationMs > 0) it.durationMs else it.bytes * 1_000L / 18_000L }
            return DownloadStats(
                count = list.size,
                bytes = bytes,
                playtimeMs = playtime,
                averageBytes = bytes / list.size,
                largest = list.maxByOrNull { it.bytes },
                newestMs = list.maxOf { it.addedMs },
                opus = list.count { it.format == "Opus" },
                aac = list.count { it.format == "AAC" },
            )
        }
    }

private val OPUS_EXTENSIONS = setOf("webm", "opus")
