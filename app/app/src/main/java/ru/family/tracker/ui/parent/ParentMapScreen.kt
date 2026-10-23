package ru.family.tracker.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import ru.family.tracker.R
import ru.family.tracker.Settings
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.ws.KtorLiveSocket
import ru.family.tracker.parent.ChildMarker
import ru.family.tracker.parent.LiveMonitor
import ru.family.tracker.parent.SeedState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MOSCOW = GeoPoint(55.7558, 37.6173)
private const val DEFAULT_ZOOM = 15.0

/**
 * Parent home: an OpenStreetMap view with one marker per child, fed by [LiveMonitor].
 *
 * The monitor lives as long as the composition: it is started once and stopped on
 * dispose, so the WebSocket does not outlive the screen. [onChildClick] is a hook for
 * Task 10 (route history); for now the caller leaves it as a no-op.
 */
@Composable
fun ParentMapScreen(
    api: ApiClient,
    settings: Settings,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier,
    onShowSosHistory: () -> Unit = {},
    onChildClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val monitor = remember(api, settings) {
        LiveMonitor(
            api = api,
            socketFactory = {
                KtorLiveSocket(
                    baseUrlProvider = { settings.baseUrl },
                    tokenProvider = { settings.token },
                )
            },
            scope = scope,
        )
    }
    val state by monitor.state.collectAsStateWithLifecycle()
    val currentOnChildClick by rememberUpdatedState(onChildClick)
    val qrDescription = stringResource(R.string.parent_home_show_qr)

    LaunchedEffect(monitor) { monitor.start() }
    DisposableEffect(monitor) {
        onDispose { monitor.stop() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ParentMap(
            markers = state.markers,
            onChildClick = { currentOnChildClick(it) },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = when {
                            state.error == LiveMonitor.ERROR_UNAUTHORIZED ->
                                stringResource(R.string.parent_map_status_unauthorized)

                            state.connected -> stringResource(R.string.parent_map_status_online)
                            else -> stringResource(R.string.parent_map_status_reconnecting)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.connected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    when (state.seedState) {
                        SeedState.LOADING -> Text(
                            text = stringResource(R.string.parent_map_children_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        SeedState.ERROR -> Text(
                            text = stringResource(R.string.parent_map_children_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        SeedState.READY -> Text(
                            text = stringResource(
                                R.string.parent_map_children_count,
                                state.childrenCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowSosHistory) {
                    Text(stringResource(R.string.sos_history_title))
                }
                Button(
                    onClick = onShowQr,
                    modifier = Modifier.semantics {
                        contentDescription = qrDescription
                    },
                ) {
                    Text(stringResource(R.string.parent_map_qr))
                }
            }
        }

        val placeholder = when {
            state.seedState != SeedState.READY -> null
            state.childrenCount == 0 -> R.string.parent_map_empty
            state.markers.isEmpty() -> R.string.parent_map_waiting_location
            else -> null
        }
        if (placeholder != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = stringResource(placeholder),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ParentMap(
    markers: List<ChildMarker>,
    onChildClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnChildClick by rememberUpdatedState(onChildClick)
    // Center on a child exactly once; afterwards the user's pan/zoom wins over updates.
    var centeredOnChild by remember { mutableStateOf<String?>(null) }

    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(MOSCOW)
            // Tile licence requires visible attribution ("© OpenStreetMap contributors").
            overlays.add(CopyrightOverlay(context))
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            syncMarkers(view, markers) { childId -> currentOnChildClick(childId) }

            val first = markers.firstOrNull()
            if (first != null && centeredOnChild == null) {
                centeredOnChild = first.childId
                view.controller.animateTo(GeoPoint(first.lat, first.lon))
            }
        },
    )

    DisposableEffect(lifecycleOwner, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            map.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
}

private fun syncMarkers(
    map: MapView,
    markers: List<ChildMarker>,
    onChildClick: (String) -> Unit,
) {
    map.overlays.removeAll { it is Marker }
    markers.forEach { child ->
        val marker = Marker(map).apply {
            position = GeoPoint(child.lat, child.lon)
            title = child.name
            snippet = formatMarkerTime(child.recordedAtMillis)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ ->
                onChildClick(child.childId)
                true
            }
        }
        map.overlays.add(marker)
    }
    map.invalidate()
}

private fun formatMarkerTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
