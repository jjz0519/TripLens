package com.cooldog.triplens.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cooldog.triplens.R

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
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = {
            Text(stringResource(R.string.delete_confirm_message, itemName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
