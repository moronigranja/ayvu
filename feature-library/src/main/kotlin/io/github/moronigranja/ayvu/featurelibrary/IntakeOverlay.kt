package io.github.moronigranja.ayvu.featurelibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.moronigranja.ayvu.ebook.ImportStage
import io.github.moronigranja.ayvu.ui.AyvuSpacing

/**
 * The ONE import overlay for every entry point — the in-app SAF picker,
 * folder import, the external ACTION_VIEW gateway and forwarded book-file
 * shares all render here. Shows:
 *
 * - a determinate per-file progress bar ([Importing]) or indeterminate
 *   ([Scanning]),
 * - a stage status ("reading… / parsing… / saving… / indexing…") so the user
 *   sees WHICH pipeline step a file is on,
 * - the typed batch summary ([Done]),
 * - or non-import guidance (unsupported / .kfx / DRM — never a silent no-op).
 */
@Composable
fun intakeOverlay(
    importState: ImportUiState,
    guidance: Pair<String, String>?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = guidance
    if (g != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(g.first) },
            text = { Text(g.second) },
            confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }
    when (val s = importState) {
        is ImportUiState.Scanning ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Importing…") },
                text = {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(s.description, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        is ImportUiState.Importing ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Importing ${s.done}/${s.total}") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { s.done / s.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(s.currentFileName, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stageLabel(s.stage), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        is ImportUiState.Done ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (s.summary.failed.isEmpty()) "Import complete" else "Import finished") },
                text = {
                    Column {
                        Text("Added ${s.summary.added} · Unchanged ${s.summary.unchanged}")
                        if (s.summary.truncated) {
                            Text(
                                text = "Folder import reached its ${FolderScanPolicy.MAX_FILES}-file cap; later files were not imported.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = AyvuSpacing.XS),
                            )
                        }
                        for ((file, message) in s.summary.failed) {
                            Text(
                                text = "$file — $message",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = AyvuSpacing.XS),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            )
        ImportUiState.Idle -> Unit
    }
}

/** The user-facing label for a pipeline stage ([ImportStage]). */
@Composable
private fun stageLabel(stage: ImportStage): String =
    when (stage) {
        ImportStage.READING -> "reading file…"
        ImportStage.PARSING -> "parsing…"
        ImportStage.COMMITTING -> "saving…"
        ImportStage.INDEXING -> "indexing…"
    }
