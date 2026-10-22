package ru.family.tracker.child

import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.family.tracker.AppContainer
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.KeyValueStore
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.db.TrackerDatabase

/**
 * Robolectric coverage of the foreground service: permissions gate, notification,
 * Room buffering and the moving/idle update interval. Location and WorkManager are
 * replaced through the [AppContainer] seams, so no Play Services or scheduler runs.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingServiceTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeLocationSource : LocationSource {
        var requestCount = 0
        var stopCount = 0
        var lastIntervalMs: Long? = null
        var lastHighAccuracy: Boolean? = null
        private var callback: ((PointIn) -> Unit)? = null

        override fun requestUpdates(
            intervalMs: Long,
            highAccuracy: Boolean,
            onLocation: (PointIn) -> Unit,
        ) {
            requestCount++
            lastIntervalMs = intervalMs
            lastHighAccuracy = highAccuracy
            callback = onLocation
        }

        override fun stop() {
            stopCount++
        }

        fun emit(point: PointIn) {
            callback?.invoke(point)
        }
    }

    private lateinit var app: FamilyTrackerApp
    private lateinit var settings: Settings
    private lateinit var database: TrackerDatabase
    private lateinit var fakeSource: FakeLocationSource
    private var flushCalls = 0

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
        fakeSource = FakeLocationSource()
        val container = AppContainer(app, settings, { database }).apply {
            locationSourceFactory = { fakeSource }
            flushScheduler = { flushCalls++ }
        }
        app.containerOverride = container
    }

    @After
    fun tearDown() {
        app.containerOverride = null
        database.close()
    }

    @Test
    fun `child service enters foreground and starts high accuracy updates`() {
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()

        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(1, fakeSource.requestCount)
        assertEquals(INTERVAL_MOVING_MS, fakeSource.lastIntervalMs)
        assertEquals(true, fakeSource.lastHighAccuracy)

        val manager = app.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel("tracking"))
    }

    @Test
    fun `emitted location is buffered in room and queued for upload`() {
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()

        fakeSource.emit(PointIn(55.75, 37.61, 5f, 0.2f, 1_700_000_000_000))

        await {
            runBlocking { database.points().count() } == 1 && flushCalls == 1
        }
        val stored = runBlocking { database.points().pendingPoints(10) }.single()
        assertEquals(55.75, stored.lat, 0.0)
        assertEquals(37.61, stored.lon, 0.0)
        assertEquals(5f, stored.accuracy)
        assertEquals(0.2f, stored.speed)
        assertEquals(1_700_000_000_000L, stored.recordedAtMillis)

        await { settings.lastPoint != null }
        val lastPoint = settings.lastPoint!!
        assertEquals(55.75, lastPoint.lat, 0.0)
        assertEquals(37.61, lastPoint.lon, 0.0)
        assertEquals(1_700_000_000_000L, lastPoint.recordedAtMillis)

        assertEquals(INTERVAL_IDLE_MS, fakeSource.lastIntervalMs)
        assertEquals(false, fakeSource.lastHighAccuracy)

        controller.destroy()
        assertEquals(1, fakeSource.stopCount)
    }

    @Test
    fun `moving location switches back to the fast interval`() {
        Robolectric.buildService(TrackingService::class.java).create()

        fakeSource.emit(PointIn(1.0, 2.0, speed = 0f, recordedAtMillis = 100))
        await { fakeSource.lastIntervalMs == INTERVAL_IDLE_MS }

        fakeSource.emit(PointIn(1.1, 2.1, speed = 12f, recordedAtMillis = 200))
        await { fakeSource.lastIntervalMs == INTERVAL_MOVING_MS }

        assertEquals(true, fakeSource.lastHighAccuracy)
        assertEquals(INTERVAL_MOVING_MS, fakeSource.lastIntervalMs)
    }

    @Test
    fun `stop cancels the periodic flush and any pending one-shot flush`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        LocationBufferWorker.schedulePeriodic(app)
        LocationBufferWorker.enqueueFlush(app)

        val workManager = WorkManager.getInstance(app)
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(LocationBufferWorker.UNIQUE_PERIODIC).get().single().state,
        )

        TrackingService.stop(app)

        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(LocationBufferWorker.UNIQUE_PERIODIC).get().single().state,
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(LocationBufferWorker.UNIQUE_FLUSH).get().single().state,
        )
    }

    @Test
    fun `parent role never enters foreground`() {
        settings.role = Role.PARENT

        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()

        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, fakeSource.requestCount)
    }

    @Test
    fun `child without token never enters foreground`() {
        settings.token = null

        val controller = Robolectric.buildService(TrackingService::class.java).create()

        assertNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(0, fakeSource.requestCount)
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition was not met within ${timeoutMs}ms")
    }
}
