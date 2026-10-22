package ru.family.tracker.data.api

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Domain location sample used by storage, upload and history.
 *
 * The wire format carries ISO-8601 strings; inside the app time is a single epoch
 * millisecond value so Room and the upload queue never handle timestamp strings.
 */
data class PointIn(
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val recordedAtMillis: Long,
)

/** Converts a wire point to the domain model, rejecting malformed timestamps. */
fun LocationPoint.toPointIn(): PointIn {
    val millis = try {
        OffsetDateTime.parse(recordedAt).toInstant().toEpochMilli()
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("recorded_at is not an ISO-8601 timestamp: $recordedAt", error)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("recorded_at is out of epoch-millisecond range: $recordedAt", error)
    }
    return PointIn(lat, lon, accuracy, speed, millis)
}

/** Converts a domain point to the wire format with an ISO-8601 UTC timestamp. */
fun PointIn.toWire(): LocationPoint = LocationPoint(
    lat = lat,
    lon = lon,
    accuracy = accuracy,
    speed = speed,
    recordedAt = Instant.ofEpochMilli(recordedAtMillis).toString(),
)
