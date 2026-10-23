package ru.family.tracker.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.data.hasKnownLocation
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Number of complete days before today that history is available for. */
internal const val HISTORY_DAYS_BACK = 7L

data class HistoryState(
    val date: LocalDate,
    val loading: Boolean = false,
    val points: List<PointIn> = emptyList(),
    val stops: List<StopCluster> = emptyList(),
    @StringRes val error: Int? = null,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

/**
 * Owns one day of a child's route.
 *
 * The ViewModel keeps [HistoryState.date] in memory, so rotation does not reset the day.
 * Date changes always cancel the previous request: the server can take seconds, and a
 * slow answer for yesterday must never overwrite today's route.
 */
class HistoryViewModel(
    private val api: ApiClient,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val initialDate: LocalDate = today()
    private val _state = MutableStateFlow(
        HistoryState(
            date = initialDate,
            canGoBack = initialDate.isAfter(oldestDate()),
            canGoForward = initialDate.isBefore(today()),
        ),
    )
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        load(initialDate)
    }

    fun selectDate(date: LocalDate) {
        val today = today()
        val oldest = today.minusDays(HISTORY_DAYS_BACK)
        if (date.isBefore(oldest) || date.isAfter(today)) return
        load(date)
    }

    fun previousDay() {
        selectDate(_state.value.date.minusDays(1))
    }

    fun nextDay() {
        selectDate(_state.value.date.plusDays(1))
    }

    fun retry() {
        load(_state.value.date)
    }

    /**
     * Reloads the current day when the screen is (re)entered or the device rotates.
     * Skipped while a request is already in flight, so the initial load is not doubled.
     */
    fun refresh() {
        if (!_state.value.loading) load(_state.value.date)
    }

    private fun load(date: LocalDate) {
        loadJob?.cancel()
        val requestedDate = date
        val generation = ++loadGeneration
        val previous = _state.value
        // Keep the already shown route on a same-day reload (refresh/retry); a date
        // change must not display the previous day under the new date.
        val keepRoute = previous.date == requestedDate

        _state.value = HistoryState(
            date = requestedDate,
            loading = true,
            points = if (keepRoute) previous.points else emptyList(),
            stops = if (keepRoute) previous.stops else emptyList(),
            canGoBack = requestedDate.isAfter(oldestDate()),
            canGoForward = requestedDate.isBefore(today()),
        )

        loadJob = viewModelScope.launch(dispatcher) {
            try {
                val from = requestedDate.atStartOfDay(zone).toInstant().toString()
                val to = requestedDate.plusDays(1).atStartOfDay(zone)
                    .toInstant().minusMillis(1).toString()
                // 0,0 is the server's "no fix" sentinel; it must not become a marker,
                // a polyline vertex or a camera target.
                val points = api.history(deviceId, from, to)
                    .filter { hasKnownLocation(it.lat, it.lon) }

                // A cancelled coroutine can still finish a blocking call; the date and
                // generation checks make a stale answer harmless even then.
                if (!isCurrent(requestedDate, generation)) return@launch
                // Compute once: StateFlow.update may retry its lambda on a concurrent
                // write, and findStops is O(n log n).
                val stops = findStops(points)
                _state.update { current ->
                    if (!isCurrent(requestedDate, generation)) {
                        current
                    } else {
                        current.copy(
                            loading = false,
                            points = points,
                            stops = stops,
                            error = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (apiError: ApiException) {
                fail(requestedDate, generation, errorRes(apiError.status))
            } catch (_: IOException) {
                fail(requestedDate, generation, R.string.history_error_load)
            } catch (_: Exception) {
                fail(requestedDate, generation, R.string.history_error_load)
            }
        }
    }

    private fun isCurrent(date: LocalDate, generation: Int): Boolean =
        loadGeneration == generation && _state.value.date == date

    private fun fail(date: LocalDate, generation: Int, @StringRes messageRes: Int) {
        if (!isCurrent(date, generation)) return
        _state.update { current ->
            if (!isCurrent(date, generation)) {
                current
            } else {
                // Keep points/stops: an error after a refresh is shown over the route
                // that is already on screen. A first load has nothing to keep.
                current.copy(
                    loading = false,
                    error = messageRes,
                )
            }
        }
    }

    private fun errorRes(status: Int): Int = when (status) {
        401, 403, 404 -> R.string.history_error_access
        413, 422 -> R.string.history_error_range
        else -> R.string.history_error_load
    }

    private fun today(): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    private fun oldestDate(): LocalDate = today().minusDays(HISTORY_DAYS_BACK)
}

internal fun historyViewModelFactory(
    api: ApiClient,
    deviceId: String,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { HistoryViewModel(api, deviceId) }
}
