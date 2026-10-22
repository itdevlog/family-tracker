package ru.family.tracker.ui.parent

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ApiException

/**
 * Shows a one-time pairing code both as large digits (manual entry) and as a QR that
 * encodes the server address plus the code.
 *
 * The fetched code lives in saveable state and the effect skips an already loaded
 * attempt, so rotating the screen does not silently invalidate the code that the child
 * is about to scan. "Обновить код" is the only path that requests a new one.
 */
@Composable
fun PairQRScreen(
    settings: Settings,
    api: ApiClient,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var loadedAttempt by rememberSaveable { mutableIntStateOf(-1) }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var expiresAt by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        if (loadedAttempt == attempt) return@LaunchedEffect

        busy = true
        error = null
        try {
            val pairCode = api.createPairCode()
            code = pairCode.code
            expiresAt = pairCode.expiresAt
            loadedAttempt = attempt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (apiError: ApiException) {
            error = pairCodeErrorMessage(context, apiError.status)
        } catch (_: Exception) {
            error = context.getString(R.string.pair_qr_error_network)
        } finally {
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pair_qr_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.pair_qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        val currentCode = code
        val currentError = error

        when {
            currentError != null -> {
                Text(
                    text = currentError,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt += 1 }) {
                    Text(stringResource(R.string.pair_qr_retry))
                }
            }

            currentCode == null -> {
                CircularProgressIndicator()
            }

            else -> {
                val qrDescription = stringResource(R.string.pair_qr_qr_description)
                val baseUrl = settings.baseUrl
                val bitmap = remember(currentCode, baseUrl) {
                    baseUrl?.let { encodeQr(qrPayload(it, currentCode)) }
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = qrDescription,
                        modifier = Modifier.size(260.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.pair_qr_error_encode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = currentCode,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    ),
                    textAlign = TextAlign.Center,
                )
                expiresAt?.let { expiry ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pair_qr_expires, expiry),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { attempt += 1 },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.pair_qr_refresh))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.pair_qr_done),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Maps an HTTP failure to a short, actionable message; never exposes stack traces. */
private fun pairCodeErrorMessage(context: Context, status: Int): String = when {
    status == 401 || status == 403 ->
        context.getString(R.string.pair_qr_error_unauthorized)

    else -> context.getString(R.string.pair_qr_error_http, status)
}
