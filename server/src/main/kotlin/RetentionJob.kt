package familytracker

import familytracker.db.Database
import familytracker.db.deleteOldLocations
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** How long a recorded location is kept: 30 days. */
const val LOCATION_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Deletes every location recorded before `nowMs - LOCATION_RETENTION_MS` and returns the number
 * of removed rows. A point exactly [LOCATION_RETENTION_MS] old is kept (the DAO uses `<`).
 */
fun runRetentionOnce(database: Database, nowMs: Long = System.currentTimeMillis()): Int =
    database.withConnection { conn -> deleteOldLocations(conn, nowMs - LOCATION_RETENTION_MS) }

/**
 * Starts the retention job: one run happens before this function returns, then the same work is
 * repeated every [periodMs] on a single daemon thread, so an unfinished run can never keep the
 * process alive.
 *
 * The returned executor is owned by the caller and should be shut down when the application stops.
 */
fun scheduleRetention(
    database: Database,
    periodMs: Long = 24L * 60 * 60 * 1000,
    now: () -> Long = System::currentTimeMillis,
): ScheduledExecutorService {
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "family-tracker-retention").apply { isDaemon = true }
    }
    val task = Runnable {
        // Retention is best-effort: a transient SQLite failure (locked file, disk full) must not
        // stop the schedule, because scheduleAtFixedRate cancels all future runs when the task
        // throws. This module has no logger yet, so failures are swallowed on purpose.
        try {
            runRetentionOnce(database, now())
        } catch (_: Exception) {
            // Ignored by design; the next run retries.
        }
    }

    task.run()
    executor.scheduleAtFixedRate(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
    return executor
}
