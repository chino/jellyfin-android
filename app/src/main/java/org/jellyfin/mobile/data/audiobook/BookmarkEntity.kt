package org.jellyfin.mobile.data.audiobook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A saved position in an audiobook. [uuid] identifies the bookmark across devices so it can be synced later.
 */
@Entity(tableName = "bookmark", indices = [Index("item_id"), Index(value = ["uuid"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
