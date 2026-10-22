package ru.family.tracker.child

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.data.db.SosDao
import ru.family.tracker.data.db.SosEventEntity
import ru.family.tracker.data.db.TrackerDatabase

/** In-memory Room tests for the SOS outbox. */
@RunWith(RobolectricTestRunner::class)
class SosDaoTest {

    private lateinit var database: TrackerDatabase
    private lateinit var dao: SosDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.sos()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun event(lat: Double, createdAtMillis: Long) = SosEventEntity(
        lat = lat,
        lon = 37.0 + lat,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun `insert increments count and assigns ids`() = runTest {
        val first = dao.insert(event(1.0, 100))
        val second = dao.insert(event(2.0, 200))

        assertEquals(2, dao.count())
        assertTrue(first > 0)
        assertTrue(second > first)
        assertEquals(listOf(first, second), dao.pending().map { it.id })
    }

    @Test
    fun `pending events are ordered by creation time`() = runTest {
        dao.insert(event(3.0, 300))
        dao.insert(event(1.0, 100))
        dao.insert(event(2.0, 200))

        val pending = dao.pending()

        assertEquals(listOf(1.0, 2.0, 3.0), pending.map { it.lat })
        assertEquals(listOf(100L, 200L, 300L), pending.map { it.createdAtMillis })
    }

    @Test
    fun `pending respects the limit and defaults to 50`() = runTest {
        repeat(55) { dao.insert(event(it.toDouble(), it.toLong())) }

        assertEquals(50, dao.pending().size)
        assertEquals(listOf(0L, 1L), dao.pending(2).map { it.createdAtMillis })
    }

    @Test
    fun `delete removes only the given ids`() = runTest {
        dao.insert(event(1.0, 100))
        dao.insert(event(2.0, 200))
        val ids = dao.pending().map { it.id }

        dao.delete(listOf(ids.first()))

        assertEquals(1, dao.count())
        assertEquals(listOf(2.0), dao.pending().map { it.lat })
    }

    @Test
    fun `delete with an empty list keeps the queue`() = runTest {
        dao.insert(event(1.0, 100))

        dao.delete(emptyList())

        assertEquals(1, dao.count())
    }
}
