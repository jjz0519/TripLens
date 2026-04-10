package com.cooldog.triplens.ui.recording

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cooldog.triplens.R

/**
 * Bottom panel shown when the recording screen is in [RecordingViewModel.UiState.Idle] or
 * [RecordingViewModel.UiState.StartingSession].
 *
 * Layout:
 * ```
 * ┌─────────────────────────────┐
 * │                             │
 * │     ◉  Start Recording      │  ← 96 dp circular primary button (center)
 * │                             │
 * └─────────────────────────────┘
 * ```
 *
 * The Start button is disabled and shows a loading indicator while
 * [RecordingViewModel.UiState.StartingSession] is active.
 *
 * Settings are reachable via the bottom navigation bar — no need for a duplicate
 * shortcut here that would complicate back-stack navigation.
 *
 * Extracted from [RecordingScreen] so the active-state bottom panel can replace it cleanly
 * without nesting deeply inside a single composable.
 */
@Composable
internal fun RecordingIdleContent(
    uiState: RecordingViewModel.UiState,
    onStartTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(16.dp),
    ) {
        // Start Recording button — large circle, centered in the panel.
        // 160 dp (~2× the original 96 dp) is prominent enough to tap easily while recording
        // without overflowing the 40 % bottom panel on compact phone screens.
        Button(
            onClick = onStartTapped,
            enabled = uiState == RecordingViewModel.UiState.Idle,
            shape   = CircleShape,
            modifier = Modifier
                .size(160.dp)
                .align(Alignment.Center),
        ) {
            if (uiState == RecordingViewModel.UiState.StartingSession) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(32.dp),
                    color       = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Text(
                    text      = stringResource(R.string.recording_start_button),
                    style     = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
