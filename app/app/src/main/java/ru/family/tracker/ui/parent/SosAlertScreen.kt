package ru.family.tracker.ui.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.parent.SosAlert
import ru.family.tracker.parent.mapPoint
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

private val SOS_RED = Color(0xFFB00020)
private const val SOS_MAP_ZOOM = 16.0

/**
 * Full-screen SOS alert shown above the whole app, regardless of the selected role.
 *
 * "Подтвердить получение" acknowledges on the server (and closes only on success);
 * "Позже"/back closes locally without acknowledging, so the event stays unread for the
 * parent. The mini map is osmdroid, the same tile source as the other screens.
 */
@Composable
fun SosAlertScreen(
    alert: SosAlert,
    api: ApiClient,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A second SOS while the screen is already visible replaces the alert in place.
    // Keying the whole content by event id resets the ack state and creates a new MapView
    // centered on the new coordinates instead of keeping the previous alert's map.
    key(alert.eventId) {
        SosAlertContent(alert = alert, api = api, onClose = onClose, modifier = modifier)
    }
}

@Composable
private fun SosAlertContent(
    alert: SosAlert,
    api: ApiClient,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var childName by remember(alert.childId) { mutableStateOf<String?>(null) }
    var acking by remember { mutableStateOf(false) }
    var ackFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    LaunchedEffect(alert.childId) {
        childName = try {
            api.devices().firstOrNull { it.deviceId == alert.childId }?.name
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    val acknowledge = {
        if (!acking) {
            acking = true
            ackFailed = false
            scope.launch {
                try {
                    api.ackSos(alert.eventId)
                    // The acknowledged event no longer needs to occupy the shade.
                    NotificationManagerCompat.from(context)
                        .cancel(alert.eventId.hashCode())
                    onClose()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ackFailed = true
                } finally {
                    acking = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SOS_RED)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.sos_alert_title),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Text(
            text = childName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.sos_alert_child_unknown),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.sos_alert_time, formatSosTime(alert.createdAtIso)),
            color = Color.White,
            fontSize = 16.sp,
        )
        val mapPoint = alert.mapPoint()
        Text(
            text = if (mapPoint == null) {
                stringResource(R.string.sos_coords_unknown)
            } else {
                stringResource(
                    R.string.sos_alert_coords,
                    String.format(Locale.US, "%.5f", mapPoint.lat),
                    String.format(Locale.US, "%.5f", mapPoint.lon),
                )
            },
            color = Color.White,
            fontSize = 14.sp,
        )

        // A 0,0 sentinel must not become a marker or move the camera to the Atlantic.
        if (mapPoint != null) {
            SosMiniMap(
                lat = mapPoint.lat,
                lon = mapPoint.lon,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        if (ackFailed) {
            Text(
                text = stringResource(R.string.sos_alert_ack_error),
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = acknowledge,
            enabled = !acking,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = SOS_RED,
            ),
        ) {
            if (acking) {
                CircularProgressIndicator(
                    modifier = Modifier.height(22.dp),
                    color = SOS_RED,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(
                        if (ackFailed) R.string.sos_alert_retry else R.string.sos_alert_ack,
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        if (acking) {
            Text(
                text = stringResource(R.string.sos_alert_ack_sending),
                color = Color.White,
                fontSize = 13.sp,
            )
        }

        TextButton(
            onClick = onClose,
            enabled = !acking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.sos_alert_later),
                color = Color.White,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun SosMiniMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.sos_alert_map_description)
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(SOS_MAP_ZOOM)
            controller.setCenter(GeoPoint(lat, lon))
            overlays.add(CopyrightOverlay(context))
            overlays.add(
                Marker(this).apply {
                    position = GeoPoint(lat, lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                },
            )
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier.semantics { contentDescription = description },
    )

    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
}

internal fun formatSosTime(iso: String): String {
    val millis = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) {
        return iso
    }
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}
