package ru.family.tracker.ui.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import java.util.Locale

/**
 * Parent's SOS feed: every event from `GET /sos`, newest first, with a per-event ack.
 *
 * A refresh failure keeps the previous list visible (see [SosHistoryViewModel]); the
 * error is shown as a banner with a retry action instead of replacing the content.
 */
@Composable
fun SosHistoryScreen(
    api: ApiClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(api) { sosHistoryViewModelFactory(api) }
    val viewModel: SosHistoryViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backDescription = stringResource(R.string.sos_history_back)

    BackHandler(onBack = onBack)

    // The ViewModel survives leaving the screen; on every entry refresh the feed without
    // discarding the list it already holds. The initial load is skipped while it runs.
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = backDescription },
            ) {
                Text(text = "←", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = stringResource(R.string.sos_history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Text(stringResource(R.string.sos_history_refresh))
            }
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    item(key = "load-error") {
                        SosBanner(
                            text = stringResource(error),
                            actionLabel = stringResource(R.string.sos_history_refresh),
                            onAction = viewModel::refresh,
                        )
                    }
                }
                if (state.ackFailed) {
                    item(key = "ack-error") {
                        SosBanner(
                            text = stringResource(R.string.sos_history_error_ack),
                            actionLabel = stringResource(R.string.sos_alert_retry),
                            onAction = viewModel::retryAck,
                        )
                    }
                }

                if (state.items.isEmpty() && !state.loading && state.error == null) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.sos_history_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                items(state.items, key = { it.event.eventId }) { item ->
                    SosHistoryRow(
                        item = item,
                        acking = state.ackingId == item.event.eventId,
                        ackEnabled = state.ackingId == null,
                        onAck = { viewModel.ack(item.event.eventId) },
                    )
                }
            }

            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun SosHistoryRow(
    item: SosHistoryItem,
    acking: Boolean,
    ackEnabled: Boolean,
    onAck: () -> Unit,
) {
    val event = item.event
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.childName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.sos_history_child_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (event.acknowledged) {
                            R.string.sos_history_acknowledged
                        } else {
                            R.string.sos_history_not_acknowledged
                        },
                    ),
                    color = if (event.acknowledged) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(R.string.sos_alert_time, formatSosTime(event.createdAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.sos_alert_coords,
                    String.format(Locale.US, "%.5f", event.lat),
                    String.format(Locale.US, "%.5f", event.lon),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!event.acknowledged) {
                Button(
                    onClick = onAck,
                    enabled = ackEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (acking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.sos_history_ack))
                    }
                }
            }
        }
    }
}

@Composable
private fun SosBanner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}
