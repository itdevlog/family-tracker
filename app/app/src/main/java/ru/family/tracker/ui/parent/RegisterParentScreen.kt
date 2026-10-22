package ru.family.tracker.ui.parent

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.family.tracker.NtfyConfig
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException
import ru.family.tracker.data.api.normalizeBaseUrl

/**
 * First-run parent screen: collects the self-hosted server address and the parent's name,
 * creates the family on the server and stores the returned token and ntfy credentials.
 *
 * The base URL must be saved before the call because [ApiClient] resolves it on every
 * request; validation reuses the same normalization the client applies, so a URL that
 * passes here cannot fail inside the client.
 */
@Composable
fun RegisterParentScreen(
    settings: Settings,
    api: ApiClient,
    onRegistered: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf(settings.baseUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return

        val normalized = runCatching { normalizeBaseUrl(serverUrl) }.getOrNull()
        if (normalized == null) {
            error = context.getString(R.string.parent_register_error_invalid_url)
            return
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            error = context.getString(R.string.parent_register_error_empty_name)
            return
        }

        error = null
        busy = true
        scope.launch {
            try {
                settings.baseUrl = normalized
                val registration = api.registerParent(trimmedName)
                settings.token = registration.token
                settings.ntfyConfig = NtfyConfig(
                    url = registration.ntfy.url,
                    topic = registration.ntfy.topic,
                    token = registration.ntfy.token,
                )
                settings.baseUrl = normalized
                onRegistered()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (apiError: ApiException) {
                error = registrationErrorMessage(context, apiError.status)
            } catch (_: Exception) {
                error = context.getString(R.string.parent_register_error_network)
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.parent_register_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.parent_register_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.parent_register_server_label)) },
            supportingText = { Text(stringResource(R.string.parent_register_server_support)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.parent_register_name_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = ::submit,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.parent_register_submit),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Maps an HTTP failure to a short, actionable message; never exposes stack traces. */
private fun registrationErrorMessage(context: Context, status: Int): String = when {
    status == 409 -> context.getString(R.string.parent_register_error_conflict)
    status == 401 -> context.getString(R.string.parent_register_error_unauthorized)
    status == 403 -> context.getString(R.string.parent_register_error_forbidden)
    status == 422 -> context.getString(R.string.parent_register_error_unprocessable)
    status == 429 -> context.getString(R.string.parent_register_error_rate_limited)
    status in 500..599 -> context.getString(R.string.parent_register_error_server, status)
    else -> context.getString(R.string.parent_register_error_http, status)
}
