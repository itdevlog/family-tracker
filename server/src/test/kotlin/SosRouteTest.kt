package familytracker

import com.sun.net.httpserver.HttpServer
import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.RedeemResult
import familytracker.db.SosEvent
import familytracker.db.listSos
import familytracker.db.redeemChildCode
import familytracker.db.storePairCode
import familytracker.ntfy.NtfyPublisher
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SosRouteTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    /** How long a test is willing to wait for the server to push an event. */
    private val eventTimeout = 10_000L

    /** Grace period for the server to finish registering a freshly opened session. */
    private val registrationDelay = 500L

    private data class NtfyCall(val topic: String, val title: String, val body: String, val priority: Int)

    private class RecordingNtfy(private val fail: Boolean = false) : NtfyPublisher {
        val calls = CopyOnWriteArrayList<NtfyCall>()

        override fun publish(topic: String, title: String, body: String, priority: Int) {
            calls += NtfyCall(topic, title, body, priority)
            if (fail) throw IOException("ntfy is unavailable")
        }
    }

    private data class Child(val deviceId: UUID, val token: String)

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    @Test
    fun `sos stores the event and publishes exactly once`() = testApplication {
        val ntfy = RecordingNtfy()
        application { module(config(), ntfy = ntfy) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val response = client.postSos(child.token, """{"lat":55.7,"lon":37.6}""")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())

        assertEquals(1, ntfy.calls.size)
        val call = ntfy.calls.single()
        assertEquals("family-${parent.string("family_id")}-sos", call.topic)
        assertEquals(5, call.priority)
        assertTrue(call.title.isNotBlank())
        assertTrue(call.body.isNotBlank())

        val event = sosEvents(UUID.fromString(parent.string("family_id"))).single()
        assertEquals(child.deviceId, event.childId)
        assertEquals(55.7, event.lat)
        assertEquals(37.6, event.lon)
        assertFalse(event.acknowledged)
    }

    @Test
    fun `sos reaches a parent websocket session`() = testApplication {
        val ntfy = RecordingNtfy()
        application { module(config(), ntfy = ntfy) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val familyId = UUID.fromString(parent.string("family_id"))

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/live?token=${parent.string("token")}") {
            delay(registrationDelay)
            assertEquals(
                HttpStatusCode.NoContent,
                client.postSos(child.token, """{"lat":55.7,"lon":37.6}""").status,
            )

            val event = receiveEvent()
            assertEquals("sos", event.string("type"))
            assertEquals(child.deviceId.toString(), event.string("child"))
            assertEquals(55.7, event.double("lat"))
            assertEquals(37.6, event.double("lon"))

            val stored = sosEvents(familyId).single()
            assertEquals(stored.id.toString(), event.string("event_id"))
            assertEquals(Instant.ofEpochMilli(stored.createdAt).toString(), event.string("created_at"))
        }
    }

    @Test
    fun `sos rejects points outside the coordinate ranges`() = testApplication {
        val ntfy = RecordingNtfy()
        application { module(config(), ntfy = ntfy) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val response = client.postSos(child.token, """{"lat":95.0,"lon":37.6}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid point", response.error())

        val longitudes = client.postSos(child.token, """{"lat":55.7,"lon":200.0}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, longitudes.status)
        assertEquals("invalid point", longitudes.error())

        for (body in listOf("""{"lat":NaN,"lon":37.6}""", """{"lat":55.7,"lon":Infinity}""")) {
            val nonFinite = client.postSos(child.token, body)
            assertEquals(HttpStatusCode.UnprocessableEntity, nonFinite.status)
            assertEquals("invalid point", nonFinite.error())
        }

        assertTrue(ntfy.calls.isEmpty())
        assertTrue(sosEvents(UUID.fromString(parent.string("family_id"))).isEmpty())
    }

    @Test
    fun `sos requires a child bearer token`() = testApplication {
        val ntfy = RecordingNtfy()
        application { module(config(), ntfy = ntfy) }
        val parent = client.createParent()
        childOf(parent, "Kid")

        val anonymous = client.postSos(null, """{"lat":55.7,"lon":37.6}""")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val asParent = client.postSos(parent.string("token"), """{"lat":55.7,"lon":37.6}""")
        assertEquals(HttpStatusCode.Forbidden, asParent.status)

        val unknown = client.postSos("not-a-real-token", """{"lat":55.7,"lon":37.6}""")
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)

        assertTrue(ntfy.calls.isEmpty())
        assertTrue(sosEvents(UUID.fromString(parent.string("family_id"))).isEmpty())
    }

    @Test
    fun `sos still answers 204 when ntfy fails`() = testApplication {
        val ntfy = RecordingNtfy(fail = true)
        application { module(config(), ntfy = ntfy) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val familyId = UUID.fromString(parent.string("family_id"))

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/live?token=${parent.string("token")}") {
            delay(registrationDelay)
            val response = client.postSos(child.token, """{"lat":55.7,"lon":37.6}""")
            assertEquals(HttpStatusCode.NoContent, response.status)

            val event = receiveEvent()
            assertEquals("sos", event.string("type"))
            assertEquals(child.deviceId.toString(), event.string("child"))

            assertEquals(1, ntfy.calls.size)
            val stored = sosEvents(familyId).single()
            assertEquals(stored.id.toString(), event.string("event_id"))
            assertFalse(stored.acknowledged)
        }
    }

    @Test
    fun `sos publishes to the internal ntfy url while pairing advertises the public one`() = testApplication {
        val captured = CompletableFuture<String>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            captured.complete(exchange.requestURI.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        httpServer.start()
        try {
            val internalUrl = "http://127.0.0.1:${httpServer.address.port}"
            val publicUrl = "https://ntfy.example"
            application {
                module(config().copy(ntfyUrl = internalUrl, ntfyPublicUrl = publicUrl))
            }
            val parent = client.createParent()
            assertEquals(publicUrl, parent["ntfy"]!!.jsonObject.string("url"))

            val child = childOf(parent, "Kid")
            val response = client.postSos(child.token, """{"lat":55.7,"lon":37.6}""")

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("/", captured.get(5, TimeUnit.SECONDS))
        } finally {
            httpServer.stop(0)
        }
    }

    @Test
    fun `repeated sos is suppressed per child for thirty seconds`() = testApplication {
        val ntfy = RecordingNtfy()
        val clock = AtomicLong(1_000_000L)
        application { module(config(), ntfy = ntfy, now = clock::get) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val familyId = UUID.fromString(parent.string("family_id"))

        assertEquals(HttpStatusCode.NoContent, client.postSos(child.token, """{"lat":55.7,"lon":37.6}""").status)
        assertEquals(1, ntfy.calls.size)
        assertEquals(1, sosEvents(familyId).size)

        clock.addAndGet(29_999)
        assertEquals(HttpStatusCode.NoContent, client.postSos(child.token, """{"lat":55.8,"lon":37.7}""").status)
        assertEquals(1, ntfy.calls.size)
        assertEquals(1, sosEvents(familyId).size)

        // The cooldown is per child: a sibling may report immediately.
        val sibling = childOf(parent, "Sibling")
        assertEquals(HttpStatusCode.NoContent, client.postSos(sibling.token, """{"lat":55.9,"lon":37.8}""").status)
        assertEquals(2, ntfy.calls.size)
        assertEquals(2, sosEvents(familyId).size)

        // Exactly at the end of the window the first child may report again.
        clock.addAndGet(1)
        assertEquals(HttpStatusCode.NoContent, client.postSos(child.token, """{"lat":55.7,"lon":37.6}""").status)
        assertEquals(3, ntfy.calls.size)
        assertEquals(3, sosEvents(familyId).size)
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEvent(): JsonObject {
        val frame = withTimeout(eventTimeout) { incoming.receive() }
        assertIs<Frame.Text>(frame)
        return json.parseToJsonElement(frame.readText()).jsonObject
    }

    private suspend fun HttpClient.createParent(): JsonObject {
        val response = post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Mom"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun childOf(parent: JsonObject, name: String): Child {
        val familyId = UUID.fromString(parent.string("family_id"))
        val registration: ChildRegistration = Database(config().dbPath).withConnection { conn ->
            val code = storePairCode(conn, familyId)
            assertIs<RedeemResult.Redeemed>(redeemChildCode(conn, code.code, name)).registration
        }
        return Child(registration.deviceId, registration.token)
    }

    private fun sosEvents(familyId: UUID): List<SosEvent> =
        Database(config().dbPath).withConnection { conn -> listSos(conn, familyId, 0) }

    private suspend fun HttpClient.postSos(token: String?, body: String): HttpResponse =
        post("/sos") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.error(): String =
        json.parseToJsonElement(bodyAsText()).jsonObject.string("error")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double
}
