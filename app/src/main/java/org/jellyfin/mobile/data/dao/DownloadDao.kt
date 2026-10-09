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
import org.jellyfin.sdk.model.api.BaseItemDto

@Dao
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

    @Query("SELECT * FROM download")
    suspend fun getDownloads(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: DownloadEntity): Int

    /**
     * Replaces only the saved item info, so a download's status can't be overwritten by a stale copy.
     */
    @Query("UPDATE download SET item = :item WHERE id = :id")
    suspend fun updateItem(id: Long, item: BaseItemDto)

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM download_file WHERE download_id = :downloadId")
    suspend fun getFiles(downloadId: Long): List<DownloadFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(entity: DownloadFileEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateFile(entity: DownloadFileEntity): Int
}
