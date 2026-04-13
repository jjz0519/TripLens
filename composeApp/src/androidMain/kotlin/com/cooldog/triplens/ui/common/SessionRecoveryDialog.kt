package com.cooldog.triplens.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cooldog.triplens.R

/**
 * Startup dialog shown when an orphaned recording session is detected — i.e., a session with
 * `status = "recording"` exists in the database but [LocationTrackingService] is not running
 * and left no SharedPreferences marker.
 *
 * This happens when the OS killed both the app process and the foreground service before either
 * could update the session status. START_STICKY only restarts the service; it cannot recover a
 * session whose status was never written as "completed".
 *
 * @param onResume  Called when the user chooses to start a new session in the same TripGroup.
 *                  The interrupted session is marked as interrupted and a fresh session begins.
 * @param onDiscard Called when the user chooses to discard the interrupted session.
 *                  The session status is updated to interrupted and no new session is created.
 */
@Composable
fun SessionRecoveryDialog(
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        // Tapping outside dismisses = same as Discard: we don't leave the user stuck.
        onDismissRequest = onDiscard,
        title   = { Text(stringResource(R.string.recovery_dialog_title)) },
        text    = { Text(stringResource(R.string.recovery_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.recovery_action_resume))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.recovery_action_discard))
            }
        },
    )
}
