package ru.family.tracker.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Upper bound accepted by `POST /locations`; rejected locally to spare the round trip. */
private const val MAX_POINTS_PER_BATCH = 100

/** A non-2xx server answer, including the server's `error` field when it parses. */
class ApiException(val status: Int, val serverError: String?) :
    Exception("HTTP $status: ${serverError ?: ""}")

/**
 * Thin Ktor client over the family tracker HTTP API.
 *
 * The base URL and token are read from the providers on every request, so pairing screens
 * can save them just before calling a method and logout takes effect immediately.
 */
class ApiClient(
    private val baseUrlProvider: () -> String?,
    private val tokenProvider: () -> String?,
    engine: HttpClientEngine? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient(OkHttp)).config {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
        defaultRequest {
            url(resolvedBaseUrl())
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun registerParent(name: String): ParentRegistration {
        resolvedBaseUrl()
        return client.post("/pair/parent") {
            setBody(ParentNameRequest(name))
        }.bodyOrThrow()
    }

    suspend fun createPairCode(): PairCode {
        resolvedBaseUrl()
        return client.post("/pair/code").bodyOrThrow()
    }

    suspend fun redeemChildCode(code: String, name: String): ChildRegistration {
        resolvedBaseUrl()
        return client.post("/pair/child") {
            setBody(ChildPairRequest(code, name))
        }.bodyOrThrow()
    }

    /** Uploads one batch; emptiness and the server's 100-point bound fail before the network. */
    suspend fun uploadPoints(points: List<LocationPoint>) {
        resolvedBaseUrl()
        require(points.isNotEmpty()) { "points must not be empty" }
        require(points.size <= MAX_POINTS_PER_BATCH) { "at most $MAX_POINTS_PER_BATCH points per batch" }

        client.post("/locations") {
            setBody(LocationsRequest(points))
        }.ensureSuccess()
    }

    suspend fun devices(): List<DeviceSummary> {
        resolvedBaseUrl()
        return client.get("/devices").bodyOrThrow()
    }

    suspend fun history(deviceId: String, fromIso: String, toIso: String): List<HistoryPoint> {
        resolvedBaseUrl()
        return client.get("/locations") {
            parameter("device", deviceId)
            parameter("from", fromIso)
            parameter("to", toIso)
        }.bodyOrThrow()
    }

    suspend fun sendSos(lat: Double, lon: Double) {
        resolvedBaseUrl()
        client.post("/sos") {
            setBody(SosRequest(lat, lon))
        }.ensureSuccess()
    }

    suspend fun ackSos(eventId: String) {
        resolvedBaseUrl()
        client.post("/sos/ack") {
            setBody(AckSosRequest(eventId))
        }.ensureSuccess()
    }

    /** [sinceMillis] is epoch milliseconds, as parsed by the server. */
    suspend fun sosList(sinceMillis: Long = 0): List<SosEvent> {
        resolvedBaseUrl()
        return client.get("/sos") {
            parameter("since", sinceMillis)
        }.bodyOrThrow()
    }

    private fun resolvedBaseUrl(): String =
        baseUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("baseUrl is not configured")

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (!status.isSuccess()) throw toApiException()
        return body()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (!status.isSuccess()) throw toApiException()
    }

    private suspend fun HttpResponse.toApiException(): ApiException {
        val serverError = try {
            body<ApiError>().error
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        return ApiException(status.value, serverError)
    }
}
