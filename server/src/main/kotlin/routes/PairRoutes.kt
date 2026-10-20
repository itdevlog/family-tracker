package familytracker.routes

import familytracker.Config
import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.auth.requireParent
import familytracker.db.Database
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
        val request = call.receive<ParentPairRequest>()

        // A null result means the single-family slot is already taken. The Dao also guards
        // this atomically and raises IllegalStateException when two requests race.
        val registration = try {
            database.query { conn ->
                if (familyExists(conn)) null else createFamilyWithParent(conn, request.name)
            }
        } catch (_: IllegalStateException) {
            null
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
                        url = config.ntfyUrl,
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
