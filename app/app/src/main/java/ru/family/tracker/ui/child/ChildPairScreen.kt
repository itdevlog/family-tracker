package ru.family.tracker.ui.child

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.family.tracker.R
import ru.family.tracker.data.pairing.parseQrPayload

/**
 * Child pairing screen with two first-class paths: scanning the parent's QR code and
 * typing the code by hand.
 *
 * Rendering only: validation, persistence and the network call live in
 * [ChildPairViewModel]. A successful scan fills the form and shows a confirmation step;
 * it never submits on its own, because the child's name is a required field.
 */
@Composable
fun ChildPairScreen(
    initialStateServerUrl: String,
    state: ChildPairState,
    onSubmit: (serverUrl: String, code: String, name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf(initialStateServerUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var scannedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var scannedCode by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanRejected by rememberSaveable { mutableStateOf(false) }

    val busy = state is ChildPairState.Loading
    val errorMessageRes = (state as? ChildPairState.Error)?.messageRes

    if (scanning) {
        QrScanner(
            onResult = { raw ->
                val payload = parseQrPayload(raw)
                if (payload == null) {
                    scanRejected = true
                } else {
                    serverUrl = payload.server
                    code = payload.code
                    scannedServer = payload.server
                    scannedCode = payload.code
                    scanRejected = false
                }
                scanning = false
            },
            onCancel = { scanning = false },
        )
        return
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
            text = stringResource(R.string.child_pair_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.child_pair_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // The name is required on both paths, so it stays visible while confirming a scan.
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.child_pair_name_label)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        val confirmedServer = scannedServer
        val confirmedCode = scannedCode
        if (confirmedServer != null && confirmedCode != null) {
            ScanConfirmation(
                server = confirmedServer,
                code = confirmedCode,
                name = name,
                busy = busy,
                errorMessageRes = errorMessageRes,
                onSubmit = { onSubmit(confirmedServer, confirmedCode, name) },
                onManualEntry = {
                    scannedServer = null
                    scannedCode = null
                    scanRejected = false
                },
                onScanAgain = { scanning = true },
            )
        } else {
            ManualEntry(
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                code = code,
                onCodeChange = { value -> code = value.filter { it in '0'..'9' }.take(6) },
                busy = busy,
                errorMessageRes = errorMessageRes,
                scanRejected = scanRejected,
                onScan = {
                    scanRejected = false
                    scanning = true
                },
                onSubmit = { onSubmit(serverUrl, code, name) },
            )
        }
    }
}

@Composable
private fun ScanConfirmation(
    server: String,
    code: String,
    name: String,
    busy: Boolean,
    errorMessageRes: Int?,
    onSubmit: () -> Unit,
    onManualEntry: () -> Unit,
    onScanAgain: () -> Unit,
) {
    Text(
        text = stringResource(R.string.child_pair_confirm_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.child_pair_confirm_server, server),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.child_pair_confirm_code, code),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    if (name.isBlank()) {
        Text(
            text = stringResource(R.string.child_pair_confirm_name_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = stringResource(R.string.child_pair_confirm_name, name.trim()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ErrorText(errorMessageRes)

    Spacer(Modifier.height(24.dp))
    SubmitButton(busy = busy, onClick = onSubmit)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onScanAgain,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.child_pair_scan_qr))
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = onManualEntry,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.child_pair_manual_title))
    }
}

@Composable
private fun ManualEntry(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    errorMessageRes: Int?,
    scanRejected: Boolean,
    onScan: () -> Unit,
    onSubmit: () -> Unit,
) {
    Button(
        onClick = onScan,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(
            text = stringResource(R.string.child_pair_scan_qr),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (scanRejected) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.child_pair_error_scan),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))

    Text(
        text = stringResource(R.string.child_pair_manual_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.child_pair_server_label)) },
        supportingText = { Text(stringResource(R.string.child_pair_server_support)) },
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
        value = code,
        onValueChange = onCodeChange,
        label = { Text(stringResource(R.string.child_pair_code_label)) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    ErrorText(errorMessageRes)

    Spacer(Modifier.height(24.dp))
    SubmitButton(busy = busy, onClick = onSubmit)
}

@Composable
private fun SubmitButton(busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
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
                text = stringResource(R.string.child_pair_submit),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ErrorText(errorMessageRes: Int?) {
    if (errorMessageRes == null) return

    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(errorMessageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
}
