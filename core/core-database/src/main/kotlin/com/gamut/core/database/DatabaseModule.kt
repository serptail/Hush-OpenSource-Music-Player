package com.gamut.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GamutDatabase =
        Room.databaseBuilder(context, GamutDatabase::class.java, "gamut.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides fun trackDao(db: GamutDatabase): TrackDao = db.trackDao()
    @Provides fun resolvedSourceDao(db: GamutDatabase): ResolvedSourceDao = db.resolvedSourceDao()
    @Provides fun playlistDao(db: GamutDatabase): PlaylistDao = db.playlistDao()
    @Provides fun playlistTrackDao(db: GamutDatabase): PlaylistTrackDao = db.playlistTrackDao()
    @Provides fun downloadDao(db: GamutDatabase): DownloadDao = db.downloadDao()
    @Provides fun artworkCacheDao(db: GamutDatabase): ArtworkCacheDao = db.artworkCacheDao()
}