package ru.family.tracker.child

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.family.tracker.AppContainer
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.KeyValueStore
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.db.PointEntity
import ru.family.tracker.data.db.TrackerDatabase

/** Worker contract: role/token gate, success cleanup and retry propagation. */
@RunWith(RobolectricTestRunner::class)
class LocationBufferWorkerTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private lateinit var app: FamilyTrackerApp
    private lateinit var settings: Settings
    private lateinit var database: TrackerDatabase
    private var networkCalls = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        settings = Settings(InMemoryStore()).apply {
            role = Role.CHILD
            token = "child-token"
        }
        database = Room.inMemoryDatabaseBuilder(app, TrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        app.containerOverride = null
        database.close()
    }

    private fun api(status: HttpStatusCode): ApiClient {
        val engine = MockEngine {
            networkCalls++
            respond(
                content = "",
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return ApiClient({ "https://server.example" }, { "child-token" }, engine)
    }

    private fun installContainer(client: ApiClient) {
        app.containerOverride = AppContainer(app, settings, { database }, apiOverride = client)
    }

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<LocationBufferWorker>(app).build()
        return runBlocking { worker.doWork() }
    }

    private suspend fun queuePoint() {
        database.points().insert(
            PointEntity(lat = 1.0, lon = 2.0, accuracy = 5f, speed = null, recordedAtMillis = 100),
        )
    }

    @Test
    fun `successful upload deletes the batch and returns success`() {
        installContainer(api(HttpStatusCode.NoContent))
        runBlocking { queuePoint() }

        val result = runWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, networkCalls)
        assertEquals(0, runBlocking { database.points().count() })
    }

    @Test
    fun `server failure keeps the points and returns retry`() {
        installContainer(api(HttpStatusCode.InternalServerError))
        runBlocking { queuePoint() }

        val result = runWorker()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(1, networkCalls)
        assertEquals(1, runBlocking { database.points().count() })
    }

    @Test
    fun `parent role succeeds without touching queue or network`() {
        settings.role = Role.PARENT
        installContainer(api(HttpStatusCode.NoContent))
        runBlocking { queuePoint() }

        val result = runWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, networkCalls)
        assertEquals(1, runBlocking { database.points().count() })
    }

    @Test
    fun `child without token succeeds without touching queue or network`() {
        settings.token = null
        installContainer(api(HttpStatusCode.NoContent))
        runBlocking { queuePoint() }

        val result = runWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, networkCalls)
        assertEquals(1, runBlocking { database.points().count() })
    }

    @Test
    fun `empty queue returns success without a request`() {
        installContainer(api(HttpStatusCode.NoContent))

        val result = runWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, networkCalls)
    }

    @Test
    fun `schedulePeriodic enqueues the documented unique work`() {
        installContainer(api(HttpStatusCode.NoContent))

        LocationBufferWorker.schedulePeriodic(app)

        assertEquals(15L, LocationBufferWorker.PERIOD_MINUTES)
        val infos = androidx.work.WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(LocationBufferWorker.UNIQUE_PERIODIC)
            .get()
        assertFalse(infos.isEmpty())
    }
}
