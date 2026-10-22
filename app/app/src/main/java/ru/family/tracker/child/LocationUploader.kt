package ru.family.tracker.child

import android.util.Log
import kotlinx.coroutines.CancellationException
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.db.PointDao
import ru.family.tracker.data.db.toPointIn

/** Result of one queue drain, mapped to a WorkManager result by the worker. */
sealed interface FlushOutcome {
    data object NothingToSend : FlushOutcome

    data class Uploaded(val count: Int) : FlushOutcome

    /** The server rejected the batch as invalid; it was removed so it cannot wedge the queue. */
    data class Dropped(val count: Int) : FlushOutcome

    /** Transient failure: keep the points and try again later. */
    data object Retry : FlushOutcome
}

/**
 * Drains at most [batchSize] queued points into one `POST /locations`.
 *
 * Error policy (ledger ruling): 400/403/413/422 mean the batch can never be accepted, so it
 * is dropped; 401/429/5xx and transport failures keep the points and ask for a retry.
 * Only the batch size is logged, never coordinates or the token.
 */
class LocationUploader(
    private val dao: PointDao,
    private val api: ApiClient,
    private val batchSize: Int = MAX_BATCH_SIZE,
) {

    suspend fun flushOnce(): FlushOutcome {
        val pending = dao.pendingPoints(batchSize)
        if (pending.isEmpty()) return FlushOutcome.NothingToSend

        val ids = pending.map { it.id }
        return try {
            api.uploadPoints(pending.map { it.toPointIn() })
            dao.deleteUploaded(ids)
            FlushOutcome.Uploaded(ids.size)
        } catch (error: ApiException) {
            if (error.status in DROP_STATUSES) {
                dao.deleteUploaded(ids)
                Log.w(TAG, "Dropped ${ids.size} point(s) rejected with HTTP ${error.status}")
                FlushOutcome.Dropped(ids.size)
            } else {
                FlushOutcome.Retry
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // IOException and any other transport failure: the points stay in Room.
            FlushOutcome.Retry
        }
    }

    companion object {
        const val MAX_BATCH_SIZE = 100

        /** Statuses that describe the payload itself, not a temporary condition. */
        private val DROP_STATUSES = setOf(400, 403, 413, 422)

        private const val TAG = "LocationUploader"
    }
}
