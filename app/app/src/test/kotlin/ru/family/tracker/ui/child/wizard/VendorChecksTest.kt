package ru.family.tracker.ui.child.wizard

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * OEM detection and the step list are pure functions over the manufacturer string, so the
 * core of the wizard is verified without a device. Intent construction is checked under
 * Robolectric because it needs a real [android.content.Context].
 */
class VendorChecksTest {

    private val oemManufacturers = listOf("Xiaomi", "samsung", "HUAWEI", "OPPO")

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
        assertEquals(Vendor.STOCK, decode("stock"))
        assertEquals(Vendor.STOCK, decode(""))
        assertEquals(Vendor.STOCK, decode("   "))
        assertEquals(Vendor.STOCK, decode(null))
    }

    @Test
    fun `oem vendors get all four steps in order`() {
        val expected = listOf(
            StepKind.BACKGROUND_LOCATION,
            StepKind.NOTIFICATIONS,
            StepKind.BATTERY_OPTIMIZATION,
            StepKind.AUTOSTART,
        )

        oemManufacturers.forEach { manufacturer ->
            assertEquals(manufacturer, expected, requiredSteps(manufacturer).map { it.kind })
        }
    }

    @Test
    fun `stock android has no autostart step`() {
        val expected = listOf(
            StepKind.BACKGROUND_LOCATION,
            StepKind.NOTIFICATIONS,
            StepKind.BATTERY_OPTIMIZATION,
        )

        assertEquals(expected, requiredSteps("Google").map { it.kind })
        assertEquals(expected, requiredSteps(null).map { it.kind })
    }

    @Test
    fun `only autostart requires manual confirmation`() {
        oemManufacturers.forEach { manufacturer ->
            requiredSteps(manufacturer).forEach { step ->
                assertEquals(
                    "$manufacturer: step ${step.kind} autoCheckable",
                    step.kind != StepKind.AUTOSTART,
                    step.autoCheckable,
                )
            }
        }
    }
}

/** Intent construction needs Android resources/package manager, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
class VendorChecksIntentTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `background location opens app details for this package`() {
        val intent = requiredSteps("Xiaomi")
            .first { it.kind == StepKind.BACKGROUND_LOCATION }
            .openIntent(app)

        assertNotNull(intent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent!!.action)
        assertEquals(Uri.parse("package:${app.packageName}"), intent.data)
    }

    @Test
    fun `notifications open this app's notification settings`() {
        val intent = requiredSteps("Google")
            .first { it.kind == StepKind.NOTIFICATIONS }
            .openIntent(app)

        assertNotNull(intent)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent!!.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `battery step asks to ignore optimizations for this package`() {
        val intent = requiredSteps("Google")
            .first { it.kind == StepKind.BATTERY_OPTIMIZATION }
            .openIntent(app)

        assertNotNull(intent)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent!!.action)
        assertEquals(Uri.parse("package:${app.packageName}"), intent.data)
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

    @Test
    @Config(sdk = [28])
    fun `before android 10 background location follows the fine permission`() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val step = requiredSteps("Google").first { it.kind == StepKind.BACKGROUND_LOCATION }
        assertFalse("no foreground permission means no background location", step.check(app))

        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        assertTrue("background location is implicit with the foreground permission", step.check(app))
    }

    @Test
    @Config(sdk = [34])
    fun `android 10 and later require the background permission explicitly`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val step = requiredSteps("Google").first { it.kind == StepKind.BACKGROUND_LOCATION }
        assertFalse(step.check(app))

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(step.check(app))
    }
}
