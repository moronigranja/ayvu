package io.github.moronigranja.ayvu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Destructive/confirm dialog: title, body, confirm and dismiss actions. A null
 *  [dismissLabel] renders a single-action acknowledgement (the body is a result to
 *  read, not a choice to make) — otherwise the dialog would show two identical
 *  buttons when the only sensible action is "OK". */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton =
            dismissLabel?.let { label ->
                { TextButton(onClick = onDismiss) { Text(label) } }
            },
    )
}
