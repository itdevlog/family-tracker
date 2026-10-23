package ru.family.tracker.ui.parent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import java.util.concurrent.CopyOnWriteArrayList

/** SOS feed: newest first, names from /devices, ack updates locally, errors keep the list. */
@OptIn(ExperimentalCoroutinesApi::class)
class SosHistoryViewModelTest {

    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    @Volatile
    private var failSos = false

    @Volatile
    private var failAck = false

    private val engine = MockEngine { request ->
        requests += request
        when (request.url.encodedPath) {
            "/sos" -> if (failSos) {
                respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders())
            } else {
                respond(EVENTS, HttpStatusCode.OK, jsonHeaders())
            }

            "/devices" -> respond(DEVICES, HttpStatusCode.OK, jsonHeaders())

            "/sos/ack" -> if (failAck) {
                respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders())
            } else {
                respond("", HttpStatusCode.NoContent)
            }

            else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders())
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun TestScope.viewModel(): SosHistoryViewModel = SosHistoryViewModel(
        api = ApiClient({ "https://server.example" }, { "test-token" }, engine),
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun historyTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loads events newest first with child names`() = historyTest {
        val viewModel = viewModel()
        val state = viewModel.state.first { !it.loading && it.items.isNotEmpty() }

        assertEquals(listOf("new", "old"), state.items.map { it.event.eventId })
        assertEquals("Маша", state.items[0].childName)
        assertEquals("Маша", state.items[1].childName)
        assertEquals("2026-10-20T10:10:00Z", state.items[0].event.createdAt)
        assertNull(state.error)
    }

    @Test
    fun `ack marks the event acknowledged locally`() = historyTest {
        val viewModel = viewModel()
        viewModel.state.first { !it.loading && it.items.isNotEmpty() }

        viewModel.ack("new")

        val state = viewModel.state.first { current ->
            current.ackingId == null &&
                current.items.first { it.event.eventId == "new" }.event.acknowledged
        }
        assertEquals(1, requests.count { it.url.encodedPath == "/sos/ack" })
        assertEquals(2, state.items.size)
        assertNull(state.error)
    }

    @Test
    fun `ack failure keeps the list and offers a retry`() = historyTest {
        failAck = true
        val viewModel = viewModel()
        viewModel.state.first { !it.loading && it.items.isNotEmpty() }

        viewModel.ack("new")

        val state = viewModel.state.first { current -> current.ackingId == null && current.ackFailed }
        assertEquals(2, state.items.size)
        assertEquals("new", state.ackFailedId)
        assertNotNull(state.items.first { it.event.eventId == "new" })
    }

    @Test
    fun `refresh failure keeps the previously loaded list`() = historyTest {
        val viewModel = viewModel()
        val loaded = viewModel.state.first { !it.loading && it.items.isNotEmpty() }

        failSos = true
        viewModel.refresh()

        val failed = viewModel.state.first { current -> !current.loading && current.error != null }
        assertEquals(R.string.sos_history_error_load, failed.error)
        assertEquals(loaded.items.map { it.event.eventId }, failed.items.map { it.event.eventId })
        assertEquals(2, failed.items.size)
    }

    private companion object {
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
