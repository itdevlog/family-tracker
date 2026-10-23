package ru.family.tracker.parent

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.family.tracker.NtfyConfig
import java.util.concurrent.TimeUnit

/**
 * One ntfy subscription connection.
 *
 * The ntfy message body is irrelevant: the server publishes a static notification and
 * the app uses the message only as a "there is something new, ask `/sos`" trigger. A
 * text frame therefore maps to [Unit], and collecting the flow ends when ntfy closes the
 * socket (or the connection breaks); reconnecting is [SosListener]'s job.
 */
interface NtfySocket {
    fun messages(): Flow<Unit>
}

/**
 * Ktor/WebSocket implementation of the ntfy subscription endpoint:
 * `wss://<ntfy-host>/<url-encoded-topic>/ws`.
 *
 * The access token travels in the `Authorization` header. It is never logged, and no
 * message body is logged either (ntfy frames may carry user text in general).
 */
class KtorNtfySocket(private val config: NtfyConfig) : NtfySocket {

    override fun messages(): Flow<Unit> = flow {
        val url = ntfyUrl(config.url, config.topic)
        val client = HttpClient(OkHttp) {
            install(WebSockets) {
                pingIntervalMillis = PING_INTERVAL_MS
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                // An idle subscription is normal; keepalive is the WebSocket ping.
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

        try {
            val session = client.webSocketSession(url) {
                header(HttpHeaders.Authorization, "Bearer ${config.token}")
            }
            for (frame in session.incoming) {
                if (frame is Frame.Text) emit(Unit)
            }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 30_000L
        const val PING_INTERVAL_SECONDS = 30L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}

/**
 * Builds the ntfy WebSocket URL from the base URL handed out by `/pair/parent`.
 *
 * `https` becomes `wss` and `http` becomes `ws` (plain http is useful for a local ntfy
 * instance). The topic is a path segment, so it is URL-encoded rather than trusted.
 */
internal fun ntfyUrl(rawUrl: String, rawTopic: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { "ntfy url is not configured" }

    val separator = trimmed.indexOf("://")
    require(separator > 0) { "ntfy url is not absolute" }
    val scheme = trimmed.substring(0, separator).lowercase()
    val wsScheme = when (scheme) {
        "https" -> "wss"
        "http" -> "ws"
        else -> throw IllegalArgumentException("ntfy url must use http or https")
    }

    val hostAndPath = trimmed.substring(separator + 3).trimEnd('/')
    require(hostAndPath.isNotEmpty()) { "ntfy url must contain a host" }

    val topic = rawTopic.trim()
    require(topic.isNotEmpty()) { "ntfy topic is not configured" }

    return "$wsScheme://$hostAndPath/${topic.encodeURLPathPart()}/ws"
}
