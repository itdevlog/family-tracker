package ru.family.tracker.parent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.KeyValueStore
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ntfy messages are only wake-up triggers; the listener must dedupe through the
 * persisted `lastSosNotifiedAt` marker and survive API failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosListenerTest {

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

        fun close() {
            channel.close()
        }
    }

    private val settings = Settings(InMemoryStore())
    private val attemptedEvents = CopyOnWriteArrayList<ru.family.tracker.data.api.SosEvent>()
    private val sinkEvents = CopyOnWriteArrayList<ru.family.tracker.data.api.SosEvent>()
    private val sosRequests = AtomicInteger(0)
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    @Volatile
    private var failNextRequests = 0

    @Volatile
    private var sosBody = EVENTS

    @Volatile
    private var sinkDecision: (ru.family.tracker.data.api.SosEvent) -> Boolean = { true }

    private val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/sos" -> {
                requests += request
                sosRequests.incrementAndGet()
                if (failNextRequests > 0) {
                    failNextRequests--
                    respond(
                        content = """{"error":"boom"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = jsonHeaders(),
                    )
                } else {
                    respond(content = sosBody, status = HttpStatusCode.OK, headers = jsonHeaders())
                }
            }

            "/devices" -> respond("[]", HttpStatusCode.OK, jsonHeaders())
            else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders())
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val api = ApiClient({ "https://server.example" }, { "test-token" }, engine)

    private fun listener(
        scope: kotlinx.coroutines.CoroutineScope,
        socket: FakeSocket,
        notificationsEnabled: () -> Boolean = { true },
    ) = SosListener(
        api = api,
        socketFactory = { socket },
        sink = { event ->
            attemptedEvents += event
            val delivered = sinkDecision(event)
            if (delivered) sinkEvents += event
            delivered
        },
        settings = settings,
        scope = scope,
        notificationsEnabled = notificationsEnabled,
    )

    @Test
    fun `message posts only new unacknowledged events and dedupes repeats`() =
        runTest(UnconfinedTestDispatcher()) {
            val socket = FakeSocket()
            val listener = listener(this, socket)
            listener.start()
            runCurrent()

            socket.push()
            settleUntil("first sos") { settings.lastSosNotifiedAt == "2026-10-20T10:10:00Z" }

            assertEquals(1, sinkEvents.size)
            assertEquals("new", sinkEvents.single().eventId)
            assertEquals("2026-10-20T10:10:00Z", settings.lastSosNotifiedAt)
            assertEquals(1, sosRequests.get())
            assertNull(requests.single().url.parameters["since"])

            // The same ntfy message (and a reconnect replay) must not notify twice.
            socket.push()
            settleUntil("second sos request") { sosRequests.get() == 2 }
            runCurrent()

            assertEquals(1, sinkEvents.size)
            assertEquals(2, sosRequests.get())
            assertEquals("2026-10-20T10:10:00Z", settings.lastSosNotifiedAt)
            assertEquals(
                "2026-10-20T10:10:00Z",
                requests.last().url.parameters["since"],
            )

            listener.stop()
        }

    @Test
    fun `api failure does not kill the subscription and the next message retries`() =
        runTest(UnconfinedTestDispatcher()) {
            failNextRequests = 1
            val socket = FakeSocket()
            val listener = listener(this, socket)
            listener.start()
            runCurrent()

            socket.push()
            settleUntil("failed sos request") { sosRequests.get() == 1 }

            assertEquals(0, sinkEvents.size)
            assertNull(settings.lastSosNotifiedAt)

            socket.push()
            settleUntil("retried sos request") {
                settings.lastSosNotifiedAt == "2026-10-20T10:10:00Z"
            }

            assertEquals(1, sinkEvents.size)
            assertEquals("new", sinkEvents.single().eventId)
            assertEquals("2026-10-20T10:10:00Z", settings.lastSosNotifiedAt)

            listener.stop()
        }

    @Test
    fun `disabled notifications do not advance the marker and enable later processing`() =
        runTest(UnconfinedTestDispatcher()) {
            var enabled = false
            val socket = FakeSocket()
            val listener = listener(this, socket, notificationsEnabled = { enabled })
            listener.start()
            runCurrent()

            socket.push()
            runCurrent()

            assertEquals(0, sinkEvents.size)
            assertNull(settings.lastSosNotifiedAt)
            assertEquals(0, sosRequests.get())

            enabled = true
            socket.push()
            settleUntil("sos after notifications enabled") {
                settings.lastSosNotifiedAt == "2026-10-20T10:10:00Z"
            }

            assertEquals(1, sinkEvents.size)
            assertEquals("new", sinkEvents.single().eventId)
            assertEquals("2026-10-20T10:10:00Z", settings.lastSosNotifiedAt)

            listener.stop()
        }

    @Test
    fun `failed notify keeps the marker and retries the same event`() =
        runTest(UnconfinedTestDispatcher()) {
            sinkDecision = { false }
            val socket = FakeSocket()
            val listener = listener(this, socket)
            listener.start()
            runCurrent()

            socket.push()
            settleUntil("first delivery attempt") { attemptedEvents.size == 1 }
            runCurrent()

            assertEquals(1, attemptedEvents.size)
            assertTrue(sinkEvents.isEmpty())
            assertNull(settings.lastSosNotifiedAt)

            sinkDecision = { true }
            socket.push()
            settleUntil("retried delivery") {
                settings.lastSosNotifiedAt == "2026-10-20T10:10:00Z"
            }

            assertEquals(2, attemptedEvents.size)
            assertEquals(1, sinkEvents.size)
            assertEquals("new", sinkEvents.single().eventId)

            listener.stop()
        }

    @Test
    fun `a failed first event stops the batch before the second one`() =
        runTest(UnconfinedTestDispatcher()) {
            sosBody = TWO_FRESH_EVENTS
            sinkDecision = { false }
            val socket = FakeSocket()
            val listener = listener(this, socket)
            listener.start()
            runCurrent()

            socket.push()
            settleUntil("first batch attempt") { attemptedEvents.isNotEmpty() }
            runCurrent()

            assertEquals(listOf("first"), attemptedEvents.map { it.eventId })
            assertNull(settings.lastSosNotifiedAt)

            listener.stop()
        }

    @Test
    fun `a later failure keeps the marker at the last delivered event`() =
        runTest(UnconfinedTestDispatcher()) {
            sosBody = TWO_FRESH_EVENTS
            sinkDecision = { it.eventId == "first" }
            val socket = FakeSocket()
            val listener = listener(this, socket)
            listener.start()
            runCurrent()

            socket.push()
            settleUntil("both batch attempts") { attemptedEvents.size == 2 }
            runCurrent()

            assertEquals(listOf("first", "second"), attemptedEvents.map { it.eventId })
            assertEquals("2026-10-20T10:10:00Z", settings.lastSosNotifiedAt)
            assertEquals(listOf("first"), sinkEvents.map { it.eventId })

            listener.stop()
        }

    @Test
    fun `closed socket reconnects after the backoff`() = runTest(UnconfinedTestDispatcher()) {
        val sockets = mutableListOf<FakeSocket>()
        val listener = SosListener(
            api = api,
            socketFactory = { FakeSocket().also { sockets += it } },
            sink = { event ->
                sinkEvents += event
                true
            },
            settings = settings,
            scope = this,
        )

        listener.start()
        runCurrent()
        assertEquals(1, sockets.size)

        sockets[0].close()
        runCurrent()
        assertEquals(1, sockets.size)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, sockets.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, sockets.size)

        listener.stop()
    }

    @Test
    fun `cancellation from the socket is rethrown instead of reconnecting`() =
        runTest(UnconfinedTestDispatcher()) {
            var created = 0
            val listener = SosListener(
                api = api,
                socketFactory = {
                    created++
                    object : NtfySocket {
                        override fun messages(): Flow<Unit> = flow {
                            throw CancellationException("test cancellation")
                        }
                    }
                },
                sink = { event ->
                sinkEvents += event
                true
            },
                settings = settings,
                scope = this,
            )

            listener.start()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, created)
            assertTrue(sinkEvents.isEmpty())
        }

    @Test
    fun `stop is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeSocket()
        val listener = listener(this, socket)
        listener.start()
        listener.stop()
        listener.stop()
        runCurrent()
    }

    /**
     * Ktor's MockEngine dispatches on real threads, so virtual-time `runCurrent` alone
     * is not enough. Blocking (instead of suspending) keeps runTest from auto-advancing
     * the reconnect backoff while real I/O completes.
     */
    private fun TestScope.settleUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for $description")
            }
            Thread.sleep(2)
        }
    }

    private companion object {
        val TWO_FRESH_EVENTS = """
            [
              {
                "event_id": "first",
                "child": "child-1",
                "lat": 55.70,
                "lon": 37.60,
                "created_at": "2026-10-20T10:10:00Z",
                "acknowledged": false
              },
              {
                "event_id": "second",
                "child": "child-1",
                "lat": 55.71,
                "lon": 37.61,
                "created_at": "2026-10-20T10:11:00Z",
                "acknowledged": false
              }
            ]
        """.trimIndent()

        val EVENTS = """
            [
              {
                "event_id": "old",
                "child": "child-1",
                "lat": 55.70,
                "lon": 37.60,
                "created_at": "2026-10-20T10:05:00Z",
                "acknowledged": true
              },
              {
                "event_id": "new",
                "child": "child-1",
                "lat": 55.71,
                "lon": 37.61,
                "created_at": "2026-10-20T10:10:00Z",
                "acknowledged": false
              }
            ]
        """.trimIndent()
    }
}
