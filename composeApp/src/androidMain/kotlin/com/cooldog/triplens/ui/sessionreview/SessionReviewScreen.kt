package com.cooldog.triplens.ui.sessionreview

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cooldog.triplens.R
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.model.TransportMode
import com.cooldog.triplens.ui.common.PhotoCard
import com.cooldog.triplens.ui.common.formatDistance
import com.cooldog.triplens.ui.common.formatDuration
import com.cooldog.triplens.ui.recording.MapGeoJsonBuilder
import com.cooldog.triplens.ui.recording.MediaItem
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
import com.cooldog.triplens.ui.tripdetail.TransportStat
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "TripLens/SessionReview"

/**
 * Session Review screen — biophilic redesign (Task 25).
 *
 * ## Layout
 * ```
 * ┌────────────────────────────────────┐
 * │  Top bar row (back + date + name)  │  ← no TopAppBar widget, custom row
 * │  Stat ribbon (4 cards)             │
 * │  MapLibre map (240 dp fixed)       │
 * │  LazyColumn (weight 1f):           │
 * │    ActivityStrip (transport bar)   │
 * │    Timeline header                 │
 * │    BiophilicTimelineItem × N       │
 * │    "end of session"                │
 * └────────────────────────────────────┘
 * ```
 *
 * ## Map setup
 * No live follow (unlike RecordingScreen). On style load:
 * 1. Route polyline via [MapGeoJsonBuilder.buildRouteFeatureCollection].
 * 2. Media markers via [CircleLayer] keyed by type color.
 * 3. Camera fit via [LatLngBounds].
 * 4. Click listener to detect marker taps → [SessionReviewViewModel.onMarkerTapped].
 *
 * ## Timeline scroll-to
 * When [SessionReviewViewModel.UiState.Loaded.selectedMediaId] changes, the screen scrolls
 * the [LazyColumn] to the matching [TimelineItem.MediaEntry]. Index is offset by +2 to account
 * for the ActivityStrip and Timeline header items preceding the timeline data in the lazy list.
 */
@Composable
fun SessionReviewScreen(
    viewModel: SessionReviewViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val bio = LocalBiophilicColors.current
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
        containerColor = bio.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is SessionReviewViewModel.UiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = bio.moss,
                    )
                }

                is SessionReviewViewModel.UiState.Error -> {
                    Text(
                        text = state.message,
                        color = bio.recordRed,
                        fontSize = 14.sp,
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
                        bio = bio,
                        onMapReady = { mapLibreMap = it },
                        onMarkerTapped = viewModel::onMarkerTapped,
                        onTimelineItemTapped = viewModel::onTimelineItemTapped,
                        onBack = onBack,
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
    bio: BiophilicColors,
    onMapReady: (MapLibreMap) -> Unit,
    onMarkerTapped: (mediaId: String) -> Unit,
    onTimelineItemTapped: (TimelineItem.MediaEntry) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to the timeline item matching selectedMediaId when a map marker is tapped.
    // +2 accounts for the ActivityStrip item (index 0) and the Timeline header item (index 1)
    // that precede the timelineItems data in the LazyColumn.
    LaunchedEffect(state.selectedMediaId) {
        val mediaId = state.selectedMediaId ?: return@LaunchedEffect
        val index = state.timelineItems.indexOfFirst { it.id == mediaId }
        if (index >= 0) {
            listState.animateScrollToItem(index + 2)
        }
    }

    // ── Map side-effect: layer setup + camera fit ──────────────────────────────
    // Lives inside the Loaded branch so it only runs once we have real data.
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

    // Moment count = number of MediaEntry items in the timeline.
    val momentCount = state.timelineItems.count { it is TimelineItem.MediaEntry }

    // Pace = average speed in min/km; guard against zero distance and duration.
    val paceStr = if (state.totalDistanceMeters > 0 && state.durationSeconds > 0) {
        val minPerKm = (state.durationSeconds / 60.0) / (state.totalDistanceMeters / 1000.0)
        "${minPerKm.toInt()}'"
    } else {
        "—"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 1. Top bar row ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 40 dp circle back button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bio.surface)
                    .border(1.dp, bio.line2, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = bio.ink,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Title block: date label above session name
            Column {
                Text(
                    text = formatDateLabel(state.session.startTime).uppercase(Locale.getDefault()),
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    color = bio.ink3,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = state.session.name,
                    fontSize = 22.sp,
                    fontFamily = InstrumentSerifFamily,
                    color = bio.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── 2. Stat ribbon ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionStat(
                value = formatDistance(state.totalDistanceMeters),
                unit = "",
                label = "Distance",
                primary = true,
                bio = bio,
                modifier = Modifier.weight(1f),
            )
            SessionStat(
                value = formatDuration(state.durationSeconds),
                unit = "",
                label = "Duration",
                primary = false,
                bio = bio,
                modifier = Modifier.weight(1f),
            )
            SessionStat(
                value = momentCount.toString(),
                unit = "",
                label = "Moments",
                primary = false,
                bio = bio,
                modifier = Modifier.weight(1f),
            )
            SessionStat(
                value = paceStr,
                unit = "/km",
                label = "Pace",
                primary = false,
                bio = bio,
                modifier = Modifier.weight(1f),
            )
        }

        // ── 3. Map (fixed 240 dp) ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
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

        // ── 4. LazyColumn — activity strip + timeline ──────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── ActivityStrip (index 0) ──────────────────────────────────────────
            item(key = "activity_strip") {
                ActivityStrip(bio = bio, breakdown = state.transportBreakdown)
                Spacer(Modifier.height(16.dp))
            }

            // ── Timeline header row (index 1) ────────────────────────────────────
            item(key = "timeline_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Timeline",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = bio.ink,
                    )
                    Text(
                        text = "$momentCount moments",
                        fontSize = 12.sp,
                        color = bio.ink3,
                    )
                }
            }

            // ── Timeline items (index 2+) ────────────────────────────────────────
            itemsIndexed(
                items = state.timelineItems,
                key = { _, item -> item.id },
            ) { index, item ->
                val isLast = index == state.timelineItems.lastIndex
                when (item) {
                    is TimelineItem.SegmentItem -> BiophilicTimelineItem(
                        bio = bio,
                        item = item,
                        isLast = isLast,
                        isActive = false,
                        onTimelineItemTapped = { /* segments are not tappable */ },
                    )
                    is TimelineItem.MediaEntry -> BiophilicTimelineItem(
                        bio = bio,
                        item = item,
                        isLast = isLast,
                        isActive = item.id == state.selectedMediaId,
                        onTimelineItemTapped = { onTimelineItemTapped(item) },
                    )
                }
            }

            // ── End of session footer ────────────────────────────────────────────
            item(key = "end_of_session") {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "— end of session —",
                    fontSize = 12.sp,
                    color = bio.ink3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── Stat card ─────────────────────────────────────────────────────────────────────────

/**
 * A single stat card in the ribbon.
 *
 * @param primary If true, uses [BiophilicColors.mossPale] background with [BiophilicColors.mossDeep]
 *                text — visually emphasises the distance stat. Otherwise uses [BiophilicColors.surface]
 *                with [BiophilicColors.line2] border.
 */
@Composable
private fun SessionStat(
    value: String,
    unit: String,
    label: String,
    primary: Boolean,
    bio: BiophilicColors,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (primary) bio.mossPale else bio.surface
    val valueColor = if (primary) bio.mossDeep else bio.ink
    val borderMod = if (primary) Modifier else Modifier.border(1.dp, bio.line2, RoundedCornerShape(10.dp))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(borderMod)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Value + unit on the same line, both in Instrument Serif 20sp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    fontSize = 20.sp,
                    fontFamily = InstrumentSerifFamily,
                    color = valueColor,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        fontSize = 10.sp,
                        fontFamily = InstrumentSerifFamily,
                        color = valueColor,
                    )
                }
            }
            Text(
                text = label,
                fontSize = 10.sp,
                letterSpacing = 0.3.sp,
                color = bio.ink3,
                maxLines = 1,
            )
        }
    }
}

// ── Activity strip ────────────────────────────────────────────────────────────────────

/**
 * Transport mode breakdown bar and legend.
 *
 * ## Bar rendering
 * Drawn with `drawRoundRect` directly on a [Canvas] so segment widths can be
 * calculated from pixel fractions without an inner [Row] + weights (which would struggle
 * with the 2 dp gaps between segments).
 *
 * ## Legend
 * One row per mode — colored dot + mode name + formatted distance.
 */
@Composable
private fun ActivityStrip(bio: BiophilicColors, breakdown: List<TransportStat>) {
    if (breakdown.isEmpty()) return

    val totalDist = breakdown.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
    val barHeight = 8.dp
    val segmentRadius = 4.dp
    val gapDp = 2.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bio.surface)
            .border(1.dp, bio.line2, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Segmented bar — drawn via Canvas
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val totalGaps = (breakdown.size - 1) * gapDp.toPx()
            val availableWidth = size.width - totalGaps
            var xOffset = 0f

            breakdown.forEachIndexed { idx, stat ->
                val fraction = (stat.distanceMeters / totalDist).toFloat()
                val segW = availableWidth * fraction
                val color = segmentColor(stat.mode, bio)

                // Use full radius for a single-segment bar; otherwise use the corner spec.
                // Rounding all 4 corners uniformly gives each segment a pill-like appearance.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(xOffset, 0f),
                    size = Size(segW, size.height),
                    cornerRadius = CornerRadius(segmentRadius.toPx()),
                )

                xOffset += segW
                if (idx < breakdown.lastIndex) {
                    xOffset += gapDp.toPx()
                }
            }
        }

        // Legend
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            breakdown.forEach { stat ->
                val modeColor = segmentColor(stat.mode, bio)
                val modeName = when (stat.mode) {
                    TransportMode.STATIONARY   -> "Stationary"
                    TransportMode.WALKING      -> "Walking"
                    TransportMode.CYCLING      -> "Cycling"
                    TransportMode.DRIVING      -> "Driving"
                    TransportMode.FAST_TRANSIT -> "Transit"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(modeColor),
                    )
                    Text(
                        text = modeName,
                        fontSize = 12.sp,
                        color = bio.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatDistance(stat.distanceMeters),
                        fontSize = 12.sp,
                        color = bio.ink3,
                    )
                }
            }
        }
    }
}

// ── Biophilic timeline item ────────────────────────────────────────────────────────────

/**
 * A single row in the timeline, handling both [TimelineItem.SegmentItem] and
 * [TimelineItem.MediaEntry] subtypes.
 *
 * ## Layout
 * Left side: 40 dp circle icon + 2 dp vertical connector line ([mossPale2]) down to the next
 * item. The connector is omitted for [isLast].
 * Right card: [surface] bg normally, [mossPale] when [isActive], 16 dp radius, [line2] border.
 *
 * ## isActive
 * True when this item's id matches [SessionReviewViewModel.UiState.Loaded.selectedMediaId]
 * (set by a map marker tap). Only applies to [TimelineItem.MediaEntry] — segments are never
 * active.
 */
@Composable
private fun BiophilicTimelineItem(
    bio: BiophilicColors,
    item: TimelineItem,
    isLast: Boolean,
    isActive: Boolean,
    onTimelineItemTapped: () -> Unit,
) {
    val cardBg = if (isActive) bio.mossPale else bio.surface
    val cardBorderColor = if (isActive) bio.mossPale2 else bio.line2

    // Icon appearance depends on the item type
    val iconInfo = timelineItemIconInfo(item, bio)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Left column: circle icon + vertical connector ──────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconInfo.iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconInfo.icon,
                    contentDescription = null,
                    tint = iconInfo.iconFg,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Connector line — 2 dp wide, fills remaining height of the row
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(16.dp)
                        .background(bio.mossPale2),
                )
            }
        }

        // ── Right card ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                .then(
                    if (item is TimelineItem.MediaEntry) {
                        Modifier.clickable(onClick = onTimelineItemTapped)
                    } else {
                        Modifier
                    }
                )
                .padding(12.dp),
        ) {
            when (item) {
                is TimelineItem.SegmentItem -> SegmentItemContent(bio = bio, item = item)
                is TimelineItem.MediaEntry  -> MediaEntryContent(bio = bio, entry = item)
            }
        }
    }
}

// ── Icon info helper ──────────────────────────────────────────────────────────────────

private data class TimelineIconInfo(
    val icon: ImageVector,
    val iconBg: Color,
    val iconFg: Color,
)

private fun timelineItemIconInfo(item: TimelineItem, bio: BiophilicColors): TimelineIconInfo {
    return when (item) {
        is TimelineItem.SegmentItem -> {
            val color = segmentColor(item.segment.mode, bio)
            TimelineIconInfo(
                icon = when (item.segment.mode) {
                    TransportMode.WALKING      -> Icons.AutoMirrored.Filled.DirectionsWalk
                    TransportMode.CYCLING      -> Icons.AutoMirrored.Filled.DirectionsBike
                    TransportMode.DRIVING      -> Icons.Default.DirectionsCar
                    TransportMode.STATIONARY   -> Icons.Default.Pause
                    TransportMode.FAST_TRANSIT -> Icons.Default.Train
                },
                iconBg = color.copy(alpha = 0.2f),
                iconFg = color,
            )
        }
        is TimelineItem.MediaEntry -> {
            TimelineIconInfo(
                icon = when (item.item) {
                    is MediaItem.Photo     -> Icons.Default.Photo
                    is MediaItem.Video     -> Icons.Default.Videocam
                    is MediaItem.TextNote  -> Icons.AutoMirrored.Filled.Notes
                    is MediaItem.VoiceNote -> Icons.Default.Mic
                },
                iconBg = bio.mossPale,
                iconFg = bio.mossDeep,
            )
        }
    }
}

// ── Segment card content ──────────────────────────────────────────────────────────────

@Composable
private fun SegmentItemContent(bio: BiophilicColors, item: TimelineItem.SegmentItem) {
    val seg = item.segment
    val modeName = when (seg.mode) {
        TransportMode.STATIONARY   -> "Stationary"
        TransportMode.WALKING      -> "Walking"
        TransportMode.CYCLING      -> "Cycling"
        TransportMode.DRIVING      -> "Driving"
        TransportMode.FAST_TRANSIT -> "Fast Transit"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = modeName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = bio.ink,
            )
            Text(
                // "· 1.2 km · 18m"
                text = "· ${formatDistance(seg.distanceMeters)} · ${formatDuration(seg.durationSeconds)}",
                fontSize = 12.sp,
                color = bio.ink3,
            )
        }
        Text(
            text = formatTimeLabel(seg.startTimestamp),
            fontSize = 11.sp,
            color = bio.ink3,
        )
    }
}

// ── Media entry card content ──────────────────────────────────────────────────────────

@Composable
private fun MediaEntryContent(bio: BiophilicColors, entry: TimelineItem.MediaEntry) {
    val mediaItem = entry.item
    val title = when (mediaItem) {
        is MediaItem.Photo     -> "Photo"
        is MediaItem.Video     -> "Video"
        is MediaItem.TextNote  -> "Note"
        is MediaItem.VoiceNote -> "Voice Note"
    }

    Column {
        // Top row: title + time label
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = bio.ink,
            )
            Text(
                text = formatTimeLabel(entry.timestampMs),
                fontSize = 11.sp,
                color = bio.ink3,
            )
        }

        // Type-specific content area
        when (mediaItem) {
            is MediaItem.Photo -> {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    PhotoCard(contentUri = mediaItem.contentUri, isVideo = false)
                }
            }
            is MediaItem.Video -> {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    PhotoCard(contentUri = mediaItem.contentUri, isVideo = true)
                }
            }
            is MediaItem.TextNote -> {
                if (mediaItem.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = mediaItem.preview,
                        fontSize = 13.sp,
                        color = bio.ink2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            is MediaItem.VoiceNote -> {
                Spacer(Modifier.height(8.dp))
                AudioWaveformRow(bio = bio, durationSec = mediaItem.durationSeconds)
            }
        }
    }
}

// ── Audio waveform row ────────────────────────────────────────────────────────────────

/**
 * Compact audio player row with a fake waveform visualization.
 *
 * ## Waveform
 * Bars are drawn deterministically from the bar index via `sin(i * 0.6)` so the shape
 * is consistent between recompositions. The first third of bars are fully opaque; the rest
 * are at 0.35 alpha to imply "unplayed" content. This is purely decorative — no real
 * waveform data is available at list-render time.
 *
 * @param durationSec Duration in seconds from [MediaItem.VoiceNote.durationSeconds].
 */
@Composable
private fun AudioWaveformRow(bio: BiophilicColors, durationSec: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(bio.bg2)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Play button circle
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bio.clay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }

        // Fake waveform canvas
        val clayColor = bio.clay
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
        ) {
            val barWidthPx = 2.5.dp.toPx()
            val gapPx = 3.dp.toPx()
            val step = barWidthPx + gapPx
            val nBars = (size.width / step).toInt().coerceAtLeast(1)

            for (i in 0 until nBars) {
                // Deterministic bar height based on position — sine wave gives organic look.
                val barHeightDp = (3 + abs(sin(i * 0.6)) * 10 + (i % 3) * 2).toFloat()
                val barHeightPx = barHeightDp.dp.toPx().coerceAtMost(size.height)
                val alpha = if (i < nBars / 3) 1f else 0.35f
                val x = i * step
                val y = (size.height - barHeightPx) / 2f

                drawRoundRect(
                    color = clayColor.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(barWidthPx, barHeightPx),
                    cornerRadius = CornerRadius(barWidthPx / 2f),
                )
            }
        }

        // Duration label
        Text(
            text = "0:%02d".format(durationSec),
            fontSize = 11.sp,
            color = bio.ink3,
        )
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
    // 80 dp padding on each side so the map tiles and markers are not clipped by the panel.
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
}

// ── Color mapping ─────────────────────────────────────────────────────────────────────

/**
 * Maps a [TransportMode] to its corresponding biophilic accent color for the activity bar
 * and timeline icon backgrounds.
 *
 * Rationale for choices:
 * - WALKING → moss (natural movement, green)
 * - STATIONARY → sun (warm, resting state)
 * - CYCLING → clay (earthy, rhythmic effort)
 * - DRIVING → mossDeep (deeper green, faster pace)
 * - FAST_TRANSIT → ink2 (neutral, mechanical)
 */
private fun segmentColor(mode: TransportMode, bio: BiophilicColors): Color = when (mode) {
    TransportMode.WALKING      -> bio.moss
    TransportMode.STATIONARY   -> bio.sun
    TransportMode.CYCLING      -> bio.clay
    TransportMode.DRIVING      -> bio.mossDeep
    TransportMode.FAST_TRANSIT -> bio.ink2
}

// ── Formatting helpers ────────────────────────────────────────────────────────────────

/**
 * Formats an epoch-millisecond timestamp as a human-readable date label.
 * Example: "Apr 25, 2026"
 */
private fun formatDateLabel(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

/**
 * Formats an epoch-millisecond timestamp as a wall-clock time.
 * Example: "14:32"
 */
private fun formatTimeLabel(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
