package com.gamut

import com.gamut.core.model.Track
import com.gamut.feature.playerui.LyricsRepository
import com.gamut.feature.resolver.LyricsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepositoryLyricsSource
    @Inject
    constructor() : LyricsSource {
        override suspend fun lrcFor(track: Track): String? = LyricsRepository.lyricsForDownload(track)

        override fun forget(isrc: String) = LyricsRepository.forget(isrc)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadExtrasModule {
    @Binds
    abstract fun lyricsSource(impl: RepositoryLyricsSource): LyricsSource
}
