package io.github.moronigranja.ayvu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.moronigranja.ayvu.player.formatBytes

/** Presentation-only pack row for the shared plan card (C1.4/C1.5) — hosts
 * (SetupViewModel, SettingsViewModel) map their registry state into this
 * neutral shape; core-ui stays dependency-free of the pack machinery. */
data class PlanPackRow(
    val packId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: PlanPackStatus,
    val staged: Boolean = false,
)

/** Presentation snapshot of a pack's state — no tts types in core-ui. */
sealed interface PlanPackStatus {
    data object NotDownloaded : PlanPackStatus

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : PlanPackStatus

    data object Ready : PlanPackStatus

    data class Failed(
        val error: String?,
    ) : PlanPackStatus
}

/**
 * The one plan card for required speech packs — shared by the first-run
 * DOWNLOAD_PACKS step and the Settings "Speech engine" section (when the
 * degraded device voice is active but Kokoro packs are missing). Pure
 * presentation: hosts map statuses/progress/errors; no download logic here.
 */
@Composable
fun PacksPlanCard(
    rows: List<PlanPackRow>,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Storage transparency line, e.g. "1.2 GB free — needs 363.6 MB". */
    storageLine: String? = null,
    /** Red shortfall naming, e.g. "Free 240 MB more to download the plan". */
    shortfall: String? = null,
    /** Host extras under the rows (e.g. the degraded opt-in action). */
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
    ) {
        for (row in rows) {
            PlanRow(row = row, onDownload = { onDownload(row.packId) }, onCancel = { onCancel(row.packId) })
        }
        storageLine?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
            )
        }
        shortfall?.let { gap ->
            Text(
                gap,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
            )
        }
        if (footer != null) footer()
    }
}

@Composable
private fun PlanRow(
    row: PlanPackRow,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AyvuSpacing.SM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatBytes(row.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val status = row.status) {
                is PlanPackStatus.Downloading -> {
                    val fraction =
                        if (status.totalBytes > 0L) status.downloadedBytes.toFloat() / status.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(vertical = AyvuSpacing.XS),
                    )
                    Text(
                        "${formatBytes(status.downloadedBytes)} of ${formatBytes(status.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PillButton("Cancel", onClick = onCancel)
                }
                PlanPackStatus.Ready -> {
                    Text(
                        if (row.staged) "Ready — installed" else "Ready",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = AyvuSpacing.XS),
                    )
                }
                is PlanPackStatus.Failed -> {
                    Text(
                        "failed: ${status.error ?: "download error"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = AyvuSpacing.XS),
                    )
                    PillButton("Retry", onClick = onDownload)
                }
                PlanPackStatus.NotDownloaded -> {
                    Text(
                        "download required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = AyvuSpacing.XS),
                    )
                    PillButton("Download", onClick = onDownload)
                }
            }
        }
    }
}
