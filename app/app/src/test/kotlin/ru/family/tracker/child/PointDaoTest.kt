package ru.family.tracker.child

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.data.db.PointDao
import ru.family.tracker.data.db.PointEntity
import ru.family.tracker.data.db.TrackerDatabase
import ru.family.tracker.data.db.toEntity
import ru.family.tracker.data.db.toPointIn

/** In-memory Room tests for the upload queue. */
@RunWith(RobolectricTestRunner::class)
class PointDaoTest {

    private lateinit var database: TrackerDatabase
    private lateinit var dao: PointDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.points()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun point(lat: Double, recordedAtMillis: Long) = PointEntity(
        lat = lat,
        lon = 37.0 + lat,
        accuracy = 5f,
        speed = null,
        recordedAtMillis = recordedAtMillis,
    )

    @Test
    fun `insert increments count and assigns ids`() = runTest {
        dao.insert(point(1.0, 100))
        dao.insert(point(2.0, 200))

        assertEquals(2, dao.count())
        assertEquals(listOf(1L, 2L), dao.pendingPoints(10).map { it.id })
    }

    @Test
    fun `pending points are ordered by recorded time`() = runTest {
        dao.insert(point(3.0, 300))
        dao.insert(point(1.0, 100))
        dao.insert(point(2.0, 200))

        val pending = dao.pendingPoints(10)

        assertEquals(listOf(1.0, 2.0, 3.0), pending.map { it.lat })
        assertEquals(listOf(100L, 200L, 300L), pending.map { it.recordedAtMillis })
    }

    @Test
    fun `pending points respect the limit`() = runTest {
        dao.insert(point(3.0, 300))
        dao.insert(point(1.0, 100))
        dao.insert(point(2.0, 200))

        val pending = dao.pendingPoints(2)

        assertEquals(listOf(1.0, 2.0), pending.map { it.lat })
    }

    @Test
    fun `delete uploaded removes only the given ids`() = runTest {
        dao.insert(point(1.0, 100))
        dao.insert(point(2.0, 200))
        val ids = dao.pendingPoints(10).map { it.id }

        dao.deleteUploaded(listOf(ids.first()))

        assertEquals(1, dao.count())
        assertEquals(listOf(2.0), dao.pendingPoints(10).map { it.lat })
    }

    @Test
    fun `delete with an empty list keeps the queue`() = runTest {
        dao.insert(point(1.0, 100))

        dao.deleteUploaded(emptyList())

        assertEquals(1, dao.count())
    }

    @Test
    fun `entity maps to and from the domain point`() {
        val entity = point(1.0, 100)
        val domain = entity.toPointIn()

        assertEquals(1.0, domain.lat, 0.0)
        assertEquals(38.0, domain.lon, 0.0)
        assertEquals(5f, domain.accuracy)
        assertEquals(null, domain.speed)
        assertEquals(100L, domain.recordedAtMillis)
        assertEquals(entity.copy(id = 0), domain.toEntity())
    }
}
