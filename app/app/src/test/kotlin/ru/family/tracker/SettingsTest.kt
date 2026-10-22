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
    fun `wizardDone round-trip`() {
        val settings = Settings(store)
        assertFalse(settings.wizardDone)

        settings.wizardDone = true
        assertTrue(Settings(store).wizardDone)

        settings.wizardDone = false
        assertFalse(Settings(store).wizardDone)
    }
}
