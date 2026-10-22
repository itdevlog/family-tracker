package ru.family.tracker.ui.child

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Permission request composition, in particular the FINE+COARSE pairing required on API 31+. */
@RunWith(RobolectricTestRunner::class)
class TrackingPermissionsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `fine location is always requested together with coarse`() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val missing = missingPermissions(app)

        assertTrue(
            "Android 12+ ignores FINE-only requests, COARSE must be in the same array",
            missing.contains(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        assertTrue(missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    fun `granted fine location is not requested again`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val missing = missingPermissions(app)

        assertFalse(missing.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    @Config(sdk = [32])
    fun `notification permission is not requested before android 13`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val missing = missingPermissions(app)

        assertFalse(missing.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(hasNotificationPermission(app))
    }
}
