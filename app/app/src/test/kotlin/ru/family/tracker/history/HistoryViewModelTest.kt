package ru.family.tracker.history

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val clockMillis = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 10, 20)
    private val oldest = today.minusDays(HISTORY_DAYS_BACK)

    private val requests = mutableListOf<HttpRequestData>()

    private fun historyTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun TestScope.viewModel(engine: MockEngine): HistoryViewModel = HistoryViewModel(
        api = ApiClient({ "https://server.example" }, { "test-token" }, engine),
        deviceId = "child-1",
        clock = { clockMillis },
        zone = zone,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `request uses ISO bounds of the selected day in the configured zone`() = historyTest {
        val engine = MockEngine { request ->
            requests += request
            respond("[]", HttpStatusCode.OK, jsonHeaders())
        }
        val viewModel = viewModel(engine)

        val loaded = viewModel.state.first { !it.loading }

        assertEquals(today, loaded.date)
        assertEquals(1, requests.size)
        assertEquals("child-1", requests[0].url.parameters["device"])
        assertEquals("2026-10-19T21:00:00Z", requests[0].url.parameters["from"])
        assertEquals("2026-10-20T20:59:59.999Z", requests[0].url.parameters["to"])
    }

    @Test
    fun `date window stops at today and seven days back`() = historyTest {
        val engine = MockEngine {
            respond("[]", HttpStatusCode.OK, jsonHeaders())
        }
        val viewModel = viewModel(engine)

        val initial = viewModel.state.first { !it.loading }
        assertTrue(initial.canGoBack)
        assertFalse(initial.canGoForward)

        viewModel.nextDay()
        assertEquals(today, viewModel.state.value.date)

        viewModel.selectDate(oldest)
        val atOldest = viewModel.state.first { it.date == oldest && !it.loading }
        assertFalse(atOldest.canGoBack)
        assertTrue(atOldest.canGoForward)

        viewModel.previousDay()
        assertEquals(oldest, viewModel.state.value.date)
        assertFalse(viewModel.state.value.canGoBack)

        viewModel.selectDate(today.plusDays(1))
        assertEquals(oldest, viewModel.state.value.date)
    }

    @Test
    fun `success publishes points and its stops`() = historyTest {
        val engine = MockEngine {
            respond(
                """
                [
                  {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T05:00:00Z"},
                  {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T05:02:00Z"},
                  {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T05:05:00Z"}
                ]
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val viewModel = viewModel(engine)

        val state = viewModel.state.first { !it.loading && it.points.isNotEmpty() }

        assertNull(state.error)
        assertEquals(3, state.points.size)
        assertEquals(1, state.stops.size)
        assertEquals(5L * 60_000, state.stops.single().durationMillis)
        assertEquals(3, state.stops.single().pointCount)
    }

    @Test
    fun `forbidden response becomes an access error with no points`() = historyTest {
        val engine = MockEngine {
            respond(
                """{"error":"forbidden"}""",
                HttpStatusCode.Forbidden,
                jsonHeaders(),
            )
        }
        val viewModel = viewModel(engine)

        val state = viewModel.state.first { !it.loading && it.error != null }

        assertEquals(R.string.history_error_access, state.error)
        assertTrue(state.points.isEmpty())
        assertTrue(state.stops.isEmpty())
        assertFalse(state.loading)
    }

    @Test
    fun `too large a range maps to the range error`() = historyTest {
        val engine = MockEngine {
            respond(
                """{"error":"range too large"}""",
                HttpStatusCode.PayloadTooLarge,
                jsonHeaders(),
            )
        }
        val viewModel = viewModel(engine)

        val state = viewModel.state.first { !it.loading && it.error != null }

        assertEquals(R.string.history_error_range, state.error)
    }

    @Test
    fun `selecting another day cancels the previous load and applies only the latest`() =
        historyTest {
            val yesterday = today.minusDays(1)
            val yesterdayFrom = "2026-10-18T21:00:00Z"
            val yesterdayStarted = CompletableDeferred<Unit>()
            val yesterdayCancelled = CompletableDeferred<Unit>()
            val yesterdayGate = CompletableDeferred<Unit>()

            val engine = MockEngine { request ->
                requests += request
                if (request.url.parameters["from"] == yesterdayFrom) {
                    yesterdayStarted.complete(Unit)
                    try {
                        yesterdayGate.await()
                    } catch (cancelled: CancellationException) {
                        yesterdayCancelled.complete(Unit)
                        throw cancelled
                    }
                    respond(
                        """
                        [
                          {"lat":54.00,"lon":30.00,"recorded_at":"2026-10-19T05:00:00Z"},
                          {"lat":54.00,"lon":30.00,"recorded_at":"2026-10-19T05:10:00Z"}
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                } else {
                    respond(
                        """
                        [
                          {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T06:00:00Z"}
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }
            }
            val viewModel = viewModel(engine)
            viewModel.state.first { !it.loading }

            viewModel.selectDate(yesterday)
            yesterdayStarted.await()
            assertEquals(yesterday, viewModel.state.value.date)
            assertTrue(viewModel.state.value.loading)

            viewModel.selectDate(today)
            val latest = viewModel.state.first { it.date == today && !it.loading }
            assertEquals(1, latest.points.size)
            assertEquals(55.75, latest.points.single().lat, 1e-9)

            // The in-flight request must actually be cancelled, not just ignored when
            // its response eventually arrives. Removing loadJob?.cancel() fails here.
            withTimeout(5_000) { yesterdayCancelled.await() }
            yesterdayGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(today, viewModel.state.value.date)
            assertEquals(1, viewModel.state.value.points.size)
            assertEquals(55.75, viewModel.state.value.points.single().lat, 1e-9)
            assertEquals(3, requests.size)
        }

    @Test
    fun `refresh keeps the shown route while reloading and when the reload fails`() =
        historyTest {
            val gate = CompletableDeferred<Unit>()
            var call = 0
            val engine = MockEngine {
                call += 1
                if (call == 1) {
                    respond(
                        """
                        [
                          {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T06:00:00Z"},
                          {"lat":55.75,"lon":37.61,"recorded_at":"2026-10-20T06:05:00Z"}
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                } else {
                    gate.await()
                    respond(
                        """{"error":"boom"}""",
                        HttpStatusCode.InternalServerError,
                        jsonHeaders(),
                    )
                }
            }
            val viewModel = viewModel(engine)
            val loaded = viewModel.state.first { !it.loading && it.points.isNotEmpty() }
            assertEquals(1, loaded.stops.size)

            viewModel.refresh()
            val reloading = viewModel.state.first { it.loading }
            assertEquals(2, reloading.points.size)
            assertEquals(1, reloading.stops.size)

            gate.complete(Unit)
            val failed = viewModel.state.first { !it.loading && it.error != null }
            assertEquals(R.string.history_error_load, failed.error)
            assertEquals(2, failed.points.size)
            assertEquals(1, failed.stops.size)
        }
}
