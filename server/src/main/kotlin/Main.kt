package familytracker

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

data class Config(val dbPath: String, val ntfyUrl: String, val ntfyToken: String) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
            dbPath = env["DB_PATH"] ?: "family-tracker.db",
            ntfyUrl = env["NTFY_URL"] ?: "",
            ntfyToken = env["NTFY_TOKEN"] ?: "",
        )
    }
}

fun Application.module(config: Config) {
    routing {
        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(Config.fromEnv())
    }.start(wait = true)
}
