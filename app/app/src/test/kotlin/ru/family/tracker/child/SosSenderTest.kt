package ru.family.tracker.child

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.db.SosDao
import ru.family.tracker.data.db.SosEventEntity
import ru.family.tracker.data.db.TrackerDatabase
import java.io.IOException

/** Offline queue policy: what is queued, what is sent, what is dropped or retried. */
@RunWith(RobolectricTestRunner::class)
class SosSenderTest {

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

    private fun api(status: HttpStatusCode, body: String = ""): ApiClient {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return ApiClient({ "https://server.example" }, { "child-token" }, engine)
    }

    private fun failingApi(): ApiClient {
        val engine = MockEngine { throw IOException("offline") }
        return ApiClient({ "https://server.example" }, { "child-token" }, engine)
    }

    @Test
    fun `io failure queues the event and reports not sent`() = runTest {
        val sender = SosSender(dao, failingApi())

        val sent = sender.trySend(55.75, 37.61)

        assertFalse(sent)
        val queued = dao.pending().single()
        assertEquals(55.75, queued.lat, 0.0)
        assertEquals(37.61, queued.lon, 0.0)
    }

    @Test
    fun `pending count follows the outbox`() = runTest {
        val sender = SosSender(dao, api(HttpStatusCode.NoContent))
        assertEquals(0, sender.pendingCount())

        dao.insert(SosEventEntity(lat = 1.0, lon = 2.0, createdAtMillis = 100))
        dao.insert(SosEventEntity(lat = 3.0, lon = 4.0, createdAtMillis = 200))
        assertEquals(2, sender.pendingCount())

        assertTrue(sender.flushSos())
        assertEquals(0, sender.pendingCount())
    }

    @Test
    fun `accepted event leaves the queue empty`() = runTest {
        val sender = SosSender(dao, api(HttpStatusCode.NoContent))

        val sent = sender.trySend(55.75, 37.61)

        assertTrue(sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun `queued event is flushed on the next successful attempt`() = runTest {
        var offline = true
        val engine = MockEngine {
            if (offline) throw IOException("offline")
            respond("", HttpStatusCode.NoContent)
        }
        val sender = SosSender(
            dao,
            ApiClient({ "https://server.example" }, { "child-token" }, engine),
        )

        assertFalse(sender.trySend(1.0, 2.0))
        assertEquals(1, dao.count())

        offline = false
        assertTrue(sender.flushSos())
        assertEquals(0, dao.count())
    }

    @Test
    fun `unprocessable event is dropped instead of wedging the queue`() = runTest {
        val sender = SosSender(dao, api(HttpStatusCode.UnprocessableEntity, """{"error":"bad"}"""))

        val sent = sender.trySend(1.0, 2.0)

        assertTrue("queue is empty after the invalid event is dropped", sent)
        assertEquals(0, dao.count())
    }

    @Test
    fun `transient statuses keep the event and report not sent`() = runTest {
        listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.InternalServerError,
        ).forEach { status ->
            val sender = SosSender(dao, api(status, """{"error":"later"}"""))

            assertFalse("status $status must be retried", sender.trySend(1.0, 2.0))
            assertEquals("status $status must keep the event", 1, dao.count())
            dao.delete(dao.pending().map { it.id })
        }
    }

    @Test
    fun `events are sent oldest first`() = runTest {
        dao.insert(SosEventEntity(lat = 3.0, lon = 0.0, createdAtMillis = 300))
        dao.insert(SosEventEntity(lat = 1.0, lon = 0.0, createdAtMillis = 100))
        dao.insert(SosEventEntity(lat = 2.0, lon = 0.0, createdAtMillis = 200))

        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            val content = request.body
            if (content is OutgoingContent.ByteArrayContent) {
                bodies += content.bytes().decodeToString()
            }
            respond("", HttpStatusCode.NoContent)
        }
        val sender = SosSender(
            dao,
            ApiClient({ "https://server.example" }, { "child-token" }, engine),
        )

        assertTrue(sender.flushSos())

        assertEquals(3, bodies.size)
        assertEquals(listOf(true, true, true), listOf("1.0", "2.0", "3.0").mapIndexed { index, lat ->
            bodies[index].contains("\"lat\":$lat")
        })
        assertEquals(0, dao.count())
    }

    @Test
    fun `concurrent flushes send each event exactly once`() = runTest {
        dao.insert(SosEventEntity(lat = 1.0, lon = 2.0, createdAtMillis = 100))
        var calls = 0
        val engine = MockEngine {
            calls++
            delay(100)
            respond("", HttpStatusCode.NoContent)
        }
        val sender = SosSender(
            dao,
            ApiClient({ "https://server.example" }, { "child-token" }, engine),
        )

        val first = async { sender.flushSos() }
        val second = async { sender.flushSos() }

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, calls)
        assertEquals(0, dao.count())
    }

    @Test
    fun `flush is bounded to one batch and the next run continues`() = runTest {        repeat(SosSender.MAX_BATCH + 1) { index ->
            dao.insert(SosEventEntity(lat = index.toDouble(), lon = 0.0, createdAtMillis = index.toLong()))
        }
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.NoContent)
        }
        val sender = SosSender(
            dao,
            ApiClient({ "https://server.example" }, { "child-token" }, engine),
        )

        assertFalse(sender.flushSos())
        assertEquals(SosSender.MAX_BATCH, calls)
        assertEquals(1, dao.count())

        assertTrue(sender.flushSos())
        assertEquals(0, dao.count())
    }
}
