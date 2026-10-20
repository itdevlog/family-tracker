package familytracker

import familytracker.db.Database
import familytracker.db.findDeviceByToken
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChildPairTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    @Test
    fun `valid code creates child and returns a working token`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val code = client.pairCode(parent.string("token"))

        val response = client.pairChild(code, name = "  Kid  ")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val deviceId = UUID.fromString(body.string("device_id"))
        val token = body.string("token")

        val device = Database(config().dbPath).withConnection { findDeviceByToken(it, token) }
        assertNotNull(device)
        assertEquals(deviceId, device.id)
        assertEquals(UUID.fromString(parent.string("family_id")), device.familyId)
        assertEquals("child", device.role)
        assertEquals("Kid", device.name)
    }

    @Test
    fun `reusing a redeemed code returns gone`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val code = client.pairCode(parent.string("token"))

        assertEquals(HttpStatusCode.OK, client.pairChild(code).status)

        val repeated = client.pairChild(code)
        assertEquals(HttpStatusCode.Gone, repeated.status)
        assertEquals("code used", repeated.error())
    }

    @Test
    fun `unknown code returns not found`() = testApplication {
        application { module(config()) }
        client.createParent()

        val response = client.pairChild(missingCode())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("invalid code", response.error())
    }

    @Test
    fun `expired code returns not found`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val code = insertExpiredCode(UUID.fromString(parent.string("family_id")))

        val response = client.pairChild(code)

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("invalid code", response.error())
    }

    @Test
    fun `five failures block the next attempt even with a valid code`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val validCode = client.pairCode(parent.string("token"))
        val unknownCode = missingCode()

        repeat(5) {
            assertEquals(HttpStatusCode.NotFound, client.pairChild(unknownCode).status)
        }

        val blocked = client.pairChild(validCode)

        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("too many attempts", blocked.error())
    }

    @Test
    fun `spoofed leftmost X-Forwarded-For hop does not reset the budget`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val validCode = client.pairCode(parent.string("token"))
        val unknownCode = missingCode()

        // Caddy appends the real peer as the rightmost hop; the left part is client input.
        repeat(5) { index ->
            val spoofed = "203.0.113.$index, 10.0.0.1"
            val response = client.pairChild(unknownCode, forwardedFor = spoofed)
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        val blocked = client.pairChild(validCode, forwardedFor = "198.51.100.99, 10.0.0.1")

        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("too many attempts", blocked.error())
    }

    @Test
    fun `successful pairing does not consume the failure budget`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val validCode = client.pairCode(parent.string("token"))
        val unknownCode = missingCode()

        repeat(4) {
            assertEquals(HttpStatusCode.NotFound, client.pairChild(unknownCode).status)
        }

        // A success must not be recorded as a fifth failure.
        assertEquals(HttpStatusCode.OK, client.pairChild(validCode).status)

        assertEquals(HttpStatusCode.NotFound, client.pairChild(unknownCode).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.pairChild(unknownCode).status)
    }

    @Test
    fun `malformed code or blank name is unprocessable`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val validCode = client.pairCode(parent.string("token"))

        val badCode = client.pairChild("12345")
        assertEquals(HttpStatusCode.UnprocessableEntity, badCode.status)
        assertEquals("invalid request", badCode.error())

        val blankName = client.pairChild(validCode, name = "   ")
        assertEquals(HttpStatusCode.UnprocessableEntity, blankName.status)
        assertEquals("invalid request", blankName.error())
    }

    @Test
    fun `malformed json is bad request`() = testApplication {
        application { module(config()) }

        val response = client.post("/pair/child") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"123456" """)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid request", response.error())
    }

    private suspend fun HttpClient.createParent(): JsonObject {
        val response = post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Mom"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.pairCode(token: String): String {
        val response = post("/pair/code") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject.string("code")
    }

    private suspend fun HttpClient.pairChild(
        code: String,
        name: String = "Kid",
        forwardedFor: String? = null,
    ): HttpResponse =
        post("/pair/child") {
            if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code","name":"$name"}""")
        }

    private fun insertExpiredCode(familyId: UUID): String =
        Database(config().dbPath).withConnection { conn ->
            val code = "424242"
            conn.prepareStatement(
                "INSERT INTO pair_codes(code, family_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, code)
                statement.setString(2, familyId.toString())
                statement.setLong(3, System.currentTimeMillis() - 60_000)
                statement.executeUpdate()
            }
            code
        }

    private fun missingCode(): String =
        Database(config().dbPath).withConnection { conn ->
            (0..999_999).asSequence()
                .map { "%06d".format(it) }
                .first { code ->
                    conn.prepareStatement("SELECT 1 FROM pair_codes WHERE code = ?").use { statement ->
                        statement.setString(1, code)
                        statement.executeQuery().use { result -> !result.next() }
                    }
                }
        }

    private suspend fun HttpResponse.error(): String =
        json.parseToJsonElement(bodyAsText()).jsonObject.string("error")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
