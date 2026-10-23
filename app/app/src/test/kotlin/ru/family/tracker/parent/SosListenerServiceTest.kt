package ru.family.tracker.parent

import android.app.NotificationManager
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import ru.family.tracker.NtfyConfig
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient

/**
 * End-to-end service test with the real [SosNotifier] and a fake ntfy socket wired
 * through [AppContainer.ntfySocketFactory]: one frame must reach the notification
 * manager with the SOS extras.
 */
@RunWith(RobolectricTestRunner::class)
class SosListenerServiceTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeSocket : NtfySocket {
        val channel = Channel<Unit>(Channel.UNLIMITED)

        override fun messages(): Flow<Unit> = flow {
            for (message in channel) emit(message)
        }

        fun push() {
            channel.trySend(Unit)
        }
    }

    private lateinit var app: FamilyTrackerApp
    private lateinit var settings: Settings
    private lateinit var manager: NotificationManager
    private lateinit var socket: FakeSocket

    private val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/sos" -> respond(EVENTS, HttpStatusCode.OK, jsonHeaders())
            "/devices" -> respond(DEVICES, HttpStatusCode.OK, jsonHeaders())
            else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders())
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        settings = Settings(InMemoryStore()).apply {
            role = Role.PARENT
            token = "parent-token"
            ntfyConfig = NtfyConfig(
                url = "https://ntfy.example",
                topic = "family-topic",
                token = "ntfy-token",
            )
        }
        socket = FakeSocket()
        val container = AppContainer(
            context = app,
            settingsOverride = settings,
            apiOverride = ApiClient(
                { "https://server.example" },
                { "parent-token" },
                engine,
            ),
        ).apply {
            ntfySocketFactory = { socket }
        }
        app.containerOverride = container
        manager = app.getSystemService(NotificationManager::class.java)
        SosAlertBus.clear()
    }

    @After
    fun tearDown() {
        SosAlertBus.clear()
        app.containerOverride = null
    }

    @Test
    fun `foreground service turns one ntfy frame into an sos notification and alert`() {
        val controller = Robolectric.buildService(SosListenerService::class.java).create()
        val service = controller.get()

        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 0))
        val channel = manager.getNotificationChannel(SosListenerService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        socket.push()
        settleUntil("sos notification") {
            shadowOf(manager).getNotification("evt-1".hashCode()) != null
        }

        val notification = shadowOf(manager).getNotification("evt-1".hashCode())
        assertEquals("evt-1", notification.extras.getString(SosAlert.EXTRA_SOS_ID))
        assertEquals("child-1", notification.extras.getString(SosAlert.EXTRA_CHILD_ID))

        settleUntil("sos alert on the bus") { SosAlertBus.alert.value?.eventId == "evt-1" }
        val alert = SosAlertBus.alert.value!!
        assertEquals("child-1", alert.childId)
        assertEquals(55.71, alert.lat, 0.0)
        assertEquals("2026-10-20T10:10:00Z", alert.createdAtIso)
        settleUntil("marker advanced") {
            settings.lastSosNotifiedAt == "2026-10-20T10:10:00Z"
        }

        controller.destroy()
    }

    @Test
    fun `a service without ntfy config stops and is not sticky`() {
        settings.ntfyConfig = null

        val controller = Robolectric.buildService(SosListenerService::class.java).create()
        val service = controller.get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 0))

        controller.destroy()
    }

    private fun settleUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for $description")
            }
            Thread.sleep(5)
        }
    }

    private companion object {
        val EVENTS = """
            [
              {
                "event_id": "evt-1",
                "child": "child-1",
                "lat": 55.71,
                "lon": 37.61,
                "created_at": "2026-10-20T10:10:00Z",
                "acknowledged": false
              }
            ]
        """.trimIndent()

        val DEVICES = """
            [
              {
                "device_id": "child-1",
                "name": "Маша",
                "last_location": null
              }
            ]
        """.trimIndent()
    }
}
