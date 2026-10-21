package familytracker

import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.live.LiveBroadcaster
import familytracker.routes.RateLimiter
import familytracker.routes.childPairRoute
import familytracker.routes.liveRoutes
import familytracker.routes.locationsRoutes
import familytracker.routes.pairRoutes
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.util.UUID

data class Config(val dbPath: String, val ntfyUrl: String, val ntfyToken: String) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
            dbPath = env["DB_PATH"] ?: "family-tracker.db",
            ntfyUrl = env["NTFY_URL"] ?: "",
            ntfyToken = env["NTFY_TOKEN"] ?: "",
        )
    }
}

fun Application.module(
    config: Config,
    onLocationsInserted: (familyId: UUID, deviceId: UUID, points: List<PointIn>) -> Unit =
        { _, _, _ -> },
) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets)

    // The Database constructor applies the schema, so the file is ready before routing starts.
    val database = Database(config.dbPath)

    // One limiter per application instance: failures are shared across requests but do
    // not leak between tests or restarts.
    val rateLimiter = RateLimiter()

    // Bound to the application lifetime: pending pushes are cancelled on shutdown.
    val broadcaster = LiveBroadcaster(this)

    routing {
        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }
        pairRoutes(database, config)
        childPairRoute(database, rateLimiter)
        locationsRoutes(database) { familyId, deviceId, points ->
            onLocationsInserted(familyId, deviceId, points)
            broadcaster.broadcastLocations(familyId, deviceId, points)
        }
        liveRoutes(database, broadcaster)
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(Config.fromEnv())
    }.start(wait = true)
}
