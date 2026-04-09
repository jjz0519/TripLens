package com.cooldog.triplens.ui.sessionreview

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cooldog.triplens.domain.Segment
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.ui.common.formatDistance
import com.cooldog.triplens.ui.common.formatDuration
import com.cooldog.triplens.ui.common.modeEmoji
import com.cooldog.triplens.ui.recording.MediaItem
import com.cooldog.triplens.ui.tripdetail.TransportStat
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.cooldog.triplens.ui.recording.MapGeoJsonBuilder

/**
 * Session Review screen — full trajectory on a map + scrollable timeline.
 *
 * ## Layout
 * ```
 * ┌────────────────────────────────────┐
 * │  TopAppBar (session name + back)   │  ← Scaffold top bar
 * ├────────────────────────────────────┤
 * │                                    │
 * │          MapLibre map              │  ← weight(0.55f) of content area
 * │  (polyline + media markers)        │
 * │                                    │
 * ├────────────────────────────────────┤
 * │  [distance] [duration] [modes…]    │  ← stats header card
 * │  ─────────────────────────────     │
 * │  🚶 Walking — 1.2 km, 18 min       │  ← timeline items
 * │  📷 [photo thumbnail]              │
 * │  📝 Note text preview…             │  ← weight(0.45f) of content area
 * │  🎤 0:42 voice note                │
 * │  🚗 Driving — 45.1 km, 32 min      │
 * └────────────────────────────────────┘
 * ```
 *
 * ## Map setup
 * No live follow (unlike [com.cooldog.triplens.ui.recording.RecordingScreen]). On style load:
 * 1. Add route polyline via [MapGeoJsonBuilder.buildRouteFeatureCollection] — same data-driven
 *    [LineLayer] pattern as the recording screen.
 * 2. Add media markers via a [CircleLayer] keyed by type color.
 * 3. Fit camera to the full route bounds via [LatLngBounds].
 * 4. Register a click listener to detect marker taps and call [SessionReviewViewModel.onMarkerTapped].
 *
 * ## Timeline scroll-to
 * When [SessionReviewViewModel.UiState.Loaded.selectedMediaId] changes (set by a marker tap),
 * the screen scrolls the [LazyColumn] to the matching [TimelineItem.MediaEntry].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionReviewScreen(
    viewModel: SessionReviewViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // mapLibreMap is null until the tile style finishes loading.
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    // Create MapView once; onCreate(null) is acceptable — no camera state to restore.
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    // Collect one-shot events (snackbar messages).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SessionReviewViewModel.Event.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // Forward Android lifecycle to MapLibre's GL renderer to prevent battery drain and
    // resource leaks. Pattern is identical to RecordingScreen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START  -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView.onPause()
                Lifecycle.Event.ON_STOP   -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Scaffold(
        topBar = {
            val sessionName = (uiState as? SessionReviewViewModel.UiState.Loaded)
                ?.session?.name ?: "Session Review"
            TopAppBar(
                title = {
                    Text(
                        text = sessionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is SessionReviewViewModel.UiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is SessionReviewViewModel.UiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                }

                is SessionReviewViewModel.UiState.Loaded -> {
                    SessionReviewContent(
                        state = state,
                        mapView = mapView,
                        mapLibreMap = mapLibreMap,
                        onMapReady = { mapLibreMap = it },
                        onMarkerTapped = viewModel::onMarkerTapped,
                        onTimelineItemTapped = viewModel::onTimelineItemTapped,
                    )

                    // Preview sheet — rendered outside the content column so it overlays
                    // the full screen and is not constrained by the timeline weight.
                    state.previewEntry?.let { entry ->
                        MediaPreviewSheet(
                            entry = entry,
                            onDismiss = viewModel::onPreviewDismissed,
                        )
                    }
                }
            }
        }
    }
}

// ── Loaded content ────────────────────────────────────────────────────────────────────

@Composable
private fun SessionReviewContent(
    state: SessionReviewViewModel.UiState.Loaded,
    mapView: MapView,
    mapLibreMap: MapLibreMap?,
    onMapReady: (MapLibreMap) -> Unit,
    onMarkerTapped: (mediaId: String) -> Unit,
    onTimelineItemTapped: (TimelineItem.MediaEntry) -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to the timeline item matching selectedMediaId when a map marker is tapped.
    // +1 accounts for the SessionStatsHeader which occupies lazy-list index 0; the
    // timelineItems data list is zero-based but the LazyColumn starts at index 1.
    LaunchedEffect(state.selectedMediaId) {
        val mediaId = state.selectedMediaId ?: return@LaunchedEffect
        val index = state.timelineItems.indexOfFirst { it.id == mediaId }
        if (index >= 0) {
            listState.animateScrollToItem(index + 1)
        }
    }

    // ── Map side-effect: layer setup + camera fit ──────────────────────────────
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.getStyle { style ->
            // Route polyline — same data-driven LineLayer as RecordingScreen.
            if (style.getSource("route-source") == null) {
                style.addSource(
                    GeoJsonSource(
                        "route-source",
                        MapGeoJsonBuilder.buildRouteFeatureCollection(state.trackPoints),
                    )
                )
            }
            if (style.getLayer("route-layer") == null) {
                style.addLayer(
                    LineLayer("route-layer", "route-source").withProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }

            // Media markers — one GeoJSON point per MediaEntry with a lat/lng.
            if (style.getSource("media-source") == null) {
                style.addSource(
                    GeoJsonSource(
                        "media-source",
                        buildMediaFeatureCollection(state.timelineItems),
                    )
                )
            }
            if (style.getLayer("media-markers-layer") == null) {
                // Data-driven CircleLayer: reads "color" property from each feature.
                style.addLayerAbove(
                    CircleLayer("media-markers-layer", "media-source").withProperties(
                        PropertyFactory.circleColor(Expression.get("color")),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                    "route-layer",
                )
            }

            // Fit camera to route bounds so the full trajectory is visible on load.
            fitCameraToRoute(map, state.trackPoints)
        }
    }

    // ── Map marker click detection ─────────────────────────────────────────────
    // DisposableEffect is required here (not LaunchedEffect) because addOnMapClickListener
    // accumulates listeners — without removeOnMapClickListener in onDispose, each
    // recomposition that sets a new mapLibreMap would add a second listener, causing
    // onMarkerTapped to fire N times per tap. DisposableEffect cleans up on each new value.
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            // Convert LatLng to screen point and query the markers layer.
            val screenPoint = map.projection.toScreenLocation(latLng)
            val features = map.queryRenderedFeatures(screenPoint, "media-markers-layer")
            val tappedId = features.firstOrNull()?.getStringProperty("id")
            if (tappedId != null) {
                Log.d(TAG, "Marker tapped: id=$tappedId")
                onMarkerTapped(tappedId)
                true   // consumed
            } else {
                false  // not consumed
            }
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Map area (~55% of screen height) ──────────────────────────────────
        Box(modifier = Modifier.weight(0.55f)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Load the map style once; subsequent recompositions are no-ops.
                    view.getMapAsync { map ->
                        if (mapLibreMap == null) {
                            map.setStyle("https://tiles.openfreemap.org/styles/bright") {
                                onMapReady(map)
                            }
                        }
                    }
                },
            )
        }

        // ── Timeline column (~45% of screen height) ────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(0.45f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        ) {
            // Stats header
            item {
                SessionStatsHeader(
                    totalDistance = state.totalDistanceMeters,
                    durationSeconds = state.durationSeconds,
                    transportBreakdown = state.transportBreakdown,
                )
            }

            // Timeline items
            items(state.timelineItems, key = { it.id }) { item ->
                when (item) {
                    is TimelineItem.SegmentItem -> SegmentCard(item.segment)
                    is TimelineItem.MediaEntry -> MediaEntryRow(
                        entry = item,
                        isSelected = item.id == state.selectedMediaId,
                        onTap = { onTimelineItemTapped(item) },
                    )
                }
            }
        }
    }
}

// ── Stats header ──────────────────────────────────────────────────────────────────────

@Composable
private fun SessionStatsHeader(
    totalDistance: Double,
    durationSeconds: Long,
    transportBreakdown: List<TransportStat>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatColumn(label = "Distance", value = formatDistance(totalDistance))
                StatColumn(label = "Duration", value = formatDuration(durationSeconds))
            }
            if (transportBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    transportBreakdown.forEach { stat ->
                        Text(
                            text = "${modeEmoji(stat.mode)} ${formatDistance(stat.distanceMeters)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

// ── Timeline item composables ─────────────────────────────────────────────────────────

/**
 * Transport segment card in the timeline.
 *
 * Example: "🚶 Walking — 1.2 km · 18 min"
 */
@Composable
private fun SegmentCard(segment: Segment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${modeEmoji(segment.mode)} ${modeLabel(segment.mode)} — " +
                    "${formatDistance(segment.distanceMeters)} · ${formatDuration(segment.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A tappable media/note entry row in the timeline.
 *
 * Highlighted with a [secondaryContainer] background when its ID matches [isSelected]
 * (set by a map marker tap).
 */
@Composable
private fun MediaEntryRow(
    entry: TimelineItem.MediaEntry,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Type icon
            val (icon, tint) = when (entry.item) {
                is MediaItem.Photo     -> Icons.Default.Photo to MaterialTheme.colorScheme.primary
                is MediaItem.Video     -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
                is MediaItem.TextNote  -> Icons.Default.TextFields to MaterialTheme.colorScheme.secondary
                is MediaItem.VoiceNote -> Icons.Default.Mic to MaterialTheme.colorScheme.tertiary
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )

            // Type-specific label
            Text(
                text = when (val item = entry.item) {
                    is MediaItem.Photo     -> "Photo"
                    is MediaItem.Video     -> "Video"
                    is MediaItem.TextNote  -> item.preview.ifEmpty { "Note" }
                    is MediaItem.VoiceNote -> {
                        val m = item.durationSeconds / 60
                        val s = item.durationSeconds % 60
                        "Voice note — %d:%02d".format(m, s)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── MapLibre helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a [FeatureCollection] of point features for media markers.
 *
 * Only includes [TimelineItem.MediaEntry] items that have a non-null [TimelineItem.MediaEntry.lat]
 * and [TimelineItem.MediaEntry.lng]. Each feature carries:
 * - `"id"` — the media item's ID, used by the click listener to identify which item was tapped.
 * - `"color"` — type-based color string read by the data-driven [CircleLayer].
 */
private fun buildMediaFeatureCollection(items: List<TimelineItem>): FeatureCollection {
    val features = items
        .filterIsInstance<TimelineItem.MediaEntry>()
        .filter { it.lat != null && it.lng != null }
        .map { entry ->
            Feature.fromGeometry(Point.fromLngLat(entry.lng!!, entry.lat!!)).also { feature ->
                feature.addStringProperty("id", entry.id)
                feature.addStringProperty("color", markerColor(entry.item))
            }
        }
    return FeatureCollection.fromFeatures(features)
}

/**
 * Returns a color hex string for a media marker based on the item type.
 *
 * Colors are chosen to be distinct from each other and from the route polyline palette.
 */
private fun markerColor(item: MediaItem): String = when (item) {
    is MediaItem.Photo     -> "#1976D2"  // blue
    is MediaItem.Video     -> "#7B1FA2"  // purple
    is MediaItem.TextNote  -> "#388E3C"  // green
    is MediaItem.VoiceNote -> "#F57C00"  // orange
}

/**
 * Animates the camera to fit all non-auto-paused track points within the viewport.
 *
 * Falls back to a no-op if there are fewer than 2 active points (nothing meaningful to fit).
 * Padding is added so markers near the edge are not clipped by the bottom panel.
 */
private fun fitCameraToRoute(map: MapLibreMap, trackPoints: List<TrackPoint>) {
    val activePoints = trackPoints.filter { !it.isAutoPaused }
    if (activePoints.size < 2) {
        // Single point — zoom to it directly.
        activePoints.firstOrNull()?.let { pt ->
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(pt.latitude, pt.longitude), 14.0)
            )
        }
        return
    }

    val boundsBuilder = LatLngBounds.Builder()
    activePoints.forEach { pt -> boundsBuilder.include(LatLng(pt.latitude, pt.longitude)) }
    // 80dp padding on each side so the map tiles and markers are not clipped by the panel.
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
}

private const val TAG = "TripLens/SessionReview"

// ── Formatting helpers ────────────────────────────────────────────────────────────────
// formatDistance, formatDuration, modeEmoji are imported from ui/common/FormatUtils.kt

private fun modeLabel(mode: TransportMode): String = when (mode) {
    TransportMode.STATIONARY   -> "Stationary"
    TransportMode.WALKING      -> "Walking"
    TransportMode.CYCLING      -> "Cycling"
    TransportMode.DRIVING      -> "Driving"
    TransportMode.FAST_TRANSIT -> "Fast Transit"
}
