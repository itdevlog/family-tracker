package familytracker

import familytracker.db.Database
import familytracker.db.PointIn
import familytracker.live.LiveBroadcaster
import familytracker.ntfy.HttpNtfyPublisher
import familytracker.ntfy.NtfyPublisher
import familytracker.routes.RateLimiter
import familytracker.routes.childPairRoute
import familytracker.routes.liveRoutes
import familytracker.routes.locationsRoutes
import familytracker.routes.pairRoutes
import familytracker.routes.parentReads
import familytracker.routes.sosRoute
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

data class Config(
    val dbPath: String,
    val ntfyUrl: String,
    val ntfyToken: String,
    /**
     * Public ntfy base URL handed to devices in the pairing response. Defaults to
     * [ntfyUrl] so existing deployments and tests keep their behaviour.
     */
    val ntfyPublicUrl: String = ntfyUrl,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val ntfyUrl = env["NTFY_URL"] ?: ""
            return Config(
                dbPath = env["DB_PATH"] ?: "family-tracker.db",
                ntfyUrl = ntfyUrl,
                ntfyToken = env["NTFY_TOKEN"] ?: "",
                ntfyPublicUrl = env["NTFY_PUBLIC_URL"] ?: ntfyUrl,
            )
        }
    }
}

fun Application.module(
    config: Config,
    onLocationsInserted: (familyId: UUID, deviceId: UUID, points: List<PointIn>) -> Unit =
        { _, _, _ -> },
    ntfy: NtfyPublisher = HttpNtfyPublisher(config.ntfyUrl, config.ntfyToken),
    database: Database = Database(config.dbPath),
    sosCooldownMs: Long = 30_000,
    now: () -> Long = System::currentTimeMillis,
) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets)

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
        sosRoute(database, broadcaster, ntfy, sosCooldownMs, now)
        parentReads(database)
    }
}

fun main() {
    val config = Config.fromEnv()
    // The Database constructor applies the schema, so the file is ready before the server binds.
    val database = Database(config.dbPath)
    scheduleRetention(database)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(config, database = database)
    }.start(wait = true)
}
