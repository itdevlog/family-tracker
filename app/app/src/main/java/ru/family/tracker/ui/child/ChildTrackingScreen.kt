package ru.family.tracker.ui.child

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.family.tracker.R
import ru.family.tracker.child.LocationBufferWorker
import ru.family.tracker.child.TrackingService

/**
 * Minimal tracking control screen for a paired child.
 *
 * Requests the runtime permissions, starts the foreground service and the 15-minute
 * periodic flush, and offers a confirmed stop. The full background-location wizard
 * ("Always" + battery optimization) is Task 8; here only a hint is shown.
 */
@Composable
fun ChildTrackingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var showStopConfirmation by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locationGranted = hasLocationPermission(context)
        notificationsGranted = hasNotificationPermission(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationGranted = hasLocationPermission(context)
        notificationsGranted = hasNotificationPermission(context)
        if (locationGranted) startTracking(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.child_tracking_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = stringResource(
                R.string.child_tracking_status_location,
                permissionLabel(locationGranted),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.child_tracking_status_notifications,
                notificationLabel(notificationsGranted),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(R.string.child_tracking_background_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                val missing = missingPermissions(context)
                if (missing.isEmpty()) {
                    startTracking(context)
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.child_tracking_start))
        }

        OutlinedButton(
            onClick = { showStopConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.child_tracking_stop))
        }
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text(stringResource(R.string.child_tracking_stop_confirm_title)) },
            text = { Text(stringResource(R.string.child_tracking_stop_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirmation = false
                        TrackingService.stop(context)
                    },
                ) {
                    Text(stringResource(R.string.child_tracking_stop_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text(stringResource(R.string.child_tracking_stop_confirm_no))
                }
            },
        )
    }
}

private fun startTracking(context: Context) {
    TrackingService.start(context)
    LocationBufferWorker.schedulePeriodic(context)
}

private fun missingPermissions(context: Context): List<String> = buildList {
    if (!hasLocationPermission(context)) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (!hasNotificationPermission(context)) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

@Composable
private fun permissionLabel(granted: Boolean): String = stringResource(
    if (granted) R.string.child_tracking_permission_granted else R.string.child_tracking_permission_denied,
)

@Composable
private fun notificationLabel(granted: Boolean): String =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        stringResource(R.string.child_tracking_permission_not_required)
    } else {
        permissionLabel(granted)
    }
