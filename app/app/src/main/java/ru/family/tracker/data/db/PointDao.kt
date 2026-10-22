package ru.family.tracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Upload queue access. All methods are suspend so callers stay off the main thread. */
@Dao
interface PointDao {

    @Insert
    suspend fun insert(point: PointEntity)

    @Query("SELECT * FROM points ORDER BY recorded_at ASC LIMIT :limit")
    suspend fun pendingPoints(limit: Int): List<PointEntity>

    /** Most recent fix, used as the coordinates of an SOS press. */
    @Query("SELECT * FROM points ORDER BY recorded_at DESC LIMIT 1")
    suspend fun latestPoint(): PointEntity?

    @Query("DELETE FROM points WHERE id IN (:ids)")
    suspend fun deleteUploaded(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM points")
    suspend fun count(): Int
}
