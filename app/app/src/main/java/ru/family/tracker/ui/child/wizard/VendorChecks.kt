package ru.family.tracker.ui.child.wizard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Phone maker whose background restrictions need a dedicated step. */
enum class Vendor { XIAOMI, SAMSUNG, HUAWEI, OPPO, STOCK }

enum class StepKind { BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY_OPTIMIZATION, AUTOSTART }

/**
 * One wizard row.
 *
 * [autoCheckable] is false when the system exposes no API for the setting; such a step is
 * confirmed by the user ("Я включил") instead. [openIntent] never throws — it may return
 * null only when there is nothing to open; missing handlers are caught by the UI.
 */
data class VendorStep(
    val kind: StepKind,
    val autoCheckable: Boolean,
    val check: (Context) -> Boolean,
    val openIntent: (Context) -> Intent?,
)

/**
 * Manufacturer string → vendor family.
 *
 * [Build.MANUFACTURER] is inconsistent across models of the same brand (Redmi/POCO for
 * Xiaomi, HONOR for Huawei, realme/OnePlus for OPPO), so matching is by substring.
 */
fun decode(manufacturer: String?): Vendor {
    val name = manufacturer?.trim()?.lowercase().orEmpty()
    return when {
        "xiaomi" in name || "redmi" in name || "poco" in name -> Vendor.XIAOMI
        "samsung" in name -> Vendor.SAMSUNG
        "huawei" in name || "honor" in name -> Vendor.HUAWEI
        "oppo" in name || "realme" in name || "oneplus" in name -> Vendor.OPPO
        else -> Vendor.STOCK
    }
}

/**
 * Steps the child must pass for tracking to survive the phone's power management.
 *
 * Background location, notifications and battery optimization exist on every vendor;
 * autostart is an OEM-only setting with no public API, so it is only added for the four
 * known families.
 */
fun requiredSteps(manufacturer: String?): List<VendorStep> = buildList {
    add(backgroundLocationStep())
    add(notificationsStep())
    add(batteryOptimizationStep())
    autostartComponent(decode(manufacturer))?.let { component ->
        add(
            VendorStep(
                kind = StepKind.AUTOSTART,
                autoCheckable = false,
                check = { false },
                openIntent = { Intent().setComponent(component) },
            ),
        )
    }
}

private fun backgroundLocationStep(): VendorStep = VendorStep(
    kind = StepKind.BACKGROUND_LOCATION,
    autoCheckable = true,
    check = { context ->
        // Before Android 10 background location was granted together with the foreground
        // permission, so there was nothing extra to ask for. Checking the nonexistent
        // permission there would report DENIED and make the wizard impossible to finish.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    },
    openIntent = { context ->
        // Android 11+ no longer shows a system dialog for "Allow all the time"; the user
        // has to pick it on the app's details page.
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    },
)

private fun notificationsStep(): VendorStep = VendorStep(
    kind = StepKind.NOTIFICATIONS,
    autoCheckable = true,
    check = { context ->
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
    openIntent = { context ->
        // The persistent tracking notification is how the user and the system know that
        // tracking is alive. The app-details fallback is handled by the UI.
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    },
)

private fun batteryOptimizationStep(): VendorStep = VendorStep(
    kind = StepKind.BATTERY_OPTIMIZATION,
    autoCheckable = true,
    check = { context ->
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    },
    openIntent = { context ->
        // The general battery list is the UI's fallback if this request has no handler.
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
    },
)

private fun autostartComponent(vendor: Vendor): ComponentName? = when (vendor) {
    Vendor.XIAOMI -> ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity",
    )

    Vendor.SAMSUNG -> ComponentName(
        "com.samsung.android.lool",
        "com.samsung.android.sm.ui.battery.BatteryActivity",
    )

    Vendor.HUAWEI -> ComponentName(
        "com.huawei.systemmanager",
        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    )

    Vendor.OPPO -> ComponentName(
        "com.coloros.safecenter",
        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    )

    Vendor.STOCK -> null
}
