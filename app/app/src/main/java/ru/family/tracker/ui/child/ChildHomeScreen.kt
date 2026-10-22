package ru.family.tracker.ui.child

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.family.tracker.R
import ru.family.tracker.child.SosFlushWorker
import ru.family.tracker.child.SosSender
import ru.family.tracker.data.db.PointDao

private enum class SosStatus { IDLE, SENDING, SENT, PENDING }

/**
 * Paired child's home: a hold-to-confirm SOS plus the tracking controls.
 *
 * A press is always written to Room first (inside [SosSender]); when the send fails the
 * event stays queued and [SosFlushWorker] is scheduled for the next network window.
 * A missing location fix must not block the alert, so the documented `0.0, 0.0` is sent
 * and the screen says so.
 */
@Composable
fun ChildHomeScreen(
    sosSender: SosSender,
    pointDao: PointDao,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusName by rememberSaveable { mutableStateOf(SosStatus.IDLE.name) }
    val status = SosStatus.entries.firstOrNull { it.name == statusName } ?: SosStatus.IDLE
    var locationMissing by rememberSaveable { mutableStateOf(false) }

    fun sendSos() {
        if (status == SosStatus.SENDING) return
        statusName = SosStatus.SENDING.name
        scope.launch {
            val point = try {
                pointDao.latestPoint()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            locationMissing = point == null

            val sent = withContext(Dispatchers.IO) {
                try {
                    sosSender.trySend(
                        lat = point?.lat ?: 0.0,
                        lon = point?.lon ?: 0.0,
                    )
                } catch (cancellation: CancellationException) {
                    // The press may already be in Room; schedule a worker before letting the
                    // UI coroutine die (rotation/back), otherwise the event waits for tracking.
                    runCatching { SosFlushWorker.enqueue(context) }
                    throw cancellation
                } catch (_: Exception) {
                    false
                }
            }

            statusName = if (sent) SosStatus.SENT.name else SosStatus.PENDING.name
            if (!sent) {
                runCatching { SosFlushWorker.enqueue(context) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.child_sos_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        LongPressSosButton(
            onTriggered = ::sendSos,
            enabled = status != SosStatus.SENDING,
        )

        Text(
            text = stringResource(R.string.child_sos_hold_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        when (status) {
            SosStatus.SENDING -> Text(
                text = stringResource(R.string.child_sos_sending),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            SosStatus.SENT -> Text(
                text = stringResource(R.string.child_sos_sent),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )

            SosStatus.PENDING -> Text(
                text = stringResource(R.string.child_sos_pending),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )

            SosStatus.IDLE -> Unit
        }

        if (locationMissing && status != SosStatus.IDLE && status != SosStatus.SENDING) {
            Text(
                text = stringResource(R.string.child_sos_no_location),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.child_tracking_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        TrackingControls(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Circular SOS pad that fires after exactly [HOLD_MS] of continuous pressure.
 *
 * The ring tracks how long the finger has been down; releasing earlier cancels the press.
 * A haptic pulse marks the moment the alert is accepted, and the callback fires once per
 * gesture even if the sending state later re-enables the button.
 */
@Composable
fun LongPressSosButton(
    onTriggered: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val progress = remember { Animatable(0f) }
    var pressing by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val latestOnTriggered by rememberUpdatedState(onTriggered)
    val description = stringResource(R.string.child_sos_button_description)

    LaunchedEffect(pressing, enabled) {
        if (!pressing) {
            fired = false
            progress.animateTo(0f, tween(SOS_RELEASE_MS))
            return@LaunchedEffect
        }
        if (!enabled || fired) return@LaunchedEffect

        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = HOLD_MS, easing = LinearEasing),
        )
        fired = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        latestOnTriggered()
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.error
    val padColor = MaterialTheme.colorScheme.errorContainer
    val labelColor = MaterialTheme.colorScheme.onErrorContainer

    Box(
        modifier = modifier
            .size(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressing = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressing = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(
                width = size.width - stroke,
                height = size.height - stroke,
            )
            val topLeft = Offset(inset, inset)

            drawCircle(
                color = padColor,
                radius = size.minDimension / 2 - stroke - 4.dp.toPx(),
            )
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Text(
            text = stringResource(R.string.child_sos_button_label),
            style = MaterialTheme.typography.displaySmall,
            color = labelColor,
        )
    }
}

internal const val HOLD_MS = 2_000
private const val SOS_RELEASE_MS = 150
