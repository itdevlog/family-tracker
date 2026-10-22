package ru.family.tracker.child

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.family.tracker.AppContainer
import ru.family.tracker.FamilyTrackerApp
import ru.family.tracker.KeyValueStore
import ru.family.tracker.Role
import ru.family.tracker.Settings

/** Boot handling: restart tracking only for a paired child. */
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private lateinit var app: FamilyTrackerApp
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        settings = Settings(InMemoryStore())
        app.containerOverride = AppContainer(app, settings)
    }

    @After
    fun tearDown() {
        app.containerOverride = null
    }

    private fun boot() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `paired child starts the tracking service`() {
        settings.role = Role.CHILD
        settings.token = "child-token"

        boot()

        val started = shadowOf(app).nextStartedService
        assertEquals(TrackingService::class.java.name, started?.component?.className)
    }

    @Test
    fun `quickboot also restarts tracking`() {
        settings.role = Role.CHILD
        settings.token = "child-token"

        BootReceiver().onReceive(app, Intent(BootReceiver.ACTION_QUICKBOOT))

        val started = shadowOf(app).nextStartedService
        assertEquals(TrackingService::class.java.name, started?.component?.className)
    }

    @Test
    fun `parent does not start the service`() {
        settings.role = Role.PARENT
        settings.token = "parent-token"

        boot()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `child without token does not start the service`() {
        settings.role = Role.CHILD
        settings.token = null

        boot()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `unrelated broadcast is ignored`() {
        settings.role = Role.CHILD
        settings.token = "child-token"

        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))

        assertNull(shadowOf(app).nextStartedService)
    }
}
