package ru.family.tracker.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One SOS press waiting to reach the server.
 *
 * An event is inserted before the network call, so an offline press is never lost.
 * Only coordinates are stored — the child's name and token stay out of the database.
 */
@Entity(
    tableName = "sos_events",
    indices = [Index("created_at")],
)
data class SosEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)
