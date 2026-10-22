package ru.family.tracker.child

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.family.tracker.FamilyTrackerApp

/**
 * Drains the SOS outbox once the network is back.
 *
 * The sender itself never schedules work (it has no Context); the child home screen enqueues
 * this on a failed press, and the location worker also calls [SosSender.flushSos] so a
 * reappearing network flushes both queues.
 */
class SosFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? FamilyTrackerApp)?.container
            ?: return Result.success()
        if (!container.settings.canTrack()) return Result.success()

        return if (container.sosSender.flushSos()) Result.success() else Result.retry()
    }

    companion object {
        internal const val UNIQUE_FLUSH = "sos-flush"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SosFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_FLUSH,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
