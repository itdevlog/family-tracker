package ru.family.tracker.ui.child

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.R
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
        PairedPlaceholder()
    }
}

@Composable
private fun PairedPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.child_root_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.child_pair_success_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
