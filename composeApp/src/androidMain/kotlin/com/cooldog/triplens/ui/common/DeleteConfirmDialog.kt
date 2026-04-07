package com.cooldog.triplens.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation dialog for destructive delete actions.
 * Shows the item name in the message body so the user knows exactly what will be deleted.
 *
 * @param itemName  The name of the item to be deleted (e.g. trip group name).
 * @param onConfirm Called when the user taps "Delete" to confirm the action.
 * @param onDismiss Called when the user taps "Cancel" or taps outside the dialog.
 */
@Composable
fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Trip") },
        text = {
            Text("Delete \"$itemName\" and all its sessions? This action cannot be undone.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
