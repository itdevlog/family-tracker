package ru.family.tracker.child

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.db.SosDao
import ru.family.tracker.data.db.SosEventEntity

/**
 * Offline-first SOS outbox.
 *
 * Every press is written to Room *before* the network call, so losing connectivity or
 * killing the app still leaves the signal queued. [SosSender] deliberately knows nothing
 * about WorkManager/Context: after a failed send the UI schedules [SosFlushWorker].
 *
 * Error policy matches the location queue: `400/403/413/422` describe an event the server
 * can never accept, so it is dropped instead of retrying forever; `401/429/5xx` and
 * transport failures keep it for a later attempt. Coordinates and the token are never
 * logged — at most a count or an HTTP status.
 */
class SosSender(
    private val dao: SosDao,
    private val api: ApiClient,
    private val settings: Settings,
) {

    /**
     * Serializes drains: the UI, [SosFlushWorker] and [LocationBufferWorker] may all try
     * to flush at once, and without this lock both could read the same pending row and
     * send a duplicate SOS.
     */
    private val flushMutex = Mutex()

    /** Queues the press and immediately attempts to drain the outbox. */
    suspend fun trySend(lat: Double, lon: Double): Boolean {
        dao.insert(
            SosEventEntity(
                lat = lat,
                lon = lon,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return flushSos()
    }

    /**
     * Sends up to [MAX_BATCH] events in press order.
     *
     * Returns true only when the outbox is empty afterwards, i.e. every queued event was
     * either accepted or permanently dropped as invalid.
     */
    suspend fun flushSos(): Boolean = flushMutex.withLock {
        drainLocked()
    }

    /** Number of SOS events still waiting in Room; the child UI polls this after a failure. */
    suspend fun pendingCount(): Int = dao.count()

    private suspend fun drainLocked(): Boolean {
        val pending = dao.pending(MAX_BATCH)
        if (pending.isEmpty()) return true

        var dropped = false
        for (event in pending) {
            try {
                api.sendSos(event.lat, event.lon)
                dao.delete(listOf(event.id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: ApiException) {
                if (error.status in DROP_STATUSES) {
                    dao.delete(listOf(event.id))
                    // The queue is drained, but the parent never got this alert. Keep the
                    // code so the child UI can say "rejected" instead of "sent".
                    settings.lastSosDropErrorCode = error.status
                    dropped = true
                    Log.w(TAG, "Dropped an SOS event rejected with HTTP ${error.status}")
                } else {
                    // 401/429/5xx: keep the event; a later flush may succeed.
                    return false
                }
            } catch (_: Exception) {
                // IOException and any other transport failure: keep the event.
                return false
            }
        }

        // Only a run with no rejected event proves the stale drop marker is obsolete.
        if (!dropped) settings.lastSosDropErrorCode = null
        return dao.count() == 0
    }

    companion object {
        /** Bounded so one flush does not hold a worker forever; the next run continues. */
        const val MAX_BATCH = 50

        private val DROP_STATUSES = setOf(400, 403, 413, 422)

        private const val TAG = "SosSender"
    }
}
