package familytracker.ntfy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Push-notification sink; separated from the HTTP details so routes can be tested with a fake. */
fun interface NtfyPublisher {
    fun publish(topic: String, title: String, body: String, priority: Int)
}

@Serializable
private data class NtfyMessage(
    val topic: String,
    val title: String,
    val message: String,
    val priority: Int,
)

/**
 * Publishes messages to an ntfy server with a plain JSON POST to [baseUrl].
 *
 * A back-pressure-free fire-and-forget call is intentional: the SOS route treats a failed
 * notification as best effort, because the event is already stored and broadcast live.
 */
class HttpNtfyPublisher(
    private val baseUrl: String,
    private val token: String,
) : NtfyPublisher {
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    override fun publish(topic: String, title: String, body: String, priority: Int) {
        // Checked here rather than in the constructor so an unconfigured server still starts.
        if (baseUrl.isBlank()) throw IllegalStateException("ntfy url is not configured")

        val payload = Json.encodeToString(NtfyMessage(topic, title, body, priority))
        val request = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
            .apply {
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
            }
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw IOException("ntfy answered ${response.statusCode()}")
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
