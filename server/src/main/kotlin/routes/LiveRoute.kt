package familytracker.routes

import familytracker.auth.ROLE_PARENT
import familytracker.auth.query
import familytracker.db.Database
import familytracker.db.findDeviceByToken
import familytracker.live.LiveBroadcaster
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

/** Close code used when the handshake carries no valid parent token; 4xxx is app-private. */
const val WS_UNAUTHORIZED: Short = 4401

/**
 * Live event stream for parents: `GET /live?token=<device token>` upgraded to WebSocket.
 *
 * The token is passed in the query because browsers cannot set an `Authorization` header
 * on a WebSocket handshake. It is hashed and looked up exactly like the HTTP bearer token,
 * and only the parent role is accepted. The session stays registered in [broadcaster] for
 * the lifetime of the connection, in the room of its own family only.
 */
fun Route.liveRoutes(database: Database, broadcaster: LiveBroadcaster) {
    webSocket("/live") {
        val token = call.request.queryParameters["token"]
        val device = token?.let { raw -> database.query { findDeviceByToken(it, raw) } }

        if (device == null || device.role != ROLE_PARENT) {
            close(CloseReason(WS_UNAUTHORIZED, "unauthorized"))
            return@webSocket
        }

        broadcaster.register(device.familyId, this)
        try {
            // The client has nothing to say yet; reading keeps the connection open and
            // notices the close frame (or a dropped peer) so the session can be released.
            for (frame in incoming) {
                // Ignore application frames until a client-to-server message is specified.
            }
        } finally {
            broadcaster.unregister(device.familyId, this)
        }
    }
}
