package ru.family.tracker.child

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.data.db.PointDao
import ru.family.tracker.data.db.PointEntity
import ru.family.tracker.data.db.SosEventEntity
import ru.family.tracker.data.db.TrackerDatabase

/**
 * Test-only v1 database. Using Room itself (instead of raw SQL) guarantees that
 * `PRAGMA user_version` and the identity hash are exactly what an installed v1 app had.
 */
@Database(entities = [PointEntity::class], version = 1, exportSchema = false)
abstract class V1Database : RoomDatabase() {
    abstract fun points(): PointDao
}

/**
 * Real 1→2 migration check. `exportSchema = false`, so instead of `MigrationTestHelper`
 * this test opens a genuine v1 Room database with [TrackerDatabase.MIGRATION_1_2] and
 * verifies that the old point survives and the new table works.
 */
@RunWith(RobolectricTestRunner::class)
class TrackerDatabaseMigrationTest {

    private lateinit var context: Context
    private val databaseName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatabase()
    }

    @After
    fun tearDown() {
        deleteDatabase()
    }

    private fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    private fun createV1DatabaseWithPoint() {
        val v1 = Room.databaseBuilder(context, V1Database::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                v1.points().insert(
                    PointEntity(
                        lat = 55.75,
                        lon = 37.61,
                        accuracy = 5f,
                        speed = null,
                        recordedAtMillis = 1234,
                    ),
                )
            }
        } finally {
            v1.close()
        }
    }

    @Test
    fun `v1 points survive the upgrade and the sos table is created`() {
        createV1DatabaseWithPoint()
        val rawVersion = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { it.version }
        assertEquals(1, rawVersion)

        val room = Room.databaseBuilder(context, TrackerDatabase::class.java, databaseName)
            .addMigrations(TrackerDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val point = runBlocking { room.points().latestPoint() }
            assertNotNull(point)
            assertEquals(55.75, point!!.lat, 0.0)
            assertEquals(37.61, point.lon, 0.0)
            assertEquals(1234L, point.recordedAtMillis)
            assertEquals(1, runBlocking { room.points().count() })

            val id = runBlocking {
                room.sos().insert(SosEventEntity(lat = 1.0, lon = 2.0, createdAtMillis = 500))
            }
            assertTrue(id > 0)
            assertEquals(1, runBlocking { room.sos().count() })
        } finally {
            room.close()
        }
    }
}
