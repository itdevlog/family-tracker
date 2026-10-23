package ru.family.tracker.ui.child

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Snapshot of the last location upload, read cheaply on the child home. */
internal data class UploadStatusSnapshot(
    val lastUploadAtMillis: Long?,
    val errorCode: Int?,
    val online: Boolean,
    val nowMillis: Long,
)

internal enum class UploadStatusKind { ONLINE, OFFLINE, NEVER, REJECTED }

/**
 * A rejected token wins over connectivity and timestamps: an online network with a revoked
 * token must never be shown as "Онлайн" — the child has to re-pair.
 */
internal fun uploadStatusKind(snapshot: UploadStatusSnapshot): UploadStatusKind = when {
    snapshot.errorCode == 401 || snapshot.errorCode == 403 -> UploadStatusKind.REJECTED
    snapshot.lastUploadAtMillis == null -> UploadStatusKind.NEVER
    snapshot.online -> UploadStatusKind.ONLINE
    else -> UploadStatusKind.OFFLINE
}

/**
 * True when the active network claims both internet access and successful validation.
 * `VALIDATED` filters out captive portals and Wi-Fi without upstream connectivity.
 */
internal fun isNetworkOnline(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private val DATE_TIME = DateTimeFormatter.ofPattern(
    "dd.MM.yyyy HH:mm",
    Locale.forLanguageTag("ru"),
)

/**
 * Human-readable age of the last upload.
 *
 * Boundaries are deliberate: under a minute is "только что", then whole minutes and whole
 * hours are floored; at 24 hours (and older) the absolute date/time is clearer than
 * "24 ч назад". Future timestamps (clock skew) are clamped to "только что".
 */
internal fun formatRelativeTime(
    thenMillis: Long,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "только что"
        elapsed < 3_600_000 -> "${elapsed / 60_000} мин назад"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} ч назад"
        else -> Instant.ofEpochMilli(thenMillis).atZone(zone).format(DATE_TIME)
    }
}
