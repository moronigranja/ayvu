package io.github.moronigranja.ayvu.featuresettings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.ConfirmDialog
import io.github.moronigranja.ayvu.ui.LoadingState
import io.github.moronigranja.ayvu.ui.PillButton
import io.github.moronigranja.ayvu.ui.SectionHeader

/**
 * E1: the Settings "Backup & restore" section — one-shot SAF export/import
 * (decisions #109). Export writes a versioned zip of all six sections (+
 * opt-in original book files) to a user-chosen document; Restore picks a zip,
 * asks for confirmation, then merges it into this device. Busy/done states
 * render in place; errors never leave a partial merge.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var includeBooks by remember { mutableStateOf(false) }
    // The picked backup to restore, held until the user confirms the dialog.
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { viewModel.export(includeBooks, it) }
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { pendingRestore = it }
        }

    Column {
        SectionHeader("Backup & restore", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS))
        Text(
            "Exports settings, library, progress, bookmarks and undo history. Audio caches and engine packs are excluded.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { includeBooks = !includeBooks }
                    .padding(vertical = AyvuSpacing.XS),
        ) {
            Checkbox(checked = includeBooks, onCheckedChange = { includeBooks = it })
            Text("Include book files", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
            modifier = Modifier.padding(vertical = AyvuSpacing.XS),
        ) {
            PillButton("Export", onClick = { exportLauncher.launch("backup.zip") })
            PillButton(
                "Restore",
                onClick = {
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
            )
        }

        when (val current = state) {
            BackupUiState.Idle -> Unit
            BackupUiState.Exporting -> LoadingState("Exporting\u2026", Modifier.padding(vertical = AyvuSpacing.SM))
            BackupUiState.Restoring -> LoadingState("Restoring\u2026", Modifier.padding(vertical = AyvuSpacing.SM))
            is BackupUiState.Finished ->
                // Terminal result: one acknowledgement, not a choice.
                ConfirmDialog(
                    title = if (current.isError) "Backup failed" else "Backup complete",
                    text = current.message,
                    confirmLabel = "OK",
                    onConfirm = viewModel::consumeResult,
                    onDismiss = viewModel::consumeResult,
                    dismissLabel = null,
                )
        }

        pendingRestore?.let { uri ->
            ConfirmDialog(
                title = "Restore backup?",
                text = "Merges settings, library, progress, bookmarks and undo history into this device.",
                confirmLabel = "Restore",
                onConfirm = {
                    pendingRestore = null
                    viewModel.restore(uri)
                },
                onDismiss = { pendingRestore = null },
            )
        }
    }
}
