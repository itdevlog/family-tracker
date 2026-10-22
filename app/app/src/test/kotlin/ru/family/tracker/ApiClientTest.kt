package ru.family.tracker

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.api.LocationPoint
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.api.normalizeBaseUrl
import ru.family.tracker.data.api.toPointIn
import ru.family.tracker.data.api.toWire
import java.io.IOException
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * Contract tests against a [MockEngine]: real [ApiClient], real serialization, no sockets.
 */
class ApiClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    private val json = Json { ignoreUnknownKeys = true }

    private fun api(
        baseUrl: String? = "https://server.example",
        token: String? = "test-token",
        handler: (HttpRequestData) -> Pair<HttpStatusCode, String> = {
            HttpStatusCode.OK to "{}"
        },
    ): ApiClient {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return ApiClient({ baseUrl }, { token }, engine)
    }

    private fun bodyText(request: HttpRequestData): String {
        val body = request.body
        require(body is TextContent) { "unexpected request body: ${body::class.simpleName}" }
        return body.text
    }

    @Test
    fun `registerParent posts name without bearer even with a stale token`() = runTest {
        val registration = api(token = "stale") { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/pair/parent", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("""{"name":"Мама"}""", bodyText(request))
            HttpStatusCode.OK to PARENT_RESPONSE
        }.registerParent("Мама")

        assertEquals("dev-1", registration.deviceId)
        assertEquals("fam-1", registration.familyId)
        assertEquals("parent-token", registration.token)
        assertEquals("https://ntfy.example", registration.ntfy.url)
        assertEquals("family-fam-1", registration.ntfy.topic)
        assertEquals("ntfy-token", registration.ntfy.token)
    }

    @Test
    fun `createPairCode sends bearer and parses expiry`() = runTest {
        val pairCode = api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/pair/code", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            HttpStatusCode.OK to """{"code":"123456","expires_at":"2026-10-20T12:00:00Z"}"""
        }.createPairCode()

        assertEquals("123456", pairCode.code)
        assertEquals("2026-10-20T12:00:00Z", pairCode.expiresAt)
    }

    @Test
    fun `redeemChildCode posts code and name without bearer even with a stale token`() = runTest {
        val registration = api(token = "stale") { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/pair/child", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            val sent = json.parseToJsonElement(bodyText(request)).jsonObject
            assertEquals("123456", sent["code"]!!.jsonPrimitive.content)
            assertEquals("Маша", sent["name"]!!.jsonPrimitive.content)
            HttpStatusCode.OK to """{"device_id":"child-1","token":"child-token"}"""
        }.redeemChildCode("123456", "Маша")

        assertEquals("child-1", registration.deviceId)
        assertEquals("child-token", registration.token)
    }

    @Test
    fun `redeemChildCode maps 410 to ApiException`() = runTest {
        val client = api(token = null) {
            HttpStatusCode.Gone to """{"error":"code used"}"""
        }

        val error = assertFailsWith<ApiException> {
            client.redeemChildCode("123456", "Маша")
        }

        assertEquals(410, error.status)
        assertEquals("code used", error.serverError)
    }

    @Test
    fun `redeemChildCode maps 404 to ApiException`() = runTest {
        val client = api(token = null) {
            HttpStatusCode.NotFound to """{"error":"invalid code"}"""
        }

        val error = assertFailsWith<ApiException> {
            client.redeemChildCode("123456", "Маша")
        }

        assertEquals(404, error.status)
        assertEquals("invalid code", error.serverError)
    }

    @Test
    fun `uploadPoints posts bearer batch with iso timestamps`() = runTest {
        val points = listOf(
            PointIn(55.75, 37.61, 5f, 1.5f, Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()),
            PointIn(55.76, 37.62, recordedAtMillis = Instant.parse("2026-10-20T12:00:30Z").toEpochMilli()),
            PointIn(55.77, 37.63, recordedAtMillis = Instant.parse("2026-10-20T12:01:00Z").toEpochMilli()),
        )

        api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/locations", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])

            val sent = json.parseToJsonElement(bodyText(request)).jsonObject
            assertNull(sent["device_id"])
            val sentPoints = sent["points"]!!.jsonArray
            assertEquals(3, sentPoints.size)
            val first = sentPoints[0].jsonObject
            assertEquals("2026-10-20T12:00:00Z", first["recorded_at"]!!.jsonPrimitive.content)
            assertEquals(5f, first["accuracy"]!!.jsonPrimitive.content.toFloat())
            assertEquals(1.5f, first["speed"]!!.jsonPrimitive.content.toFloat())
            assertEquals("2026-10-20T12:01:00Z", sentPoints[2].jsonObject["recorded_at"]!!.jsonPrimitive.content)

            HttpStatusCode.NoContent to ""
        }.uploadPoints(points)

        assertEquals(1, requests.size)
    }

    @Test
    fun `uploadPoints rejects an empty batch before the network`() = runTest {
        val client = api()

        assertFailsWith<IllegalArgumentException> {
            client.uploadPoints(emptyList())
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `uploadPoints rejects more than 100 points before the network`() = runTest {
        val client = api()
        val points = List(101) { PointIn(0.0, 0.0, recordedAtMillis = 0) }

        assertFailsWith<IllegalArgumentException> {
            client.uploadPoints(points)
        }

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `history encodes device from and to and returns domain points`() = runTest {
        val from = "2026-01-01T00:00:00+03:00"
        val to = "2026-01-02T00:00:00+03:00"
        val points = api { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/locations", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("child-1", request.url.parameters["device"])
            assertEquals(from, request.url.parameters["from"])
            assertEquals(to, request.url.parameters["to"])
            // '+' must be percent-encoded in a query value, otherwise it decodes as a space.
            assertTrue(request.url.encodedQuery.contains("%2B03%3A00"))
            HttpStatusCode.OK to """
                [{"lat":55.75,"lon":37.61,"accuracy":3.0,"speed":2.0,"recorded_at":"2026-01-01T10:00:00Z"}]
            """.trimIndent()
        }.history("child-1", from, to)

        assertEquals(1, points.size)
        assertEquals(55.75, points[0].lat, 0.0)
        assertEquals(37.61, points[0].lon, 0.0)
        assertEquals(3f, points[0].accuracy)
        assertEquals(2f, points[0].speed)
        assertEquals(Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(), points[0].recordedAtMillis)
    }

    @Test
    fun `sosList omits since when called without an argument`() = runTest {
        val events = api { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/sos", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.url.parameters["since"])
            assertFalse(request.url.encodedQuery.contains("since"))
            HttpStatusCode.OK to "[]"
        }.sosList()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `sosList sends since as an iso timestamp`() = runTest {
        val since = "2026-10-20T10:00:00Z"
        val events = api { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/sos", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals(since, request.url.parameters["since"])
            HttpStatusCode.OK to """
                [{"event_id":"evt-1","child":"child-1","lat":55.75,"lon":37.61,
                  "created_at":"2026-10-20T10:00:00Z","acknowledged":false}]
            """.trimIndent()
        }.sosList(since)

        assertEquals(1, events.size)
        assertEquals("evt-1", events[0].eventId)
        assertEquals("child-1", events[0].child)
        assertFalse(events[0].acknowledged)
    }

    @Test
    fun `ackSos posts event id`() = runTest {
        api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/sos/ack", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            val sent = json.parseToJsonElement(bodyText(request)).jsonObject
            assertEquals("evt-1", sent["event_id"]!!.jsonPrimitive.content)
            HttpStatusCode.NoContent to ""
        }.ackSos("evt-1")

        assertEquals(1, requests.size)
    }

    @Test
    fun `sendSos posts bearer and exactly lat lon`() = runTest {
        api { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/sos", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])

            val sent = json.parseToJsonElement(bodyText(request)).jsonObject
            assertEquals(setOf("lat", "lon"), sent.keys)
            assertEquals(55.75, sent["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
            assertEquals(37.61, sent["lon"]!!.jsonPrimitive.content.toDouble(), 0.0)

            HttpStatusCode.NoContent to ""
        }.sendSos(55.75, 37.61)

        assertEquals(1, requests.size)
    }

    @Test
    fun `devices parses absent and present last location`() = runTest {
        val devices = api { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/devices", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            HttpStatusCode.OK to """
                [
                  {"device_id":"child-1","name":"Маша","last_location":null},
                  {"device_id":"child-2","name":"Петя",
                   "last_location":{"lat":55.75,"lon":37.61,"recorded_at":"2026-01-01T10:00:00Z"}}
                ]
            """.trimIndent()
        }.devices()

        assertEquals(2, devices.size)
        assertEquals("Маша", devices[0].name)
        assertNull(devices[0].lastLocation)
        assertEquals("Петя", devices[1].name)
        assertEquals(55.75, devices[1].lastLocation!!.lat, 0.0)
        assertEquals("2026-01-01T10:00:00Z", devices[1].lastLocation!!.recordedAt)
    }

    @Test
    fun `all authenticated calls send the bearer from the provider`() = runTest {
        val client = api(token = "stale") { request ->
            assertEquals("Bearer stale", request.headers[HttpHeaders.Authorization])
            when {
                request.url.encodedPath == "/pair/code" ->
                    HttpStatusCode.OK to """{"code":"123456","expires_at":"2026-10-20T12:00:00Z"}"""

                request.url.encodedPath == "/devices" ->
                    HttpStatusCode.OK to "[]"

                request.url.encodedPath == "/locations" && request.method == HttpMethod.Get ->
                    HttpStatusCode.OK to "[]"

                request.url.encodedPath == "/locations" ->
                    HttpStatusCode.NoContent to ""

                request.url.encodedPath == "/sos" && request.method == HttpMethod.Get ->
                    HttpStatusCode.OK to "[]"

                request.url.encodedPath == "/sos" ->
                    HttpStatusCode.NoContent to ""

                request.url.encodedPath == "/sos/ack" ->
                    HttpStatusCode.NoContent to ""

                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }

        client.createPairCode()
        client.devices()
        client.history("child-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
        client.uploadPoints(listOf(PointIn(1.0, 2.0, recordedAtMillis = 0)))
        client.sendSos(1.0, 2.0)
        client.ackSos("evt-1")
        client.sosList("2026-01-01T00:00:00Z")

        assertEquals(7, requests.size)
    }

    @Test
    fun `points round trip through the wire format`() {
        val original = PointIn(
            lat = 55.75,
            lon = 37.61,
            accuracy = 5f,
            speed = 1.5f,
            recordedAtMillis = 1_700_000_000_000,
        )

        val wire = original.toWire()

        assertEquals("2023-11-14T22:13:20Z", wire.recordedAt)
        assertEquals(original, wire.toPointIn())

        val withoutExtras = PointIn(-1.5, 2.5, recordedAtMillis = 0)
        assertEquals(withoutExtras, withoutExtras.toWire().toPointIn())
        assertNull(withoutExtras.toWire().accuracy)
        assertNull(withoutExtras.toWire().speed)
    }

    @Test
    fun `malformed recorded_at fails mapping with a clear exception`() {
        val wire = LocationPoint(1.0, 2.0, recordedAt = "not-a-timestamp")

        val error = assertFailsWith<IllegalArgumentException> { wire.toPointIn() }

        assertTrue(error.message!!.contains("ISO-8601"))
    }

    @Test
    fun `missing base url fails before any request`() = runTest {
        var called = false
        val client = api(baseUrl = null) {
            called = true
            HttpStatusCode.OK to "{}"
        }

        val error = assertFailsWith<IllegalStateException> {
            client.createPairCode()
        }

        assertEquals("baseUrl is not configured", error.message)
        assertFalse(called)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `blank base url fails before any request`() = runTest {
        var called = false
        val client = api(baseUrl = "   ") {
            called = true
            HttpStatusCode.OK to "{}"
        }

        assertFailsWith<IllegalStateException> {
            client.devices()
        }

        assertFalse(called)
    }

    @Test
    fun `base url is validated and normalized`() {
        assertEquals("https://api.example.net", normalizeBaseUrl("https://api.example.net/"))
        assertEquals("https://api.example.net", normalizeBaseUrl("  https://api.example.net  "))
        assertEquals("http://192.168.1.10:8080", normalizeBaseUrl("http://192.168.1.10:8080"))

        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("https://api.example.net/api") }
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("https://api.example.net?x=1") }
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("https://api.example.net#frag") }
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("ftp://api.example.net") }
        assertFailsWith<IllegalArgumentException> { normalizeBaseUrl("https://") }
    }

    @Test
    fun `invalid base url fails before any request`() = runTest {
        var called = false
        val client = api(baseUrl = "https://api.example.net/api") {
            called = true
            HttpStatusCode.OK to "{}"
        }

        assertFailsWith<IllegalArgumentException> {
            client.devices()
        }

        assertFalse(called)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `204 responses are accepted for upload and ack`() = runTest {
        val client = api { HttpStatusCode.NoContent to "" }

        client.uploadPoints(listOf(PointIn(1.0, 2.0, recordedAtMillis = 0)))
        client.ackSos("evt-1")

        assertEquals(2, requests.size)
    }

    @Test
    fun `non-json error body keeps the status with a null server error`() = runTest {
        val client = api {
            HttpStatusCode.InternalServerError to "<html>boom</html>"
        }

        val error = assertFailsWith<ApiException> {
            client.devices()
        }

        assertEquals(500, error.status)
        assertNull(error.serverError)
    }

    @Test
    fun `conflict and rate limit statuses are preserved`() = runTest {
        var status = HttpStatusCode.Conflict
        val client = api {
            status to """{"error":"family exists"}"""
        }

        val conflict = assertFailsWith<ApiException> { client.createPairCode() }
        assertEquals(409, conflict.status)
        assertEquals("family exists", conflict.serverError)

        status = HttpStatusCode.TooManyRequests
        val limited = assertFailsWith<ApiException> { client.createPairCode() }
        assertEquals(429, limited.status)
        assertEquals("family exists", limited.serverError)
    }

    @Test
    fun `transport IOException is not converted to ApiException`() = runTest {
        val client = api {
            throw IOException("offline")
        }

        val error = assertFailsWith<IOException> {
            client.devices()
        }

        assertEquals("offline", error.message)
    }

    private companion object {
        const val PARENT_RESPONSE = """
            {"device_id":"dev-1","family_id":"fam-1","token":"parent-token",
             "ntfy":{"url":"https://ntfy.example","topic":"family-fam-1","token":"ntfy-token"}}
        """
    }
}
