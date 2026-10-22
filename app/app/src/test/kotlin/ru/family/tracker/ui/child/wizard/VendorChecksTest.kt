package ru.family.tracker.ui.child.wizard

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OEM detection and the step list are pure functions over the manufacturer string, so the
 * core of the wizard is verified without a device. Intent construction is checked under
 * Robolectric because it needs a real [android.content.Context].
 */
class VendorChecksTest {

    @Test
    fun `known manufacturers decode to their vendor`() {
        assertEquals(Vendor.XIAOMI, decode("Xiaomi"))
        assertEquals(Vendor.XIAOMI, decode("Redmi"))
        assertEquals(Vendor.XIAOMI, decode("POCO"))
        assertEquals(Vendor.SAMSUNG, decode("samsung"))
        assertEquals(Vendor.HUAWEI, decode("HUAWEI"))
        assertEquals(Vendor.HUAWEI, decode("HONOR"))
        assertEquals(Vendor.OPPO, decode("OPPO"))
        assertEquals(Vendor.OPPO, decode("realme"))
        assertEquals(Vendor.OPPO, decode("OnePlus"))
    }

    @Test
    fun `unknown and missing manufacturers fall back to stock`() {
        assertEquals(Vendor.STOCK, decode("Google"))
        assertEquals(Vendor.STOCK, decode(""))
        assertEquals(Vendor.STOCK, decode("   "))
        assertEquals(Vendor.STOCK, decode(null))
    }

    @Test
    fun `oem vendors get background, battery and autostart steps in order`() {
        val expected = listOf(
            StepKind.BACKGROUND_LOCATION,
            StepKind.BATTERY_OPTIMIZATION,
            StepKind.AUTOSTART,
        )

        assertEquals(expected, requiredSteps("Xiaomi").map { it.kind })
        assertEquals(expected, requiredSteps("samsung").map { it.kind })
        assertEquals(expected, requiredSteps("HUAWEI").map { it.kind })
        assertEquals(expected, requiredSteps("OPPO").map { it.kind })
    }

    @Test
    fun `stock android has no autostart step`() {
        val kinds = requiredSteps("Google").map { it.kind }

        assertEquals(
            listOf(StepKind.BACKGROUND_LOCATION, StepKind.BATTERY_OPTIMIZATION),
            kinds,
        )
        assertEquals(requiredSteps(null).map { it.kind }, kinds)
    }

    @Test
    fun `only autostart requires manual confirmation`() {
        requiredSteps("Xiaomi").forEach { step ->
            assertEquals(
                "step ${step.kind} autoCheckable",
                step.kind != StepKind.AUTOSTART,
                step.autoCheckable,
            )
        }
    }
}

/** Intent construction needs Android resources/package manager, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
class VendorChecksIntentTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `background location and battery steps produce launchable intents`() {
        val steps = requiredSteps("Xiaomi")

        val background = steps.first { it.kind == StepKind.BACKGROUND_LOCATION }
        val battery = steps.first { it.kind == StepKind.BATTERY_OPTIMIZATION }

        assertNotNull(background.openIntent(app))
        assertNotNull(battery.openIntent(app))
    }

    @Test
    fun `stock battery intent falls back to the battery optimization list`() {
        val battery = requiredSteps("Google")
            .first { it.kind == StepKind.BATTERY_OPTIMIZATION }

        val action = battery.openIntent(app)?.action

        assertNotNull(action)
        assertTrue(
            "battery intent must be either the package request or the settings list",
            action == android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ||
                action == android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
    }

    @Test
    fun `stock android has no autostart intent`() {
        assertFalse(requiredSteps("Google").any { it.kind == StepKind.AUTOSTART })
    }

    @Test
    fun `autostart intent points at the vendor guard for every oem`() {
        val expected = mapOf(
            "Xiaomi" to ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            "samsung" to ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            "HUAWEI" to ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            "OPPO" to ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )

        expected.forEach { (manufacturer, component) ->
            val intent = requiredSteps(manufacturer)
                .first { it.kind == StepKind.AUTOSTART }
                .openIntent(app)
            assertEquals(component, intent?.component)
        }
    }
}
