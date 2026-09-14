package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moronigranja.localttsreader.ui.AyvuSpacing

/**
 * The shared "Read in" picker (decisions #114): Off + the target languages
 * the active engine can voice, plus the inline translation-pack download row
 * when the pack is not ready. One composable for the reader voice sheet and
 * the library row/card menus — never a second convention.
 *
 * [ReadInLanguageUiState] is presentation data: the target is the book's
 * per-book APP language code (`pt-BR`), languages are canonical codes from
 * the active engine's catalog filtered to SMaLL-100 support; the VM owns
 * persistence + the playback rebuild.
 */
data class ReadInLanguageUiState(
    val bookId: String? = null,
    /** The book's current target app language code, null = original language. */
    val target: String? = null,
    /** Canonical target codes the active engine can voice (catalog order). */
    val languages: List<String> = emptyList(),
    /** The translate pack verified (or null: the download row shows). */
    val packDownloaded: Boolean? = null,
    /** Non-null while the pack download is in flight (0..1). */
    val downloadProgress: Float? = null,
    /** Why the selected target is not rendering (null = in force, or Off).
     * The picker's feedback row — a silent degrade reads as "broken". */
    val degradeReason: String? = null,
)

/** Display label for a canonical target app language code. */
fun languageLabel(code: String): String =
    when (code) {
        "en" -> "English"
        "en-GB" -> "English (UK)"
        "de" -> "German"
        "es" -> "Spanish"
        "fr" -> "French"
        "it" -> "Italian"
        "ja" -> "Japanese"
        "hi" -> "Hindi"
        "zh" -> "Chinese"
        "pt-BR" -> "Portuguese (Brazil)"
        else -> code
    }

/**
 * Rows: "Off" + one per [ReadInLanguageUiState.languages]; selecting persists
 * the per-book target (null = off). When the translate pack is not verified
 * an inline download row with progress takes the action instead — selecting a
 * language before the pack exists would only degrade silently.
 */
@Composable
fun ReadInLanguagePicker(
    state: ReadInLanguageUiState,
    onSelect: (String?) -> Unit,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LanguageRow(
            label = "Off",
            selected = state.target == null,
            onClick = { onSelect(null) },
        )
        for (code in state.languages) {
            LanguageRow(
                label = languageLabel(code),
                selected = state.target == code,
                onClick = { onSelect(code) },
            )
        }
        if (state.target != null && state.degradeReason != null) {
            Text(
                "Playing original — ${state.degradeReason}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = AyvuSpacing.SM),
            )
        }
        if (state.packDownloaded == false) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDownload() }
                        .padding(vertical = AyvuSpacing.SM),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = false, onClick = { onDownload() })
                    Column {
                        Text("Download translation pack (~916 MB)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "SMaLL-100, all languages. Sizes and status come from the Speech settings section.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.downloadProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(start = AyvuSpacing.SM),
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
