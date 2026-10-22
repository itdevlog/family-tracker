package ru.family.tracker.child

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.family.tracker.FamilyTrackerApp
import java.util.concurrent.TimeUnit

/**
 * Uploads the Room point queue.
 *
 * Two entry points keep the data flowing:
 * * [enqueueFlush] — one-shot, requested after every stored point (network-constrained, KEEP
 *   so a burst of points collapses into a single upload);
 * * [schedulePeriodic] — 15-minute periodic safety net for Doze/process death.
 */
class LocationBufferWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? FamilyTrackerApp)?.container
            ?: return Result.success()
        if (!container.settings.canTrack()) return Result.success()

        val uploader = LocationUploader(container.room.points(), container.api)
        return when (uploader.flushOnce()) {
            FlushOutcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        internal const val UNIQUE_FLUSH = "location-flush"
        internal const val UNIQUE_PERIODIC = "location-periodic"
        internal const val PERIOD_MINUTES = 15L

        fun enqueueFlush(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocationBufferWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_FLUSH,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationBufferWorker>(
                PERIOD_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Stops both the in-flight flush and the periodic safety net. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(UNIQUE_PERIODIC)
                cancelUniqueWork(UNIQUE_FLUSH)
            }
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
