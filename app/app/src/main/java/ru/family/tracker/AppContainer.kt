package ru.family.tracker

import android.content.Context
import ru.family.tracker.child.FusedLocationSource
import ru.family.tracker.child.LocationBufferWorker
import ru.family.tracker.child.LocationSource
import ru.family.tracker.child.SosSender
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.db.TrackerDatabase

/**
 * Manual dependency container owned by [FamilyTrackerApp].
 *
 * [settings] may be supplied by tests to bypass EncryptedSharedPreferences; [roomFactory],
 * [locationSourceFactory] and [flushScheduler] are seams so Robolectric tests never touch
 * Play Services or WorkManager.
 */
class AppContainer(
    private val context: Context,
    settingsOverride: Settings? = null,
    databaseFactory: ((Context) -> TrackerDatabase)? = null,
    private val apiOverride: ApiClient? = null,
) {
    val settings: Settings = settingsOverride ?: Settings.create(context)

    private val databaseFactory: (Context) -> TrackerDatabase =
        databaseFactory ?: { TrackerDatabase.build(it) }

    val room: TrackerDatabase by lazy { databaseFactory(context) }

    var locationSourceFactory: (Context) -> LocationSource = { FusedLocationSource(it) }

    var flushScheduler: (Context) -> Unit = { LocationBufferWorker.enqueueFlush(it) }

    val api: ApiClient by lazy {
        apiOverride ?: ApiClient({ settings.baseUrl }, { settings.token })
    }

    /** Offline-first SOS outbox shared by the home screen and both flush workers. */
    val sosSender: SosSender by lazy { SosSender(room.sos(), api) }
}
