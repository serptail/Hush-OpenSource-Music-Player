package com.hush.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, ResolvedSourceEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class, DownloadEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class HushDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun resolvedSourceDao(): ResolvedSourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun downloadDao(): DownloadDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val legacyColumn = listOf("spot", "ifyId").joinToString("")
        database.execSQL("ALTER TABLE tracks RENAME COLUMN $legacyColumn TO musicBrainzId")
    }
}