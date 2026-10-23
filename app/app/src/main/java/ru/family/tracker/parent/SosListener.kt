package ru.family.tracker.parent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.SosEvent
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.logging.Logger

/**
 * Turns ntfy subscription messages into new SOS events.
 *
 * ntfy is used only as a wake-up signal: every text frame triggers `GET /sos` with the
 * persisted [Settings.lastSosNotifiedAt] marker. Events that are already acknowledged or
 * not newer than the marker are ignored, which makes duplicate messages and reconnects
 * idempotent. The marker is advanced only after the new events were handed to [sink].
 *
 * A failed `/sos` request is logged (class name only) and does not tear down the
 * subscription; the next message retries. A closed/broken socket is reconnected with
 * exponential backoff.
 */
class SosListener(
    private val api: ApiClient,
    private val socketFactory: () -> NtfySocket,
    private val sink: (SosEvent) -> Unit,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                socketFactory().messages().collect {
                    // A frame proves the subscription works again; the next reconnect
                    // after a genuine close starts from the shortest delay.
                    attempt = 0
                    handleMessage()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warning("ntfy socket failed: ${error.javaClass.simpleName}")
            }

            if (!currentCoroutineContext().isActive) break

            attempt++
            delay(backoffFor(attempt))
        }
    }

    /**
     * Handles one ntfy message. API failures are swallowed per message: a transient 5xx
     * must not drop a healthy subscription, and the next message (or reconnect) retries
     * from the same persisted marker.
     */
    private suspend fun handleMessage() {
        val marker = parseInstant(settings.lastSosNotifiedAt)
        val events = try {
            api.sosList(sinceIso = marker?.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.warning("sos list failed: ${error.javaClass.simpleName}")
            return
        }

        val fresh = events
            .mapNotNull { event -> parseInstant(event.createdAt)?.let { event to it } }
            .filter { (event, createdAt) ->
                !event.acknowledged && (marker == null || createdAt.isAfter(marker))
            }
            .sortedBy { (_, createdAt) -> createdAt }

        fresh.forEach { (event, _) -> sink(event) }

        // Advance past every event in this answer, not only the pushed ones: an
        // acknowledged event (or an old unacknowledged one already seen by an earlier
        // cycle) must not keep the marker behind and cause repeated queries.
        val newest = (events.mapNotNull { parseInstant(it.createdAt) } + listOfNotNull(marker))
            .maxOrNull()
        if (newest != null) settings.lastSosNotifiedAt = newest.toString()
    }

    private fun backoffFor(attempt: Int): Long {
        if (backoffMs.isEmpty()) return DEFAULT_BACKOFF_MS.first()
        val index = (attempt - 1).coerceIn(0, backoffMs.lastIndex)
        return backoffMs[index]
    }

    private fun parseInstant(value: String?): Instant? {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(trimmed).toInstant()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    companion object {
        val DEFAULT_BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        private val log: Logger = Logger.getLogger(SosListener::class.java.name)
    }
}
