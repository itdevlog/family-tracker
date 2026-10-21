package familytracker

import familytracker.db.Database
import familytracker.db.deleteOldLocations
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** How long a recorded location is kept: 30 days. */
const val LOCATION_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/** How often the retention job runs. */
const val RETENTION_PERIOD_MS = 24L * 60 * 60 * 1000

private val logger = LoggerFactory.getLogger("familytracker.RetentionJob")

/**
 * Deletes every location recorded before `nowMs - LOCATION_RETENTION_MS` and returns the number
 * of removed rows. A point exactly [LOCATION_RETENTION_MS] old is kept (the DAO uses `<`).
 *
 * If the cutoff falls outside the `Long` range, nothing is deleted: a wrapped cutoff could prune
 * the newest history instead of the oldest. This is reachable only with a nonsensical clock.
 */
fun runRetentionOnce(database: Database, nowMs: Long = System.currentTimeMillis()): Int {
    val cutoffMs = try {
        Math.subtractExact(nowMs, LOCATION_RETENTION_MS)
    } catch (_: ArithmeticException) {
        return 0
    }
    return database.withConnection { conn -> deleteOldLocations(conn, cutoffMs) }
}

/**
 * Starts the retention job: one run happens before this function returns, then the same work is
 * repeated every [periodMs] on a single daemon thread, so an unfinished run can never keep the
 * process alive.
 *
 * [run] is injectable for tests; by default it deletes rows older than the retention window.
 * The returned executor is owned by the caller and should be shut down when the application stops.
 */
fun scheduleRetention(
    database: Database,
    periodMs: Long = RETENTION_PERIOD_MS,
    now: () -> Long = System::currentTimeMillis,
    run: () -> Int = { runRetentionOnce(database, now()) },
): ScheduledExecutorService {
    require(periodMs > 0) { "periodMs must be positive, was $periodMs" }

    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "family-tracker-retention").apply { isDaemon = true }
    }
    val task = Runnable {
        // Retention is best-effort: one failing run (locked/read-only/corrupt database, full disk)
        // must not cancel the schedule, because an exception escaping this task makes
        // scheduleAtFixedRate drop every future run. Only the failure and its stack trace are
        // logged; no device ids, coordinates or tokens are included.
        try {
            run()
        } catch (failure: Exception) {
            logger.warn("location retention failed", failure)
        }
    }

    task.run()
    executor.scheduleAtFixedRate(task, periodMs, periodMs, TimeUnit.MILLISECONDS)
    return executor
}
