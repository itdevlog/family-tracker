package ru.family.tracker.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs of the family tracker server, mirroring
 * `server/src/main/kotlin/routes` field by field.
 *
 * Timestamps are ISO-8601 strings (`Instant.toString()` on the client); the server
 * accepts any `OffsetDateTime` and answers with UTC instants.
 */

@Serializable
data class NtfyInfo(val url: String, val topic: String, val token: String)

@Serializable
data class ParentRegistration(
    @SerialName("device_id") val deviceId: String,
    @SerialName("family_id") val familyId: String,
    val token: String,
    val ntfy: NtfyInfo,
)

@Serializable
data class PairCode(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ChildRegistration(
    @SerialName("device_id") val deviceId: String,
    val token: String,
)

@Serializable
data class LocationPoint(
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    @SerialName("recorded_at") val recordedAt: String,
)

/** `Points` history and upload share the same JSON shape. */
typealias HistoryPoint = LocationPoint

/** Upload batch; `device_id` is deliberately absent because the server takes it from the token. */
@Serializable
data class LocationsRequest(val points: List<LocationPoint>)

@Serializable
data class DeviceSummary(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    @SerialName("last_location") val lastLocation: LastLocation? = null,
)

@Serializable
data class LastLocation(
    val lat: Double,
    val lon: Double,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class SosEvent(
    @SerialName("event_id") val eventId: String,
    val child: String,
    val lat: Double,
    val lon: Double,
    @SerialName("created_at") val createdAt: String,
    val acknowledged: Boolean,
)

@Serializable
data class AckSosRequest(@SerialName("event_id") val eventId: String)

@Serializable
data class ApiError(val error: String? = null)

@Serializable
internal data class ParentNameRequest(val name: String)

@Serializable
internal data class ChildPairRequest(val code: String, val name: String)

@Serializable
internal data class SosRequest(val lat: Double, val lon: Double)
