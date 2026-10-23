package ru.family.tracker.ui.parent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient

private enum class ParentScreen { REGISTER, PAIR_QR, HOME }

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
    val screen = ParentScreen.entries.firstOrNull { it.name == screenName } ?: initialScreen(settings)

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
            // Task 10 wires marker taps to the route history screen.
            onChildClick = {},
        )
    }
}
