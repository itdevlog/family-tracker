package ru.family.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM persistence tests: [Settings] is exercised through an in-memory
 * [KeyValueStore], so no Android Keystore/Robolectric is involved (ruling 2).
 */
class SettingsTest {

    private class InMemoryKeyValueStore(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val store = InMemoryKeyValueStore()

    @Test
    fun `role persists`() {
        Settings(store).role = Role.CHILD

        assertEquals(Role.CHILD, Settings(store).role)
    }

    @Test
    fun `role is null by default`() {
        assertNull(Settings(store).role)
    }

    @Test
    fun `base url and token round-trip`() {
        Settings(store).apply {
            baseUrl = "https://api.example.net"
            token = "secret-token"
        }

        val reloaded = Settings(store)
        assertEquals("https://api.example.net", reloaded.baseUrl)
        assertEquals("secret-token", reloaded.token)
    }

    @Test
    fun `ntfy config round-trip`() {
        val config = NtfyConfig(
            url = "https://ntfy.example.net",
            topic = "family-123",
            token = "ntfy-token",
        )
        Settings(store).ntfyConfig = config

        assertEquals(config, Settings(store).ntfyConfig)
    }

    @Test
    fun `null ntfy config removes it`() {
        val store = InMemoryKeyValueStore()
        Settings(store).ntfyConfig = NtfyConfig("https://ntfy.example.net", "topic", "token")
        Settings(store).ntfyConfig = null

        assertNull(Settings(store).ntfyConfig)
    }

    @Test
    fun `last known point round-trips and clears`() {
        val settings = Settings(store)
        assertNull(settings.lastPoint)

        settings.setLastPoint(lat = 55.75, lon = 37.61, timeMillis = 1_700_000_000_000)

        val reloaded = Settings(store).lastPoint
        assertEquals(55.75, reloaded?.lat ?: 0.0, 0.0)
        assertEquals(37.61, reloaded?.lon ?: 0.0, 0.0)
        assertEquals(1_700_000_000_000L, reloaded?.recordedAtMillis)

        settings.clearLastPoint()
        assertNull(Settings(store).lastPoint)
    }

    @Test
    fun `corrupt last point is treated as absent`() {
        store.putString("last_point", "{not json")

        assertNull(Settings(store).lastPoint)
    }

    @Test
    fun `last upload timestamp and error round-trip`() {
        val settings = Settings(store)
        assertNull(settings.lastUploadAtMillis)
        assertNull(settings.lastUploadErrorCode)

        settings.lastUploadAtMillis = 1_700_000_000_000
        settings.lastUploadErrorCode = 401

        val reloaded = Settings(store)
        assertEquals(1_700_000_000_000L, reloaded.lastUploadAtMillis)
        assertEquals(401, reloaded.lastUploadErrorCode)
    }

    @Test
    fun `last upload status clears independently`() {
        val settings = Settings(store)
        settings.lastUploadAtMillis = 42L
        settings.lastUploadErrorCode = 403

        settings.lastUploadAtMillis = null
        settings.lastUploadErrorCode = null

        val reloaded = Settings(store)
        assertNull(reloaded.lastUploadAtMillis)
        assertNull(reloaded.lastUploadErrorCode)
    }

    @Test
    fun `wizardDone round-trip`() {
        val settings = Settings(store)
        assertFalse(settings.wizardDone)

        settings.wizardDone = true
        assertTrue(Settings(store).wizardDone)

        settings.wizardDone = false
        assertFalse(Settings(store).wizardDone)
    }
}
