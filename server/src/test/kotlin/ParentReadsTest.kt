package familytracker

import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.db.RedeemResult
import familytracker.db.SosEvent
import familytracker.db.insertLocations
import familytracker.db.insertSos
import familytracker.db.newToken
import familytracker.db.redeemChildCode
import familytracker.db.sha256Hex
import familytracker.db.storePairCode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParentReadsTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    private data class Child(val deviceId: UUID, val token: String)

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    // GET /devices

    @Test
    fun `devices lists children with last location or null`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val alpha = childOf(parent, "Alpha")
        val beta = childOf(parent, "Beta")
        insertPoints(
            alpha.deviceId,
            listOf(
                PointIn(1.0, 1.0, 5f, 1.5f, BASE + 1_000),
                PointIn(2.0, 2.0, 6f, 2.5f, BASE + 2_000),
            ),
        )

        val response = client.getRead("/devices", parent.token())

        assertEquals(HttpStatusCode.OK, response.status)
        val devices = response.array().map { it.jsonObject }
        assertEquals(2, devices.size)
        assertEquals(alpha.deviceId, devices[0].uuid("device_id"))
        assertEquals("Alpha", devices[0].string("name"))
        val lastLocation = devices[0].getValue("last_location").jsonObject
        assertEquals(2.0, lastLocation.getValue("lat").jsonPrimitive.double)
        assertEquals(2.0, lastLocation.getValue("lon").jsonPrimitive.double)
        assertEquals(Instant.ofEpochMilli(BASE + 2_000).toString(), lastLocation.string("recorded_at"))
        // The device summary carries only lat/lon/recorded_at, not accuracy or speed.
        assertFalse(lastLocation.containsKey("accuracy"))
        assertFalse(lastLocation.containsKey("speed"))

        assertEquals(beta.deviceId, devices[1].uuid("device_id"))
        assertEquals("Beta", devices[1].string("name"))
        assertEquals(JsonNull, devices[1].getValue("last_location"))
    }

    @Test
    fun `devices is empty before any child is paired`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()

        val response = client.getRead("/devices", parent.token())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.array().isEmpty())
    }

    @Test
    fun `devices requires a parent token`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val missing = client.getRead("/devices")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("unauthorized", missing.error())

        val asChild = client.getRead("/devices", child.token)
        assertEquals(HttpStatusCode.Forbidden, asChild.status)
        assertEquals("forbidden", asChild.error())
    }

    // GET /locations

    @Test
    fun `locations returns the range in ascending time order with ISO timestamps`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        insertPoints(
            child.deviceId,
            listOf(
                PointIn(55.2, 37.2, 9f, 2.5f, BASE),
                PointIn(55.0, 37.0, 5f, 1.5f, BASE + 2_000),
                PointIn(55.1, 37.1, null, null, BASE + 1_000),
                PointIn(55.3, 37.3, null, null, BASE - 1_000),
                PointIn(55.4, 37.4, null, null, BASE + 3_000),
            ),
        )

        val response = client.getRead(
            "/locations",
            parent.token(),
            mapOf("device" to child.deviceId.toString(), "from" to iso(BASE), "to" to iso(BASE + 2_000)),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val points = response.array().map { it.jsonObject }
        assertEquals(3, points.size)
        assertEquals(
            listOf(BASE, BASE + 1_000, BASE + 2_000),
            points.map { Instant.parse(it.string("recorded_at")).toEpochMilli() },
        )
        assertEquals(55.2, points[0].getValue("lat").jsonPrimitive.double)
        assertEquals(9f, points[0].getValue("accuracy").jsonPrimitive.content.toFloat())
        assertEquals(JsonNull, points[1].getValue("accuracy"))
        assertEquals(JsonNull, points[1].getValue("speed"))
        assertEquals(Instant.ofEpochMilli(BASE + 2_000).toString(), points[2].string("recorded_at"))
    }

    @Test
    fun `locations requires device from and to`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val token = parent.token()

        val queries = listOf(
            mapOf("from" to iso(BASE), "to" to iso(BASE + 1_000)),
            mapOf("device" to child.deviceId.toString(), "to" to iso(BASE + 1_000)),
            mapOf("device" to child.deviceId.toString(), "from" to iso(BASE)),
        )

        queries.forEach { query ->
            val response = client.getRead("/locations", token, query)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "query=$query")
            assertEquals("invalid query", response.error())
        }
    }

    @Test
    fun `locations rejects an inverted range`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val response = client.getRead(
            "/locations",
            parent.token(),
            mapOf(
                "device" to child.deviceId.toString(),
                "from" to iso(BASE + 1_000),
                "to" to iso(BASE),
            ),
        )

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("invalid query", response.error())
    }

    @Test
    fun `locations rejects malformed and overflowing timestamps`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val malformed = client.getRead(
            "/locations",
            parent.token(),
            mapOf("device" to child.deviceId.toString(), "from" to "yesterday", "to" to iso(BASE)),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, malformed.status)
        assertEquals("invalid query", malformed.error())

        // Syntactically valid ISO-8601 whose epoch-millisecond conversion overflows a Long.
        val overflowing = client.getRead(
            "/locations",
            parent.token(),
            mapOf(
                "device" to child.deviceId.toString(),
                "from" to "+999999999-12-31T23:59:59Z",
                "to" to iso(BASE),
            ),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, overflowing.status)
        assertEquals("invalid query", overflowing.error())
    }

    @Test
    fun `locations rejects a device that is not a child of the family`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val token = parent.token()
        val from = iso(BASE)
        val to = iso(BASE + 1_000)

        val unknown = client.getRead(
            "/locations",
            token,
            mapOf("device" to UUID.randomUUID().toString(), "from" to from, "to" to to),
        )
        assertEquals(HttpStatusCode.Forbidden, unknown.status)
        assertEquals("forbidden", unknown.error())

        // A parent is not a readable child even inside the same family.
        val self = client.getRead(
            "/locations",
            token,
            mapOf("device" to parent.string("device_id"), "from" to from, "to" to to),
        )
        assertEquals(HttpStatusCode.Forbidden, self.status)
        assertEquals("forbidden", self.error())

        val foreign = client.getRead(
            "/locations",
            token,
            mapOf("device" to insertForeignFamilyChild().toString(), "from" to from, "to" to to),
        )
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals("forbidden", foreign.error())
    }

    @Test
    fun `locations rejects a child token`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val response = client.getRead(
            "/locations",
            child.token,
            mapOf("device" to child.deviceId.toString(), "from" to iso(BASE), "to" to iso(BASE + 1_000)),
        )

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.error())
    }

    // GET /sos

    @Test
    fun `sos is empty before any event`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        childOf(parent, "Kid")

        val response = client.getRead("/sos", parent.token())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.array().isEmpty())
    }

    @Test
    fun `sos lists events unacknowledged and filters by since`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val event = insertSosEvent(child.deviceId, lat = 55.7, lon = 37.6)

        val all = client.getRead("/sos", parent.token())
        assertEquals(HttpStatusCode.OK, all.status)
        val events = all.array().map { it.jsonObject }
        assertEquals(1, events.size)
        val listed = events.single()
        assertEquals(event.id, listed.uuid("event_id"))
        assertEquals(child.deviceId, listed.uuid("child"))
        assertEquals(55.7, listed.getValue("lat").jsonPrimitive.double)
        assertEquals(37.6, listed.getValue("lon").jsonPrimitive.double)
        assertEquals(Instant.ofEpochMilli(event.createdAt).toString(), listed.string("created_at"))
        assertFalse(listed.getValue("acknowledged").jsonPrimitive.boolean)

        val included = client.getRead(
            "/sos",
            parent.token(),
            mapOf("since" to iso(event.createdAt)),
        )
        assertEquals(1, included.array().size)

        val excluded = client.getRead(
            "/sos",
            parent.token(),
            mapOf("since" to iso(event.createdAt + 1)),
        )
        assertTrue(excluded.array().isEmpty())
    }

    @Test
    fun `sos excludes events of another family`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val foreignChild = insertForeignFamilyChild()
        insertSosEvent(foreignChild)

        val response = client.getRead("/sos", parent.token())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.array().isEmpty())
    }

    @Test
    fun `sos rejects a malformed since and a child token`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")

        val malformed = client.getRead("/sos", parent.token(), mapOf("since" to "yesterday"))
        assertEquals(HttpStatusCode.UnprocessableEntity, malformed.status)
        assertEquals("invalid query", malformed.error())

        val asChild = client.getRead("/sos", child.token)
        assertEquals(HttpStatusCode.Forbidden, asChild.status)
        assertEquals("forbidden", asChild.error())
    }

    // POST /sos/ack

    @Test
    fun `ack marks the event acknowledged and is idempotent`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val event = insertSosEvent(child.deviceId)

        val first = client.ack(parent.token(), """{"event_id":"${event.id}"}""")
        assertEquals(HttpStatusCode.NoContent, first.status)

        val listed = client.getRead("/sos", parent.token()).array().single().jsonObject
        assertTrue(listed.getValue("acknowledged").jsonPrimitive.boolean)

        val repeated = client.ack(parent.token(), """{"event_id":"${event.id}"}""")
        assertEquals(HttpStatusCode.NoContent, repeated.status)
    }

    @Test
    fun `ack of an unknown or foreign event is not found`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()

        val unknown = client.ack(parent.token(), """{"event_id":"${UUID.randomUUID()}"}""")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("not found", unknown.error())

        val foreignEvent = insertSosEvent(insertForeignFamilyChild())
        val foreign = client.ack(parent.token(), """{"event_id":"${foreignEvent.id}"}""")
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("not found", foreign.error())
    }

    @Test
    fun `ack rejects missing or malformed event ids`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()

        val malformed = client.ack(parent.token(), """{"event_id":"not-a-uuid"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, malformed.status)
        assertEquals("invalid request", malformed.error())

        val missing = client.ack(parent.token(), "{}")
        assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
        assertEquals("invalid request", missing.error())
    }

    @Test
    fun `ack rejects a child token`() = testApplication {
        application { module(config()) }
        val parent = client.createParent()
        val child = childOf(parent, "Kid")
        val event = insertSosEvent(child.deviceId)

        val response = client.ack(child.token, """{"event_id":"${event.id}"}""")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.error())
    }

    // Helpers

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

    private fun insertPoints(deviceId: UUID, points: List<PointIn>) {
        Database(config().dbPath).withConnection { conn ->
            insertLocations(conn, deviceId, points)
        }
    }

    private fun insertSosEvent(childId: UUID, lat: Double = 55.7, lon: Double = 37.6): SosEvent =
        Database(config().dbPath).withConnection { conn ->
            insertSos(conn, childId, lat, lon)
        }

    /**
     * Inserts a device of a second family directly: the routes refuse to create a second
     * family, but the schema itself allows it and the read endpoints must isolate families.
     */
    private fun insertForeignFamilyChild(): UUID =
        Database(config().dbPath).withConnection { conn ->
            val familyId = UUID.randomUUID()
            val deviceId = UUID.randomUUID()
            val now = System.currentTimeMillis()
            conn.prepareStatement("INSERT INTO families(id, created_at) VALUES (?, ?)").use { statement ->
                statement.setString(1, familyId.toString())
                statement.setLong(2, now)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO devices(id, family_id, role, name, token_hash, created_at) " +
                    "VALUES (?, ?, 'child', ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, deviceId.toString())
                statement.setString(2, familyId.toString())
                statement.setString(3, "Stranger")
                statement.setString(4, sha256Hex(newToken()))
                statement.setLong(5, now)
                statement.executeUpdate()
            }
            deviceId
        }

    private suspend fun HttpClient.getRead(
        path: String,
        token: String? = null,
        params: Map<String, String> = emptyMap(),
    ): HttpResponse =
        get(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            url {
                params.forEach { (key, value) -> parameters.append(key, value) }
            }
        }

    private suspend fun HttpClient.ack(token: String?, body: String): HttpResponse =
        post("/sos/ack") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.array(): JsonArray =
        json.parseToJsonElement(bodyAsText()).jsonArray

    private suspend fun HttpResponse.error(): String =
        json.parseToJsonElement(bodyAsText()).jsonObject.string("error")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.uuid(key: String): UUID = UUID.fromString(string(key))

    private fun JsonObject.token(): String = string("token")

    private fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    private companion object {
        const val BASE = 1_700_000_000_000L
    }
}
