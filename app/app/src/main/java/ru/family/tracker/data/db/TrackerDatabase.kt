package ru.family.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single app database. Task 7 adds the SOS outbox as a second table and bumps the version.
 *
 * `exportSchema = false`: this app has no schema migrations to publish yet and the database
 * is a cache/outbox, not the source of truth (points already accepted by the server live there).
 */
@Database(entities = [PointEntity::class], version = 1, exportSchema = false)
abstract class TrackerDatabase : RoomDatabase() {

    abstract fun points(): PointDao

    companion object {
        private const val NAME = "tracker.db"

        fun build(context: Context): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, NAME)
                .build()
    }
}
