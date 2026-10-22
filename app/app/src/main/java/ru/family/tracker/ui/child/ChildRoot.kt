package ru.family.tracker.ui.child

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient

/**
 * Child destination: pairing while no token is stored, then the paired placeholder.
 *
 * The pairing ViewModel is scoped to this destination, so an in-flight redeem survives
 * rotation. Task 7 replaces the placeholder with `ChildHomeScreen`.
 */
@Composable
fun ChildRoot(settings: Settings, api: ApiClient) {
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
        ChildTrackingScreen()
    }
}
