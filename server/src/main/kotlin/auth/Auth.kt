package familytracker.auth

import familytracker.ErrorResponse
import familytracker.db.AuthDevice
import familytracker.db.Database
import familytracker.db.findDeviceByToken
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

const val ROLE_PARENT = "parent"
const val ROLE_CHILD = "child"

/** Authenticated device of the current call; populated by [requireAuth] / [requireParent]. */
val AuthDeviceKey = AttributeKey<AuthDevice>("familytracker.auth.device")

val ApplicationCall.authDevice: AuthDevice?
    get() = attributes.getOrNull(AuthDeviceKey)

/**
 * Runs a blocking JDBC block on the IO dispatcher, keeping Ktor's request threads free.
 * Route handlers should use this instead of [Database.withConnection] directly.
 */
suspend fun <T> Database.query(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) { withConnection(block) }

/**
 * Resolves `Authorization: Bearer <token>` to a device and stores it in [AuthDeviceKey].
 * Responds 401 and returns null when the header is missing or the token is unknown.
 */
suspend fun ApplicationCall.requireAuth(database: Database): AuthDevice? {
    val token = bearerToken()
    val device = token?.let { raw -> database.query { findDeviceByToken(it, raw) } }
    if (device == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        return null
    }
    attributes.put(AuthDeviceKey, device)
    return device
}

/**
 * Like [requireAuth], but additionally requires the parent role.
 * Responds 403 and returns null for any other role.
 */
suspend fun ApplicationCall.requireParent(database: Database): AuthDevice? {
    val device = requireAuth(database) ?: return null
    if (device.role != ROLE_PARENT) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        return null
    }
    return device
}

private const val BEARER_PREFIX = "Bearer "

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
    return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
}
