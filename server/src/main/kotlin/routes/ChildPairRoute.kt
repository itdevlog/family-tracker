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
 * Sliding-window budget of pairing attempts per client key.
 *
 * An attempt is reserved before touching the database with [tryAcquire]; a request that
 * turns out to be successful (or rejected for a non-brute-force reason) calls [refund],
 * so only real failed guesses consume the budget.
 *
 * [maxKeys] bounds memory under a flood of unique keys: expired entries are dropped first,
 * then the entries with the oldest [Attempt.lastSeen].
 */
class RateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Attempt(val timestamps: ArrayDeque<Long>, var lastSeen: Long)

    private val failures = mutableMapOf<String, Attempt>()

    /**
     * Atomically prunes, checks and records one attempt. Returns false when [key] already
     * spent its whole budget inside the current window.
     */
    fun tryAcquire(key: String): Boolean = synchronized(failures) {
        val now = clock()
        val attempt = failures[key]
        if (attempt == null) {
            makeRoom(now)
            failures[key] = Attempt(ArrayDeque<Long>().apply { addLast(now) }, now)
            return@synchronized true
        }

        pruneKey(attempt, now)
        attempt.lastSeen = now
        if (attempt.timestamps.size >= maxFailures) {
            return@synchronized false
        }
        attempt.timestamps.addLast(now)
        true
    }

    /** Releases the reservation made by the last [tryAcquire] for [key]. */
    fun refund(key: String) {
        synchronized(failures) {
            val attempt = failures[key] ?: return
            if (attempt.timestamps.isNotEmpty()) attempt.timestamps.removeLast()
            if (attempt.timestamps.isEmpty()) failures.remove(key)
        }
    }

    internal fun trackedKeys(): Set<String> = synchronized(failures) { failures.keys.toSet() }

    private fun pruneKey(attempt: Attempt, now: Long) {
        val cutoff = now - windowMs
        while (attempt.timestamps.isNotEmpty() && attempt.timestamps.first() <= cutoff) {
            attempt.timestamps.removeFirst()
        }
    }

    private fun makeRoom(now: Long) {
        if (failures.size < maxKeys) return
        pruneExpired(now)
        while (failures.size >= maxKeys) {
            val oldest = failures.entries.minByOrNull { it.value.lastSeen } ?: break
            failures.remove(oldest.key)
        }
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - windowMs
        val iterator = failures.entries.iterator()
        while (iterator.hasNext()) {
            val attempt = iterator.next().value
            while (attempt.timestamps.isNotEmpty() && attempt.timestamps.first() <= cutoff) {
                attempt.timestamps.removeFirst()
            }
            if (attempt.timestamps.isEmpty()) iterator.remove()
        }
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

        // Reserve before any work: the reservation is refunded below unless the code was
        // actually wrong, so concurrent requests cannot all slip through the check.
        if (!rateLimiter.tryAcquire(clientKey)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too many attempts"))
            return@post
        }

        val request = try {
            call.receive<ChildPairRequest>()
        } catch (_: BadRequestException) {
            rateLimiter.refund(clientKey)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        } catch (_: ContentTransformationException) {
            rateLimiter.refund(clientKey)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        } catch (_: SerializationException) {
            rateLimiter.refund(clientKey)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid request"))
            return@post
        }

        val code = request.code
        val name = request.name.trim()
        if (!code.matches(SIX_DIGITS) || name.isEmpty()) {
            rateLimiter.refund(clientKey)
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid request"))
            return@post
        }

        // A lost race between two redemptions surfaces as a constraint failure, not a
        // server bug: ask the client to retry instead of returning 500.
        val result = try {
            database.query { conn -> redeemChildCode(conn, code, name) }
        } catch (_: SQLException) {
            rateLimiter.refund(clientKey)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("try again"))
            return@post
        }

        when (result) {
            is RedeemResult.Redeemed -> {
                rateLimiter.refund(clientKey)
                call.respond(
                    ChildPairResponse(
                        deviceId = result.registration.deviceId.toString(),
                        token = result.registration.token,
                    ),
                )
            }

            RedeemResult.Used -> call.respond(HttpStatusCode.Gone, ErrorResponse("code used"))

            RedeemResult.Invalid, RedeemResult.Expired ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("invalid code"))
        }
    }
}

/**
 * Client identity for the rate limit.
 *
 * When a trusted reverse proxy (Caddy) supplies `X-Forwarded-For`, it appends the address
 * of the hop it actually observed, so only the rightmost element is trustworthy. The
 * leftmost elements are written by the client and are trivially spoofable.
 */
private fun ApplicationCall.clientKey(): String {
    val forwarded = request.headers[HttpHeaders.XForwardedFor]
    if (forwarded != null) {
        val trustedHop = forwarded.substringAfterLast(',').trim()
        if (trustedHop.isNotEmpty()) return trustedHop
    }
    return request.origin.remoteHost
}
