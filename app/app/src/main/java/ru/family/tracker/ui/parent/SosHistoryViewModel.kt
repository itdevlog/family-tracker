package ru.family.tracker.ui.parent

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
import ru.family.tracker.data.api.SosEvent
import java.time.DateTimeException
import java.time.OffsetDateTime

/** One SOS row: the wire event plus a resolved child name (null when unknown). */
data class SosHistoryItem(
    val event: SosEvent,
    val childName: String?,
)

data class SosHistoryState(
    val loading: Boolean = false,
    val items: List<SosHistoryItem> = emptyList(),
    @StringRes val error: Int? = null,
    val ackingId: String? = null,
    val ackFailed: Boolean = false,
    val ackFailedId: String? = null,
)

/**
 * Owns the parent's SOS feed.
 *
 * The list is kept on a failed refresh/ack: losing events because the network blipped
 * would hide exactly the alerts the parent cares about. [ViewModel] scope makes the feed
 * survive rotation, and [refresh] is explicit (button), not tied to composition.
 */
class SosHistoryViewModel(
    private val api: ApiClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SosHistoryState())
    val state: StateFlow<SosHistoryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var ackJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }

        loadJob = viewModelScope.launch(dispatcher) {
            try {
                val events = api.sosList(sinceIso = null)
                val names = try {
                    api.devices().associate { it.deviceId to it.name }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Names are cosmetic: the feed still shows events without them.
                    emptyMap()
                }
                _state.update { current ->
                    current.copy(
                        loading = false,
                        items = events
                            .sortedByDescending { createdMillis(it.createdAt) }
                            .map { event ->
                                SosHistoryItem(event, names[event.child])
                            },
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { current ->
                    // Keep the already loaded list; only the error banner is new.
                    current.copy(
                        loading = false,
                        error = R.string.sos_history_error_load,
                    )
                }
            }
        }
    }

    fun ack(eventId: String) {
        if (_state.value.ackingId != null) return
        ackJob?.cancel()
        _state.update { it.copy(ackingId = eventId, ackFailed = false, ackFailedId = null) }

        ackJob = viewModelScope.launch(dispatcher) {
            try {
                api.ackSos(eventId)
                _state.update { current ->
                    current.copy(
                        ackingId = null,
                        ackFailed = false,
                        ackFailedId = null,
                        items = current.items.map { item ->
                            if (item.event.eventId == eventId) {
                                item.copy(event = item.event.copy(acknowledged = true))
                            } else {
                                item
                            }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { current ->
                    current.copy(ackingId = null, ackFailed = true, ackFailedId = eventId)
                }
            }
        }
    }

    /** Retries the acknowledgement that failed, if any. */
    fun retryAck() {
        val eventId = _state.value.ackFailedId ?: return
        _state.update { it.copy(ackFailed = false) }
        ack(eventId)
    }

    private fun createdMillis(iso: String): Long = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: DateTimeException) {
        0L
    } catch (_: ArithmeticException) {
        0L
    }
}

internal fun sosHistoryViewModelFactory(api: ApiClient): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { SosHistoryViewModel(api) }
    }
