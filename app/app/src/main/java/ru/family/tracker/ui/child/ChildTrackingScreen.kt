package ru.family.tracker.ui.child

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.family.tracker.R
import ru.family.tracker.child.LocationBufferWorker
import ru.family.tracker.child.TrackingService

/**
 * Tracking-only destination retained for callers that do not show the SOS button.
 *
 * The actual permission/start/stop controls live in [TrackingControls], shared with
 * [ChildHomeScreen] so permission logic exists in exactly one place.
 */
@Composable
fun ChildTrackingScreen(modifier: Modifier = Modifier) {
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
        TrackingControls()
    }
}

/**
 * Permission status, permission request, start and a confirmed stop.
 *
 * Requests the runtime permissions, starts the foreground service and the 15-minute
 * periodic flush. The full background-location wizard ("Always" + battery optimization)
 * is Task 8; here only a hint is shown.
 */
@Composable
fun TrackingControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    var showPermissionHelp by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locationGranted = hasLocationPermission(context)
        notificationsGranted = hasNotificationPermission(context)
        if (locationGranted) showPermissionHelp = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationGranted = hasLocationPermission(context)
        notificationsGranted = hasNotificationPermission(context)
        if (locationGranted) {
            showPermissionHelp = false
            startTracking(context)
        } else {
            // Covers "don't ask again" and a permanently denied permission: without this
            // the Start button would look like a silent no-op.
            showPermissionHelp = true
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        if (showPermissionHelp && !locationGranted) {
            Text(
                text = stringResource(R.string.child_tracking_permission_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { openAppSettings(context) }) {
                Text(stringResource(R.string.child_tracking_open_settings))
            }
        }

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

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}

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
