package familytracker.routes

import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.auth.requireChild
import familytracker.db.Database
import familytracker.db.insertSos
import familytracker.live.LiveBroadcaster
import familytracker.ntfy.NtfyPublisher
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("familytracker.routes.SosRoute")

@Serializable
data class SosRequest(
    val lat: Double,
    val lon: Double,
)

/**
 * Child-side SOS report: `POST /sos` with `{"lat": ..., "lon": ...}`.
 *
 * The event is stored first, then pushed to the family's live sockets, and only then
 * forwarded to ntfy. The response is 204 once the event is stored, even when the push
 * notification fails: the emergency is already persisted and visible to parents.
 *
 * Repeats from the same child within [cooldownMs] are acknowledged with 204 without
 * storing, broadcasting or publishing anything. This absorbs client retry loops and
 * prevents a stolen child token from spamming the family. The cooldown state is
 * in-memory and per application instance, which is enough for the single-family server.
 */
fun Route.sosRoute(
    database: Database,
    broadcaster: LiveBroadcaster,
    ntfy: NtfyPublisher,
    cooldownMs: Long = 30_000,
    now: () -> Long = System::currentTimeMillis,
) {
    val lastSosAt = ConcurrentHashMap<UUID, Long>()

    post("/sos") {
        val child = call.requireChild(database) ?: return@post

        // Check before reading the body: a retrying client should not even be parsed.
        val previous = lastSosAt[child.id]
        if (previous != null && now() - previous < cooldownMs) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }

        val request = try {
            call.receive<SosRequest>()
        } catch (_: BadRequestException) {
            // A body whose coordinates cannot be decoded (including NaN/Infinity literals,
            // which the JSON decoder rejects) is reported like any other invalid point.
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        } catch (_: SerializationException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        }

        if (!request.lat.isFinite() || !request.lon.isFinite() ||
            request.lat !in -90.0..90.0 || request.lon !in -180.0..180.0
        ) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid point"))
            return@post
        }

        // Database failures intentionally propagate: without a stored event there is
        // nothing to acknowledge, so the child must see a 5xx and retry.
        val event = database.query { conn -> insertSos(conn, child.id, request.lat, request.lon) }
        // Start the cooldown only after the event is durable; a failed insert must not
        // silence the child's retry.
        lastSosAt[child.id] = now()
        broadcaster.broadcastSos(child.familyId, child.id, event.id, event.lat, event.lon, event.createdAt)

        val topic = "family-${child.familyId}-sos"
        try {
            // The publisher is blocking (java.net.http), so keep it off the request threads.
            withContext(Dispatchers.IO) {
                ntfy.publish(
                    topic = topic,
                    title = "SOS",
                    body = "Ребёнок ${child.name} просит помощи",
                    priority = 5,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Best effort: the event is already in the database and was already pushed to
            // live sockets, so a failed notification must not turn the report into an error.
            // Log only the topic, child id and error type: never the token or coordinates.
            logger.warn(
                "sos notification failed: topic={} child={} error={}",
                topic,
                child.id,
                error.javaClass.simpleName,
            )
        }

        call.respond(HttpStatusCode.NoContent)
    }
}
