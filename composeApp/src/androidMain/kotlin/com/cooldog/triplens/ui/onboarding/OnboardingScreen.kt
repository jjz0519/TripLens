package com.cooldog.triplens.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-launch permission onboarding screen. Shown exactly once — [OnboardingViewModel] writes
 * the completion flag to DataStore so it is never shown again.
 *
 * ## Permission request sequence
 * Android requires ACCESS_BACKGROUND_LOCATION to be requested separately after ACCESS_FINE_LOCATION.
 * Two launchers handle this:
 *   1. [mainPermissionsLauncher]: FINE_LOCATION + COARSE_LOCATION + RECORD_AUDIO +
 *      READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION (system may batch the dialogs).
 *   2. [bgLocationLauncher]: ACCESS_BACKGROUND_LOCATION — only launched if step 1 granted fine location.
 *
 * After both launchers return (any outcome), [OnboardingViewModel.onPermissionsHandled] is called.
 * Onboarding completes regardless of which optional permissions were denied.
 *
 * ## "Open Settings" fallback
 * If fine location is permanently denied (rationale suppressed + previously requested),
 * a button to the app settings page replaces the primary Grant button.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Tracks whether location permission is permanently denied.
    // shouldShowRequestPermissionRationale() returns false both on first launch AND after
    // permanent denial — we check this state after the user is denied the first time.
    var locationPermanentlyDenied by remember { mutableStateOf(false) }

    // Step 2: background location (launched only after fine location is granted).
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Any outcome → mark onboarding done.
        viewModel.onPermissionsHandled()
    }

    // Step 1: all non-background permissions.
    val mainPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Fine location denied. Detect permanent denial: rationale suppressed after a request.
            val canShowRationale = activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ?: true
            if (!canShowRationale) {
                locationPermanentlyDenied = true
            }
            viewModel.onPermissionsHandled()
        }
    }

    // Navigate away when ViewModel emits completion.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingViewModel.Event.NavigateToTripList -> onComplete()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "TripLens", style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "To record your trips, TripLens needs the following permissions:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        PermissionRow(
            icon = Icons.Default.LocationOn,
            title = "Location (Always)",
            description = "Required to track your trip in the background",
        )
        PermissionRow(
            icon = Icons.Default.Mic,
            title = "Microphone",
            description = "For recording voice notes during your trip",
        )
        PermissionRow(
            icon = Icons.Default.Photo,
            title = "Photos & Media",
            description = "To auto-index photos taken during your trip",
        )

        Spacer(modifier = Modifier.height(40.dp))

        if (locationPermanentlyDenied) {
            Text(
                text = "Location permission was denied. Please enable it in Settings to use TripLens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Settings")
            }
        } else {
            Button(
                onClick = {
                    mainPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.ACCESS_MEDIA_LOCATION,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant Permissions")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "You can change these later in Settings",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
