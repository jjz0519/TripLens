package com.cooldog.triplens.ui.recording

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cooldog.triplens.R

/**
 * Post-onboarding fallback shown when ACCESS_FINE_LOCATION is revoked after the app is installed.
 *
 * The normal permission flow runs once at first launch via [OnboardingScreen]. This dialog is a
 * recovery path for users who manually revoke location in Android Settings after onboarding.
 *
 * Deep-links to the app's system settings page so the user can re-enable the permission without
 * having to know how to navigate there manually.
 */
@Composable
fun PermissionRationaleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_rationale_title)) },
        text = { Text(stringResource(R.string.permission_rationale_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
