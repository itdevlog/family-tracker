package ru.family.tracker.ui.parent

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.family.tracker.R

/**
 * Parent notification setup: asks for `POST_NOTIFICATIONS` once a parent is paired and
 * keeps a visible card when alerts cannot be heard (notifications disabled, or Android
 * 14+ full-screen intents denied).
 *
 * The permission request is remembered with `rememberSaveable`, so a rotation does not
 * ask again; after a system-settings trip the state is re-read in `ON_RESUME`.
 */
@Composable
fun ParentNotificationSetup(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var requested by rememberSaveable { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(notificationsEnabled(context)) }
    var fullScreenAllowed by remember { mutableStateOf(fullScreenAllowed(context)) }

    fun refresh() {
        notificationsEnabled = notificationsEnabled(context)
        fullScreenAllowed = fullScreenAllowed(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requested = true
        refresh()
    }

    val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

    LaunchedEffect(visible, needsRuntimePermission, requested) {
        if (visible && needsRuntimePermission && !requested) {
            // Set before launching: a configuration change while the dialog is open must
            // not queue a second request when the activity is recreated.
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!visible || (notificationsEnabled && fullScreenAllowed)) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!notificationsEnabled) {
                Text(
                    text = stringResource(R.string.sos_notifications_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.sos_notifications_off_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = { openAppNotificationSettings(context) }) {
                    Text(stringResource(R.string.sos_notifications_open_settings))
                }
            }

            if (!fullScreenAllowed) {
                if (notificationsEnabled) {
                    Text(
                        text = stringResource(R.string.sos_full_screen_off_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = stringResource(R.string.sos_full_screen_off_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = { openFullScreenSettings(context) }) {
                    Text(stringResource(R.string.sos_full_screen_allow))
                }
            }
        }
    }
}

internal fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

internal fun fullScreenAllowed(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    } else {
        true
    }

private fun openAppNotificationSettings(context: Context) {
    context.startSettingsSafely(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}

private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    context.startSettingsSafely(
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}

/** Opens a settings screen; falls back to the app details page on ROMs lacking it. */
private fun Context.startSettingsSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // No settings activity at all (unusual test/OEM environment); do not crash.
        }
    } catch (_: SecurityException) {
        // Same fallback contract: the card stays visible and the user can retry.
    }
}
