package ru.family.tracker.parent

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
import ru.family.tracker.NtfyConfig
import ru.family.tracker.Role
import ru.family.tracker.Settings

/** The parent SOS subscription is restarted after boot only for a fully paired parent. */
@RunWith(RobolectricTestRunner::class)
class SosBootReceiverTest {

    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private lateinit var app: FamilyTrackerApp
    private lateinit var settings: Settings

    private val config = NtfyConfig(
        url = "https://ntfy.example",
        topic = "family-topic",
        token = "ntfy-token",
    )

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

    private fun boot(action: String = Intent.ACTION_BOOT_COMPLETED) {
        SosBootReceiver().onReceive(app, Intent(action))
    }

    @Test
    fun `paired parent with ntfy configuration starts the listener`() {
        settings.role = Role.PARENT
        settings.token = "parent-token"
        settings.ntfyConfig = config

        boot()

        assertEquals(
            SosListenerService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }

    @Test
    fun `quickboot is treated like boot`() {
        settings.role = Role.PARENT
        settings.token = "parent-token"
        settings.ntfyConfig = config

        boot(SosBootReceiver.ACTION_QUICKBOOT)

        assertEquals(
            SosListenerService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }

    @Test
    fun `missing ntfy configuration is a no-op`() {
        settings.role = Role.PARENT
        settings.token = "parent-token"

        boot()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `child role is a no-op`() {
        settings.role = Role.CHILD
        settings.token = "child-token"
        settings.ntfyConfig = config

        boot()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `unpaired parent is a no-op`() {
        settings.role = Role.PARENT
        settings.ntfyConfig = config

        boot()

        assertNull(shadowOf(app).nextStartedService)
    }
}
