package org.jellyfin.mobile.data.audiobook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A clip of an audiobook, from [positionMs] to [endPositionMs], with an optional name and note. [uuid] identifies it
 * across devices so it can be synced later.
 */
@Entity(tableName = "bookmark", indices = [Index("item_id"), Index(value = ["uuid"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "note") val note: String?,
    /** A short label for the clip, so it is easier to find. */
    @ColumnInfo(name = "name") val name: String? = null,
    /** Where the clip ends. Only empty for bookmarks made before clips, which the 2 to 3 migration fills in. */
    @ColumnInfo(name = "end_position_ms") val endPositionMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
