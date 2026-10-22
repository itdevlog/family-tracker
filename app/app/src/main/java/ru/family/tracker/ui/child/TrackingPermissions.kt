package ru.family.tracker.ui.child

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Accuracy is judged by [Manifest.permission.ACCESS_FINE_LOCATION].
 *
 * Android 12+ ignores a runtime request that asks for FINE without COARSE ("must be
 * requested with ACCESS_COARSE_LOCATION"), and the whole dialog result comes back denied.
 * COARSE is therefore always requested in the same array as FINE.
 */
internal fun missingPermissions(context: Context): List<String> = buildList {
    if (!hasLocationPermission(context)) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (!hasNotificationPermission(context)) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

internal fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
