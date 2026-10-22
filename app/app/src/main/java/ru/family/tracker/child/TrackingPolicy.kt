package ru.family.tracker.child

import ru.family.tracker.Role
import ru.family.tracker.Settings

/** Moving threshold in metres per second: walking pace and above. */
const val MOVING_SPEED_THRESHOLD_MPS = 1f

/** High-accuracy cadence while the child is moving. */
const val INTERVAL_MOVING_MS = 10_000L

/** Balanced-power cadence while the child is stationary. */
const val INTERVAL_IDLE_MS = 60_000L

/** `speed > 1 m/s` means walking, cycling or driving; null/unknown is treated as idle. */
fun isMoving(speed: Float?): Boolean = speed != null && speed > MOVING_SPEED_THRESHOLD_MPS

fun intervalFor(moving: Boolean): Long = if (moving) INTERVAL_MOVING_MS else INTERVAL_IDLE_MS

/** Foreground tracking is allowed only for a paired child (ledger ruling). */
fun Settings.canTrack(): Boolean = role == Role.CHILD && !token.isNullOrBlank()
