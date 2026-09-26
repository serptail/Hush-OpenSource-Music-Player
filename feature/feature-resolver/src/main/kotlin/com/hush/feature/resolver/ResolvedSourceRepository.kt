package com.hush.feature.resolver

import com.hush.core.database.ResolvedSourceDao
import com.hush.core.database.toDomain
import com.hush.core.database.toEntity
import com.hush.core.model.ResolvedSource
import com.hush.core.network.ResolvedSourceDto
import com.hush.core.network.ResolverSyncApi
import com.hush.core.network.BuildConfig
import javax.inject.Inject
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

interface ResolvedSourceRepository {
    suspend fun getLocal(isrc: String): ResolvedSource?
    suspend fun getRemote(isrc: String): ResolvedSource?
    suspend fun saveLocal(source: ResolvedSource)
    suspend fun submitRemote(source: ResolvedSource)
}

class ResolvedSourceRepositoryImpl @Inject constructor(private val dao: ResolvedSourceDao, private val api: ResolverSyncApi) : ResolvedSourceRepository {
    private val remoteEnabled = !BuildConfig.SYNC_BASE_URL.startsWith("http://10.0.2.2")
    override suspend fun getLocal(isrc: String) = dao.get(isrc)?.toDomain()
    override suspend fun getRemote(isrc: String) = if (remoteEnabled) runCatching { api.get(isrc).body()?.toDomain() }.getOrNull() else null
    override suspend fun saveLocal(source: ResolvedSource) = dao.upsert(source.toEntity())
    override suspend fun submitRemote(source: ResolvedSource) { if (remoteEnabled) runCatching { api.save(source.toDto()) } }
}

private fun ResolvedSourceDto.toDomain() = ResolvedSource(isrc, youtubeVideoId, com.hush.core.model.ResolutionMethod.valueOf(resolutionMethod), confidenceScore, verifiedCount)
private fun ResolvedSource.toDto() = ResolvedSourceDto(isrc, youtubeVideoId, resolutionMethod.name, confidenceScore, verifiedCount)

@Module
@InstallIn(SingletonComponent::class)
abstract class ResolverRepositoryModule {
    @Binds abstract fun bindResolvedSourceRepository(impl: ResolvedSourceRepositoryImpl): ResolvedSourceRepository
    @Binds abstract fun bindYoutubeCandidateRepository(impl: YoutubeCandidateRepositoryImpl): YoutubeCandidateRepository
    @Binds abstract fun bindAudioSourceRepository(impl: AudioSourceRepositoryImpl): AudioSourceRepository
}