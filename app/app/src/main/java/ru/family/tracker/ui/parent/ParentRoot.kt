package ru.family.tracker.ui.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.Role
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.parent.SosListenerService

private enum class ParentScreen { REGISTER, PAIR_QR, HOME, HISTORY, SOS_HISTORY }

private fun initialScreen(settings: Settings): ParentScreen =
    if (settings.token.isNullOrBlank()) ParentScreen.REGISTER else ParentScreen.HOME

/**
 * Parent flow: register the family while no token is stored, otherwise land on the live
 * map. The screen is saveable, so rotation keeps the QR screen (and its code) in place;
 * the registration ViewModel owns the request and survives rotation as well.
 */
@Composable
fun ParentRoot(settings: Settings, api: ApiClient) {
    var screenName by rememberSaveable { mutableStateOf(initialScreen(settings).name) }
    var historyChildId by rememberSaveable { mutableStateOf<String?>(null) }
    val storedScreen = ParentScreen.entries.firstOrNull { it.name == screenName }
        ?: initialScreen(settings)
    // HISTORY always has a child; a restored bundle without one must not crash.
    val screen = if (storedScreen == ParentScreen.HISTORY && historyChildId == null) {
        ParentScreen.HOME
    } else {
        storedScreen
    }

    // Keep the ntfy subscription alive while the parent app is in use. The service is
    // idempotent (onCreate owns the listener), so recomposition/rotation is harmless.
    val context = LocalContext.current
    LaunchedEffect(settings.token, settings.ntfyConfig) {
        if (settings.role == Role.PARENT &&
            !settings.token.isNullOrBlank() &&
            settings.ntfyConfig != null
        ) {
            SosListenerService.start(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            ParentScreen.REGISTER -> {
                val factory = remember(settings, api) { registerParentViewModelFactory(settings, api) }
                val registerViewModel: RegisterParentViewModel = viewModel(factory = factory)
                val registerState by registerViewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(registerState) {
                    if (registerState is RegisterState.Success) {
                        screenName = ParentScreen.PAIR_QR.name
                    }
                }

                RegisterParentScreen(
                    initialServerUrl = settings.baseUrl.orEmpty(),
                    state = registerState,
                    onSubmit = registerViewModel::submit,
                )
            }

            ParentScreen.PAIR_QR -> PairQRScreen(
                settings = settings,
                api = api,
                onDone = { screenName = ParentScreen.HOME.name },
            )

            ParentScreen.HOME -> ParentMapScreen(
                api = api,
                settings = settings,
                onShowQr = { screenName = ParentScreen.PAIR_QR.name },
                onShowSosHistory = { screenName = ParentScreen.SOS_HISTORY.name },
                onChildClick = { childId ->
                    historyChildId = childId
                    screenName = ParentScreen.HISTORY.name
                },
            )

            ParentScreen.SOS_HISTORY -> SosHistoryScreen(
                api = api,
                onBack = { screenName = ParentScreen.HOME.name },
            )

            ParentScreen.HISTORY -> {
                val childId = historyChildId
                if (childId != null) {
                    HistoryScreen(
                        api = api,
                        deviceId = childId,
                        onBack = { screenName = ParentScreen.HOME.name },
                    )
                }
            }
        }

        ParentNotificationSetup(
            visible = settings.role == Role.PARENT && !settings.token.isNullOrBlank(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
