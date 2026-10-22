package ru.family.tracker.ui.parent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Local wall-clock time of the pair-code expiry, e.g. `14:05`. */
internal fun formatLocalExpiryTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMAT.withZone(zone).format(instant)

/** Countdown in `mm:ss`, clamped at zero. */
internal fun formatRemainingTime(remainingMillis: Long): String {
    val totalSeconds = remainingMillis.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
