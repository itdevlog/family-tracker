package familytracker.live

import familytracker.db.PointIn
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
class LiveBroadcaster(private val scope: CoroutineScope) {
    private val json = Json

    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    fun register(familyId: UUID, session: WebSocketSession) {
        sessions.computeIfAbsent(familyId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(familyId: UUID, session: WebSocketSession) {
        val familySessions = sessions[familyId] ?: return
        familySessions.remove(session)
        if (familySessions.isEmpty()) sessions.remove(familyId, familySessions)
    }

    fun broadcastLocation(familyId: UUID, childId: UUID, point: PointIn) {
        val event = LocationEvent(
            type = "location",
            child = childId.toString(),
            lat = point.lat,
            lon = point.lon,
            accuracy = point.accuracy,
            speed = point.speed,
            recordedAt = Instant.ofEpochMilli(point.recordedAt).toString(),
        )
        publish(familyId, json.encodeToString(event))
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
        publish(familyId, json.encodeToString(event))
    }

    /**
     * Snapshots the recipients and returns immediately; the actual writes happen on
     * [scope]. A snapshot may contain a session that closes in the meantime, which is
     * handled like any other send failure.
     */
    private fun publish(familyId: UUID, payload: String) {
        val recipients = sessions[familyId]?.toList().orEmpty()
        if (recipients.isEmpty()) return

        scope.launch {
            recipients.forEach { session ->
                try {
                    session.send(Frame.Text(payload))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    unregister(familyId, session)
                    runCatching { session.close() }
                }
            }
        }
    }
}