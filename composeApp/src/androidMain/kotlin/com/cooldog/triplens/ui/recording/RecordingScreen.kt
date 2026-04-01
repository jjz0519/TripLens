package com.cooldog.triplens.ui.recording

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.cooldog.triplens.navigation.SettingsRoute
import com.cooldog.triplens.ui.AppViewModel
// MapLibre 11.x uses org.maplibre.android package names (renamed from com.mapbox.mapboxsdk in ~v10).
// If the build fails with "unresolved reference: MapView", check the exact package in the SDK sources.
import org.maplibre.android.maps.MapView

/**
 * Recording screen — idle state.
 *
 * Layout:
 *  - Upper ~60%: MapLibre map (read-only in idle state; no polyline or markers yet).
 *  - Lower ~40%: large circular "Start Recording" button (96 dp) + gear icon to Settings.
 *
 * ## Permission check at tap time
 * ACCESS_FINE_LOCATION is checked when the user taps Start, not at composition time. If revoked
 * after onboarding, [PermissionRationaleDialog] is shown and recording does not start.
 *
 * ## Active state
 * This file only implements the idle state. Task 13 adds the active recording overlay
 * (polyline, media strip, voice note button, stop button) as additional [RecordingViewModel.UiState]
 * branches in this same composable.
 *
 * ## MapView lifecycle
 * [AndroidView] does NOT automatically forward Android lifecycle callbacks to hosted views.
 * [DisposableEffect] attaches a [LifecycleEventObserver] that forwards ON_START/RESUME/PAUSE/STOP
 * to [MapView]. [MapView.onDestroy] is called in [DisposableEffect.onDispose] to release GL
 * resources and stop the renderer thread.
 */
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

    // Create MapView once so it can be referenced in both DisposableEffect and AndroidView.
    // onCreate(null) must be called here — null bundle is acceptable because the idle screen
    // does not need to restore camera state after process death.
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    // AndroidView does NOT automatically forward Android lifecycle callbacks to hosted views.
    // MapLibre's MapView requires explicit onStart/onResume/onPause/onStop/onDestroy calls
    // to manage the GL surface and renderer thread correctly. Omitting these causes GL resource
    // leaks (renderer keeps running after onStop) and incorrect pause/resume behavior.
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

    // Collect one-shot events from ViewModel.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RecordingViewModel.Event.NavigateToActiveRecording -> {
                    // Notify AppViewModel so the Record tab icon starts pulsing.
                    appViewModel.onSessionActiveChanged(true)
                    // Task 13: this branch will trigger the active recording UI overlay.
                    // For now UiState.StartingSession already shows a loading indicator.
                }
                RecordingViewModel.Event.ShowPermissionRationale -> {
                    showRationaleDialog = true
                }
            }
        }
    }

    if (showRationaleDialog) {
        PermissionRationaleDialog(onDismiss = { showRationaleDialog = false })
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Map area (~60% of screen height) ──────────────────────────────────────
        AndroidView(
            factory = {
                // mapView is created via remember above and initialized with onCreate(null).
                // Placing setStyle in factory (not update) ensures it is called exactly once —
                // update runs on every recomposition, and MapLibre's setStyle tears down and
                // rebuilds the style (unloading tiles, re-fetching from network).
                mapView.also { mv ->
                    mv.getMapAsync { map ->
                        // Idle state: display the base map only. Task 13 adds the GPS polyline.
                        map.setStyle("https://tiles.openfreemap.org/styles/bright")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f),
        )

        // ── Bottom panel (~40% of screen height) ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .padding(16.dp),
        ) {
            // Gear icon — top-right corner navigates to Settings.
            IconButton(
                onClick = { navController.navigate(SettingsRoute) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }

            // Start Recording button — large circle, centered in the panel.
            Button(
                onClick = {
                    val locationGranted = PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PermissionChecker.PERMISSION_GRANTED
                    viewModel.onStartTapped(locationGranted)
                },
                enabled = uiState == RecordingViewModel.UiState.Idle,
                shape = CircleShape,
                modifier = Modifier
                    .size(96.dp)
                    .align(Alignment.Center),
            ) {
                if (uiState == RecordingViewModel.UiState.StartingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "Start\nRecording",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
