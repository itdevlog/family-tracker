package ru.family.tracker.ui.child

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.child.SosFlushWorker
import ru.family.tracker.child.SosSender

internal enum class SosStatus { IDLE, SENDING, SENT, PENDING, REJECTED }

/**
 * Saved results survive recreation; SENDING does not.
 *
 * A legacy or crashed screen may still have "SENDING" in the saved bundle. Restoring it
 * would leave the button disabled forever, so anything but a real result maps to IDLE.
 */
internal fun restoredSosStatus(savedName: String?): SosStatus = when (savedName) {
    SosStatus.SENT.name -> SosStatus.SENT
    SosStatus.PENDING.name -> SosStatus.PENDING
    SosStatus.REJECTED.name -> SosStatus.REJECTED
    else -> SosStatus.IDLE
}

/**
 * Paired child's home: a hold-to-confirm SOS plus the tracking controls.
 *
 * A press is always written to Room first (inside [SosSender]); when the send fails the
 * event stays queued and [SosFlushWorker] is scheduled for the next network window.
 * Coordinates come from [Settings.lastPoint], not the upload outbox, because uploaded
 * points are deleted. A missing fix must not block the alert, so the documented
 * `0.0, 0.0` is sent and the screen says that the coordinates are unknown.
 */
@Composable
fun ChildHomeScreen(
    sosSender: SosSender,
    settings: Settings,
    modifier: Modifier = Modifier,
    onOpenWizard: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resultName by rememberSaveable { mutableStateOf(SosStatus.IDLE.name) }
    var sending by remember { mutableStateOf(false) }
    var locationMissing by rememberSaveable { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<UploadStatusSnapshot?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Never trust a restored SENDING: the coroutine that would finish it no longer exists.
    LaunchedEffect(Unit) {
        if (resultName == SosStatus.SENDING.name) resultName = SosStatus.IDLE.name
    }

    val resultStatus = restoredSosStatus(resultName)
    val status = if (sending) SosStatus.SENDING else resultStatus

    // Re-read on every resume and every 30 s while the screen is visible. Settings and
    // ConnectivityManager are IO-ish, so keep them off the main thread.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                uploadStatus = withContext(Dispatchers.IO) {
                    UploadStatusSnapshot(
                        lastUploadAtMillis = settings.lastUploadAtMillis,
                        errorCode = settings.lastUploadErrorCode,
                        online = isNetworkOnline(context),
                        nowMillis = System.currentTimeMillis(),
                        locationErrorCode = settings.locationErrorCode,
                    )
                }
                delay(UPLOAD_STATUS_REFRESH_MS)
            }
        }
    }

    // A failed SOS stays PENDING until Room confirms the outbox is empty. This also covers
    // delivery by SosFlushWorker while the screen is open, so the child sees the outcome
    // without pressing the button again. Polling stops as soon as the screen is paused.
    LaunchedEffect(status, lifecycleOwner) {
        if (status != SosStatus.PENDING) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(SOS_PENDING_POLL_MS)
                val (pending, dropError) = withContext(Dispatchers.IO) {
                    runCatching {
                        sosSender.pendingCount() to settings.lastSosDropErrorCode
                    }.getOrNull() ?: (1 to null)
                }
                if (pending == 0) {
                    resultName = if (dropError != null) {
                        SosStatus.REJECTED.name
                    } else {
                        SosStatus.SENT.name
                    }
                    break
                }
            }
        }
    }

    fun sendSos() {
        if (sending) return
        sending = true
        // Schedule before the request: if the process dies mid-network, the event already
        // written to Room still has a worker. KEEP deduplicates this with the failure path.
        runCatching { SosFlushWorker.enqueue(context) }

        scope.launch {
            try {
                val point = withContext(Dispatchers.IO) { settings.lastPoint }
                locationMissing = point == null

                val (sent, dropError) = withContext(Dispatchers.IO) {
                    try {
                        val accepted = sosSender.trySend(
                            lat = point?.lat ?: 0.0,
                            lon = point?.lon ?: 0.0,
                        )
                        accepted to settings.lastSosDropErrorCode
                    } catch (cancellation: CancellationException) {
                        // The press may already be in Room; schedule a worker before letting the
                        // UI coroutine die (rotation/back), otherwise the event waits for tracking.
                        runCatching { SosFlushWorker.enqueue(context) }
                        throw cancellation
                    } catch (_: Exception) {
                        false to null
                    }
                }

                resultName = when {
                    !sent -> SosStatus.PENDING.name
                    dropError != null -> SosStatus.REJECTED.name
                    else -> SosStatus.SENT.name
                }
                if (!sent) {
                    runCatching { SosFlushWorker.enqueue(context) }
                }
            } finally {
                sending = false
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
            enabled = !sending,
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

            SosStatus.REJECTED -> Text(
                text = stringResource(R.string.child_sos_rejected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )

            SosStatus.IDLE -> Unit
        }

        if (locationMissing && status != SosStatus.IDLE &&
            status != SosStatus.SENDING && status != SosStatus.REJECTED
        ) {
            Text(
                text = stringResource(R.string.child_sos_no_location),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        if (uploadStatus?.let { isLocationUnavailable(it.locationErrorCode) } == true) {
            Text(
                text = stringResource(R.string.child_location_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        uploadStatus?.let { snapshot ->
            val kind = uploadStatusKind(snapshot)
            val lastUpload = snapshot.lastUploadAtMillis ?: snapshot.nowMillis
            val text = when (kind) {
                UploadStatusKind.REJECTED -> stringResource(R.string.child_upload_rejected)
                UploadStatusKind.NEVER -> stringResource(R.string.child_upload_never)
                UploadStatusKind.OFFLINE_NEVER -> stringResource(R.string.child_upload_offline_never)
                UploadStatusKind.ONLINE -> stringResource(
                    R.string.child_upload_online,
                    formatRelativeTime(lastUpload, snapshot.nowMillis),
                )
                UploadStatusKind.OFFLINE -> stringResource(
                    R.string.child_upload_offline,
                    formatRelativeTime(lastUpload, snapshot.nowMillis),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (kind == UploadStatusKind.REJECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.child_tracking_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenWizard) {
                Text(stringResource(R.string.child_wizard_open))
            }
        }
        TrackingControls(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Circular SOS pad that fires after exactly [HOLD_MS] of continuous pressure.
 *
 * The hold is measured by [delay] (monotonic) rather than by the animation clock, so
 * "Remove animations" cannot fire the alert instantly; the ring is decoration only.
 * Releasing earlier cancels the press. A haptic pulse marks the moment the alert is
 * accepted, and the callback fires once per gesture even if the sending state later
 * re-enables the button.
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
        val ring = launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = HOLD_MS, easing = LinearEasing),
            )
        }
        delay(HOLD_MS.toLong())
        ring.cancel()
        progress.snapTo(1f)

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
internal const val UPLOAD_STATUS_REFRESH_MS = 30_000L
internal const val SOS_PENDING_POLL_MS = 5_000L
private const val SOS_RELEASE_MS = 150
