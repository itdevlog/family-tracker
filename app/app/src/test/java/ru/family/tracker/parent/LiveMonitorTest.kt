package ru.family.tracker.parent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.api.toWire
import ru.family.tracker.data.api.ws.LiveEvent
import ru.family.tracker.data.api.ws.LiveSocket
import ru.family.tracker.data.api.ws.LiveUnauthorizedException
import ru.family.tracker.data.api.ws.liveUrl
import ru.family.tracker.data.api.ws.parseLiveEvent
import java.time.Instant

/**
 * The live map must never lose the points that arrive while the WebSocket is down.
 * These tests drive a real [ApiClient] over [MockEngine] and a fake [LiveSocket] whose
 * lifetime is fully controlled by the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMonitorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<HttpRequestData>()
    private var historyResponder: (HttpRequestData) -> List<PointIn> = { emptyList() }

    private val engine = MockEngine { request ->
        requests += request
        when (request.url.encodedPath) {
            "/devices" -> respondJson(DEVICES_ONE_CHILD)
            "/locations" -> respondJson(
                json.encodeToString(
                    historyResponder(request).map { it.toWire() },
                ),
            )

            else -> respond(
                content = """{"error":"unexpected"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }

    private val api = ApiClient({ BASE_URL }, { TOKEN }, engine)

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    @Test
    fun `ws death triggers history catch-up and updates the marker`() = runTest(UnconfinedTestDispatcher()) {
        var historyCalls = 0
        historyResponder = {
            historyCalls++
            if (historyCalls == 1) {
                emptyList()
            } else {
                listOf(point(55.72, 37.62, "2026-10-20T10:06:00Z"))
            }
        }

        val sockets = FakeSocketFactory()
        val monitor = LiveMonitor(
            api = api,
            socketFactory = sockets::newSocket,
            scope = this,
            clock = { NOW },
        )

        monitor.start()
        settleUntil("first socket and seed") {
            sockets.sockets.size == 1 && monitor.state.value.connected
        }
        assertTrue(monitor.state.value.connected)

        sockets.current.send(location(55.71, 37.61, "2026-10-20T10:05:00Z"))
        settleUntil("websocket point") {
            monitor.state.value.markers.single().lat == 55.71
        }

        sockets.current.close()
        settleUntil("history catch-up after ws death") {
            historyCalls >= 2 && monitor.state.value.markers.single().lat == 55.72
        }

        val history = requests.last { it.url.encodedPath == "/locations" }
        assertEquals("child-1", history.url.parameters["device"])
        assertEquals("2026-10-20T10:05:00Z", history.url.parameters["from"])
        assertEquals("2026-10-20T10:10:00Z", history.url.parameters["to"])

        val marker = monitor.state.value.markers.single()
        assertEquals("Маша", marker.name)
        assertEquals(55.72, marker.lat, 0.0)
        assertEquals(
            Instant.parse("2026-10-20T10:06:00Z").toEpochMilli(),
            marker.recordedAtMillis,
        )
        assertFalse(monitor.state.value.connected)

        monitor.stop()
    }

    @Test
    fun `reconnect does not duplicate caught-up points and the newest point wins`() = runTest(UnconfinedTestDispatcher()) {
        historyResponder = { request ->
            when (request.url.parameters["from"]) {
                "2026-10-20T10:00:00Z" -> listOf(point(55.71, 37.61, "2026-10-20T10:05:00Z"))
                "2026-10-20T10:05:00Z" -> listOf(point(55.72, 37.62, "2026-10-20T10:06:00Z"))
                else -> emptyList()
            }
        }

        val sockets = FakeSocketFactory()
        val monitor = LiveMonitor(
            api = api,
            socketFactory = sockets::newSocket,
            scope = this,
            clock = { NOW },
        )

        monitor.start()
        settleUntil("seed and initial history") {
            sockets.sockets.size == 1 && monitor.state.value.markers.single().lat == 55.71
        }
        val first = sockets.current

        first.close()
        settleUntil("catch-up on close") {
            monitor.state.value.markers.single().recordedAtMillis ==
                Instant.parse("2026-10-20T10:06:00Z").toEpochMilli()
        }
        advanceTimeBy(2_000)
        runCurrent()
        settleUntil("reconnect") { sockets.sockets.size == 2 }

        sockets.current.send(location(99.0, 99.0, "2026-10-20T10:06:00Z"))
        sockets.current.send(location(55.73, 37.63, "2026-10-20T10:07:00Z"))
        sockets.current.send(location(99.0, 99.0, "2026-10-20T10:07:00Z"))
        settleUntil("newest websocket point") {
            monitor.state.value.markers.single().recordedAtMillis ==
                Instant.parse("2026-10-20T10:07:00Z").toEpochMilli()
        }

        val marker = monitor.state.value.markers.single()
        assertEquals(55.73, marker.lat, 0.0)
        assertEquals(
            Instant.parse("2026-10-20T10:07:00Z").toEpochMilli(),
            marker.recordedAtMillis,
        )

        monitor.stop()
    }

    @Test
    fun `stop cancels reconnects and issues no more requests`() = runTest(UnconfinedTestDispatcher()) {
        val sockets = FakeSocketFactory()
        val monitor = LiveMonitor(
            api = api,
            socketFactory = sockets::newSocket,
            scope = this,
            clock = { NOW },
        )

        monitor.start()
        settleUntil("first socket") { sockets.sockets.size == 1 }
        sockets.current.close()
        settleUntil("catch-up on close") {
            requests.count { it.url.encodedPath == "/locations" } >= 2
        }

        val requestsAtStop = requests.size
        monitor.stop()
        advanceTimeBy(5 * 60_000)
        runCurrent()

        assertEquals(requestsAtStop, requests.size)
        assertEquals(1, sockets.sockets.size)
        assertFalse(monitor.state.value.connected)
    }

    @Test
    fun `unauthorized socket sets the error and stops reconnecting`() = runTest(UnconfinedTestDispatcher()) {
        var socketsCreated = 0
        val monitor = LiveMonitor(
            api = api,
            socketFactory = {
                socketsCreated++
                object : LiveSocket {
                    override fun events(): Flow<LiveEvent> = flow {
                        throw LiveUnauthorizedException()
                    }
                }
            },
            scope = this,
            clock = { NOW },
        )

        monitor.start()
        settleUntil("unauthorized state") {
            monitor.state.value.error == LiveMonitor.ERROR_UNAUTHORIZED
        }
        val requestsAtError = requests.size

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, socketsCreated)
        assertEquals(requestsAtError, requests.size)
        assertFalse(monitor.state.value.connected)
        monitor.stop()
    }

    @Test
    fun `live url converts the scheme and encodes the token`() {
        assertEquals(
            "wss://api.example/live?token=abc%2Bdef",
            liveUrl("https://api.example/", "abc+def"),
        )
        assertEquals(
            "ws://localhost:8080/live?token=local",
            liveUrl("http://localhost:8080", "local"),
        )
    }

    @Test
    fun `parseLiveEvent understands location sos open and ignores unknown or broken frames`() {
        val location = parseLiveEvent(
            """
            {"type":"location","child":"child-1","lat":55.75,"lon":37.61,
             "accuracy":3.0,"speed":null,"recorded_at":"2026-10-20T10:00:00Z","extra":"ignored"}
            """.trimIndent(),
        )
        assertTrue(location is LiveEvent.Location)
        location as LiveEvent.Location
        assertEquals("child-1", location.childId)
        assertEquals(55.75, location.lat, 0.0)
        assertEquals(37.61, location.lon, 0.0)
        assertEquals(3f, location.accuracy)
        assertNull(location.speed)
        assertEquals(
            Instant.parse("2026-10-20T10:00:00Z").toEpochMilli(),
            location.recordedAtMillis,
        )

        val sos = parseLiveEvent(
            """
            {"type":"sos","event_id":"event-1","child":"child-1","lat":55.75,"lon":37.61,
             "created_at":"2026-10-20T10:01:00Z"}
            """.trimIndent(),
        )
        assertTrue(sos is LiveEvent.Sos)
        sos as LiveEvent.Sos
        assertEquals("event-1", sos.eventId)
        assertEquals("child-1", sos.childId)
        assertEquals(
            Instant.parse("2026-10-20T10:01:00Z").toEpochMilli(),
            sos.createdAtMillis,
        )

        assertEquals(LiveEvent.Open, parseLiveEvent("""{"type":"open"}"""))
        assertNull(parseLiveEvent("""{"type":"heartbeat"}"""))
        assertNull(parseLiveEvent("""{"type":"location","child":"x"}"""))
        assertNull(
            parseLiveEvent(
                """{"type":"location","child":"x","lat":1.0,"lon":2.0,"recorded_at":"not-a-time"}""",
            ),
        )
        assertNull(parseLiveEvent("not json"))
        assertNull(parseLiveEvent("""{"type":"sos","child":"x"}"""))
    }

    /**
     * Ktor's MockEngine still hops to a real dispatcher, so `advanceUntilIdle` alone would
     * not wait for a background request (and virtual time would trip HttpTimeout). This
     * pumps queued continuations while giving the real dispatcher a few milliseconds.
     */
    private suspend fun TestScope.settleUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for $description")
            }
            // Blocking (not suspending) keeps runTest from auto-advancing virtual time,
            // so a pending reconnect backoff cannot fire while we wait for real I/O.
            Thread.sleep(2)
        }
    }

    private fun point(lat: Double, lon: Double, iso: String) = PointIn(
        lat = lat,
        lon = lon,
        recordedAtMillis = Instant.parse(iso).toEpochMilli(),
    )

    private fun location(lat: Double, lon: Double, iso: String) = LiveEvent.Location(
        childId = "child-1",
        lat = lat,
        lon = lon,
        accuracy = null,
        speed = null,
        recordedAtMillis = Instant.parse(iso).toEpochMilli(),
    )

    /** Creates one fake connection per factory call and remembers them in order. */
    private class FakeSocketFactory {
        val sockets = mutableListOf<FakeSocket>()

        fun newSocket(): LiveSocket = FakeSocket().also { sockets += it }

        val current: FakeSocket get() = sockets.last()
    }

    private class FakeSocket : LiveSocket {
        private val channel = Channel<LiveEvent>(Channel.UNLIMITED)

        override fun events(): Flow<LiveEvent> = flow {
            emit(LiveEvent.Open)
            for (event in channel) emit(event)
        }

        suspend fun send(event: LiveEvent) {
            channel.send(event)
        }

        fun close() {
            channel.close()
        }
    }

    private companion object {
        const val BASE_URL = "https://server.example"
        const val TOKEN = "test-token"
        const val NOW_ISO = "2026-10-20T10:10:00Z"
        val NOW: Long = Instant.parse(NOW_ISO).toEpochMilli()

        val DEVICES_ONE_CHILD = """
            [{"device_id":"child-1","name":"Маша",
              "last_location":{"lat":55.70,"lon":37.60,"recorded_at":"2026-10-20T10:00:00Z"}}]
        """.trimIndent()
    }
}
