package io.github.moronigranja.ayvu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.moronigranja.ayvu.player.AuditionStage
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.formatBytes
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMeta
import io.github.moronigranja.ayvu.ui.AyvuSpacing

/**
 * The shared voice row presentation (decisions #166 follow-up — the compact
 * replacement for the C2 radio list): one voice's name, language/gender and
 * required-pack size, plus selection/favorite/preview/download markers. Purely
 * presentation data; the host derives it through [buildEngineVoiceState].
 */
data class VoiceRowUi(
    val name: String,
    val language: String,
    val gender: String,
    /** Presentation label — "Heart ❤️"-style (KokoroVoiceMeta.displayName). */
    val displayName: String = "",
    /** Upstream data-grade ("A"…"F+"); null for the ungraded es/pt families. */
    val grade: String? = null,
    val favorite: Boolean = false,
    /** Whether the voice's pack is ready; false → the row's download action
     * replaces Preview. */
    val ready: Boolean = true,
    /** The voice's required-pack size (0 when no pack is needed) — the
     * "needs download · N MiB" marker on the compact picker. */
    val bytes: Long = 0L,
    val selected: Boolean = false,
    val preview: VoicePreviewUi = VoicePreviewUi.Idle,
)

sealed interface VoicePreviewUi {
    data object Idle : VoicePreviewUi

    data object Generating : VoicePreviewUi

    data object Playing : VoicePreviewUi

    data class Failed(
        val reason: String,
    ) : VoicePreviewUi
}

/** One engine row of the engine dropdown. */
data class EngineOptionUi(
    val id: String,
    val displayName: String,
    val description: String,
    val ready: Boolean,
)

data class EngineVoiceUiState(
    val engineId: String = DefaultEngines.kokoro.id,
    val engines: List<EngineOptionUi> = emptyList(),
    val rows: List<VoiceRowUi> = emptyList(),
    /** Persistent summary — "Selected voice: X (name)" for the settings row. */
    val summary: String = "",
    /** A persisted voice absent from the static catalog (e.g. from a pack the
     * current build does not know): shown as unavailable with a download /
     * reselect action, never as an all-unselected list. */
    val unavailableSavedVoice: String? = null,
    /** The SELECTED voice's required packs are ready. */
    val ready: Boolean = false,
)

/**
 * Mirrors `SettingsStore.SYSTEM_TTS_ENGINE` — core-ui cannot see the
 * settings store, so the degraded engine's id is mirrored here.
 */
private const val SYSTEM_ENGINE_ID = "system-tts"

/**
 * Fixed engine order: Kokoro (default), Piper, Device voice (system). Labels
 * and subs mirror the Settings SpeechPane's engine rows verbatim.
 */
fun engineOptions(
    engineId: String,
    readyFor: (String) -> Boolean,
): List<EngineOptionUi> =
    listOf(
        EngineOptionUi(
            id = DefaultEngines.kokoro.id,
            displayName = "Kokoro-82M",
            description = "High-quality offline voices — download required.",
            ready = readyFor(DefaultEngines.kokoro.id),
        ),
        EngineOptionUi(
            id = DefaultEngines.piper.id,
            displayName = "Piper",
            description =
                "Compact open-weight voices — English (US) and German. Download required; no read-along highlights.",
            ready = readyFor(DefaultEngines.piper.id),
        ),
        EngineOptionUi(
            id = SYSTEM_ENGINE_ID,
            displayName = "Device voice (system)",
            description = "Zero-download fallback — degraded quality, no read-along highlights.",
            ready = readyFor(SYSTEM_ENGINE_ID),
        ),
    )

/**
 * The ONE engine+voice picker-state builder (decisions #102.4 + #166
 * follow-up): Settings, first-run setup and the reader voice sheet all derive
 * [EngineVoiceUiState] through this function — never a second convention.
 * Rows come from the active engine's static catalog, readiness/bytes from the
 * required-pack table ([io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks]),
 * selection/favorites from the settings snapshot, audition stage from the
 * shared coordinator.
 */
fun buildEngineVoiceState(
    engineId: String,
    engines: List<EngineOptionUi>,
    voices: List<KokoroVoiceMeta>,
    selectedVoice: String,
    favorites: Set<String>,
    readyFor: (String) -> Boolean,
    bytesFor: (String) -> Long,
    audition: AuditionUiState,
): EngineVoiceUiState {
    val known = voices.map(KokoroVoiceMeta::name).toSet()
    val unavailable = selectedVoice.takeIf { it !in known }
    return EngineVoiceUiState(
        engineId = engineId,
        engines = engines,
        rows =
            voices.map { meta ->
                VoiceRowUi(
                    name = meta.name,
                    language = meta.language,
                    gender = meta.gender,
                    displayName = meta.displayName,
                    grade = meta.grade,
                    favorite = meta.name in favorites,
                    ready = readyFor(meta.name),
                    bytes = bytesFor(meta.name),
                    selected = meta.name == selectedVoice,
                    preview = previewStage(meta.name, audition),
                )
            },
        summary =
            if (unavailable == null) {
                val shown =
                    voices
                        .firstOrNull { it.name == selectedVoice }
                        ?.displayName
                        .orEmpty()
                        .ifEmpty { selectedVoice }
                "Selected voice: $shown ($selectedVoice)"
            } else {
                ""
            },
        unavailableSavedVoice = unavailable,
        ready = readyFor(selectedVoice),
    )
}

/**
 * The compact engine+voice picker (decisions #166 follow-up): dropdowns
 * instead of the long radio lists, one shared surface on every voice-picking
 * screen. Pure presentation — engine/voice selection, favorite, preview and
 * download actions come from the host:
 *
 * - the engine dropdown is rendered only when the host lets the user change
 *   the engine ([onEngineSelect] non-null) and lists engines exist;
 * - one voice dropdown ([voiceLabel] labels it — "Voice" everywhere today);
 * - a saved voice absent from the catalog renders as unavailable with a
 *   download/reselect action instead of leaving every option unselected;
 * - the selected voice's row shows its favorite star and its Preview/Stop /
 *   Generating / Failed / Download action (missing packs replace Preview with
 *   the same explicit download action used elsewhere);
 * - when the selected voice's packs are not ready and [onOpenSettings] is
 *   non-null, a "Manage downloads in Settings" link leaves the current
 *   surface (the reader voice sheet closes itself) and opens Settings.
 */
@Composable
fun EngineVoicePicker(
    state: EngineVoiceUiState,
    voiceLabel: String,
    onEngineSelect: ((String) -> Unit)?,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (String) -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val engineSelect = onEngineSelect
        if (engineSelect != null && state.engines.isNotEmpty()) {
            val selectedEngine = state.engines.firstOrNull { it.id == state.engineId }
            LabeledDropdown(
                label = "Speech engine",
                selectedLabel = selectedEngine?.displayName ?: state.engineId,
                options =
                    state.engines.map {
                        DropdownOption(
                            id = it.id,
                            label = it.displayName,
                            description = it.description,
                            trailing = if (it.ready) "downloaded" else "needs download",
                        )
                    },
                onSelect = engineSelect,
                modifier = Modifier.padding(bottom = AyvuSpacing.SM),
            )
        }
        val selectedRow = state.rows.firstOrNull { it.selected }
        LabeledDropdown(
            label = voiceLabel,
            selectedLabel =
                selectedRow?.let { it.displayName.ifEmpty { it.name } }
                    ?: state.unavailableSavedVoice?.let { "$it (unavailable)" }
                    ?: "—",
            options =
                state.rows.map { row ->
                    DropdownOption(
                        id = row.name,
                        label = row.displayName.ifEmpty { row.name },
                        description = "${row.language} · ${row.gender}",
                        trailing =
                            if (row.ready) {
                                "downloaded"
                            } else {
                                "needs download · ${formatBytes(row.bytes)}"
                            },
                    )
                },
            onSelect = onSelect,
            modifier = Modifier.padding(bottom = AyvuSpacing.SM),
        )
        val unavailable = state.unavailableSavedVoice
        if (unavailable != null) {
            Text(
                "Selected voice: $unavailable (unavailable)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "This voice is not in the current catalog — download its pack or choose another.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onDownload(unavailable) }) { Text("Download / choose again") }
        } else if (selectedRow != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        selectedRow.displayName.ifEmpty { selectedRow.name },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        listOfNotNull(
                            selectedRow.name,
                            selectedRow.gender,
                            selectedRow.grade?.let { "grade $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onToggleFavorite(selectedRow.name) }) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription =
                            if (selectedRow.favorite) {
                                "Remove ${selectedRow.name} from favorites"
                            } else {
                                "Favorite ${selectedRow.name}"
                            },
                        tint =
                            if (selectedRow.favorite) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            when {
                !selectedRow.ready ->
                    TextButton(onClick = { onDownload(selectedRow.name) }) {
                        Text("Download this voice's pack")
                    }
                selectedRow.preview is VoicePreviewUi.Generating ->
                    Text(
                        "Generating sample…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = AyvuSpacing.SM),
                    )
                selectedRow.preview is VoicePreviewUi.Playing ->
                    TextButton(onClick = onStopPreview) {
                        Text("Stop")
                    }
                selectedRow.preview is VoicePreviewUi.Failed ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selectedRow.preview.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { onPreview(selectedRow.name) }) { Text("Retry") }
                    }
                else ->
                    TextButton(onClick = { onPreview(selectedRow.name) }) {
                        Text("Preview")
                    }
            }
        }
        if (!state.ready && onOpenSettings != null) {
            TextButton(onClick = onOpenSettings) { Text("Manage downloads in Settings") }
        }
    }
}

private fun previewStage(
    name: String,
    audition: AuditionUiState,
): VoicePreviewUi =
    if (audition.voice == name) {
        val stage = audition.stage
        when (stage) {
            AuditionStage.Generating -> VoicePreviewUi.Generating
            AuditionStage.Playing -> VoicePreviewUi.Playing
            is AuditionStage.Failed ->
                VoicePreviewUi.Failed(stage.reason)
            AuditionStage.Idle -> VoicePreviewUi.Idle
        }
    } else {
        VoicePreviewUi.Idle
    }
