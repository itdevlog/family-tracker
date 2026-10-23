package ru.family.tracker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.family.tracker.data.api.PointIn

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

    /**
     * Wall-clock time of the last successful `POST /locations` by this device.
     *
     * Kept outside the point queue, which is drained on success: the child UI needs a
     * record of delivery even when there is nothing left to upload. No coordinates are
     * stored here.
     */
    var lastUploadAtMillis: Long?
        get() = store.getString(KEY_LAST_UPLOAD_AT)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_UPLOAD_AT, value?.toString())

    /**
     * Status of the most recent upload rejection, if any (401/403).
     *
     * Cleared only by a successful upload of a later batch, so the child keeps seeing
     * "server rejected access" until re-pairing actually fixes the token.
     */
    var lastUploadErrorCode: Int?
        get() = store.getString(KEY_LAST_UPLOAD_ERROR)?.toIntOrNull()
        set(value) = store.putString(KEY_LAST_UPLOAD_ERROR, value?.toString())

    /**
     * `created_at` of the newest SOS event already pushed to this parent.
     *
     * The ntfy message itself carries no payload we rely on: every message triggers a
     * `GET /sos?since=...` and this marker is what makes repeated messages (and repeated
     * reconnects) idempotent. Stored as an ISO-8601 instant.
     */
    var lastSosNotifiedAt: String?
        get() = store.getString(KEY_LAST_SOS_NOTIFIED_AT)
        set(value) = store.putString(KEY_LAST_SOS_NOTIFIED_AT, value)

    /**
     * Last fix seen by tracking, kept outside the upload outbox.
     *
     * The `points` table is drained/emptied by uploads, so it cannot answer "where is the
     * child right now" — the SOS button needs this value even while online.
     */
    var lastPoint: PointIn?
        get() = store.getString(KEY_LAST_POINT)?.let { stored ->
            runCatching { json.decodeFromString<PointIn>(stored) }.getOrNull()
        }
        set(value) = store.putString(KEY_LAST_POINT, value?.let { json.encodeToString(it) })

    fun setLastPoint(lat: Double, lon: Double, timeMillis: Long) {
        lastPoint = PointIn(lat = lat, lon = lon, recordedAtMillis = timeMillis)
    }

    fun clearLastPoint() {
        lastPoint = null
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ROLE = "role"
        private const val KEY_TOKEN = "token"
        private const val KEY_NTFY = "ntfy_config"
        private const val KEY_WIZARD_DONE = "wizard_done"
        private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
        private const val KEY_LAST_UPLOAD_ERROR = "last_upload_error"
        private const val KEY_LAST_POINT = "last_point"
        private const val KEY_LAST_SOS_NOTIFIED_AT = "last_sos_notified_at"

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
