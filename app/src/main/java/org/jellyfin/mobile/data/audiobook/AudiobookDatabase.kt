package org.jellyfin.mobile.data.audiobook

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Bookmarks and listening history for audiobooks. Kept apart from the main database so its versions
 * don't have to line up with upstream's.
 */
@Database(
    entities = [BookmarkEntity::class, ListeningSessionEntity::class],
    version = 1,
)
abstract class AudiobookDatabase : RoomDatabase() {
    abstract val audiobookDao: AudiobookDao
}
