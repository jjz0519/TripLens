package com.cooldog.triplens.ui.recording

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cooldog.triplens.domain.HaversineUtils
import com.cooldog.triplens.model.TrackPoint
import com.cooldog.triplens.ui.theme.BiophilicColors
import com.cooldog.triplens.ui.theme.InstrumentSerifFamily
import com.cooldog.triplens.ui.theme.LocalBiophilicColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

// ---------------------------------------------------------------------------
// Distance helper
// ---------------------------------------------------------------------------

/**
 * Computes the total haversine path distance across the given track points, in kilometres.
 *
 * Delegates to [HaversineUtils.totalDistance] which handles the consecutive-pair summation
 * and returns 0.0 for fewer than 2 points.
 */
internal fun computeDistanceKm(points: List<TrackPoint>): Float =
    (HaversineUtils.totalDistance(points) / 1000.0).toFloat()

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * Biophilic top bar overlaid on the map during an active recording session.
 *
 * Layout (left → right):
 *   [ProgressRing (60 dp)]   [● RECORDING / timer / km]   (stop is moved to bottom dock)
 *
 * The [ProgressRing] renders:
 * - A faint track ring (full 1-hour span)
 * - A green elapsed arc sweeping clockwise from 12 o'clock
 * - Up to 12 moment-petal dots orbiting the ring
 * - A pulsing red centre dot
 *
 * The pulsing "RECORDING" label (700 ms Reverse cycle) provides a second visual cue that
 * the session is live without relying on the ring colour alone.
 *
 * @param groupName      Trip group name displayed next to the timer.
 * @param elapsedSeconds Session elapsed time in seconds.
 * @param momentCount    Number of captured moments; drives ProgressRing petal count.
 * @param distanceKm     Accumulated haversine distance in kilometres; shown next to timer.
 * @param onStopTapped   Callback forwarded to [RecordingViewModel.onStopTapped].
 * @param modifier       Caller-supplied modifier (typically fillMaxWidth + align TopStart).
 */
@Composable
internal fun RecordingActiveTopBar(
    groupName: String,
    elapsedSeconds: Long,
    momentCount: Int,
    distanceKm: Float,
    onStopTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bio = LocalBiophilicColors.current

    // Pulsing alpha on "RECORDING" label — 700 ms Reverse cycle is fast enough to feel live
    // without being distracting during navigation.
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )

    Surface(
        modifier = modifier,
        // Semi-opaque so the map is still readable behind the top bar.
        color = bio.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ProgressRing gives an at-a-glance elapsed/moment summary without needing text.
            ProgressRing(
                elapsedSeconds = elapsedSeconds,
                momentCount = momentCount,
                paused = false,  // no pause state in ViewModel; always show recording colour
                bio = bio,
                modifier = Modifier.size(60.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                // Live badge — pulsing dot + "RECORDING" label in recordRed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(bio.recordRed.copy(alpha = dotAlpha)),
                    )
                    Text(
                        text = "RECORDING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = bio.recordRed.copy(alpha = dotAlpha),
                        letterSpacing = 0.6.sp,
                    )
                }

                // Timer row: HH:MM:SS | distance
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    // InstrumentSerif gives the numerals a warm, analogue-instrument feel
                    // consistent with the biophilic design language.
                    Text(
                        text = formatElapsedTime(elapsedSeconds),
                        fontFamily = InstrumentSerifFamily,
                        fontSize = 26.sp,
                        color = bio.ink,
                        letterSpacing = (-0.02).em,
                    )
                    // Thin vertical divider between timer and distance
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(bio.line)
                            .align(Alignment.CenterVertically),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "%.2f".format(distanceKm),
                            fontFamily = InstrumentSerifFamily,
                            fontSize = 19.sp,
                            color = bio.ink2,
                        )
                        Text(text = " km", fontSize = 12.sp, color = bio.ink3)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom panel + MapLibre side-effects
// ---------------------------------------------------------------------------

/**
 * Bottom panel and MapLibre side-effects for the active recording state.
 *
 * ## MapLibre side-effects (three LaunchedEffect / DisposableEffect blocks)
 *
 * **LaunchedEffect(mapLibreMap)**
 * Runs once when the style-loaded map becomes available. Adds:
 * - `"route-source"`: [GeoJsonSource] initially empty, immediately populated with the current
 *   track points (avoids a 5 s wait for the first poll).
 * - `"route-layer"`: data-driven [LineLayer] that reads a `"color"` string property from each
 *   GeoJSON Feature — one layer replaces five per-mode layer/source pairs.
 * - LocationComponent: blue GPS dot + accuracy circle. `CameraMode.NONE` because camera
 *   position is managed here, not by the LocationComponent itself.
 *
 * **LaunchedEffect(state.trackPoints, state.isCameraFollowing)**
 * Runs on every GPS poll (~5 s) and also when auto-follow is re-enabled by the user.
 * Updates the GeoJSON source and, when `isCameraFollowing` is true, animates the camera to
 * the last non-paused point.
 *
 * **DisposableEffect(mapLibreMap)**
 * Registers [MapLibreMap.OnCameraMoveStartedListener]. When the user pans the map
 * ([REASON_API_GESTURE]) the listener calls [onMapPanned] to disable auto-follow and show
 * the Re-center button. Removed on disposal to prevent a listener leak.
 *
 * ## Bottom panel layout
 * ```
 * ┌────────────────────────────────────────────────┐
 * │  [Text]   [Voice]   [Photo]   ← capture bar    │
 * │  ──── moment timeline (scrollable) ────         │
 * │  [Pause]   [● STOP ●]   [Re-center]            │  ← bottom dock
 * └────────────────────────────────────────────────┘
 * ```
 *
 * @param state                Active recording state snapshot (polled every 5 s by the ViewModel).
 * @param mapLibreMap          Non-null only after [RecordingScreen] receives the style-loaded callback.
 * @param onStopTapped         Forwards to [RecordingViewModel.onStopTapped].
 * @param onTextNoteTapped     Opens the text note sheet in [RecordingScreen].
 * @param onVoiceNoteStart     Starts the voice recorder via the ViewModel.
 * @param onVoiceNoteStop      Stops the voice recorder and saves the note via the ViewModel.
 * @param onMapPanned          Disables camera auto-follow — called by the gesture listener.
 * @param onRecenterTapped     Re-enables camera auto-follow — bound to the Re-center dock button.
 * @param onMediaScrollChanged Called with true when the media list is scrolled down (expand panel),
 *                             false when scrolled back to the top (restore default ratio).
 * @param modifier             Caller-supplied modifier (typically fillMaxWidth + weight).
 */
@Composable
internal fun RecordingActiveContent(
    state: RecordingViewModel.UiState.ActiveRecording,
    mapLibreMap: MapLibreMap?,
    onStopTapped: () -> Unit,
    onTextNoteTapped: () -> Unit,
    onVoiceNoteStart: () -> Unit,
    onVoiceNoteStop: () -> Unit,
    onMapPanned: () -> Unit,
    onRecenterTapped: () -> Unit,
    // Called with true when the media list is scrolled down (expand panel),
    // false when scrolled back to the top (restore default ratio).
    onMediaScrollChanged: (isScrolledDown: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val bio = LocalBiophilicColors.current

    // ── Map side-effect 1: one-time layer setup ────────────────────────────────────
    //
    // Runs when mapLibreMap first becomes non-null (style loaded). Guards with null checks
    // before addSource/addLayer so recomposition never double-adds the same source/layer.
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.getStyle { style ->
            if (style.getSource("route-source") == null) {
                style.addSource(
                    GeoJsonSource("route-source", FeatureCollection.fromFeatures(emptyList()))
                )
            }
            if (style.getLayer("route-layer") == null) {
                // Data-driven LineLayer: reads the "color" string property added by
                // MapGeoJsonBuilder.buildRouteFeatureCollection() to each GeoJSON Feature.
                style.addLayer(
                    LineLayer("route-layer", "route-source").withProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }
            // Populate immediately so the polyline shows current GPS fixes without waiting
            // for the next 5-second poll cycle.
            (style.getSource("route-source") as? GeoJsonSource)
                ?.setGeoJson(MapGeoJsonBuilder.buildRouteFeatureCollection(state.trackPoints))
            // Note: LocationComponent is activated in RecordingScreen.LaunchedEffect(mapLibreMap)
            // so it is available in both idle and active states. No setup needed here.
        }
    }

    // ── Map side-effect 2: polyline + camera update ────────────────────────────────
    //
    // Keyed on both trackPoints and isCameraFollowing so it re-runs when:
    //   (a) the poll delivers new GPS fixes, OR
    //   (b) the user taps Re-center (isCameraFollowing flips true → camera catches up).
    LaunchedEffect(state.trackPoints, state.isCameraFollowing) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.getStyle { style ->
            (style.getSource("route-source") as? GeoJsonSource)
                ?.setGeoJson(MapGeoJsonBuilder.buildRouteFeatureCollection(state.trackPoints))
        }
        if (state.isCameraFollowing) {
            val lastPoint = state.trackPoints.lastOrNull { !it.isAutoPaused }
            if (lastPoint != null) {
                val latLng = LatLng(lastPoint.latitude, lastPoint.longitude)
                // If the map is still at a distant zoom (e.g. world view because no lastLocation
                // was available before recording started), snap to streets level on the first fix.
                // Otherwise preserve the user's current zoom so panning-and-re-centering feels
                // natural and doesn't forcibly zoom back in.
                val update = if (map.cameraPosition.zoom < 10.0) {
                    CameraUpdateFactory.newLatLngZoom(latLng, 15.0)
                } else {
                    CameraUpdateFactory.newLatLng(latLng)
                }
                map.animateCamera(update)
            }
        }
    }

    // ── Map side-effect 3: user pan detection ─────────────────────────────────────
    //
    // REASON_API_GESTURE fires when the user moves the map with a finger, distinguishing
    // it from programmatic camera moves (REASON_API_ANIMATION) that we trigger ourselves.
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                onMapPanned()
            }
        }
        map.addOnCameraMoveStartedListener(listener)
        onDispose { map.removeOnCameraMoveStartedListener(listener) }
    }

    // ── Bottom panel ────────────────────────────────────────────────────────────────

    Column(modifier = modifier) {

        // ── Capture bar: Text / Voice / Photo ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureButton(
                label = "Text",
                bg = bio.mossPale,
                fg = bio.mossDeep,
                icon = Icons.Outlined.TextFields,
                onClick = onTextNoteTapped,
                modifier = Modifier.weight(1f),
            )
            CaptureButton(
                label = "Voice",
                bg = bio.clay.copy(alpha = 0.1f),
                fg = bio.clay,
                icon = if (state.isVoiceRecording) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                onClick = if (state.isVoiceRecording) onVoiceNoteStop else onVoiceNoteStart,
                modifier = Modifier.weight(1f),
            )
            CaptureButton(
                label = "Photo",
                bg = bio.sun.copy(alpha = 0.1f),
                fg = bio.sun,
                icon = Icons.Outlined.CameraAlt,
                onClick = { /* stub — camera capture not yet implemented */ },
                modifier = Modifier.weight(1f),
            )
        }

        // ── Moment timeline (scrollable) ──────────────────────────────────────────
        //
        // NestedScrollConnection detects scroll direction before layout to avoid the
        // clamping feedback loop that occurs when reacting to scrollState.value == 0
        // while the expanded panel makes all content fit.
        //
        // Sign convention (Compose nested scroll):
        //   available.y < 0 → user dragging finger UP   → list scrolls down → expand
        //   available.y > 0 → user dragging finger DOWN → list scrolls up
        //     + scrollState.value == 0 → already at top → collapse
        val scrollState = rememberScrollState()
        // rememberUpdatedState ensures the lambda captured inside the bare remember{} block
        // always calls the latest onMediaScrollChanged from the current composition, avoiding
        // stale-closure bugs if the caller's lambda identity changes between recompositions.
        val currentOnMediaScrollChanged by rememberUpdatedState(onMediaScrollChanged)
        val scrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    when {
                        available.y < 0 -> currentOnMediaScrollChanged(true)
                        available.y > 0 && scrollState.value == 0 -> currentOnMediaScrollChanged(false)
                    }
                    return Offset.Zero  // never consume — let the Column scroll normally
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(scrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            if (state.recentMedia.isEmpty()) {
                Text(
                    text = "No moments yet — tap Text, Voice, or Photo to capture.",
                    fontSize = 12.sp,
                    color = bio.ink3,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                state.recentMedia.forEach { item ->
                    MomentTimelineRow(item, bio)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Bottom dock: Pause / Stop / Re-center ─────────────────────────────────
        RecordingBottomDock(
            onStop = onStopTapped,
            onPause = { /* stub — no pause state in ViewModel */ },
            onRecenter = onRecenterTapped,
            bio = bio,
        )
    }
}

// ---------------------------------------------------------------------------
// Private helper composables
// ---------------------------------------------------------------------------

/**
 * Pill-shaped capture button used in the three-button capture bar (Text / Voice / Photo).
 *
 * Using explicit bg/fg parameters (rather than MaterialTheme roles) lets each button carry
 * its own biophilic colour identity without requiring three separate ButtonColors objects
 * at the call site.
 */
@Composable
private fun CaptureButton(
    label: String,
    bg: Color,
    fg: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        // Zero elevation: the subtle tinted background already differentiates each button;
        // a shadow would look heavy against the biophilic surface.
        elevation = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Single row in the moment timeline for one captured [MediaItem].
 *
 * Layout: [icon circle]  [label card with type + time + optional preview text]
 *
 * Colour coding:
 * - Text note → moss green (same palette as the capture button)
 * - Photo/Video → sun amber
 * - Voice note → clay brown
 *
 * The card is intentionally minimal (no thumbnails, no waveform) so the timeline stays
 * compact; the goal is a chronological log, not a media browser.
 */
@Composable
private fun MomentTimelineRow(item: MediaItem, bio: BiophilicColors) {
    // Destructure type-specific display properties with a when block.
    // Quintuple/tuple destructuring is not available in Kotlin; using separate vars is idiomatic.
    val iconBg: Color
    val iconFg: Color
    val icon: ImageVector
    val label: String
    val preview: String
    when (item) {
        is MediaItem.TextNote  -> {
            iconBg  = bio.mossPale
            iconFg  = bio.mossDeep
            icon    = Icons.Outlined.TextFields
            label   = "Note"
            preview = item.preview
        }
        is MediaItem.Photo     -> {
            iconBg  = bio.sun.copy(alpha = 0.12f)
            iconFg  = bio.sun
            icon    = Icons.Outlined.Photo
            label   = "Photo"
            preview = ""
        }
        is MediaItem.Video     -> {
            iconBg  = bio.sun.copy(alpha = 0.12f)
            iconFg  = bio.sun
            icon    = Icons.Outlined.Videocam
            label   = "Video"
            preview = ""
        }
        is MediaItem.VoiceNote -> {
            iconBg  = bio.clay.copy(alpha = 0.1f)
            iconFg  = bio.clay
            icon    = Icons.Outlined.Mic
            label   = "Voice"
            // Proper minute:second formatting handles recordings >= 60 s correctly.
            // Old "0:%02d" would display "0:75" for a 75-second clip; this gives "1:15".
            preview = "%d:%02d".format(item.durationSeconds / 60, item.durationSeconds % 60)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(14.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(bio.surface)
                .border(1.dp, bio.line2, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = bio.ink)
                Text(formatCapturedAt(item.capturedAt), fontSize = 11.sp, color = bio.ink3)
            }
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = bio.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Bottom dock with Pause (stub), Stop, and Re-center controls.
 *
 * The Stop button is deliberately large (72 dp) and prominently red — it is a destructive,
 * high-consequence action and needs to be clearly identifiable under physical activity.
 * The outer 84 dp ring adds a visual "press target" halo that draws the eye without
 * increasing the actual tap area (IconButton provides a 48 dp minimum already).
 *
 * Pause is a stub because the ViewModel currently has no pause state; the button occupies
 * its space now so the dock layout won't shift when pause is added later.
 */
@Composable
private fun RecordingBottomDock(
    onStop: () -> Unit,
    onPause: () -> Unit,
    onRecenter: () -> Unit,
    bio: BiophilicColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pause — stub; always enabled so the layout is stable when pause is implemented
        IconButton(
            onClick = onPause,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bio.surface)
                .border(1.dp, bio.line2, CircleShape),
        ) {
            Icon(Icons.Outlined.Pause, contentDescription = "Pause", tint = bio.ink2, modifier = Modifier.size(20.dp))
        }

        // Stop — large red circle; outer ring provides a press-target halo
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .border(2.dp, bio.recordRed.copy(alpha = 0.25f), CircleShape),
            )
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(bio.recordRed),
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop recording",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // Re-center — always visible (occupies same space whether following or not)
        // so the dock layout doesn't shift when the user pans and re-centres.
        IconButton(
            onClick = onRecenter,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bio.surface)
                .border(1.dp, bio.line2, CircleShape),
        ) {
            Icon(Icons.Outlined.MyLocation, contentDescription = "Re-center", tint = bio.ink2, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

// File-level singleton formatter — avoids allocating a new SimpleDateFormat on every
// timeline recomposition. SimpleDateFormat is not thread-safe, but Compose recompositions
// always run on the main thread, so sharing this instance is safe here.
private val capturedAtFormatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

/**
 * Formats a [MediaItem.capturedAt] epoch-millisecond timestamp as "HH:mm" local time.
 *
 * Uses [capturedAtFormatter] (device locale) so AM/PM vs. 24-hour display respects user
 * preference. The formatter is a file-level val to avoid per-call allocation. Shown on
 * each timeline row card.
 */
private fun formatCapturedAt(epochMs: Long): String =
    capturedAtFormatter.format(java.util.Date(epochMs))

/**
 * Formats an elapsed duration in seconds to a zero-padded HH:MM:SS string.
 *
 * Hours are always included (even "00:") to keep the display width constant throughout
 * a session — a layout shift at the 59:59 → 1:00:00 boundary would be jarring.
 *
 * Uses explicit `%02d` formatting (not [java.text.SimpleDateFormat]) so the output is
 * locale-independent and predictable in unit tests.
 *
 * @param seconds Non-negative elapsed seconds since session start.
 * @return e.g. "00:05:42", "01:23:00", "12:00:00"
 */
internal fun formatElapsedTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
