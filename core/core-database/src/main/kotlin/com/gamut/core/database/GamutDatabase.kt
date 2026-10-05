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
        FavoriteEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class GamutDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun resolvedSourceDao(): ResolvedSourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun artworkCacheDao(): ArtworkCacheDao
    abstract fun favoriteDao(): FavoriteDao
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS favorites (" +
                "isrc TEXT NOT NULL, addedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(isrc))",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE playlists ADD COLUMN coverUri TEXT")
        database.execSQL("ALTER TABLE playlists ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
    }
}