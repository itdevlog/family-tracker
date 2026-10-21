package familytracker.live

import familytracker.db.PointIn
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class LocationEvent(
    val type: String,
    val child: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
    val speed: Float?,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
private data class SosEvent(
    val type: String,
    val child: String,
    @SerialName("event_id") val eventId: String,
    val lat: Double,
    val lon: Double,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Pushes live events to the WebSocket sessions of one family.
 *
 * Sessions are grouped by family, so a token can only ever observe its own family.
 * Publishing never blocks the caller (the HTTP request that inserted the points) and
 * never throws into it: frames are written from coroutines of [scope] and a session
 * that fails to accept a frame is simply unregistered.
 */
class LiveBroadcaster(
    private val scope: CoroutineScope,
    private val sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS,
) {
    private val json = Json

    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    /**
     * Adding and removing a session, as well as dropping an emptied room, happen under
     * [ConcurrentHashMap.compute] / [ConcurrentHashMap.computeIfPresent] on the same key,
     * so a concurrent [register] cannot add its session to a set that [unregister] has
     * just detached from the map. Without that atomicity the new socket would be
     * registered in an unreachable set and never get events.
     */
    fun register(familyId: UUID, session: WebSocketSession) {
        sessions.compute(familyId) { _, familySessions ->
            (familySessions ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
        }
    }

    fun unregister(familyId: UUID, session: WebSocketSession) {
        sessions.computeIfPresent(familyId) { _, familySessions ->
            familySessions.remove(session)
            if (familySessions.isEmpty()) null else familySessions
        }
    }

    /** Publishes a single point; delegates to [broadcastLocations]. */
    fun broadcastLocation(familyId: UUID, childId: UUID, point: PointIn) {
        broadcastLocations(familyId, childId, listOf(point))
    }

    /**
     * Publishes every point of one upload batch with a single asynchronous send per
     * session. Iterating the batch in one coroutine keeps `recorded_at` order on the wire,
     * which matters for offline catch-up where a device uploads many points at once.
     */
    fun broadcastLocations(familyId: UUID, childId: UUID, points: List<PointIn>) {
        if (points.isEmpty()) return
        val payloads = points.map { point ->
            json.encodeToString(
                LocationEvent(
                    type = "location",
                    child = childId.toString(),
                    lat = point.lat,
                    lon = point.lon,
                    accuracy = point.accuracy,
                    speed = point.speed,
                    recordedAt = Instant.ofEpochMilli(point.recordedAt).toString(),
                ),
            )
        }
        publish(familyId, payloads)
    }

    fun broadcastSos(
        familyId: UUID,
        childId: UUID,
        eventId: UUID,
        lat: Double,
        lon: Double,
        createdAtMs: Long,
    ) {
        val event = SosEvent(
            type = "sos",
            child = childId.toString(),
            eventId = eventId.toString(),
            lat = lat,
            lon = lon,
            createdAt = Instant.ofEpochMilli(createdAtMs).toString(),
        )
        publish(familyId, listOf(json.encodeToString(event)))
    }

    /**
     * Snapshots the recipients and returns immediately; the actual writes happen on
     * [scope]. A snapshot may contain a session that closes in the meantime, which is
     * handled like any other send failure.
     */
    private fun publish(familyId: UUID, payloads: List<String>) {
        if (payloads.isEmpty()) return
        val recipients = sessions[familyId]?.toList().orEmpty()
        if (recipients.isEmpty()) return

        scope.launch {
            recipients.forEach { session -> sendAll(familyId, session, payloads) }
        }
    }

    /**
     * Writes [payloads] to one session in order. A peer that stops reading must not pin a
     * broadcaster coroutine forever, so every frame is bounded by [sendTimeoutMs]; on
     * failure or timeout the session is dropped (the remaining sessions are still served).
     */
    private suspend fun sendAll(familyId: UUID, session: WebSocketSession, payloads: List<String>) {
        try {
            payloads.forEach { payload ->
                withTimeout(sendTimeoutMs) { session.send(Frame.Text(payload)) }
            }
        } catch (_: TimeoutCancellationException) {
            drop(familyId, session)
        } catch (cancellation: CancellationException) {
            // The broadcaster's own scope is being cancelled: let shutdown proceed.
            throw cancellation
        } catch (_: Throwable) {
            drop(familyId, session)
        }
    }

    private suspend fun drop(familyId: UUID, session: WebSocketSession) {
        unregister(familyId, session)
        // Best effort, and bounded for the same reason as the sends above.
        try {
            withTimeout(sendTimeoutMs) { session.close() }
        } catch (_: CancellationException) {
            // The session is already unreachable; nothing else to release here.
        }
    }
}

/** Upper bound for one frame write; a client that stops reading is disconnected. */
private const val DEFAULT_SEND_TIMEOUT_MS = 5_000L
