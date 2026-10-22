package ru.family.tracker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Role { PARENT, CHILD }

@Serializable
data class NtfyConfig(val url: String, val topic: String, val token: String)

/**
 * Storage seam: production uses encrypted preferences, tests can use an in-memory map.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class Settings(private val store: KeyValueStore) {

    var baseUrl: String?
        get() = store.getString(KEY_BASE_URL)
        set(value) = store.putString(KEY_BASE_URL, value)

    var role: Role?
        get() = store.getString(KEY_ROLE)?.let { stored ->
            Role.entries.firstOrNull { it.name == stored }
        }
        set(value) = store.putString(KEY_ROLE, value?.name)

    var token: String?
        get() = store.getString(KEY_TOKEN)
        set(value) = store.putString(KEY_TOKEN, value)

    var ntfyConfig: NtfyConfig?
        get() = store.getString(KEY_NTFY)?.let { stored ->
            runCatching { json.decodeFromString<NtfyConfig>(stored) }.getOrNull()
        }
        set(value) = store.putString(KEY_NTFY, value?.let { json.encodeToString(it) })

    var wizardDone: Boolean
        get() = store.getString(KEY_WIZARD_DONE) == "true"
        set(value) = store.putString(KEY_WIZARD_DONE, value.toString())

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ROLE = "role"
        private const val KEY_TOKEN = "token"
        private const val KEY_NTFY = "ntfy_config"
        private const val KEY_WIZARD_DONE = "wizard_done"

        private val json = Json { ignoreUnknownKeys = true }

        fun create(context: Context): Settings {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                "family_tracker_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            return Settings(object : KeyValueStore {
                override fun getString(key: String): String? = prefs.getString(key, null)

                override fun putString(key: String, value: String?) {
                    // commit(), not apply(): a successful registration response carries a
                    // token that cannot be re-obtained (a retry answers 409), so writes of
                    // these set-once values must reach disk before the caller proceeds.
                    prefs.edit().apply {
                        if (value == null) remove(key) else putString(key, value)
                    }.commit()
                }
            })
        }
    }
}
