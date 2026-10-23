package ru.family.tracker

import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.family.tracker.parent.SosAlert
import ru.family.tracker.parent.SosAlertBus

/**
 * Lock-screen visibility is a property of the SOS overlay only: an ordinary map/history
 * session must not appear over the keyguard, and closing the alert must release the flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLockScreenTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private lateinit var app: FamilyTrackerApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.containerOverride = AppContainer(app, Settings(InMemoryStore()))
        SosAlertBus.clear()
    }

    @After
    fun tearDown() {
        SosAlertBus.clear()
        app.containerOverride = null
    }

    private fun sosIntent(): Intent = Intent(app, MainActivity::class.java).apply {
        putExtra(SosAlert.EXTRA_SOS_ID, "evt-1")
        putExtra(SosAlert.EXTRA_CHILD_ID, "child-1")
        putExtra(SosAlert.EXTRA_LAT, 55.71)
        putExtra(SosAlert.EXTRA_LON, 37.61)
        putExtra(SosAlert.EXTRA_CREATED_AT, "2026-10-20T10:10:00Z")
    }

    private val lockFlags =
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

    @Test
    fun `ordinary launch does not show the activity over the keyguard`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertFalse(shadowOf(activity).getShowWhenLocked())
        assertFalse(shadowOf(activity).getTurnScreenOn())
        assertEquals(0, activity.window.attributes.flags and lockFlags)

        controller.destroy()
    }

    @Test
    fun `sos intent shows over the keyguard and clearing the alert removes it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, sosIntent()).create()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertTrue(shadowOf(activity).getShowWhenLocked())
        assertTrue(shadowOf(activity).getTurnScreenOn())
        assertTrue(activity.window.attributes.flags and lockFlags != 0)

        SosAlertBus.clear()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(shadowOf(activity).getShowWhenLocked())
        assertFalse(shadowOf(activity).getTurnScreenOn())
        assertEquals(0, activity.window.attributes.flags and lockFlags)

        controller.destroy()
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 uses window flags and clears them too`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, sosIntent()).create()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertTrue(activity.window.attributes.flags and lockFlags != 0)

        SosAlertBus.clear()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, activity.window.attributes.flags and lockFlags)

        controller.destroy()
    }
}
