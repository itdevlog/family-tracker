package ru.family.tracker.data

import kotlin.math.abs

/**
 * `0.0/0.0` is the only "no fix" value the server accepts, so the child sends it when its
 * last known position is missing. It is a sentinel, not a real place (it sits in the
 * Atlantic), and must never be rendered or used as a map centre.
 */
const val UNKNOWN_LOCATION_EPSILON = 1e-7

fun hasKnownLocation(lat: Double, lon: Double): Boolean =
    !(abs(lat) < UNKNOWN_LOCATION_EPSILON && abs(lon) < UNKNOWN_LOCATION_EPSILON)

/** A marker/camera target, or null when the coordinates are the unknown sentinel. */
data class MapPoint(val lat: Double, val lon: Double)

fun mapPointOrNull(lat: Double, lon: Double): MapPoint? =
    if (hasKnownLocation(lat, lon)) MapPoint(lat, lon) else null
