package familytracker.routes

import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.auth.requireChild
import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.db.insertLocationsReturningInserted
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.UUID

/** Upper bound for one upload batch; keeps a single request cheap to validate and store. */
const val MAX_POINTS_PER_BATCH = 100

@Serializable
data class LocationPointDto(
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class LocationsRequest(
    /**
     * Accepted for compatibility and deliberately ignored: the device always comes from the
     * bearer token, so a child can never write points for another device.
     */
    @SerialName("device_id") val deviceId: String? = null,
    val points: List<LocationPointDto>,
)

/**
 * Child-side location upload: `POST /locations` with a JSON batch.
 *
 * The device is taken from the bearer token only; a `device_id` in the body is ignored,
 * so a child can never write points for another device.
 *
 * [onInserted] is invoked once per request and only with samples that were actually
 * inserted, so a replayed batch (deduplicated on `(device_id, recorded_at)`) emits no
 * duplicate event. Task 6 wires WebSocket broadcasting through this hook.
 */
fun Route.locationsRoutes(
    database: Database,
    onInserted: (familyId: UUID, deviceId: UUID, points: List<PointIn>) -> Unit = { _, _, _ -> },
) {
    post("/locations") {
        val child = call.requireChild(database) ?: return@post

        val request = try {
            call.receive<LocationsRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        } catch (_: SerializationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        }

        if (request.points.isEmpty()) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        }
        if (request.points.size > MAX_POINTS_PER_BATCH) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too many points"))
            return@post
        }

        val parsed = request.points.map { it.toPointInOrNull() }
        if (parsed.any { it == null }) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        }
        val points = parsed.filterNotNull()

        val inserted = database.query { conn ->
            insertLocationsReturningInserted(conn, child.id, points)
        }
        if (inserted.isNotEmpty()) {
            onInserted(child.familyId, child.id, inserted)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun LocationPointDto.toPointInOrNull(): PointIn? {
    // NaN and infinities fail these range checks as well.
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val epochMillis = try {
        OffsetDateTime.parse(recordedAt).toInstant().toEpochMilli()
    } catch (_: DateTimeException) {
        // DateTimeParseException (the usual malformed timestamp) is a DateTimeException.
        return null
    } catch (_: ArithmeticException) {
        // A syntactically valid year outside the Long epoch-millisecond range overflows.
        return null
    }
    return PointIn(lat, lon, accuracy, speed, epochMillis)
}
