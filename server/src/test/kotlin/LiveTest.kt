package familytracker

import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.RedeemResult
import familytracker.db.redeemChildCode
import familytracker.db.storePairCode
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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LiveTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    /** How long a test is willing to wait for the server to push an event. */
    private val eventTimeout = 10_000L

    /** Grace period for the server to finish registering a freshly opened session. */
    private val registrationDelay = 500L

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    @Test
    fun `two parents of the same family receive every new point`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent)
        val token = parent.string("token")

        val firstClient = createClient { install(WebSockets) }
        val secondClient = createClient { install(WebSockets) }

        coroutineScope {
            val firstEvents = async { firstClient.collectEvents(token, 3) }
            val secondEvents = async { secondClient.collectEvents(token, 3) }

            delay(registrationDelay)
            val response = client.postLocations(child.token, batch(3))
            assertEquals(HttpStatusCode.NoContent, response.status)

            for (events in listOf(firstEvents.await(), secondEvents.await())) {
                assertEquals(3, events.size)
                assertEquals(
                    (0 until 3).map { Instant.ofEpochMilli(BASE_TIME + it).toString() }.sorted(),
                    events.map { it.string("recorded_at") }.sorted(),
                )
                events.forEach { event ->
                    assertEquals("location", event.string("type"))
                    assertEquals(child.deviceId.toString(), event.string("child"))
                    assertEquals(5.0, event.getValue("accuracy").jsonPrimitive.double)
                    assertEquals(1.5, event.getValue("speed").jsonPrimitive.double)
                }
                val coordinates = events.map { it.double("lat") to it.double("lon") }.sortedBy { it.first }
                assertEquals(
                    (0 until 3).map { (55.0 + it / 1_000.0) to (37.0 + it / 1_000.0) },
                    coordinates,
                )
            }
        }
    }

    @Test
    fun `unknown token closes the socket with 4401`() = testApplication {
        application { module(config()) }
        client.createParent()

        val wsClient = createClient { install(WebSockets) }
        var reason: CloseReason? = null
        wsClient.webSocket("/live?token=not-a-real-token") {
            reason = withTimeout(eventTimeout) { closeReason.await() }
        }

        assertEquals(4401, reason?.code?.toInt())
    }

    @Test
    fun `child token closes the socket with 4401`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent)

        val wsClient = createClient { install(WebSockets) }
        var reason: CloseReason? = null
        wsClient.webSocket("/live?token=${child.token}") {
            reason = withTimeout(eventTimeout) { closeReason.await() }
        }

        assertEquals(4401, reason?.code?.toInt())
    }

    @Test
    fun `replayed batch produces no extra events`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/live?token=${parent.string("token")}") {
            delay(registrationDelay)
            assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(3)).status)
            assertEquals(
                (0 until 3).map { Instant.ofEpochMilli(BASE_TIME + it).toString() }.sorted(),
                receiveEvents(3).map { it.string("recorded_at") }.sorted(),
            )

            // Replaying the same batch inserts nothing, so nothing must be pushed.
            assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(3)).status)
            assertNull(withTimeoutOrNull(1_000) { incoming.receive() })

            // A new sample still arrives; if the replay had leaked, its event would come first.
            assertEquals(
                HttpStatusCode.NoContent,
                client.postLocations(child.token, batch(1, start = 3)).status,
            )
            val next = withTimeout(eventTimeout) { incoming.receive() }
            assertIs<Frame.Text>(next)
            assertEquals(
                Instant.ofEpochMilli(BASE_TIME + 3).toString(),
                json.parseToJsonElement(next.readText()).jsonObject.string("recorded_at"),
            )
        }
    }

    @Test
    fun `location event keeps null accuracy and speed`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent)

        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/live?token=${parent.string("token")}") {
            delay(registrationDelay)
            val recordedAt = Instant.ofEpochMilli(BASE_TIME)
            val body = """{"points":[{"lat":55.0,"lon":37.0,"accuracy":null,"speed":null,"recorded_at":"$recordedAt"}]}"""
            assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, body).status)
            val event = receiveEvents(1).single()
            assertEquals("location", event.string("type"))
            assertEquals(JsonNull, event.getValue("accuracy"))
            assertEquals(JsonNull, event.getValue("speed"))
        }
    }

    @Test
    fun `upload succeeds when nobody is subscribed`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent)

        val response = client.postLocations(child.token, batch(3))

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private suspend fun HttpClient.collectEvents(token: String, count: Int): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        webSocket("/live?token=$token") {
            while (events.size < count) {
                val frame = withTimeout(eventTimeout) { incoming.receive() }
                if (frame is Frame.Text) {
                    events += json.parseToJsonElement(frame.readText()).jsonObject
                }
            }
        }
        return events
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveEvents(
        count: Int,
    ): List<JsonObject> = buildList {
        while (size < count) {
            val frame = withTimeout(eventTimeout) { incoming.receive() }
            if (frame is Frame.Text) {
                add(json.parseToJsonElement(frame.readText()).jsonObject)
            }
        }
    }

    private suspend fun HttpClient.createParent(name: String = "Mom"): JsonObject {
        val response = post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun childOf(parent: JsonObject): ChildRegistration {
        val familyId = UUID.fromString(parent.string("family_id"))
        return Database(config().dbPath).withConnection { conn ->
            val code = storePairCode(conn, familyId)
            assertIs<RedeemResult.Redeemed>(redeemChildCode(conn, code.code, "Kid")).registration
        }
    }

    private suspend fun HttpClient.postLocations(token: String?, body: String): HttpResponse =
        post("/locations") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun batch(count: Int, start: Int = 0): String =
        (start until start + count).joinToString(prefix = """{"points":[""", postfix = "]}") { index ->
            point(index)
        }

    private fun point(index: Int): String {
        val lat = 55.0 + index / 1_000.0
        val lon = 37.0 + index / 1_000.0
        val recordedAt = Instant.ofEpochMilli(BASE_TIME + index)
        return """{"lat":$lat,"lon":$lon,"accuracy":5.0,"speed":1.5,"recorded_at":"$recordedAt"}"""
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
