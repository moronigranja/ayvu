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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.moronigranja.localttsreader.player.DisplayMode
import com.moronigranja.localttsreader.player.formatBytes
import com.moronigranja.localttsreader.ui.AyvuSpacing

/**
 * The shared "Read in" picker (decisions #114): Off + the target languages
 * the active engine can voice, plus the inline translation-pack download row
 * when the pack is not ready. One composable for the reader voice sheet and
 * the library row/card menus — never a second convention.
 *
 * [ReadInLanguageUiState] is presentation data: the target is the book's
 * per-book APP language code (`pt-BR`), languages are canonical codes from
 * the active engine's catalog filtered to what the translator can be prompted
 * for (decisions #162); the VM owns persistence + the playback rebuild.
 *
 * The read-in-language DISPLAY surface is reader-only: [displayTarget] is
 * the book's display language, [speechLanguages] the constrained SPEECH row
 * set (Off + the display language — a passage is translated at most once),
 * and [displayMode] the reader's layout. The library surface omits the
 * display section entirely ([onDisplaySelect] null): there the speech rows
 * stay the full catalog and picking an unconstrained language degrades the
 * same way it always did.
 */
data class ReadInLanguageUiState(
    val bookId: String? = null,
    /** The book's current target app language code, null = original language. */
    val target: String? = null,
    /** Canonical target codes the active engine can voice (catalog order). */
    val languages: List<String> = emptyList(),
    /** The book's DISPLAY language (read-in-language display): the app code
     * whose translation the reader SHOWS, null = original language. */
    val displayTarget: String? = null,
    /** The reader's display mode (global reading style). */
    val displayMode: DisplayMode = DisplayMode.INTERLEAVED,
    /** The constrained SPEECH row set: Off + the display language. Null when
     * no display language is set — the speech rows are the full catalog (the
     * library surface, where the display section is absent). */
    val speechLanguages: List<String>? = null,
    /** The translate pack verified (or null: the download row shows). */
    val packDownloaded: Boolean? = null,
    /** Non-null while the pack download is in flight (0..1). */
    val downloadProgress: Float? = null,
    /** Why the selected target is not rendering (null = in force, or Off).
     * The picker's feedback row — a silent degrade reads as "broken". */
    val degradeReason: String? = null,
    /** The stored target-language voice id, or null = automatic (catalog default). */
    val translateVoice: String? = null,
    /** Voices of the target language in the active engine's catalog. */
    val translateVoices: List<VoiceRowUi> = emptyList(),
    /** The explicitly selected target voice's packs are ready (true when automatic). */
    val translateVoiceReady: Boolean = true,
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

private const val OFF_ID = "__off__"
private const val AUTO_ID = "__auto__"

/**
 * Rows: "Off" + one per [ReadInLanguageUiState.languages]; selecting persists
 * the per-book target (null = off). When the translate pack is not verified
 * an inline download row with progress takes the action instead — selecting a
 * language before the pack exists would only degrade silently.
 *
 * The DISPLAY surface ([onDisplaySelect] non-null — the reader) adds a
 * "Show translation in" dropdown before the speech rows: its options are
 * [ReadInLanguageUiState.languages] (a display change is a text
 * re-projection; the CALLER decides whether a session rebuild follows). The
 * SPEECH rows contract to [ReadInLanguageUiState.speechLanguages] when one
 * is set (Off + the display language — a passage is translated at most
 * once); the mode toggle ([onModeSelect] non-null + a display target set)
 * picks the reader's layout. The spoken TRANSLATION's own voice
 * ([onTranslateVoiceSelect] non-null + a speech target in force) is a
 * per-target-language dropdown over the active engine's voices for that
 * language, with per-voice pack download state and a download action for the
 * selected voice.
 *
 * All sections are compact [LabeledDropdown]s (decisions #166 follow-up):
 * the long radio lists are gone.
 */
@Composable
fun ReadInLanguagePicker(
    state: ReadInLanguageUiState,
    onSelect: (String?) -> Unit,
    onDownload: () -> Unit,
    onDisplaySelect: ((String?) -> Unit)? = null,
    onModeSelect: ((DisplayMode) -> Unit)? = null,
    onTranslateVoiceSelect: ((String?) -> Unit)? = null,
    onDownloadVoice: ((String) -> Unit)? = null,
) {
    fun voiceLabelOf(id: String): String =
        state.translateVoices.firstOrNull { it.name == id }?.let { it.displayName.ifEmpty { it.name } } ?: id

    Column(modifier = Modifier.fillMaxWidth()) {
        val displaySelect = onDisplaySelect
        if (displaySelect != null) {
            LabeledDropdown(
                label = "Show translation in",
                selectedLabel = state.displayTarget?.let(::languageLabel) ?: "Off",
                options =
                    listOf(DropdownOption(OFF_ID, "Off")) +
                        state.languages.map { DropdownOption(it, languageLabel(it)) },
                onSelect = { id -> displaySelect(id.takeUnless { it == OFF_ID }) },
                modifier = Modifier.padding(bottom = AyvuSpacing.SM),
            )
            val modeSelect = onModeSelect
            if (state.displayTarget != null && modeSelect != null) {
                // The reader's layout — its OWN labelled dropdown so the two
                // options do not read as part of the language picker above:
                // one column with the translation below its original, or the
                // translation alone.
                LabeledDropdown(
                    label = "Layout",
                    selectedLabel =
                        if (state.displayMode == DisplayMode.INTERLEAVED) {
                            "Translation below original"
                        } else {
                            "Translation only"
                        },
                    options =
                        listOf(
                            DropdownOption(DisplayMode.INTERLEAVED.key, "Translation below original"),
                            DropdownOption(DisplayMode.TRANSLATED_ONLY.key, "Translation only"),
                        ),
                    onSelect = { id -> modeSelect(DisplayMode.from(id)) },
                    modifier = Modifier.padding(bottom = AyvuSpacing.SM),
                )
            }
        }
        // The speech rows: the constrained set (Off + the display language)
        // when a display language is in force — picking a third language
        // would require a second translation of every passage.
        val speechRows = state.speechLanguages ?: state.languages
        LabeledDropdown(
            label = "Read aloud in",
            selectedLabel = state.target?.let(::languageLabel) ?: "Off",
            options =
                listOf(DropdownOption(OFF_ID, "Off")) +
                    speechRows.map { DropdownOption(it, languageLabel(it)) },
            onSelect = { id -> onSelect(id.takeUnless { it == OFF_ID }) },
            modifier = Modifier.padding(bottom = AyvuSpacing.SM),
        )
        val translateVoiceSelect = onTranslateVoiceSelect
        if (state.target != null && translateVoiceSelect != null) {
            LabeledDropdown(
                label = "Translation voice",
                selectedLabel = state.translateVoice?.let(::voiceLabelOf) ?: "Automatic",
                options =
                    listOf(
                        DropdownOption(
                            AUTO_ID,
                            "Automatic (best for ${languageLabel(state.target)})",
                        ),
                    ) + state.translateVoices.map { row ->
                        DropdownOption(
                            id = row.name,
                            label = row.displayName.ifEmpty { row.name },
                            description = "${row.language} · ${row.gender}",
                            trailing =
                                if (row.ready) {
                                    "downloaded"
                                } else {
                                    "${formatBytes(row.bytes)} — needs download"
                                },
                        )
                    },
                onSelect = { id -> translateVoiceSelect(id.takeUnless { it == AUTO_ID }) },
                modifier = Modifier.padding(bottom = AyvuSpacing.SM),
            )
            if (state.translateVoice != null && !state.translateVoiceReady) {
                TextButton(onClick = { onDownloadVoice?.invoke(state.translateVoice) }) {
                    Text("Download this voice's pack")
                }
            }
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
                        Text("Download translation pack (~730 MB)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "LFM2.5-1.2B, all supported languages. Sizes and status come from the Speech settings section.",
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
