package familytracker

import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.db.RedeemResult
import familytracker.db.locationHistory
import familytracker.db.redeemChildCode
import familytracker.db.storePairCode
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertTrue

class LocationsRouteTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    @Test
    fun `batch of three points is stored`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(child.token, batch(3))

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(3, storedPoints(child.deviceId).size)
    }

    @Test
    fun `repeated batch is accepted but not duplicated`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(3)).status)
        val repeated = client.postLocations(child.token, batch(3))

        assertEquals(HttpStatusCode.NoContent, repeated.status)
        assertEquals(3, storedPoints(child.deviceId).size)
    }

    @Test
    fun `more than one hundred points is rejected with payload too large`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(child.token, batch(101))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("too many points", response.error())
        assertTrue(storedPoints(child.deviceId).isEmpty())
    }

    @Test
    fun `empty batch is unprocessable`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(child.token, """{"points":[]}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid point", response.error())
    }

    @Test
    fun `latitude out of range is unprocessable`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(
            child.token,
            """{"points":[{"lat":95.0,"lon":37.0,"accuracy":5.0,"speed":1.5,"recorded_at":"2023-11-14T22:13:20Z"}]}""",
        )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid point", response.error())
        assertTrue(storedPoints(child.deviceId).isEmpty())
    }

    @Test
    fun `longitude out of range is unprocessable`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(
            child.token,
            """{"points":[{"lat":55.0,"lon":200.0,"accuracy":5.0,"speed":1.5,"recorded_at":"2023-11-14T22:13:20Z"}]}""",
        )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid point", response.error())
    }

    @Test
    fun `unparsable recorded at is unprocessable`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(
            child.token,
            """{"points":[{"lat":55.0,"lon":37.0,"accuracy":5.0,"speed":1.5,"recorded_at":"yesterday"}]}""",
        )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid point", response.error())
        assertTrue(storedPoints(child.deviceId).isEmpty())
    }

    @Test
    fun `missing or unknown token is unauthorized`() = testApplication {
        application { module(config()) }
        client.createParent()

        val missing = client.postLocations(null, batch(1))
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("unauthorized", missing.error())

        val unknown = client.postLocations("not-a-real-token", batch(1))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("unauthorized", unknown.error())
    }

    @Test
    fun `parent token is forbidden`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()

        val response = client.postLocations(parent.string("token"), batch(1))

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.error())
    }

    @Test
    fun `null accuracy and speed are accepted`() = testApplication {
        application { module(config()) }
        val child = client.childWithParent()

        val response = client.postLocations(
            child.token,
            """{"points":[{"lat":55.0,"lon":37.0,"accuracy":null,"speed":null,"recorded_at":"2023-11-14T22:13:20Z"}]}""",
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        val stored = storedPoints(child.deviceId).single()
        assertEquals(55.0, stored.lat)
        assertNull(stored.accuracy)
        assertNull(stored.speed)
    }

    @Test
    fun `device id in body is ignored`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val ownChild = childOf(parent)
        val foreignChild = childOf(parent)

        val body = """{"device_id":"${foreignChild.deviceId}","points":[${point(0)}]}"""
        val response = client.postLocations(ownChild.token, body)

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1, storedPoints(ownChild.deviceId).size)
        assertTrue(storedPoints(foreignChild.deviceId).isEmpty())
    }

    @Test
    fun `insert hook receives the token device and only new points`() = testApplication {
        val events = mutableListOf<Triple<UUID, UUID, List<PointIn>>>()
        application {
            module(
                config(),
                onLocationsInserted = { familyId, deviceId, points ->
                    events += Triple(familyId, deviceId, points)
                },
            )
        }
        val parent = client.createParent()
        val child = childOf(parent)

        assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(3)).status)
        assertEquals(1, events.size)
        val (familyId, deviceId, points) = events.single()
        assertEquals(parent.familyId(), familyId)
        assertEquals(child.deviceId, deviceId)
        assertEquals(3, points.size)

        // A duplicate resend must not emit another event.
        assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(3)).status)
        assertEquals(1, events.size)

        // A partly overlapping batch reports only the newly inserted samples.
        assertEquals(HttpStatusCode.NoContent, client.postLocations(child.token, batch(4, start = 2)).status)
        assertEquals(2, events.size)
        assertEquals(
            listOf(BASE_TIME + 3, BASE_TIME + 4, BASE_TIME + 5),
            events[1].third.map { it.recordedAt },
        )
    }

    private fun storedPoints(deviceId: UUID): List<PointIn> =
        Database(config().dbPath).withConnection {
            locationHistory(it, deviceId, 0L, Long.MAX_VALUE)
        }

    private suspend fun HttpClient.createParent(name: String = "Mom"): JsonObject {
        val response = post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.childWithParent(): ChildRegistration = childOf(createParent())

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

    private suspend fun HttpResponse.error(): String =
        json.parseToJsonElement(bodyAsText()).jsonObject.string("error")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.familyId(): UUID = UUID.fromString(string("family_id"))

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
