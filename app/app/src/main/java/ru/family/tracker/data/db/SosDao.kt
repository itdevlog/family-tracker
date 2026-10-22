package ru.family.tracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** SOS outbox access. All methods are suspend so callers stay off the main thread. */
@Dao
interface SosDao {

    @Insert
    suspend fun insert(event: SosEventEntity): Long

    @Query("SELECT * FROM sos_events ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<SosEventEntity>

    @Query("DELETE FROM sos_events WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM sos_events")
    suspend fun count(): Int
}
