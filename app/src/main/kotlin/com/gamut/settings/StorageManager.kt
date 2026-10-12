package com.gamut.settings

import android.content.Context
import android.os.StatFs
import com.gamut.core.database.DownloadIndex
import com.gamut.core.database.TrackDao
import com.gamut.core.database.toDomain
import com.gamut.core.model.Track
import com.gamut.feature.resolver.AudioSourceRepository
import com.gamut.feature.resolver.StorageSettings
import com.gamut.feature.resolver.TrackDownloadManager
import com.gamut.feature.search.DiscoverDiskCache
import com.gamut.feature.search.DiscoverRepository
import com.gamut.feature.search.SearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the Storage screen shows in numbers. */
data class StorageSnapshot(
    val downloadsBytes: Long,
    val imageCacheBytes: Long,
    val discoverCacheBytes: Long,
    val appDataBytes: Long,
    val leftoverBytes: Long,
    val leftoverCount: Int,
    val deviceFreeBytes: Long,
    val deviceTotalBytes: Long,
) {
    val cacheBytes get() = imageCacheBytes + discoverCacheBytes
    val totalBytes get() = downloadsBytes + cacheBytes + appDataBytes + leftoverBytes
}

enum class CacheTarget { IMAGES, DISCOVER, ALL }

/**
 * Measures and tidies what Gamut keeps on the device. Deleting DOWNLOADS always goes through [TrackDownloadManager]
 * (index row + file + queue state), never straight to disk, so every screen and the player stay in step.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: TrackDownloadManager,
    private val index: DownloadIndex,
    private val trackDao: TrackDao,
    private val discoverDiskCache: DiscoverDiskCache,
    private val discover: DiscoverRepository,
    private val search: SearchRepository,
    private val audioSources: AudioSourceRepository,
    private val settings: StorageSettings,
) {
    // ---- measuring ----

    suspend fun measure(): StorageSnapshot = withContext(Dispatchers.IO) {
        val indexed = indexedPaths()
        val downloadsBytes = index.downloads.value.values.sumOf { File(it.localFilePath).takeIf { f -> f.exists() }?.length() ?: 0L }
        val leftovers = leftovers(indexed)
        val discoverBytes = discoverDiskCache.sizeBytes()
        val dbBytes = context.databaseList().sumOf { context.getDatabasePath(it).length() }
        val filesOutsideDownloads = (sizeOf(context.filesDir) - sizeOf(downloads.downloadsDir)).coerceAtLeast(0L)
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        StorageSnapshot(
            downloadsBytes = downloadsBytes,
            imageCacheBytes = cacheEntries().sumOf { sizeOf(it) },
            discoverCacheBytes = discoverBytes,
            appDataBytes = dbBytes + filesOutsideDownloads + sizeOf(context.noBackupFilesDir),
            leftoverBytes = leftovers.sumOf { it.length() },
            leftoverCount = leftovers.size,
            deviceFreeBytes = stat?.availableBytes ?: 0L,
            deviceTotalBytes = stat?.totalBytes ?: 0L,
        )
    }

    /** Title / artist / duration of downloaded songs (saved by the download manager when each one finished). */
    suspend fun trackInfo(isrcs: Collection<String>): Map<String, Track> = withContext(Dispatchers.IO) {
        try {
            isrcs.chunked(400).flatMap { trackDao.getByIsrcs(it) }.map { it.toDomain() }.associateBy { it.isrc }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- clearing ----

    /** Clears the chosen cache and returns how many bytes that freed. */
    suspend fun clear(target: CacheTarget): Long = withContext(Dispatchers.IO) {
        val before = cacheBytes()
        if (target != CacheTarget.DISCOVER) {
            cacheEntries().forEach { it.deleteRecursively() }
            audioSources.clearCache()
        }
        if (target != CacheTarget.IMAGES) {
            discover.clearCache()
            search.clearCache()
            discoverDiskCache.clear()
        }
        (before - cacheBytes()).coerceAtLeast(0L)
    }

    /** Keeps the image / temporary-file cache under the limit from settings by dropping the oldest files first. */
    suspend fun trimCacheIfNeeded() = withContext(Dispatchers.IO) {
        val limit = settings.current.cacheLimitMb * MB
        if (limit <= 0) return@withContext
        val files = cacheEntries().flatMap { it.walkTopDown().filter { f -> f.isFile && !f.name.startsWith("journal") }.toList() }
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Removes download files no song points to (a crash between "file saved" and "row written") and returns the bytes. */
    suspend fun cleanLeftovers(): Long = withContext(Dispatchers.IO) {
        leftovers(indexedPaths()).sumOf { f -> f.length().also { f.delete() } }
    }

    /**
     * Run whenever the screen opens: forgets downloads whose file is gone (so the app stops showing them as offline)
     * and removes leftovers. Both are no-ops on a healthy install.
     */
    suspend fun repair() = withContext(Dispatchers.IO) {
        val missing = index.downloads.value.values.filter { !File(it.localFilePath).exists() }.map { it.isrc }
        if (missing.isNotEmpty()) downloads.remove(missing)
        cleanLeftovers()
        Unit
    }

    // ---- helpers ----

    private fun indexedPaths() = index.downloads.value.values.map { it.localFilePath }.toSet()

    /** Files in the downloads folder that no song points to. Fresh ones are skipped: they may be mid-download. */
    private fun leftovers(indexed: Set<String>): List<File> {
        val now = System.currentTimeMillis()
        return downloads.downloadsDir.listFiles().orEmpty().filter {
            it.isFile && it.absolutePath !in indexed && now - it.lastModified() > LEFTOVER_MIN_AGE_MS
        }
    }

    /** Everything in cacheDir except Discover's own file (listed separately) and anything in [PROTECTED]. */
    private fun cacheEntries(): List<File> =
        context.cacheDir.listFiles().orEmpty().filter { it.name !in DISCOVER_FILES && it.name !in PROTECTED }

    private suspend fun cacheBytes() = cacheEntries().sumOf { sizeOf(it) } + discoverDiskCache.sizeBytes()

    private fun sizeOf(file: File): Long = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val MB = 1024L * 1024L
        const val LEFTOVER_MIN_AGE_MS = 5 * 60_000L
        val DISCOVER_FILES = setOf("discover.json", "discover.json.tmp")   // DiscoverDiskCache
        /** Folder names inside cacheDir that must survive "Clear cache" (e.g. a media3 SimpleCache directory). */
        val PROTECTED = emptySet<String>()
    }
}
