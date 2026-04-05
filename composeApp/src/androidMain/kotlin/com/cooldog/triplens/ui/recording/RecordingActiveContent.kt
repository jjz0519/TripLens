package com.cooldog.triplens.ui.recording

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/**
 * Top bar overlaid on the map during an active recording session.
 *
 * Layout (left → right):
 *   [● groupName]   [HH:MM:SS]   [■ Stop]
 *
 * - The red dot pulses 1.0 → 0.3 alpha on an 800 ms cycle to signal "live recording".
 * - Timer always shows hours (even "00") to avoid a layout-width shift at 59:59 → 1:00:00.
 * - Stop button is filled red — clearly destructive, clearly terminates the session.
 *
 * Overlaid on the map (not above it) so the map retains its full weight(0.6f) allocation;
 * only the top ~56 dp is occluded, which is acceptable because no meaningful map data sits
 * in that strip.
 */
@Composable
internal fun RecordingActiveTopBar(
    groupName: String,
    elapsedSeconds: Long,
    onStopTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pulsing red recording indicator — 800 ms reverse cycle conveys "live".
    val infiniteTransition = rememberInfiniteTransition(label = "recordingDotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotAlpha",
    )

    Surface(
        modifier = modifier,
        // Semi-opaque so the map is still visible behind the top bar.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Left: pulsing dot + trip group name ───────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // 8 dp solid circle pulsing in red — universally understood "recording" symbol.
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .background(Color.Red, shape = CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Center: HH:MM:SS elapsed timer ────────────────────────────────────
            // Monospace font keeps the digits from jumping as numbers change width.
            Text(
                text = formatElapsedTime(elapsedSeconds),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            // ── Right: Stop button ─────────────────────────────────────────────────
            Button(
                onClick = onStopTapped,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop recording",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "Stop", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Bottom panel + MapLibre side-effects for the active recording state.
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
 * ┌────────────────────────────────────┐
 * │  [✏ Text Note]   [🎙 Voice Note]  │  ← action row (weight split)
 * │  [Re-center]  ← shown when off    │
 * │  [photo] [photo] [note] [voice] → │  ← LazyRow media strip, 10 items max
 * └────────────────────────────────────┘
 * ```
 *
 * @param state              Active recording state snapshot (polled every 5 s by the ViewModel).
 * @param mapLibreMap        Non-null only after [RecordingScreen] receives the style-loaded callback.
 * @param onTextNoteTapped   Opens the text note sheet in [RecordingScreen].
 * @param onVoiceNoteStart   Starts the voice recorder via the ViewModel.
 * @param onVoiceNoteStop    Stops the voice recorder and saves the note via the ViewModel.
 * @param onMapPanned        Disables camera auto-follow — called by the gesture listener.
 * @param onRecenterTapped   Re-enables camera auto-follow — bound to the Re-center button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingActiveContent(
    state: RecordingViewModel.UiState.ActiveRecording,
    mapLibreMap: MapLibreMap?,
    onTextNoteTapped: () -> Unit,
    onVoiceNoteStart: () -> Unit,
    onVoiceNoteStop: () -> Unit,
    onMapPanned: () -> Unit,
    onRecenterTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

    Column(modifier = modifier.padding(vertical = 8.dp)) {

        // ── Action row: Text Note + Voice Note buttons ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onTextNoteTapped,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Text Note")
            }

            VoiceNoteButton(
                isRecording = state.isVoiceRecording,
                voiceElapsedSeconds = state.voiceElapsedSeconds,
                onVoiceNoteStart = onVoiceNoteStart,
                onVoiceNoteStop = onVoiceNoteStop,
                modifier = Modifier.weight(1f),
            )
        }

        // Re-center appears only when the user has panned away from the GPS dot.
        if (!state.isCameraFollowing) {
            OutlinedButton(
                onClick = onRecenterTapped,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp),
            ) {
                Text("Re-center", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Media grid ─────────────────────────────────────────────────────────────
        // Items flow left-to-right then wrap to the next row (oldest at upper-left).
        // verticalScroll lets overflow rows scroll without needing a fixed row count.
        if (state.recentMedia.isEmpty()) {
            Text(
                text = "Photos, videos, and notes appear here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.recentMedia.forEach { item ->
                    MediaStripItem(item)
                }
            }
        }
    }
}

/**
 * Voice note button with two visual states:
 * - **Idle**: FilledTonal style with Mic icon — tap to start recording.
 * - **Recording**: Red filled style with pulsing alpha + elapsed M:SS — tap to stop.
 *
 * The pulsing animation is self-contained here so [rememberInfiniteTransition] is only
 * allocated while voice recording is active (conditional call is safe because this whole
 * composable is conditionally rendered by [RecordingActiveContent]).
 *
 * Voice note elapsed time uses M:SS (not HH:MM:SS) because recordings are expected to be
 * short; displaying hours would waste width and confuse users.
 */
@Composable
private fun VoiceNoteButton(
    isRecording: Boolean,
    voiceElapsedSeconds: Long,
    onVoiceNoteStart: () -> Unit,
    onVoiceNoteStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isRecording) {
        // Pulsing alpha on the recording button signals "active microphone" without obscuring text.
        val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
        val buttonAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "voiceButtonAlpha",
        )
        val m = voiceElapsedSeconds / 60
        val s = voiceElapsedSeconds % 60
        Button(
            onClick = onVoiceNoteStop,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = modifier.alpha(buttonAlpha),
        ) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = "Stop voice recording",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("%d:%02d".format(m, s))
        }
    } else {
        FilledTonalButton(
            onClick = onVoiceNoteStart,
            modifier = modifier,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Start voice recording",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Voice Note")
        }
    }
}

/**
 * Single cell in the media strip LazyRow.
 *
 * All four item types are rendered as 64×64 dp [Card]s for consistent visual rhythm:
 * - **Photo**: full-bleed Coil [AsyncImage].
 * - **Video**: full-bleed thumbnail + play icon overlay in the bottom-right corner.
 * - **TextNote**: edit icon + 40-char preview text (truncated if needed).
 * - **VoiceNote**: mic icon + M:SS duration label.
 *
 * Items are keyed by [MediaItem.id] in the parent LazyRow so Compose can animate additions
 * without re-creating existing cells.
 */
@Composable
private fun MediaStripItem(item: MediaItem) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(64.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (item) {
                is MediaItem.Photo -> AsyncImage(
                    model = item.contentUri,
                    contentDescription = "Photo",
                    modifier = Modifier.fillMaxSize(),
                )
                is MediaItem.Video -> {
                    AsyncImage(
                        model = item.contentUri,
                        contentDescription = "Video thumbnail",
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Play icon overlay distinguishes videos from photos at a glance.
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                    )
                }
                is MediaItem.TextNote -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = item.preview,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is MediaItem.VoiceNote -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    val m = item.durationSeconds / 60
                    val s = item.durationSeconds % 60
                    Text(
                        text = "%d:%02d".format(m, s),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

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
