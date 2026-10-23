package ru.family.tracker.parent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.api.ws.LiveEvent
import ru.family.tracker.data.api.ws.LiveSocket
import ru.family.tracker.data.api.ws.LiveUnauthorizedException
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.logging.Logger

/** Last known position of one child, ready for an osmdroid marker. */
data class ChildMarker(
    val childId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val recordedAtMillis: Long,
)

/** Whether the initial `/devices` request has succeeded. */
enum class SeedState { LOADING, READY, ERROR }

data class LiveMapState(
    val markers: List<ChildMarker> = emptyList(),
    val connected: Boolean = false,
    val error: String? = null,
    val childrenCount: Int = 0,
    val seedState: SeedState = SeedState.LOADING,
)

/**
 * Owns the live map connection: seeds positions from `/devices`, consumes the WebSocket,
 * and fills every gap with `/locations` history.
 *
 * The socket itself is deliberately dumb (one connection, ends on close); all
 * reconnect/backoff logic lives here because each reconnect must be preceded by a
 * catch-up request, otherwise points produced during the outage would be lost.
 *
 * Catch-up intentionally runs **after** the backoff delay: points that reach the server
 * while we are waiting are then included in the next history request. Running it before
 * the delay would query too early and lose them until the following reconnect.
 */
class LiveMonitor(
    private val api: ApiClient,
    private val socketFactory: () -> LiveSocket,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {
    private val _state = MutableStateFlow(LiveMapState())
    val state: StateFlow<LiveMapState> = _state.asStateFlow()

    private val children = LinkedHashMap<String, String>()
    private val lastSeen = HashMap<String, Long>()
    private val seenPoints = HashSet<PointKey>()
    private val markers = HashMap<String, ChildMarker>()

    @Volatile
    private var seeded = false

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.update {
            it.copy(
                error = null,
                seedState = if (seeded) SeedState.READY else SeedState.LOADING,
            )
        }
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(connected = false, error = null) }
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (!seeded) {
                seedFromDevices()
            }
            catchUp()

            var unauthorized = false
            try {
                socketFactory().events().collect { event ->
                    when (event) {
                        LiveEvent.Open -> {
                            attempt = 0
                            _state.update { it.copy(connected = true, error = null) }
                            // A failed seed is retried on every connection attempt, so a
                            // transient /devices error cannot leave UUID names for long.
                            if (!seeded && seedFromDevices()) {
                                catchUp()
                            }
                        }

                        is LiveEvent.Location -> {
                            apply(
                                event.childId,
                                PointIn(
                                    lat = event.lat,
                                    lon = event.lon,
                                    accuracy = event.accuracy,
                                    speed = event.speed,
                                    recordedAtMillis = event.recordedAtMillis,
                                ),
                            )
                            _state.update { it.copy(connected = true) }
                        }

                        // SOS alerts are Task 11; the map itself has nothing to show yet.
                        is LiveEvent.Sos -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unauthorizedError: LiveUnauthorizedException) {
                unauthorized = true
            } catch (error: Exception) {
                log.warning("live socket failed: ${error.javaClass.simpleName}")
            }

            if (!currentCoroutineContext().isActive) break

            _state.update { it.copy(connected = false) }

            if (unauthorized) {
                // 4401/401 means the token is dead. Retrying would only hammer the server.
                _state.update { it.copy(error = ERROR_UNAUTHORIZED) }
                break
            }

            attempt++
            delay(backoffFor(attempt))
        }
    }

    /** Returns true when `/devices` answered, so names and the child list are authoritative. */
    private suspend fun seedFromDevices(): Boolean = try {
        api.devices().forEach { device ->
            children[device.deviceId] = device.name
            val last = device.lastLocation ?: return@forEach
            val millis = isoMillis(last.recordedAt) ?: return@forEach
            apply(
                device.deviceId,
                PointIn(
                    lat = last.lat,
                    lon = last.lon,
                    recordedAtMillis = millis,
                ),
            )
        }
        seeded = true
        _state.update {
            it.copy(seedState = SeedState.READY, childrenCount = children.size)
        }
        refreshMarkerNames()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // The map still works without the seed; WS/history may fill it in, and the
        // reconnect loop retries this request until it succeeds.
        log.warning("live seed failed: ${error.javaClass.simpleName}")
        if (!seeded) {
            _state.update {
                it.copy(seedState = SeedState.ERROR, childrenCount = children.size)
            }
        }
        false
    }

    /**
     * Requests every child's points since its last seen timestamp. Failures are logged
     * and swallowed: one broken catch-up must not stop the live loop.
     */
    private suspend fun catchUp() {
        val now = clock()
        for (childId in children.keys.toList()) {
            val from = lastSeen[childId] ?: (now - HISTORY_WINDOW_MS)
            try {
                api.history(
                    deviceId = childId,
                    fromIso = Instant.ofEpochMilli(from).toString(),
                    toIso = Instant.ofEpochMilli(now).toString(),
                )
                    .asSequence()
                    .filter { it.recordedAtMillis > from }
                    .sortedBy { it.recordedAtMillis }
                    .forEach { apply(childId, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // No coordinates or token in the log line.
                log.warning("live catch-up failed for $childId: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun apply(childId: String, point: PointIn) {
        if (!seenPoints.add(PointKey(childId, point.recordedAtMillis))) return

        val wasKnown = children.containsKey(childId)
        children.getOrPut(childId) { childId }
        if (!wasKnown) {
            _state.update { it.copy(childrenCount = children.size) }
        }

        val stamp = point.recordedAtMillis
        val previousSeen = lastSeen[childId]
        if (previousSeen == null || stamp > previousSeen) {
            lastSeen[childId] = stamp
        }

        val current = markers[childId]
        if (current == null || stamp >= current.recordedAtMillis) {
            markers[childId] = ChildMarker(
                childId = childId,
                name = children[childId] ?: childId,
                lat = point.lat,
                lon = point.lon,
                recordedAtMillis = stamp,
            )
            publishMarkers()
        }
    }

    /** A child discovered only through WS still shows its UUID until `/devices` succeeds. */
    private fun refreshMarkerNames() {
        var changed = false
        for (childId in markers.keys.toList()) {
            val marker = markers[childId] ?: continue
            val realName = children[childId] ?: continue
            if (marker.name != realName) {
                markers[childId] = marker.copy(name = realName)
                changed = true
            }
        }
        if (changed) publishMarkers()
    }

    private fun publishMarkers() {
        val ordered = markers.values.sortedWith(compareBy({ it.name }, { it.childId }))
        _state.update { it.copy(markers = ordered) }
    }

    private fun backoffFor(attempt: Int): Long {
        if (backoffMs.isEmpty()) return DEFAULT_BACKOFF_MS.first()
        val index = (attempt - 1).coerceIn(0, backoffMs.lastIndex)
        return backoffMs[index]
    }

    private data class PointKey(val childId: String, val recordedAtMillis: Long)

    companion object {
        const val ERROR_UNAUTHORIZED = "unauthorized"

        val DEFAULT_BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        private const val HISTORY_WINDOW_MS = 24L * 60 * 60 * 1000

        private val log: Logger = Logger.getLogger(LiveMonitor::class.java.name)

        private fun isoMillis(value: String): Long? = try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
