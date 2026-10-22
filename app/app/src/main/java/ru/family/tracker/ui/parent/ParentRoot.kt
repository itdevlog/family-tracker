package ru.family.tracker.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient

private enum class ParentScreen { REGISTER, PAIR_QR, HOME }

private fun initialScreen(settings: Settings): ParentScreen =
    if (settings.token.isNullOrBlank()) ParentScreen.REGISTER else ParentScreen.HOME

/**
 * Parent flow: register the family while no token is stored, otherwise land on the home
 * stub. The screen is saveable, so rotation keeps the QR screen (and its code) in place.
 */
@Composable
fun ParentRoot(settings: Settings, api: ApiClient) {
    var screenName by rememberSaveable { mutableStateOf(initialScreen(settings).name) }
    val screen = ParentScreen.entries.firstOrNull { it.name == screenName } ?: initialScreen(settings)

    when (screen) {
        ParentScreen.REGISTER -> RegisterParentScreen(
            settings = settings,
            api = api,
            onRegistered = { screenName = ParentScreen.PAIR_QR.name },
        )

        ParentScreen.PAIR_QR -> PairQRScreen(
            settings = settings,
            api = api,
            onDone = { screenName = ParentScreen.HOME.name },
        )

        ParentScreen.HOME -> ParentHome(
            onShowQr = { screenName = ParentScreen.PAIR_QR.name },
        )
    }
}

@Composable
private fun ParentHome(onShowQr: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.parent_root_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.parent_home_coming_soon),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onShowQr,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_home_show_qr),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
