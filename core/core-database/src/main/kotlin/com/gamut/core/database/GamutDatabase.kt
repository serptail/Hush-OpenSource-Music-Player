package com.gamut.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        ResolvedSourceEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        DownloadEntity::class,
        ArtworkCacheEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class GamutDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun resolvedSourceDao(): ResolvedSourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun artworkCacheDao(): ArtworkCacheDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val legacyColumn = listOf("spot", "ifyId").joinToString("")
        database.execSQL("ALTER TABLE tracks RENAME COLUMN $legacyColumn TO musicBrainzId")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE tracks ADD COLUMN source TEXT NOT NULL DEFAULT 'MUSICBRAINZ'")
        database.execSQL("ALTER TABLE tracks ADD COLUMN youtubeVideoId TEXT")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS artwork_cache (" +
                "releaseId TEXT NOT NULL PRIMARY KEY, artworkUrl TEXT NOT NULL, cachedAtEpochMs INTEGER NOT NULL)",
        )
    }
}