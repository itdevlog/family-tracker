package ru.family.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Single app database. Version 2 adds the SOS outbox as a second table.
 *
 * `exportSchema = false`: this database is a cache/outbox, not the source of truth
 * (points already accepted by the server live there). The 1→2 migration is explicit
 * and additive, so a queued location backlog survives an app update.
 */
@Database(
    entities = [PointEntity::class, SosEventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TrackerDatabase : RoomDatabase() {

    abstract fun points(): PointDao

    abstract fun sos(): SosDao

    companion object {
        private const val NAME = "tracker.db"

        /** Additive migration: existing points are left untouched. */
        internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sos_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lon` REAL NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sos_events_created_at` " +
                        "ON `sos_events` (`created_at`)",
                )
            }
        }

        fun build(context: Context): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
