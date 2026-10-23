package ru.family.tracker.child

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.KeyValueStore
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.db.PointDao
import ru.family.tracker.data.db.PointEntity
import java.io.IOException

/** Upload policy tests: what is deleted, what is kept, what is retried. */
@RunWith(RobolectricTestRunner::class)
class LocationUploaderTest {

    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun settings() = Settings(MemoryStore())

    private class FakePointDao(initial: List<PointEntity>) : PointDao {
        val rows: MutableList<PointEntity> = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

        override suspend fun insert(point: PointEntity) {
            rows += point.copy(id = nextId++)
        }

        override suspend fun pendingPoints(limit: Int): List<PointEntity> =
            rows.sortedBy { it.recordedAtMillis }.take(limit)

        override suspend fun deleteUploaded(ids: List<Long>) {
            rows.removeAll { it.id in ids }
        }

        override suspend fun count(): Int = rows.size
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

    private fun points() = listOf(
        PointEntity(id = 1, lat = 1.0, lon = 2.0, accuracy = 5f, speed = null, recordedAtMillis = 100),
        PointEntity(id = 2, lat = 3.0, lon = 4.0, accuracy = null, speed = 1.5f, recordedAtMillis = 200),
    )

    @Test
    fun `successful upload deletes the uploaded batch`() = runTest {
        val dao = FakePointDao(points())
        val outcome = LocationUploader(dao, api(HttpStatusCode.NoContent), settings()).flushOnce()

        assertEquals(FlushOutcome.Uploaded(2), outcome)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `empty queue does not call the network`() = runTest {
        val dao = FakePointDao(emptyList())
        var called = false
        val engine = MockEngine {
            called = true
            respond("", HttpStatusCode.NoContent)
        }
        val client = ApiClient({ "https://server.example" }, { "child-token" }, engine)

        val outcome = LocationUploader(dao, client, settings()).flushOnce()

        assertEquals(FlushOutcome.NothingToSend, outcome)
        assertTrue(!called)
    }

    @Test
    fun `successful upload records the time and clears the rejection`() = runTest {
        val settings = settings().apply {
            lastUploadAtMillis = 100L
            lastUploadErrorCode = 401
        }

        val outcome = LocationUploader(
            FakePointDao(points()),
            api(HttpStatusCode.NoContent),
            settings,
            now = { 5_000L },
        ).flushOnce()

        assertEquals(FlushOutcome.Uploaded(2), outcome)
        assertEquals(5_000L, settings.lastUploadAtMillis)
        assertNull(settings.lastUploadErrorCode)
    }

    @Test
    fun `unauthorized records the rejection and keeps the previous timestamp`() = runTest {
        val settings = settings().apply { lastUploadAtMillis = 1_234L }

        val outcome = LocationUploader(
            FakePointDao(points()),
            api(HttpStatusCode.Unauthorized, """{"error":"token"}"""),
            settings,
            now = { 5_000L },
        ).flushOnce()

        assertEquals(FlushOutcome.Retry, outcome)
        assertEquals(1_234L, settings.lastUploadAtMillis)
        assertEquals(401, settings.lastUploadErrorCode)
    }

    @Test
    fun `forbidden also records a rejection`() = runTest {
        val settings = settings()

        val outcome = LocationUploader(
            FakePointDao(points()),
            api(HttpStatusCode.Forbidden, """{"error":"forbidden"}"""),
            settings,
            now = { 5_000L },
        ).flushOnce()

        assertEquals(FlushOutcome.Dropped(2), outcome)
        assertNull(settings.lastUploadAtMillis)
        assertEquals(403, settings.lastUploadErrorCode)
    }

    @Test
    fun `io failure keeps the points and asks for a retry`() = runTest {
        val dao = FakePointDao(points())
        val engine = MockEngine { throw IOException("offline") }
        val client = ApiClient({ "https://server.example" }, { "child-token" }, engine)

        val outcome = LocationUploader(dao, client, settings()).flushOnce()

        assertEquals(FlushOutcome.Retry, outcome)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `server failure keeps the points and asks for a retry`() = runTest {
        val dao = FakePointDao(points())
        val outcome = LocationUploader(dao, api(HttpStatusCode.InternalServerError, """{"error":"boom"}"""), settings()).flushOnce()

        assertEquals(FlushOutcome.Retry, outcome)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `unauthorized keeps the points and asks for a retry`() = runTest {
        val dao = FakePointDao(points())
        val outcome = LocationUploader(dao, api(HttpStatusCode.Unauthorized, """{"error":"token"}"""), settings()).flushOnce()

        assertEquals(FlushOutcome.Retry, outcome)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `unprocessable batch is dropped instead of wedging the queue`() = runTest {
        val dao = FakePointDao(points())
        val outcome = LocationUploader(dao, api(HttpStatusCode.UnprocessableEntity, """{"error":"bad point"}"""), settings()).flushOnce()

        assertEquals(FlushOutcome.Dropped(2), outcome)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `bad request and payload too large are also dropped`() = runTest {
        listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.PayloadTooLarge).forEach { status ->
            val dao = FakePointDao(points())
            val outcome = LocationUploader(dao, api(status, """{"error":"no"}"""), settings()).flushOnce()

            assertEquals(FlushOutcome.Dropped(2), outcome)
            assertTrue("status $status must drop the batch", dao.rows.isEmpty())
        }
    }

    @Test
    fun `upload sends at most one batch of the configured size`() = runTest {
        val dao = FakePointDao(
            List(5) {
                PointEntity(
                    id = it.toLong() + 1,
                    lat = it.toDouble(),
                    lon = 0.0,
                    accuracy = null,
                    speed = null,
                    recordedAtMillis = it.toLong(),
                )
            },
        )
        val outcome = LocationUploader(dao, api(HttpStatusCode.NoContent), settings(), batchSize = 3).flushOnce()

        assertEquals(FlushOutcome.Uploaded(3), outcome)
        assertEquals(2, dao.rows.size)
    }
}
