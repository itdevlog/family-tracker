package ru.family.tracker.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
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
import java.net.URI
import java.net.URISyntaxException

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
 *
 * Bearer authentication is added explicitly per authenticated endpoint, never globally:
 * `/pair/parent` and `/pair/child` must not carry an Authorization header even when an
 * old token is still stored.
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
        return client.post("/pair/code") {
            withAuth()
        }.bodyOrThrow()
    }

    suspend fun redeemChildCode(code: String, name: String): ChildRegistration {
        resolvedBaseUrl()
        return client.post("/pair/child") {
            setBody(ChildPairRequest(code, name))
        }.bodyOrThrow()
    }

    /** Uploads one batch; emptiness and the server's 100-point bound fail before the network. */
    suspend fun uploadPoints(points: List<PointIn>) {
        resolvedBaseUrl()
        require(points.isNotEmpty()) { "points must not be empty" }
        require(points.size <= MAX_POINTS_PER_BATCH) { "at most $MAX_POINTS_PER_BATCH points per batch" }

        client.post("/locations") {
            withAuth()
            setBody(LocationsRequest(points.map { it.toWire() }))
        }.ensureSuccess()
    }

    suspend fun devices(): List<DeviceSummary> {
        resolvedBaseUrl()
        return client.get("/devices") {
            withAuth()
        }.bodyOrThrow()
    }

    suspend fun history(deviceId: String, fromIso: String, toIso: String): List<PointIn> {
        resolvedBaseUrl()
        return client.get("/locations") {
            withAuth()
            parameter("device", deviceId)
            parameter("from", fromIso)
            parameter("to", toIso)
        }.bodyOrThrow<List<LocationPoint>>().map { it.toPointIn() }
    }

    suspend fun sendSos(lat: Double, lon: Double) {
        resolvedBaseUrl()
        client.post("/sos") {
            withAuth()
            setBody(SosRequest(lat, lon))
        }.ensureSuccess()
    }

    suspend fun ackSos(eventId: String) {
        resolvedBaseUrl()
        client.post("/sos/ack") {
            withAuth()
            setBody(AckSosRequest(eventId))
        }.ensureSuccess()
    }

    /**
     * Lists SOS events after [sinceIso], an ISO-8601 timestamp such as
     * `2026-10-20T10:00:00Z`.
     *
     * When [sinceIso] is null the `since` parameter is not sent at all, which the server
     * treats as "since the beginning". The wire value is an ISO-8601 timestamp, not an
     * epoch number.
     */
    suspend fun sosList(sinceIso: String? = null): List<SosEvent> {
        resolvedBaseUrl()
        return client.get("/sos") {
            withAuth()
            sinceIso?.let { parameter("since", it) }
        }.bodyOrThrow()
    }

    private fun HttpRequestBuilder.withAuth() {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
    }

    private fun resolvedBaseUrl(): String = normalizeBaseUrl(baseUrlProvider())

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

/**
 * Validates and normalizes a configured server URL.
 *
 * Only absolute `https` URLs with a host are accepted; a lone trailing slash is dropped.
 * Plain `http` is rejected: targetSdk 35 blocks cleartext traffic anyway, so failing here
 * produces an actionable error instead of a confusing transport failure later.
 * A path (other than `/`), query or fragment is rejected rather than silently
 * discarded, so a misconfigured base URL fails loudly instead of hitting the wrong route.
 */
internal fun normalizeBaseUrl(raw: String?): String {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) throw IllegalStateException("baseUrl is not configured")

    val uri = try {
        URI(trimmed)
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("baseUrl is not a valid URL: $trimmed", error)
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "https") {
        throw IllegalArgumentException("baseUrl must use https: $trimmed")
    }
    if (uri.host.isNullOrBlank()) {
        throw IllegalArgumentException("baseUrl must contain a host: $trimmed")
    }
    if (uri.rawQuery != null || uri.rawFragment != null) {
        throw IllegalArgumentException("baseUrl must not contain a query or fragment: $trimmed")
    }
    val path = uri.rawPath ?: ""
    if (path.isNotEmpty() && path != "/") {
        throw IllegalArgumentException("baseUrl must not contain a path: $trimmed")
    }

    return trimmed.removeSuffix("/")
}
