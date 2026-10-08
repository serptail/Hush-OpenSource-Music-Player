package com.gamut.core.database

import com.gamut.core.model.LocalAudioIndex
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Every finished download, kept in memory (isrc -> row) and in step with the `downloads` table, so the player can ask
 * "is this track on disk?" synchronously. Uses the existing `downloads` table: no schema change, no migration.
 */
@Singleton
class DownloadIndex @Inject constructor(database: GamutDatabase) : LocalAudioIndex {
    private val dao = database.downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads: StateFlow<Map<String, DownloadEntity>> = dao.observeAll()
        .map { rows -> rows.associateBy { it.isrc } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun pathFor(isrc: String): String? = downloads.value[isrc]?.localFilePath

    suspend fun save(entity: DownloadEntity) = dao.upsert(entity)

    /** Deletes the row and returns the file path it pointed at (the caller deletes the file). */
    suspend fun remove(isrc: String): String? {
        val path = downloads.value[isrc]?.localFilePath
        dao.delete(isrc)
        return path
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadIndexModule {
    @Binds abstract fun localAudioIndex(impl: DownloadIndex): LocalAudioIndex
}