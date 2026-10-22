package ru.family.tracker.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.family.tracker.data.api.PointIn

/**
 * A location sample waiting to be uploaded.
 *
 * Points are written by the tracking service and drained by [ru.family.tracker.child.LocationUploader],
 * so the queue survives process death and offline periods.
 */
@Entity(
    tableName = "points",
    indices = [Index("recorded_at")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    @ColumnInfo(name = "recorded_at") val recordedAtMillis: Long,
)

fun PointEntity.toPointIn(): PointIn = PointIn(
    lat = lat,
    lon = lon,
    accuracy = accuracy,
    speed = speed,
    recordedAtMillis = recordedAtMillis,
)

fun PointIn.toEntity(): PointEntity = PointEntity(
    lat = lat,
    lon = lon,
    accuracy = accuracy,
    speed = speed,
    recordedAtMillis = recordedAtMillis,
)
