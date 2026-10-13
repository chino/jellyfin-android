package org.jellyfin.mobile.data.audiobook

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.jellyfin.mobile.utils.Constants

/**
 * Clips and listening history for audiobooks. Kept apart from the main database so its versions
 * don't have to line up with upstream's.
 */
@Database(
    entities = [BookmarkEntity::class, ListeningSessionEntity::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4)],
)
abstract class AudiobookDatabase : RoomDatabase() {
    abstract val audiobookDao: AudiobookDao

    companion object {
        /** Clips replaced single-point bookmarks: each old bookmark becomes the clip leading up to it. */
        @Suppress("MagicNumber")
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val clipMs = Constants.AUDIOBOOK_CLIP_SECONDS * Constants.MILLIS_PER_SECOND
                db.execSQL(
                    "UPDATE bookmark SET end_position_ms = position_ms, " +
                        "position_ms = MAX(0, position_ms - $clipMs) WHERE end_position_ms IS NULL",
                )
            }
        }
    }
}
