package ru.family.tracker.ui.child

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.family.tracker.R
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.api.normalizeBaseUrl

sealed interface ChildPairState {
    data object Idle : ChildPairState
    data object Loading : ChildPairState
    data object Success : ChildPairState
    data class Error(@StringRes val messageRes: Int) : ChildPairState
}

/** Exactly six ASCII digits, matching the code the parent's server issues. */
private val sixDigitCode = Regex("[0-9]{6}")

/**
 * Owns child pairing for the lifetime of the child destination.
 *
 * Validation runs before the ViewModel touches the network or [Settings]: an invalid
 * server address, a malformed code or a blank name can never consume a pairing code.
 * The request lives in [viewModelScope], so rotating or recreating the screen keeps it
 * in flight instead of sending `POST /pair/child` twice (the server would answer 410
 * because the first call already redeemed the code).
 */
class ChildPairViewModel(
    private val settings: Settings,
    private val api: ApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow<ChildPairState>(ChildPairState.Idle)
    val state: StateFlow<ChildPairState> = _state.asStateFlow()

    private var busy = false

    fun submit(serverUrl: String, code: String, name: String) {
        if (busy) return

        val normalized = runCatching { normalizeBaseUrl(serverUrl) }.getOrNull()
        if (normalized == null) {
            _state.value = ChildPairState.Error(R.string.child_pair_error_invalid_server)
            return
        }
        val trimmedCode = code.trim()
        if (!sixDigitCode.matches(trimmedCode)) {
            _state.value = ChildPairState.Error(R.string.child_pair_error_invalid_code)
            return
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _state.value = ChildPairState.Error(R.string.child_pair_error_empty_name)
            return
        }

        busy = true
        _state.value = ChildPairState.Loading

        viewModelScope.launch {
            try {
                // ApiClient resolves the base URL per call, so it must be saved first.
                settings.baseUrl = normalized
                val registration = api.redeemChildCode(trimmedCode, trimmedName)
                settings.token = registration.token
                settings.role = Role.CHILD
                _state.value = ChildPairState.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (apiError: ApiException) {
                _state.value = ChildPairState.Error(errorMessageRes(apiError.status))
            } catch (_: Exception) {
                _state.value = ChildPairState.Error(R.string.child_pair_error_network)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Maps the server status to a user-facing message. The server's `error` string is
     * never shown: it may contain internals and is not localized.
     */
    private fun errorMessageRes(status: Int): Int = when {
        status == 410 -> R.string.child_pair_error_code_used
        status == 404 -> R.string.child_pair_error_code_invalid
        status == 422 -> R.string.child_pair_error_invalid_input
        status == 429 -> R.string.child_pair_error_too_many_attempts
        status == 503 -> R.string.child_pair_error_server_busy
        status in 500..599 -> R.string.child_pair_error_server
        else -> R.string.child_pair_error_unknown
    }
}

internal fun childPairViewModelFactory(
    settings: Settings,
    api: ApiClient,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { ChildPairViewModel(settings, api) }
}
