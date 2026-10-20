package familytracker

import familytracker.db.ChildRegistration
import familytracker.db.Database
import familytracker.db.FamilyAlreadyExistsException
import familytracker.db.RedeemResult
import familytracker.db.redeemChildCode
import familytracker.db.storePairCode
import familytracker.routes.ignoreFamilyAlreadyExists
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
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairRoutesTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json

    private fun config() = Config(
        dbPath = tempDir.resolve("test.db").toString(),
        ntfyUrl = "https://ntfy.example",
        ntfyToken = "ntfy-secret",
    )

    @Test
    fun `first parent registration creates family and returns working bearer token`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()

        assertTrue(UUID.fromString(parent.string("device_id")).version() == 4)
        assertTrue(UUID.fromString(parent.string("family_id")).version() == 4)
        assertTrue(parent.string("token").isNotBlank())

        val ntfy = parent["ntfy"]!!.jsonObject
        assertEquals("https://ntfy.example", ntfy.string("url"))
        assertEquals("family-${parent.string("family_id")}-sos", ntfy.string("topic"))
        assertEquals("ntfy-secret", ntfy.string("token"))

        // The freshly issued token must be accepted by the Bearer auth check.
        val codeResponse = client.post("/pair/code") {
            header(HttpHeaders.Authorization, "Bearer ${parent.string("token")}")
        }
        assertEquals(HttpStatusCode.OK, codeResponse.status)
    }

    @Test
    fun `second parent registration is rejected with conflict`() = testApplication {
        application { module(config()) }

        client.createParent()

        val response = client.post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Other parent"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("family exists", response.error())
    }

    @Test
    fun `repeated parent registration is conflict even without a valid body`() = testApplication {
        application { module(config()) }

        client.createParent()

        val noBody = client.post("/pair/parent")
        assertEquals(HttpStatusCode.Conflict, noBody.status)
        assertEquals("family exists", noBody.error())

        val emptyBody = client.post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.Conflict, emptyBody.status)
        assertEquals("family exists", emptyBody.error())

        val malformedBody = client.post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":""")
        }
        assertEquals(HttpStatusCode.Conflict, malformedBody.status)
        assertEquals("family exists", malformedBody.error())
    }

    @Test
    fun `only the dedicated family conflict exception is swallowed`() = runBlocking {
        assertEquals("created", ignoreFamilyAlreadyExists { "created" })
        assertNull(ignoreFamilyAlreadyExists<String> { throw FamilyAlreadyExistsException() })

        val unexpected = assertFailsWith<IllegalStateException> {
            ignoreFamilyAlreadyExists<String> { throw IllegalStateException("database is broken") }
        }
        assertEquals("database is broken", unexpected.message)
    }

    @Test
    fun `pair code requires a valid bearer token`() = testApplication {
        application { module(config()) }

        val missing = client.post("/pair/code")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("unauthorized", missing.error())

        val unknown = client.post("/pair/code") {
            header(HttpHeaders.Authorization, "Bearer not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("unauthorized", unknown.error())
    }

    @Test
    fun `parent receives a six digit pair code that expires in five minutes`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val before = System.currentTimeMillis()

        val response = client.post("/pair/code") {
            header(HttpHeaders.Authorization, "Bearer ${parent.string("token")}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val code = body.string("code")
        assertTrue(code.matches(Regex("\\d{6}")), "unexpected code $code")
        val expiresAt = Instant.parse(body.string("expires_at")).toEpochMilli()
        assertTrue(
            expiresAt in (before + 4 * 60_000L)..(before + 6 * 60_000L),
            "unexpected expiry $expiresAt",
        )
    }

    @Test
    fun `child token is forbidden from creating a pair code`() = testApplication {
        application { module(config()) }

        val parent = client.createParent()
        val child = childInDatabase(parent)

        val response = client.post("/pair/code") {
            header(HttpHeaders.Authorization, "Bearer ${child.token}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.error())
    }

    private suspend fun HttpClient.createParent(name: String = "Mom"): JsonObject {
        val response = post("/pair/parent") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun childInDatabase(parent: JsonObject): ChildRegistration {
        val familyId = UUID.fromString(parent.string("family_id"))
        return Database(config().dbPath).withConnection { conn ->
            val code = storePairCode(conn, familyId)
            assertIs<RedeemResult.Redeemed>(redeemChildCode(conn, code.code, "Kid")).registration
        }
    }

    private suspend fun HttpResponse.error(): String =
        json.parseToJsonElement(bodyAsText()).jsonObject.string("error")

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
