package com.gamut.core.database

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Provides the two new DAOs. Assumes your existing module already provides [GamutDatabase]. */
@Module
@InstallIn(SingletonComponent::class)
object SyncDaoModule {
    @Provides fun pendingSyncDao(db: GamutDatabase): PendingSyncDao = db.pendingSyncDao()
    @Provides fun syncStateDao(db: GamutDatabase): SyncStateDao = db.syncStateDao()
}
