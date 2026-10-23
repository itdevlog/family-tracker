package ru.family.tracker.data.api.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DateTimeException
import java.time.OffsetDateTime

/** Application-private close code the server uses for a bad or non-parent token. */
internal const val WS_UNAUTHORIZED: Short = 4401

/** One live event from the server. */
sealed interface LiveEvent {
    /** Emitted once the WebSocket handshake succeeded. */
    data object Open : LiveEvent

    data class Location(
        val childId: String,
        val lat: Double,
        val lon: Double,
        val accuracy: Float?,
        val speed: Float?,
        val recordedAtMillis: Long,
    ) : LiveEvent

    data class Sos(
        val eventId: String,
        val childId: String,
        val lat: Double,
        val lon: Double,
        val createdAtMillis: Long,
    ) : LiveEvent
}

/**
 * A single WebSocket connection. Collecting the flow opens the socket and returns when
 * the peer closes it or the connection breaks; reconnecting is [LiveMonitor]'s job,
 * because every reconnect has to be followed by an HTTP catch-up.
 */
interface LiveSocket {
    fun events(): Flow<LiveEvent>
}

/** The server rejected the token; retrying would only hammer the server. */
class LiveUnauthorizedException : Exception("live socket rejected the token")

/**
 * Ktor/OkHttp implementation of [LiveSocket].
 *
 * The token travels in the query string because a WebSocket handshake cannot carry an
 * `Authorization` header. It is never logged.
 */
class KtorLiveSocket(
    private val baseUrlProvider: () -> String?,
    private val tokenProvider: () -> String?,
) : LiveSocket {

    override fun events(): Flow<LiveEvent> = flow {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("live socket token is not configured")
        val url = liveUrl(baseUrlProvider(), token)

        val client = HttpClient(OkHttp) {
            install(WebSockets)
        }
        try {
            val session = try {
                client.webSocketSession(url)
            } catch (error: ClientRequestException) {
                if (error.response.status.value == 401) throw LiveUnauthorizedException()
                throw error
            }

            emit(LiveEvent.Open)

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    parseLiveEvent(frame.readText())?.let { emit(it) }
                }
            }

            val closeCode = session.closeReason.await()?.code
            if (closeCode == WS_UNAUTHORIZED || closeCode == HTTP_UNAUTHORIZED_CLOSE) {
                throw LiveUnauthorizedException()
            }
        } finally {
            client.close()
        }
    }
}

private const val HTTP_UNAUTHORIZED_CLOSE: Short = 401

/**
 * Builds `wss://host/live?token=...` from the configured base URL.
 *
 * `http` is kept for a local `Caddy.local` setup only; release traffic must be TLS.
 */
internal fun liveUrl(rawBaseUrl: String?, token: String): String {
    val trimmed = rawBaseUrl?.trim().orEmpty()
    require(trimmed.isNotEmpty()) { "baseUrl is not configured" }

    val separator = trimmed.indexOf("://")
    require(separator > 0) { "baseUrl is not an absolute URL" }
    val scheme = trimmed.substring(0, separator).lowercase()
    val wsScheme = when (scheme) {
        "https" -> "wss"
        "http" -> "ws"
        else -> throw IllegalArgumentException("baseUrl must use http or https")
    }
    val hostAndPath = trimmed.substring(separator + 3).trimEnd('/')
    require(hostAndPath.isNotEmpty()) { "baseUrl must contain a host" }

    return "$wsScheme://$hostAndPath/live?token=${token.encodeURLParameter()}"
}

@Serializable
private data class LocationWire(
    val child: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
private data class SosWire(
    @SerialName("event_id") val eventId: String,
    val child: String,
    val lat: Double,
    val lon: Double,
    @SerialName("created_at") val createdAt: String,
)

private val liveJson = Json { ignoreUnknownKeys = true }

/**
 * Parses one server frame. Unknown types and malformed payloads are ignored instead of
 * tearing the connection down: a single bad frame must not stop live updates.
 */
internal fun parseLiveEvent(raw: String): LiveEvent? = try {
    val type = liveJson.parseToJsonElement(raw)
        .jsonObject["type"]
        ?.jsonPrimitive
        ?.contentOrNull

    when (type) {
        "open" -> LiveEvent.Open
        "location" -> liveJson.decodeFromString<LocationWire>(raw).let { wire ->
            isoMillis(wire.recordedAt)?.let { millis ->
                LiveEvent.Location(
                    childId = wire.child,
                    lat = wire.lat,
                    lon = wire.lon,
                    accuracy = wire.accuracy,
                    speed = wire.speed,
                    recordedAtMillis = millis,
                )
            }
        }

        "sos" -> liveJson.decodeFromString<SosWire>(raw).let { wire ->
            isoMillis(wire.createdAt)?.let { millis ->
                LiveEvent.Sos(
                    eventId = wire.eventId,
                    childId = wire.child,
                    lat = wire.lat,
                    lon = wire.lon,
                    createdAtMillis = millis,
                )
            }
        }

        else -> null
    }
} catch (_: Exception) {
    null
}

private fun isoMillis(value: String): Long? = try {
    OffsetDateTime.parse(value).toInstant().toEpochMilli()
} catch (_: DateTimeException) {
    null
} catch (_: ArithmeticException) {
    null
}
