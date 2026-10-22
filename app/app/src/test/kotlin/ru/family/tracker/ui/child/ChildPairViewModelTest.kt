package ru.family.tracker.ui.child

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.KeyValueStore
import ru.family.tracker.R
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChildPairViewModelTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    private fun pairTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun viewModel(
        settings: Settings,
        engine: MockEngine,
    ) = ChildPairViewModel(settings, ApiClient({ settings.baseUrl }, { settings.token }, engine))

    private data class InvalidInput(
        val server: String,
        val code: String,
        val name: String,
        val messageRes: Int,
    )

    private suspend fun ChildPairViewModel.awaitTerminal(): ChildPairState =
        state.first { it is ChildPairState.Success || it is ChildPairState.Error }

    @Test
    fun `success saves normalized server token and child role`() = pairTest {
        val settings = Settings(InMemoryStore())
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(CHILD_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }

        val viewModel = viewModel(settings, engine)
        viewModel.submit("  https://api.example.net/  ", " 123456 ", "  Миша ")

        assertEquals(ChildPairState.Success, viewModel.awaitTerminal())
        assertEquals("https://api.example.net", settings.baseUrl)
        assertEquals("child-token", settings.token)
        assertEquals(Role.CHILD, settings.role)
        assertEquals(1, requests.size)
        assertEquals("/pair/child", requests[0].url.encodedPath)
        // The code is a secret-like one-time value: it must travel only in the POST body.
        assertNull(requests[0].url.parameters["code"])
        assertFalse(requests[0].url.encodedQuery.contains("123456"))
        val sent = Json.parseToJsonElement(
            (requests[0].body as TextContent).text,
        ).jsonObject
        assertEquals("123456", sent["code"]!!.jsonPrimitive.content)
        assertEquals("Миша", sent["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `http statuses map to specific messages`() = pairTest {
        val cases = mapOf(
            HttpStatusCode.Gone to R.string.child_pair_error_code_used,
            HttpStatusCode.NotFound to R.string.child_pair_error_code_invalid,
            HttpStatusCode.UnprocessableEntity to R.string.child_pair_error_invalid_input,
            HttpStatusCode.TooManyRequests to R.string.child_pair_error_too_many_attempts,
            HttpStatusCode.ServiceUnavailable to R.string.child_pair_error_server_busy,
            HttpStatusCode.InternalServerError to R.string.child_pair_error_server,
            HttpStatusCode.BadRequest to R.string.child_pair_error_unknown,
        )

        for ((status, messageRes) in cases) {
            val settings = Settings(InMemoryStore())
            val engine = MockEngine {
                respond("""{"error":"internal detail"}""", status, jsonHeaders())
            }

            val viewModel = viewModel(settings, engine)
            viewModel.submit("https://api.example.net", "123456", "Миша")

            assertEquals(
                "status $status",
                ChildPairState.Error(messageRes),
                viewModel.awaitTerminal(),
            )
            assertNull(settings.token)
            assertNull(settings.role)
        }
    }

    @Test
    fun `transport failure reports network`() = pairTest {
        val settings = Settings(InMemoryStore())
        val engine = MockEngine { throw IOException("offline") }

        val viewModel = viewModel(settings, engine)
        viewModel.submit("https://api.example.net", "123456", "Миша")

        assertEquals(
            ChildPairState.Error(R.string.child_pair_error_network),
            viewModel.awaitTerminal(),
        )
        assertNull(settings.token)
    }

    @Test
    fun `invalid input is rejected before the network`() = pairTest {
        val cases = listOf(
            InvalidInput("api.example.net", "123456", "Миша", R.string.child_pair_error_invalid_server),
            InvalidInput("https://api.example.net/api", "123456", "Миша", R.string.child_pair_error_invalid_server),
            InvalidInput("https://api.example.net", "12ab56", "Миша", R.string.child_pair_error_invalid_code),
            InvalidInput("https://api.example.net", "12345", "Миша", R.string.child_pair_error_invalid_code),
            InvalidInput("https://api.example.net", "123456", "   ", R.string.child_pair_error_empty_name),
        )

        var called = false
        val engine = MockEngine {
            called = true
            respond(CHILD_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }

        for (case in cases) {
            val settings = Settings(InMemoryStore())
            val viewModel = viewModel(settings, engine)
            viewModel.submit(case.server, case.code, case.name)

            assertEquals(ChildPairState.Error(case.messageRes), viewModel.state.value)
            assertNull(settings.baseUrl)
        }
        assertTrue(!called)
    }

    @Test
    fun `loading blocks a second submit until the first completes`() = pairTest {
        val settings = Settings(InMemoryStore())
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            started.complete(Unit)
            gate.await()
            respond(CHILD_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }

        val viewModel = viewModel(settings, engine)
        viewModel.submit("https://api.example.net", "123456", "Миша")
        assertTrue(viewModel.state.value is ChildPairState.Loading)

        started.await()
        viewModel.submit("https://api.example.net", "123456", "Миша")
        assertEquals(1, requests.size)

        gate.complete(Unit)
        assertEquals(ChildPairState.Success, viewModel.awaitTerminal())
        assertEquals(1, requests.size)
    }

    private companion object {
        const val CHILD_RESPONSE = """{"device_id":"dev-2","token":"child-token"}"""
    }
}
