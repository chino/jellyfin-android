package org.jellyfin.mobile.data.audiobook

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM bookmark WHERE item_id = :itemId ORDER BY position_ms")
    suspend fun getBookmarks(itemId: String): List<BookmarkEntity>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM listening_session WHERE item_id = :itemId ORDER BY started_at DESC LIMIT :limit")
    suspend fun getListeningSessions(itemId: String, limit: Int): List<ListeningSessionEntity>

    @Insert
    suspend fun insertListeningSession(session: ListeningSessionEntity): Long
}
