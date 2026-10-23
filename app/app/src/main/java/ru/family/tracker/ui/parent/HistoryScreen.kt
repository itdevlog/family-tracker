package ru.family.tracker.ui.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ru.family.tracker.R
import ru.family.tracker.data.api.ApiClient
import ru.family.tracker.data.api.PointIn
import ru.family.tracker.history.HistoryViewModel
import ru.family.tracker.history.StopCluster
import ru.family.tracker.history.historyViewModelFactory
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val HISTORY_ZOOM = 15.0
private const val ROUTE_WIDTH_PX = 6f
private const val CAMERA_PADDING_PX = 96

/**
 * A child's route for one day: the whole-day polyline plus markers for stops.
 *
 * The date window (today and the previous seven days) is enforced by
 * [HistoryViewModel]; the arrows are disabled at its edges. Back returns to the live map
 * without touching the QR flow.
 */
@Composable
fun HistoryScreen(
    api: ApiClient,
    deviceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(api, deviceId) { historyViewModelFactory(api, deviceId) }
    // The parent flow is hand-rolled, so viewModel() would otherwise share one instance
    // across all children (and show child A's route for child B).
    val viewModel: HistoryViewModel = viewModel(key = deviceId, factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error = state.error

    BackHandler(onBack = onBack)

    LaunchedEffect(viewModel) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        HistoryHeader(
            date = state.date,
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            onBack = onBack,
            onPreviousDay = viewModel::previousDay,
            onNextDay = viewModel::nextDay,
        )
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DayMap(
                points = state.points,
                stops = state.stops,
                modifier = Modifier.fillMaxSize(),
            )

            when {
                state.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                error != null -> HistoryMessage(
                    text = stringResource(error),
                    actionLabel = stringResource(R.string.history_retry),
                    onAction = viewModel::retry,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.points.isEmpty() -> HistoryMessage(
                    text = stringResource(R.string.history_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    date: LocalDate,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
) {
    val backDescription = stringResource(R.string.history_back)
    val previousDescription = stringResource(R.string.history_previous_day)
    val nextDescription = stringResource(R.string.history_next_day)
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }

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
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onPreviousDay,
            enabled = canGoBack,
            modifier = Modifier.semantics { contentDescription = previousDescription },
        ) {
            Text(text = "‹", fontSize = 26.sp)
        }
        Text(
            text = date.format(formatter),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(
            onClick = onNextDay,
            enabled = canGoForward,
            modifier = Modifier.semantics { contentDescription = nextDescription },
        ) {
            Text(text = "›", fontSize = 26.sp)
        }
    }
}

@Composable
private fun HistoryMessage(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DayMap(
    points: List<PointIn>,
    stops: List<StopCluster>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    // Redraw overlays only when the day actually changes, never on every recomposition:
    // recreating 100k polyline points for a state tick would be wasteful.
    var renderedKey by remember { mutableStateOf<String?>(null) }

    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(HISTORY_ZOOM)
            // Tile licence requires visible attribution ("© OpenStreetMap contributors").
            overlays.add(CopyrightOverlay(context))
        }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            val key = dayKey(points, stops)
            if (key != renderedKey) {
                renderedKey = key
                renderDay(view, points, stops, routeColor)
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

private fun dayKey(points: List<PointIn>, stops: List<StopCluster>): String =
    "${System.identityHashCode(points)}:${points.size}:" +
        "${System.identityHashCode(stops)}:${stops.size}"

private fun renderDay(
    map: MapView,
    points: List<PointIn>,
    stops: List<StopCluster>,
    routeColor: Int,
) {
    map.overlays.removeAll { it is Polyline || it is Marker }
    val context = map.context
    val geoPoints = points.map { GeoPoint(it.lat, it.lon) }

    if (geoPoints.size >= 2) {
        map.overlays.add(
            Polyline(map).apply {
                setPoints(geoPoints)
                outlinePaint.color = routeColor
                outlinePaint.strokeWidth = ROUTE_WIDTH_PX
            },
        )
    }

    if (geoPoints.isNotEmpty()) {
        map.overlays.add(
            Marker(map).apply {
                position = geoPoints.first()
                title = context.getString(R.string.history_start)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            },
        )
        if (geoPoints.size >= 2) {
            map.overlays.add(
                Marker(map).apply {
                    position = geoPoints.last()
                    title = context.getString(R.string.history_finish)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                },
            )
        }
    }

    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    stops.forEach { stop ->
        map.overlays.add(
            Marker(map).apply {
                position = GeoPoint(stop.lat, stop.lon)
                title = context.getString(R.string.history_stop_title)
                snippet = context.getString(
                    R.string.history_stop_snippet,
                    timeFormat.format(Date(stop.startMillis)),
                    timeFormat.format(Date(stop.endMillis)),
                    stop.durationMillis / 60_000,
                )
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            },
        )
    }

    fitCamera(map, geoPoints)
    map.invalidate()
}

private fun fitCamera(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return

    val box = BoundingBox.fromGeoPoints(points)
    val hasArea = box.latNorth - box.latSouth > 1e-6 || box.lonEast - box.lonWest > 1e-6
    if (points.size >= 2 && hasArea) {
        map.zoomToBoundingBox(box, true, CAMERA_PADDING_PX)
    } else {
        map.controller.setZoom(HISTORY_ZOOM)
        map.controller.setCenter(points.first())
    }
}
