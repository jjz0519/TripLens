package com.cooldog.triplens.ui.recording

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Bottom panel shown when the recording screen is in [RecordingViewModel.UiState.Idle] or
 * [RecordingViewModel.UiState.StartingSession].
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐
 * │                         ⚙️  │  ← gear icon, top-right → Settings
 * │                             │
 * │     ◉  Start Recording      │  ← 96 dp circular primary button (center)
 * │                             │
 * └─────────────────────────────┘
 * ```
 *
 * The Start button is disabled and shows a loading indicator while
 * [RecordingViewModel.UiState.StartingSession] is active.
 *
 * Extracted from [RecordingScreen] so the active-state bottom panel can replace it cleanly
 * without nesting deeply inside a single composable.
 */
@Composable
internal fun RecordingIdleContent(
    uiState: RecordingViewModel.UiState,
    onStartTapped: () -> Unit,
    onSettingsTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(16.dp),
    ) {
        // Gear icon — navigates to the Settings screen.
        IconButton(
            onClick = onSettingsTapped,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }

        // Start Recording button — large circle, centered in the panel.
        Button(
            onClick = onStartTapped,
            enabled = uiState == RecordingViewModel.UiState.Idle,
            shape   = CircleShape,
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.Center),
        ) {
            if (uiState == RecordingViewModel.UiState.StartingSession) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(24.dp),
                    color       = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text      = "Start\nRecording",
                    style     = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
