package com.cooldog.triplens.ui.recording

import android.Manifest
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.cooldog.triplens.navigation.SessionReviewRoute
import com.cooldog.triplens.ui.AppViewModel
import com.google.android.gms.location.LocationServices
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Recording screen — state router.
 *
 * This composable is intentionally thin: it owns shared infrastructure (MapView lifecycle,
 * NavController, dialogs) and delegates all visual content to sub-composables based on
 * [RecordingViewModel.UiState].
 *
 * ## State routing
 * ```
 * UiState.Idle / StartingSession  →  RecordingIdleContent   (bottom panel)
 * UiState.ActiveRecording         →  RecordingActiveTopBar  (overlaid on map)
 *                                 +  RecordingActiveContent (bottom panel + map effects)
 * ```
 *
 * ## MapView stability
 * [AndroidView] is always at the same position in the composition tree (index 0 inside a
 * [Box] that is index 0 inside the outer [Column]). If it were at different indices in idle
 * vs active layouts, Compose would tear it down and recreate it on state transition, losing
 * the GL context. Placing [RecordingActiveTopBar] as an overlay inside that same Box keeps
 * [AndroidView] stable across state changes.
 *
 * ## mapLibreMap state
 * [mapLibreMap] is set inside the `setStyle` loaded callback. It becomes non-null after the
 * tile style has loaded, which is the correct moment to add GeoJSON layers. Passing it down
 * to [RecordingActiveContent] lets that composable set up the route layer via
 * [LaunchedEffect] without this screen needing to know MapLibre internals.
 *
 * ## MapView lifecycle
 * [AndroidView] does NOT automatically forward Android lifecycle callbacks to hosted views.
 * [DisposableEffect] attaches a [LifecycleEventObserver] that forwards
 * ON_START/RESUME/PAUSE/STOP; [MapView.onDestroy] is called in [DisposableEffect.onDispose]
 * to release GL resources and stop the renderer thread.
 *
 * ## Dialogs / sheets owned here
 * - [PermissionRationaleDialog] — permission revoked post-onboarding.
 * - Stop confirmation [AlertDialog] — shown on [RecordingViewModel.Event.ShowStopConfirmation].
 * - Text note [ModalBottomSheet] — shown when Text Note button is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    navController: NavHostController,
    appViewModel: AppViewModel,
    viewModel: RecordingViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showTextNoteSheet by remember { mutableStateOf(false) }

    // mapLibreMap is null until the tile style finishes loading. Set in the getMapAsync
    // callback below; passed to RecordingActiveContent so it can add the GeoJSON layers.
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    // Runs once when the tile style finishes loading (mapLibreMap becomes non-null).
    // 1. Zoom to last known location so the user sees their neighbourhood, not the world.
    // 2. Activate the blue GPS dot so it is visible in both idle and active recording states.
    //    RecordingActiveContent does NOT re-activate; it only adds the route layer.
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect

        // ── Initial zoom ──────────────────────────────────────────────────────────
        // FusedLocationProviderClient.lastLocation returns a cached fix with no extra
        // battery cost. Falls back silently when permission is missing or no fix is cached.
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(location.latitude, location.longitude),
                                15.0,  // ~streets level, ~200 m visible
                            )
                        )
                    }
                }
        } catch (e: SecurityException) {
            Log.w("TripLens/RecordingScreen", "No last location for initial zoom: ${e.message}")
        }

        // ── Location component (blue GPS dot + accuracy ring) ─────────────────────
        // Activated unconditionally so the dot shows even before recording starts.
        // CameraMode.NONE: we manage camera position manually (initial zoom above +
        // the follow logic in RecordingActiveContent) rather than letting the component
        // drive it, which allows the user to freely pan and re-centre.
        map.getStyle { style ->
            val lc = map.locationComponent
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style).build()
                )
            }
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.COMPASS
        }
    }

    // Create MapView once; onCreate(null) is acceptable — we don't need to restore camera state.
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    // ── MapView lifecycle forwarding ──────────────────────────────────────────────
    //
    // MapLibre's GL renderer requires explicit lifecycle calls; omitting them causes the
    // renderer thread to run after onStop (battery drain) and GL resource leaks.
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

    // ── One-shot event handler ────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RecordingViewModel.Event.NavigateToActiveRecording -> {
                    // Notify AppViewModel so the Record tab icon starts pulsing.
                    appViewModel.onSessionActiveChanged(true)
                }
                RecordingViewModel.Event.ShowPermissionRationale -> {
                    showRationaleDialog = true
                }
                RecordingViewModel.Event.ShowStopConfirmation -> {
                    showStopDialog = true
                }
                is RecordingViewModel.Event.NavigateToSessionReview -> {
                    // Session has ended — stop the pulsing Record tab icon.
                    appViewModel.onSessionActiveChanged(false)
                    navController.navigate(SessionReviewRoute(event.sessionId))
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────────

    if (showRationaleDialog) {
        PermissionRationaleDialog(onDismiss = { showRationaleDialog = false })
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop Recording?") },
            text = { Text("The session will be saved. You can review it afterwards.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopDialog = false
                        viewModel.onStopConfirmed()
                    }
                ) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showTextNoteSheet) {
        TextNoteSheet(
            onDismiss = { showTextNoteSheet = false },
            onSave = { text ->
                showTextNoteSheet = false
                viewModel.onSaveTextNote(text)
            },
        )
    }

    // ── Main layout ───────────────────────────────────────────────────────────────
    //
    // The outer Column always has exactly two weight-bearing children:
    //   [0] Box (weight 0.6f) — map + optional top-bar overlay
    //   [1] bottom panel     — RecordingIdleContent OR RecordingActiveContent
    //
    // This keeps AndroidView at Box[0] in both idle and active states, preventing
    // the view from being torn down on the Idle → ActiveRecording transition.

    val activeState = uiState as? RecordingViewModel.UiState.ActiveRecording

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Map area (~60 % of screen height) ─────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f),
        ) {
            AndroidView(
                factory = {
                    mapView.also { mv ->
                        mv.getMapAsync { map ->
                            // setStyle callback is called once the tiles are loaded — safe to add
                            // sources and layers from this point. Storing the MapLibreMap reference
                            // here (rather than in update) ensures the map object is available to
                            // RecordingActiveContent before any LaunchedEffect runs.
                            map.setStyle("https://tiles.openfreemap.org/styles/bright") {
                                mapLibreMap = map
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Top bar overlaid on the map — only visible during active recording.
            // Overlay avoids changing the AndroidView's position in the composition tree.
            if (activeState != null) {
                RecordingActiveTopBar(
                    groupName = activeState.groupName,
                    elapsedSeconds = activeState.elapsedSeconds,
                    onStopTapped = { viewModel.onStopTapped() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(),
                )
            }
        }

        // ── Bottom panel (~40 % of screen height) ─────────────────────────────────
        if (activeState != null) {
            RecordingActiveContent(
                state = activeState,
                mapLibreMap = mapLibreMap,
                onTextNoteTapped = { showTextNoteSheet = true },
                onVoiceNoteStart = { viewModel.onVoiceNoteStart() },
                onVoiceNoteStop = { viewModel.onVoiceNoteStop() },
                onMapPanned = { viewModel.onMapPanned() },
                onRecenterTapped = { viewModel.onRecenterTapped() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
            )
        } else {
            RecordingIdleContent(
                uiState = uiState,
                onStartTapped = {
                    val locationGranted = PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PermissionChecker.PERMISSION_GRANTED
                    viewModel.onStartTapped(locationGranted)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
            )
        }
    }
}

/**
 * Bottom sheet for composing a text note during an active session.
 *
 * The sheet is modal so the user must explicitly dismiss it (swipe down or Cancel),
 * preventing accidental data loss from a mis-tap. [imePadding] ensures the text field
 * stays above the software keyboard when it opens.
 *
 * Save is disabled while the text field is blank, which prevents storing empty notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextNoteSheet(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Add Text Note", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What's happening here?") },
                minLines = 3,
                maxLines = 6,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save Note")
            }
        }
    }
}
