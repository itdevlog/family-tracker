package ru.family.tracker.ui.parent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.KeyValueStore
import ru.family.tracker.NtfyConfig
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterParentViewModelTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    /**
     * viewModelScope needs a Main dispatcher. An unconfined test dispatcher drives the
     * ViewModel coroutine eagerly; the tests then await a terminal state instead of
     * assuming Ktor's engine completes synchronously.
     */
    private fun registerTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `success stores token ntfy and normalized base url`() = registerTest {
        val settings = Settings(InMemoryStore())
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(PARENT_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }
        val viewModel = RegisterParentViewModel(
            settings,
            ApiClient({ settings.baseUrl }, { settings.token }, engine),
        )

        viewModel.submit("  https://api.example.net/  ", "  Мама  ")

        val terminal = viewModel.state.first { it is RegisterState.Success || it is RegisterState.Error }
        assertEquals(RegisterState.Success, terminal)
        assertEquals("parent-token", settings.token)
        assertEquals(
            NtfyConfig("https://ntfy.example", "family-fam-1", "ntfy-token"),
            settings.ntfyConfig,
        )
        assertEquals("https://api.example.net", settings.baseUrl)
        assertEquals(1, requests.size)
        assertEquals("/pair/parent", requests[0].url.encodedPath)
    }

    @Test
    fun `conflict reports an error and persists no token`() = registerTest {
        val settings = Settings(InMemoryStore())
        val engine = MockEngine {
            respond("""{"error":"family exists"}""", HttpStatusCode.Conflict, jsonHeaders())
        }
        val viewModel = RegisterParentViewModel(
            settings,
            ApiClient({ settings.baseUrl }, { settings.token }, engine),
        )

        viewModel.submit("https://api.example.net", "Мама")

        val terminal = viewModel.state.first { it is RegisterState.Success || it is RegisterState.Error }
        assertEquals(
            RegisterState.Error(R.string.parent_register_error_conflict),
            terminal,
        )
        assertNull(settings.token)
        assertNull(settings.ntfyConfig)
        assertEquals("https://api.example.net", settings.baseUrl)
    }

    @Test
    fun `loading blocks a second submit until the first completes`() = registerTest {
        val settings = Settings(InMemoryStore())
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            started.complete(Unit)
            gate.await()
            respond(PARENT_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }
        val viewModel = RegisterParentViewModel(
            settings,
            ApiClient({ settings.baseUrl }, { settings.token }, engine),
        )

        viewModel.submit("https://api.example.net", "Мама")
        assertTrue(viewModel.state.value is RegisterState.Loading)

        started.await()
        viewModel.submit("https://api.example.net", "Мама")

        assertEquals(1, requests.size)

        gate.complete(Unit)
        val terminal = viewModel.state.first { it is RegisterState.Success || it is RegisterState.Error }

        assertEquals(RegisterState.Success, terminal)
        assertEquals(1, requests.size)
    }

    @Test
    fun `invalid url is rejected before the network`() = registerTest {
        val settings = Settings(InMemoryStore())
        var called = false
        val engine = MockEngine {
            called = true
            respond(PARENT_RESPONSE, HttpStatusCode.OK, jsonHeaders())
        }
        val viewModel = RegisterParentViewModel(
            settings,
            ApiClient({ settings.baseUrl }, { settings.token }, engine),
        )

        viewModel.submit("api.example.net", "Мама")

        assertEquals(
            RegisterState.Error(R.string.parent_register_error_invalid_url),
            viewModel.state.value,
        )
        assertTrue(!called)
        assertNull(settings.baseUrl)
    }

    private companion object {
        const val PARENT_RESPONSE = """
            {"device_id":"dev-1","family_id":"fam-1","token":"parent-token",
             "ntfy":{"url":"https://ntfy.example","topic":"family-fam-1","token":"ntfy-token"}}
        """
    }
}
