package ru.family.tracker.history

import ru.family.tracker.data.api.PointIn
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A period of the day spent in roughly one place.
 *
 * [lat]/[lon] are the arithmetic mean of the points in the cluster; [durationMillis] is
 * `endMillis - startMillis` (so it includes the first point's instant but not an implicit
 * trailing sample).
 */
data class StopCluster(
    val lat: Double,
    val lon: Double,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val pointCount: Int,
)

/** Default stop threshold from the spec: five minutes within a 50 m radius. */
const val DEFAULT_STOP_DURATION_MS = 5L * 60 * 1000
const val DEFAULT_STOP_RADIUS_METERS = 50.0

/**
 * Finds stops in a day of location points.
 *
 * Points are sorted by time and split into consecutive, non-overlapping clusters. A point
 * joins the current cluster while it is within [radiusMeters] of the cluster's *first*
 * point (haversine distance); the first point outside the radius closes the cluster and
 * starts the next one. A cluster that lasted at least [minDurationMs] is emitted as a
 * [StopCluster].
 *
 * This deliberately compares against the first point rather than the previous one: a slow
 * walk would otherwise keep extending a "stop" indefinitely.
 */
fun findStops(
    points: List<PointIn>,
    minDurationMs: Long = DEFAULT_STOP_DURATION_MS,
    radiusMeters: Double = DEFAULT_STOP_RADIUS_METERS,
): List<StopCluster> {
    if (points.isEmpty()) return emptyList()

    val sorted = points.sortedBy { it.recordedAtMillis }
    val stops = mutableListOf<StopCluster>()
    var startIndex = 0

    fun closeCluster(endIndex: Int) {
        val first = sorted[startIndex]
        val last = sorted[endIndex]
        val duration = last.recordedAtMillis - first.recordedAtMillis
        if (duration < minDurationMs) return

        var latSum = 0.0
        var lonSum = 0.0
        for (index in startIndex..endIndex) {
            latSum += sorted[index].lat
            lonSum += sorted[index].lon
        }
        val count = endIndex - startIndex + 1
        stops += StopCluster(
            lat = latSum / count,
            lon = lonSum / count,
            startMillis = first.recordedAtMillis,
            endMillis = last.recordedAtMillis,
            durationMillis = duration,
            pointCount = count,
        )
    }

    for (index in 1 until sorted.size) {
        val anchor = sorted[startIndex]
        val point = sorted[index]
        if (haversineMeters(anchor.lat, anchor.lon, point.lat, point.lon) <= radiusMeters) {
            continue
        }
        closeCluster(index - 1)
        startIndex = index
    }
    closeCluster(sorted.lastIndex)

    return stops
}

/** Great-circle distance in metres; private on purpose, exercised through [findStops]. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
        sin(dLon / 2) * sin(dLon / 2)
    val clamped = a.coerceIn(0.0, 1.0)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(clamped), sqrt(1 - clamped))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
