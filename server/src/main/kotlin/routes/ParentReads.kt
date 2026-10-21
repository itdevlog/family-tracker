package familytracker.routes

import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.auth.requireParent
import familytracker.db.Database
import familytracker.db.DeviceSummary
import familytracker.db.PointIn
import familytracker.db.SosEvent
import familytracker.db.ackSos
import familytracker.db.childBelongsToFamily
import familytracker.db.childrenWithLastLocation
import familytracker.db.listSos
import familytracker.db.locationHistory
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class LastLocationDto(
    val lat: Double,
    val lon: Double,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class DeviceSummaryDto(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    @SerialName("last_location") val lastLocation: LastLocationDto?,
)

@Serializable
data class ParentLocationPointDto(
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class SosEventDto(
    @SerialName("event_id") val eventId: String,
    val child: String,
    val lat: Double,
    val lon: Double,
    @SerialName("created_at") val createdAt: String,
    val acknowledged: Boolean,
)

@Serializable
data class AckSosRequest(
    @SerialName("event_id") val eventId: String,
)

/** Maximum span accepted by `/locations`; keeps one history query cheap. */
const val MAX_HISTORY_RANGE_MS = 31L * 24 * 60 * 60 * 1000

/** Maximum points returned by one `/locations` request. */
const val MAX_HISTORY_POINTS = 100_000

/**
 * Parent-side read endpoints: the family overview, location history and the SOS feed.
 *
 * Every route starts with [requireParent], so a child token never reaches the data layer.
 * A device id passed to `/locations` is additionally checked against the parent's family:
 * an unknown id, a foreign child and the parent itself all look the same (403).
 */
fun Route.parentReads(database: Database) {
    get("/devices") {
        val parent = call.requireParent(database) ?: return@get
        val children = database.query { childrenWithLastLocation(it, parent.familyId) }
        call.respond(children.map { it.toDto() })
    }

    get("/locations") {
        val parent = call.requireParent(database) ?: return@get

        val deviceParam = call.request.queryParameters["device"]
        val fromParam = call.request.queryParameters["from"]
        val toParam = call.request.queryParameters["to"]
        if (deviceParam == null || fromParam == null || toParam == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid query"))
            return@get
        }

        val deviceId = deviceParam.toUuidOrNull()
        val from = fromParam.toEpochMillisOrNull()
        val to = toParam.toEpochMillisOrNull()
        if (deviceId == null || from == null || to == null || from > to || exceedsMaxRange(from, to)) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid query"))
            return@get
        }

        // Unknown, foreign and parent devices are indistinguishable on purpose.
        val allowed = database.query { childBelongsToFamily(it, deviceId, parent.familyId) }
        if (!allowed) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            return@get
        }

        // Fetch one extra row: if it is present the response would be too large and is
        // refused before any of it is serialized.
        val points = database.query {
            locationHistory(it, deviceId, from, to, MAX_HISTORY_POINTS + 1)
        }
        if (points.size > MAX_HISTORY_POINTS) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("range too large"))
            return@get
        }
        call.respond(points.map { it.toDto() })
    }

    get("/sos") {
        val parent = call.requireParent(database) ?: return@get

        val sinceParam = call.request.queryParameters["since"]
        val since = if (sinceParam == null) 0L else sinceParam.toEpochMillisOrNull()
        if (since == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid query"))
            return@get
        }

        val events = database.query { listSos(it, parent.familyId, since) }
        call.respond(events.map { it.toDto() })
    }

    post("/sos/ack") {
        val parent = call.requireParent(database) ?: return@post

        val request = try {
            call.receive<AckSosRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        } catch (_: SerializationException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        }

        val eventId = request.eventId.toUuidOrNull()
        if (eventId == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        }

        // A missing event and an event of another family both answer 404, so the endpoint
        // does not reveal which ids exist. Re-acknowledging is idempotent.
        val acknowledged = database.query { ackSos(it, eventId, parent.familyId) }
        if (!acknowledged) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun DeviceSummary.toDto(): DeviceSummaryDto = DeviceSummaryDto(
    deviceId = deviceId.toString(),
    name = name,
    lastLocation = lastLocation?.let { LastLocationDto(it.lat, it.lon, it.recordedAt.toIsoString()) },
)

private fun PointIn.toDto(): ParentLocationPointDto = ParentLocationPointDto(
    lat = lat,
    lon = lon,
    accuracy = accuracy,
    speed = speed,
    recordedAt = recordedAt.toIsoString(),
)

private fun SosEvent.toDto(): SosEventDto = SosEventDto(
    eventId = id.toString(),
    child = childId.toString(),
    lat = lat,
    lon = lon,
    createdAt = createdAt.toIsoString(),
    acknowledged = acknowledged,
)

private fun Long.toIsoString(): String = Instant.ofEpochMilli(this).toString()

/**
 * True when `to - from` exceeds [MAX_HISTORY_RANGE_MS]. Uses [Math.subtractExact] so a
 * difference that itself overflows a Long is treated as too large rather than wrapping.
 */
private fun exceedsMaxRange(from: Long, to: Long): Boolean = try {
    Math.subtractExact(to, from) > MAX_HISTORY_RANGE_MS
} catch (_: ArithmeticException) {
    true
}

private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: IllegalArgumentException) {
    null
}

private fun String.toEpochMillisOrNull(): Long? = try {
    OffsetDateTime.parse(this).toInstant().toEpochMilli()
} catch (_: DateTimeException) {
    null
} catch (_: ArithmeticException) {
    // A syntactically valid year outside the Long epoch-millisecond range overflows.
    null
}
