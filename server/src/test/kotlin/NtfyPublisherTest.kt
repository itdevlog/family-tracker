package familytracker

import com.sun.net.httpserver.HttpServer
import familytracker.ntfy.HttpNtfyPublisher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NtfyPublisherTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    private data class Captured(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    private fun startServer(status: Int): Pair<HttpServer, CompletableFuture<Captured>> {
        val captured = CompletableFuture<Captured>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            captured.complete(
                Captured(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    body = body,
                ),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        return httpServer to captured
    }

    private fun publisher(httpServer: HttpServer, token: String = "secret") =
        HttpNtfyPublisher("http://127.0.0.1:${httpServer.address.port}", token)

    @Test
    fun `publisher posts the message as json with a bearer token`() {
        val (httpServer, captured) = startServer(200)

        publisher(httpServer).publish(
            topic = "family-abc-sos",
            title = "SOS",
            body = "Ребёнок Kid просит помощи",
            priority = 5,
        )

        val request = captured.get(5, TimeUnit.SECONDS)
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        assertEquals("Bearer secret", request.authorization)
        assertEquals("application/json", request.contentType)

        val payload = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("family-abc-sos", payload.getValue("topic").jsonPrimitive.content)
        assertEquals("SOS", payload.getValue("title").jsonPrimitive.content)
        assertEquals("Ребёнок Kid просит помощи", payload.getValue("message").jsonPrimitive.content)
        assertEquals(5, payload.getValue("priority").jsonPrimitive.int)
    }

    @Test
    fun `publisher omits authorization when no token is configured`() {
        val (httpServer, captured) = startServer(200)

        publisher(httpServer, token = "").publish("family-abc-sos", "SOS", "body", 5)

        assertNull(captured.get(5, TimeUnit.SECONDS).authorization)
    }

    @Test
    fun `publisher fails with io exception on a non success response`() {
        val (httpServer, _) = startServer(500)

        assertFailsWith<IOException> {
            publisher(httpServer).publish("family-abc-sos", "SOS", "body", 5)
        }
    }

    @Test
    fun `publisher rejects an empty base url when publishing`() {
        val publisher = HttpNtfyPublisher("", "secret")

        assertFailsWith<IllegalStateException> {
            publisher.publish("family-abc-sos", "SOS", "body", 5)
        }
    }
}
