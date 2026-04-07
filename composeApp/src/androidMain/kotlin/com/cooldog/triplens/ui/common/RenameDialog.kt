package com.cooldog.triplens.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Reusable rename dialog used by both TripListScreen (rename group) and
 * TripDetailScreen (rename session). Displays an [AlertDialog] with an
 * [OutlinedTextField] pre-filled with the current name and focused on open.
 *
 * @param currentName  Pre-filled text in the text field.
 * @param title        Dialog title (e.g. "Rename Trip" or "Rename Session").
 * @param onConfirm    Called with the new name when the user taps "Rename".
 * @param onDismiss    Called when the user taps "Cancel" or taps outside.
 */
@Composable
fun RenameDialog(
    currentName: String,
    title: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the text field when the dialog opens for immediate editing.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                // Prevent empty names — the button is disabled while the field is blank.
                enabled = text.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
