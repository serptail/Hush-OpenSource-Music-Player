package com.hush.core.database

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
    fun provideDatabase(@ApplicationContext context: Context): HushDatabase =
        Room.databaseBuilder(context, HushDatabase::class.java, "hush.db").addMigrations(MIGRATION_1_2).build()

    @Provides fun trackDao(db: HushDatabase): TrackDao = db.trackDao()
    @Provides fun resolvedSourceDao(db: HushDatabase): ResolvedSourceDao = db.resolvedSourceDao()
    @Provides fun playlistDao(db: HushDatabase): PlaylistDao = db.playlistDao()
    @Provides fun playlistTrackDao(db: HushDatabase): PlaylistTrackDao = db.playlistTrackDao()
    @Provides fun downloadDao(db: HushDatabase): DownloadDao = db.downloadDao()
}