package ru.family.tracker.child

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import ru.family.tracker.data.api.PointIn

/**
 * Seam over the platform location provider. Production uses FusedLocationProviderClient,
 * Robolectric tests inject a fake so no Play Services are required.
 */
interface LocationSource {
    fun requestUpdates(intervalMs: Long, highAccuracy: Boolean, onLocation: (PointIn) -> Unit)

    fun stop()
}

/**
 * Fused location source. A new [requestUpdates] call replaces the previous callback, so the
 * service can switch between the moving and idle cadence without leaking registrations.
 */
class FusedLocationSource(context: Context) : LocationSource {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // Caller checks the runtime permission; SecurityException is handled below.
    override fun requestUpdates(
        intervalMs: Long,
        highAccuracy: Boolean,
        onLocation: (PointIn) -> Unit,
    ) {
        removeCurrentCallback()

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()

        val fresh = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location -> onLocation(location.toPointIn()) }
            }
        }
        callback = fresh

        try {
            client.requestLocationUpdates(request, fresh, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission was revoked after the service started: stay alive without updates,
            // the UI still lets the user re-grant it.
            callback = null
        }
    }

    override fun stop() {
        removeCurrentCallback()
    }

    private fun removeCurrentCallback() {
        callback?.let { previous ->
            try {
                client.removeLocationUpdates(previous)
            } catch (_: SecurityException) {
                // Permission already gone; there is nothing left to unregister.
            }
        }
        callback = null
    }

    private fun Location.toPointIn(): PointIn = PointIn(
        lat = latitude,
        lon = longitude,
        accuracy = if (hasAccuracy()) accuracy else null,
        speed = if (hasSpeed()) speed else null,
        recordedAtMillis = time,
    )
}
