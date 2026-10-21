package familytracker.routes

import familytracker.Config
import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.auth.requireParent
import familytracker.db.Database
import familytracker.db.FamilyAlreadyExistsException
import familytracker.db.createFamilyWithParent
import familytracker.db.familyExists
import familytracker.db.storePairCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ParentPairRequest(val name: String)

@Serializable
data class NtfyInfo(val url: String, val topic: String, val token: String)

@Serializable
data class ParentPairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("family_id") val familyId: String,
    val token: String,
    val ntfy: NtfyInfo,
)

@Serializable
data class PairCodeResponse(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

/**
 * Family bootstrap and parent-side pairing endpoints.
 *
 * `/pair/parent` is deliberately unauthenticated: it is the very first request of an
 * installation and is only allowed while no family exists yet (spec §3, "Шаг 0").
 */
fun Route.pairRoutes(database: Database, config: Config) {
    post("/pair/parent") {
        // Check the single-family slot before touching the body: a repeated registration
        // must be 409 even when the client sends a missing or malformed body.
        if (database.query { familyExists(it) }) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("family exists"))
            return@post
        }

        val request = call.receive<ParentPairRequest>()

        // The Dao guards the single-family slot atomically; only its dedicated conflict
        // exception is translated into a 409. Any other failure must surface unchanged.
        val registration = ignoreFamilyAlreadyExists {
            database.query { conn -> createFamilyWithParent(conn, request.name) }
        }

        if (registration == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("family exists"))
        } else {
            call.respond(
                ParentPairResponse(
                    deviceId = registration.deviceId.toString(),
                    familyId = registration.familyId.toString(),
                    token = registration.token,
                    ntfy = NtfyInfo(
                        url = config.ntfyPublicUrl,
                        topic = registration.ntfyTopic,
                        token = config.ntfyToken,
                    ),
                ),
            )
        }
    }

    post("/pair/code") {
        val parent = call.requireParent(database) ?: return@post
        val pairCode = database.query { storePairCode(it, parent.familyId) }
        call.respond(
            PairCodeResponse(
                code = pairCode.code,
                expiresAt = Instant.ofEpochMilli(pairCode.expiresAt).toString(),
            ),
        )
    }
}

/**
 * Runs [block], returning null only when the Dao reports the single-family conflict.
 * Every other [IllegalStateException] (and any other throwable) propagates.
 */
internal suspend fun <T> ignoreFamilyAlreadyExists(block: suspend () -> T): T? =
    try {
        block()
    } catch (_: FamilyAlreadyExistsException) {
        null
    }
