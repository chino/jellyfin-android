package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.UUID

@Dao
@Suppress("TooManyFunctions")
interface DownloadDao {
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Transaction
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    fun getAllDownloadsWithFiles(): Flow<List<DownloadFiles>>

    @Transaction
    @Query("SELECT * FROM download WHERE status = 'QUEUED' OR status = 'DOWNLOADING' ORDER BY created_at ASC")
    fun getQueuedDownloads(): List<DownloadFiles>

    @Query("SELECT * FROM download WHERE item_id IN (:itemIds)")
    fun getDownloadsByItemIds(itemIds: Collection<UUID>): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE item_id = :itemId")
    fun getDownloadByItemId(itemId: UUID): DownloadEntity?

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun getDownload(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: DownloadEntity): Int

    @Query(
        "UPDATE download SET playback_position_ticks = :ticks, last_played_at = :lastPlayedAt, position_synced = 0 " +
            "WHERE item_id = :itemId",
    )
    suspend fun updatePlaybackPosition(itemId: UUID, ticks: Long, lastPlayedAt: Long)

    /**
     * Marks the position saved at [lastPlayedAt] as known to the server, unless a newer one was saved since.
     */
    @Query("UPDATE download SET position_synced = 1 WHERE item_id = :itemId AND last_played_at = :lastPlayedAt")
    suspend fun markPositionSynced(itemId: UUID, lastPlayedAt: Long)

    @Query("SELECT * FROM download WHERE position_synced = 0 AND playback_position_ticks IS NOT NULL")
    suspend fun getUnsyncedPositions(): List<DownloadEntity>

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM download_file WHERE download_id = :downloadId")
    suspend fun getFiles(downloadId: Long): List<DownloadFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(entity: DownloadFileEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateFile(entity: DownloadFileEntity): Int
}
