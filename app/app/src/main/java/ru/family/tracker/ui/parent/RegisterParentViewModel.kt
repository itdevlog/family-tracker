package ru.family.tracker.ui.parent

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
import ru.family.tracker.NtfyConfig
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.api.normalizeBaseUrl

sealed interface RegisterState {
    data object Idle : RegisterState
    data object Loading : RegisterState
    data object Success : RegisterState
    data class Error(@StringRes val messageRes: Int) : RegisterState
}

/**
 * Owns family registration for the whole lifetime of the parent destination.
 *
 * Living in a [ViewModel] keeps the in-flight request alive across configuration changes:
 * the server may already have created the family, so a rotation must never turn into a
 * second `POST /pair/parent` (which would fail with 409 and leave the app tokenless).
 *
 * On success the token, ntfy credentials and normalized server URL are persisted before
 * [RegisterState.Success] is emitted, so the QR screen can rely on them.
 */
class RegisterParentViewModel(
    private val settings: Settings,
    private val api: ApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow<RegisterState>(RegisterState.Idle)
    val state: StateFlow<RegisterState> = _state.asStateFlow()

    fun submit(serverUrl: String, name: String) {
        if (_state.value is RegisterState.Loading || _state.value is RegisterState.Success) return

        val normalized = runCatching { normalizeBaseUrl(serverUrl) }.getOrNull()
        if (normalized == null) {
            _state.value = RegisterState.Error(R.string.parent_register_error_invalid_url)
            return
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            _state.value = RegisterState.Error(R.string.parent_register_error_empty_name)
            return
        }

        // Written once, before the request: ApiClient resolves the base URL per call.
        settings.baseUrl = normalized
        _state.value = RegisterState.Loading

        viewModelScope.launch {
            try {
                val registration = api.registerParent(trimmedName)
                settings.token = registration.token
                settings.ntfyConfig = NtfyConfig(
                    url = registration.ntfy.url,
                    topic = registration.ntfy.topic,
                    token = registration.ntfy.token,
                )
                _state.value = RegisterState.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (apiError: ApiException) {
                _state.value = RegisterState.Error(errorMessageRes(apiError.status))
            } catch (_: Exception) {
                _state.value = RegisterState.Error(R.string.parent_register_error_network)
            }
        }
    }

    private fun errorMessageRes(status: Int): Int = when {
        status == 409 -> R.string.parent_register_error_conflict
        status in 500..599 -> R.string.parent_register_error_server
        else -> R.string.parent_register_error_unknown
    }
}

internal fun registerParentViewModelFactory(
    settings: Settings,
    api: ApiClient,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { RegisterParentViewModel(settings, api) }
}
