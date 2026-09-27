package org.jellyfin.mobile.data.audiobook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stretch of listening to an audiobook, from pressing play to pausing or stopping.
 */
@Entity(tableName = "listening_session", indices = [Index("item_id")])
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "start_position_ms") val startPositionMs: Long,
    @ColumnInfo(name = "end_position_ms") val endPositionMs: Long,
)
