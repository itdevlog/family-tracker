package ru.family.tracker.parent

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import ru.family.tracker.data.MapPoint
import ru.family.tracker.data.mapPointOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One SOS alert surfaced above whatever screen is currently open.
 *
 * The notification/full-screen intent carries plain extras, so the alert can be rebuilt
 * after an activity recreation without another network request.
 */
data class SosAlert(
    val eventId: String,
    val childId: String,
    val lat: Double,
    val lon: Double,
    val createdAtIso: String,
) {
    companion object {
        const val EXTRA_SOS_ID = "sos_id"
        const val EXTRA_CHILD_ID = "child_id"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_CREATED_AT = "created_at"

        /**
         * Extracts the alert from a notification/launch intent. Returns null unless the
         * intent is an SOS one and every required value is present and valid; a malformed
         * extra must never crash activity creation.
         */
        fun fromIntent(intent: Intent?): SosAlert? {
            if (intent == null || !intent.hasExtra(EXTRA_SOS_ID)) return null

            val eventId = intent.getStringExtra(EXTRA_SOS_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val childId = intent.getStringExtra(EXTRA_CHILD_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val createdAt = intent.getStringExtra(EXTRA_CREATED_AT)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (!intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LON)) return null

            val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

            return SosAlert(
                eventId = eventId,
                childId = childId,
                lat = lat,
                lon = lon,
                createdAtIso = createdAt,
            )
        }
    }
}

/** Map target for this alert, or null when the child had no fix (`0.0/0.0`). */
fun SosAlert.mapPoint(): MapPoint? = mapPointOrNull(lat, lon)

/**
 * The only process-wide state owned by the alert flow.
 *
 * It is deliberately small: the activity/intent entry points call [show], the root
 * composable observes [alert] and renders the full-screen screen, and both "ack" and
 * "later" call [clear]. A singleton is required because the full-screen intent may start
 * the activity (and compose a fresh graph) with only the intent extras available.
 */
object SosAlertBus {
    private val _alert = MutableStateFlow<SosAlert?>(null)
    val alert: StateFlow<SosAlert?> = _alert.asStateFlow()

    fun show(alert: SosAlert) {
        // The same live frame may arrive twice (or the service and the map may both see
        // it); replacing an alert with itself would restart the full-screen screen.
        if (_alert.value?.eventId == alert.eventId) return
        _alert.value = alert
    }

    fun clear() {
        _alert.value = null
    }
}
