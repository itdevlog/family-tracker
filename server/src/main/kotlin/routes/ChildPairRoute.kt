package familytracker.routes

import familytracker.ErrorResponse
import familytracker.auth.query
import familytracker.db.Database
import familytracker.db.RedeemResult
import familytracker.db.redeemChildCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.sql.SQLException

@Serializable
data class ChildPairRequest(val code: String, val name: String)

@Serializable
data class ChildPairResponse(
    @SerialName("device_id") val deviceId: String,
    val token: String,
)

/**
 * Sliding-window counter of failed pairing attempts per client key.
 *
 * Only failures are recorded: a successful pairing must not consume the budget, so a
 * parent who mistypes a code a few times can still pair.
 */
class RateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = mutableMapOf<String, ArrayDeque<Long>>()

    fun isBlocked(key: String): Boolean = synchronized(failures) {
        prune(key)
        (failures[key]?.size ?: 0) >= maxFailures
    }

    fun recordFailure(key: String) {
        synchronized(failures) {
            prune(key)
            failures.getOrPut(key) { ArrayDeque() }.addLast(clock())
        }
    }

    private fun prune(key: String) {
        val timestamps = failures[key] ?: return
        val cutoff = clock() - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
            timestamps.removeFirst()
        }
        if (timestamps.isEmpty()) failures.remove(key)
    }
}

private val SIX_DIGITS = Regex("\\d{6}")

/**
 * Child-side pairing: redeems a six digit code without a bearer token.
 *
 * Guessing is throttled per client IP: [RateLimiter] allows five failed attempts in a
 * sliding minute before answering 429. A valid pairing does not count against the limit.
 */
fun Route.childPairRoute(database: Database, rateLimiter: RateLimiter) {
    post("/pair/child") {
        val clientKey = call.clientKey()
        if (rateLimiter.isBlocked(clientKey)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many attempts"))
            return@post
        }

        val request = try {
            call.receive<ChildPairRequest>()
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

        val code = request.code
        val name = request.name.trim()
        if (!code.matches(SIX_DIGITS) || name.isEmpty()) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        }

        // A lost race between two redemptions surfaces as a constraint failure, not a
        // server bug: ask the client to retry instead of returning 500.
        val result = try {
            database.query { conn -> redeemChildCode(conn, code, name) }
        } catch (_: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("try again"))
            return@post
        }

        when (result) {
            is RedeemResult.Redeemed -> call.respond(
                ChildPairResponse(
                    deviceId = result.registration.deviceId.toString(),
                    token = result.registration.token,
                ),
            )

            RedeemResult.Used -> {
                rateLimiter.recordFailure(clientKey)
                call.respond(HttpStatusCode.Gone, ErrorResponse("code used"))
            }

            RedeemResult.Invalid, RedeemResult.Expired -> {
                rateLimiter.recordFailure(clientKey)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("invalid code"))
            }
        }
    }
}

/**
 * Client identity for the rate limit: the first hop in `X-Forwarded-For` when a proxy
 * supplies it, otherwise the socket peer.
 */
private fun ApplicationCall.clientKey(): String =
    request.headers[HttpHeaders.XForwardedFor]
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost
