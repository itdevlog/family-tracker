package ru.family.tracker.ui.child.wizard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.family.tracker.R
import ru.family.tracker.Settings

/**
 * First-run checklist that keeps tracking alive through the phone's power management.
 *
 * The automatic checks are re-evaluated on every [Lifecycle.Event.ON_RESUME], so returning
 * from a system settings page updates the tick without a manual refresh. Autostart has no
 * public API on any OEM, so it is confirmed by the user and remembered only for this
 * screen visit; the actual setting persists in the firmware.
 */
@Composable
fun WizardScreen(
    settings: Settings,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vendor = remember { decode(Build.MANUFACTURER) }
    val steps = remember(vendor) { requiredSteps(Build.MANUFACTURER) }

    var statuses by remember(steps) {
        mutableStateOf(steps.associate { it.kind to it.check(context) })
    }
    var manualConfirmed by rememberSaveable { mutableStateOf(false) }
    var showSkipDialog by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, steps) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                statuses = steps.associate { it.kind to it.check(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun finish() {
        settings.wizardDone = true
        onDone()
    }

    val allDone = steps.all { step ->
        if (step.autoCheckable) statuses[step.kind] == true else manualConfirmed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.child_wizard_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.child_wizard_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        steps.forEachIndexed { index, step ->
            val completed = if (step.autoCheckable) {
                statuses[step.kind] == true
            } else {
                manualConfirmed
            }
            StepRow(
                index = index,
                step = step,
                vendor = vendor,
                completed = completed,
                manualConfirmed = manualConfirmed,
                onManualChange = { manualConfirmed = it },
                onOpen = { openSettings(context, step) },
            )
            if (index < steps.lastIndex) {
                HorizontalDivider()
            }
        }

        Button(
            onClick = ::finish,
            enabled = allDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.child_wizard_done))
        }
        TextButton(
            onClick = { showSkipDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.child_wizard_skip))
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text(stringResource(R.string.child_wizard_skip_title)) },
            text = { Text(stringResource(R.string.child_wizard_skip_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipDialog = false
                        finish()
                    },
                ) {
                    Text(stringResource(R.string.child_wizard_skip_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text(stringResource(R.string.child_wizard_skip_cancel))
                }
            },
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: VendorStep,
    vendor: Vendor,
    completed: Boolean,
    manualConfirmed: Boolean,
    onManualChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val statusLabel = stringResource(
        if (completed) R.string.child_wizard_step_done else R.string.child_wizard_step_pending,
    )
    val statusColor = if (completed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(
                    if (completed) {
                        R.string.child_wizard_mark_done
                    } else {
                        R.string.child_wizard_mark_pending
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                color = statusColor,
                modifier = Modifier.semantics { contentDescription = statusLabel },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.child_wizard_step_title,
                        index + 1,
                        stepTitle(step.kind, vendor),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stepDescription(step.kind, vendor),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!step.autoCheckable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = manualConfirmed,
                    onCheckedChange = onManualChange,
                )
                Text(
                    text = stringResource(R.string.child_wizard_manual_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.child_wizard_open_settings))
        }
    }
}

@Composable
private fun stepTitle(kind: StepKind, vendor: Vendor): String = when (kind) {
    StepKind.BACKGROUND_LOCATION -> stringResource(R.string.child_wizard_step_location_title)
    StepKind.BATTERY_OPTIMIZATION -> stringResource(R.string.child_wizard_step_battery_title)
    StepKind.AUTOSTART -> stringResource(R.string.child_wizard_step_autostart_title, vendorLabel(vendor))
}

@Composable
private fun stepDescription(kind: StepKind, vendor: Vendor): String = when (kind) {
    StepKind.BACKGROUND_LOCATION -> stringResource(R.string.child_wizard_step_location_description)
    StepKind.BATTERY_OPTIMIZATION -> stringResource(R.string.child_wizard_step_battery_description)
    StepKind.AUTOSTART -> when (vendor) {
        Vendor.XIAOMI -> stringResource(R.string.child_wizard_step_autostart_description_xiaomi)
        Vendor.SAMSUNG -> stringResource(R.string.child_wizard_step_autostart_description_samsung)
        Vendor.HUAWEI -> stringResource(R.string.child_wizard_step_autostart_description_huawei)
        Vendor.OPPO -> stringResource(R.string.child_wizard_step_autostart_description_oppo)
        Vendor.STOCK -> stringResource(R.string.child_wizard_step_autostart_description_other)
    }
}

@Composable
private fun vendorLabel(vendor: Vendor): String = when (vendor) {
    Vendor.XIAOMI -> stringResource(R.string.child_wizard_vendor_xiaomi)
    Vendor.SAMSUNG -> stringResource(R.string.child_wizard_vendor_samsung)
    Vendor.HUAWEI -> stringResource(R.string.child_wizard_vendor_huawei)
    Vendor.OPPO -> stringResource(R.string.child_wizard_vendor_oppo)
    Vendor.STOCK -> stringResource(R.string.child_wizard_vendor_other)
}

/** OEM activities may be absent (custom ROM, newer firmware); app details always exist. */
private fun openSettings(context: Context, step: VendorStep) {
    val intent = step.openIntent(context) ?: return
    val launchable = if (context is Activity) {
        intent
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(launchable)
    } catch (_: ActivityNotFoundException) {
        openAppDetails(context)
    } catch (_: SecurityException) {
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }
}
