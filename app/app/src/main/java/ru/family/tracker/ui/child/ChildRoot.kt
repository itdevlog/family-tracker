package ru.family.tracker.ui.child

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.Settings
import ru.family.tracker.child.SosSender
import ru.family.tracker.data.api.ApiClient

/**
 * Child destination: pairing while no token is stored, then the paired home with the
 * SOS button and the tracking controls.
 *
 * The pairing ViewModel is scoped to this destination, so an in-flight redeem survives
 * rotation.
 */
@Composable
fun ChildRoot(
    settings: Settings,
    api: ApiClient,
    sosSender: SosSender,
) {
    val factory = remember(settings, api) { childPairViewModelFactory(settings, api) }
    val pairViewModel: ChildPairViewModel = viewModel(factory = factory)
    val state by pairViewModel.state.collectAsStateWithLifecycle()

    val paired = !settings.token.isNullOrBlank() || state is ChildPairState.Success
    if (!paired) {
        ChildPairScreen(
            initialStateServerUrl = settings.baseUrl.orEmpty(),
            state = state,
            onSubmit = pairViewModel::submit,
        )
    } else {
        ChildHomeScreen(sosSender = sosSender, settings = settings)
    }
}
