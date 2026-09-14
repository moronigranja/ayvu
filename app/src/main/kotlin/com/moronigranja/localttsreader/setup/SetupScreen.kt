package com.moronigranja.localttsreader.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.moronigranja.localttsreader.featurelibrary.takeReadPermission
import com.moronigranja.localttsreader.featurelibrary.toEBookSources
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.formatBytes
import com.moronigranja.localttsreader.tts.setup.SetupState
import com.moronigranja.localttsreader.tts.setup.StepKind
import com.moronigranja.localttsreader.ui.AyvuSpacing
import com.moronigranja.localttsreader.ui.PacksPlanCard
import com.moronigranja.localttsreader.ui.PillButton
import com.moronigranja.localttsreader.ui.PlanPackStatus
import com.moronigranja.localttsreader.ui.SectionHeader
import com.moronigranja.localttsreader.ui.VoicePreviewUi
import com.moronigranja.localttsreader.ui.VoiceRowUi

/**
 * C1.4: the guided first-run flow. The checklist is derived by
 * [com.moronigranja.localttsreader.tts.setup.SetupState] from durable facts
 * — the screen is mechanical: each [StepKind] in the list renders its card.
 * On a terminal step it calls [onFinished] (the gate re-derives; with books +
 * packs it is inactive from then on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onFinished: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach(context::takeReadPermission)
                context.toEBookSources(uris).let(viewModel::importBooks)
            }
        }

    LaunchedEffect(state.steps.firstOrNull(), state.importSummary) {
        val head = state.steps.firstOrNull()
        if (head == StepKind.COMPLETE || head == StepKind.DEGRADED_READY) onFinished()
    }

    // System back maps to wizard Back while a non-terminal, non-first step
    // is current (item 6); on PRIVACY the gate owns dismissal — back must
    // not escape mid-setup.
    val currentStep = state.currentStep
    BackHandler(enabled = currentStep != null && currentStep != StepKind.PRIVACY) {
        viewModel.wizardBack()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up Ayvu") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(AyvuSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(AyvuSpacing.MD),
        ) {
            val step = state.currentStep ?: return@LazyColumn
            item(key = "step-$step") {
                when (step) {
                    StepKind.PRIVACY ->
                        PrivacyCard(
                            ttsEngine = state.ttsEngine,
                            onSetEngine = viewModel::setEngine,
                        )
                    StepKind.CHOOSE_VOICE ->
                        ChooseVoiceCard(
                            voiceSelector = state.voiceSelector,
                            onSelect = viewModel::chooseVoice,
                            onPreview = viewModel::previewVoice,
                            onStopPreview = viewModel::stopPreview,
                            onDownload = viewModel::downloadVoicePacks,
                        )
                    StepKind.DOWNLOAD_PACKS ->
                        DownloadPacksCard(
                            state = state,
                            onDownload = viewModel::download,
                            onCancel = viewModel::cancelDownload,
                            onOptInSystemTts = viewModel::optInSystemTts,
                        )
                    StepKind.IMPORT_BOOK ->
                        ImportBookCard(
                            state = state,
                            onPickBooks = { launcher.launch(IMPORT_MIME_TYPES) },
                            onDismissSummary = viewModel::consumeImportSummary,
                            onFinish = onFinished,
                        )
                    StepKind.COMPLETE, StepKind.DEGRADED_READY -> Unit // LaunchedEffect above
                }
            }
            // Wizard nav (item 6): Back to the previous surviving step
            // (disabled on PRIVACY — the gate owns dismissal), Next advances;
            // Next on DOWNLOAD_PACKS is enabled only when the packs are Ready.
            if (!SetupState.isTerminal(state.steps)) {
                item(key = "wizard-nav") {
                    val index = state.steps.indexOf(step)
                    val isLast = index == state.steps.lastIndex
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton(
                            "Back",
                            onClick = viewModel::wizardBack,
                            enabled = index > 0,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isLast) {
                            PillButton("Finish", onClick = onFinished)
                        } else if (step == StepKind.DOWNLOAD_PACKS) {
                            PillButton(
                                "Next",
                                onClick = viewModel::wizardNext,
                                enabled = state.packs.all { it.status == PlanPackStatus.Ready },
                            )
                        } else {
                            PillButton("Next", onClick = viewModel::wizardNext)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard(
    ttsEngine: String,
    onSetEngine: (String) -> Unit,
) {
    StepCard {
        Text("Ayvu is offline-first", style = MaterialTheme.typography.titleMedium)
        Text(
            "Everything runs on this device. No account, no telemetry, no cloud " +
                "processing. The speech engines are downloaded separately so the app " +
                "stays small and you control the data cost — the next step shows the " +
                "exact size of everything before any download starts.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Speech engine", style = MaterialTheme.typography.titleSmall)
        EngineRow(
            label = "Kokoro-82M (default)",
            sub = "Best prosody; requires a ~364 MB download.",
            selected = ttsEngine != SettingsStore.PIPER_ENGINE && ttsEngine != SettingsStore.SYSTEM_TTS_ENGINE,
            onClick = { onSetEngine(SettingsStore.DEFAULT_TTS_ENGINE) },
        )
        EngineRow(
            label = "Piper",
            sub = "Smaller and faster — recommended on weaker devices; covers German.",
            selected = ttsEngine == SettingsStore.PIPER_ENGINE,
            onClick = { onSetEngine(SettingsStore.PIPER_ENGINE) },
        )
        EngineRow(
            label = "Device voice (system)",
            sub = "Zero download, degraded quality. Switch to an engine later in Settings.",
            selected = ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
            onClick = { onSetEngine(SettingsStore.SYSTEM_TTS_ENGINE) },
        )
    }
}

@Composable
private fun EngineRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick)
                .padding(vertical = AyvuSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = AyvuSpacing.SM)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChooseVoiceCard(
    voiceSelector: com.moronigranja.localttsreader.ui.VoiceSelectorUiState,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    StepCard {
        Text("Choose a voice", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick from Ayvu's built-in catalog — your choice is saved immediately. " +
                "Preview needs the speech packs; until they are installed the " +
                "selected voice shows the download action instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        VoiceDropdown(
            state = voiceSelector,
            onSelect = onSelect,
            onPreview = onPreview,
            onStopPreview = onStopPreview,
            onDownload = onDownload,
        )
    }
}

/**
 * Owner override (2026-09-02) of the C2 full-list selector for first-run setup:
 * a compact dropdown of the whole catalog instead of the tall inline list, with
 * the selected voice's action (Preview/Stop/download) beneath. Data still comes
 * from the ONE shared [com.moronigranja.localttsreader.ui.VoiceSelectorUiState]
 * builder — Settings keeps the full shared selector (#102.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    state: com.moronigranja.localttsreader.ui.VoiceSelectorUiState,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.rows.firstOrNull { it.selected }
    val anchorLabel =
        selected?.let { "${it.name} · ${it.language}" }
            ?: state.unavailableSavedVoice?.let { "$it (unavailable)" }
            ?: state.summary.ifBlank { "—" }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = anchorLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = if (expanded) "Close voice list" else "Open voice list",
                )
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.rows.groupBy { it.language }.forEach { (language, rows) ->
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = AyvuSpacing.MD, vertical = AyvuSpacing.XS),
                )
                rows.forEach { row ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${row.language} · ${row.gender}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = { RadioButton(selected = row.selected, onClick = null) },
                        trailingIcon =
                            if (!row.ready) {
                                {
                                    Text(
                                        "needs download",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                null
                            },
                        onClick = {
                            onSelect(row.name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    when {
        state.unavailableSavedVoice != null ->
            Column {
                Text(
                    "Selected voice: ${state.unavailableSavedVoice} (unavailable)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "This voice is not in the current catalog — download its pack or choose another.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDownload) { Text("Download / choose again") }
            }
        selected != null -> selectedVoiceAction(selected, onPreview, onStopPreview, onDownload)
    }
}

/** The selected voice's Preview/Stop/Generating/Download — the shared row's
 * action semantics (C2) kept in the compact card. */
@Composable
private fun selectedVoiceAction(
    row: VoiceRowUi,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    // Local capture: `preview` is a public API property of another module —
    // smart casts are impossible across that boundary.
    val preview = row.preview
    when {
        !row.ready ->
            TextButton(onClick = onDownload) { Text("Download this voice's pack") }
        preview is VoicePreviewUi.Generating ->
            Text(
                "Generating sample…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = AyvuSpacing.SM),
            )
        preview is VoicePreviewUi.Playing ->
            TextButton(onClick = onStopPreview) { Text("Stop") }
        preview is VoicePreviewUi.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onPreview(row.name) }) { Text("Retry") }
            }
        else -> TextButton(onClick = { onPreview(row.name) }) { Text("Preview") }
    }
}

@Composable
private fun DownloadPacksCard(
    state: SetupUiState,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOptInSystemTts: () -> Unit,
) {
    StepCard {
        Text("Download the speech engine", style = MaterialTheme.typography.titleMedium)
        Text(
            "One plan, coordinated with per-file progress. Downloads " +
                "resume after interruptions and verify checksums before they count as done.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PacksPlanCard(
            rows = state.packs,
            onDownload = onDownload,
            onCancel = onCancel,
            storageLine = storageLine(state),
            shortfall =
                if (state.shortfallBytes > 0L) {
                    "Not enough free space — free ${formatBytes(state.shortfallBytes)} more to download the plan."
                } else {
                    null
                },
            footer = {
                Text(
                    "Audio you generate later grows separately from these engine assets — " +
                        "you can delete a book's audio per book in the library.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AyvuSpacing.SM),
                )
                androidx.compose.material3.TextButton(
                    onClick = onOptInSystemTts,
                    modifier = Modifier.padding(top = AyvuSpacing.XS),
                ) {
                    Text("Continue with the device voice (degraded quality, no download)")
                }
            },
        )
    }
}

@Composable
private fun storageLine(state: SetupUiState): String {
    // "X of Y MB free — needs Z MB" where Z = the still-to-download bytes.
    val need = state.requiredBytes
    val needLabel =
        if (state.requiredBytes < state.storageTotalBytes) {
            "${formatBytes(need)} more"
        } else {
            formatBytes(state.storageTotalBytes)
        }
    return "${formatBytes(state.availableBytes)} free — needs $needLabel"
}

@Composable
private fun ImportBookCard(
    state: SetupUiState,
    onPickBooks: () -> Unit,
    onDismissSummary: () -> Unit,
    onFinish: () -> Unit,
) {
    StepCard {
        Text("Import a book", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick an EPUB or plain-text file to begin listening. On Ayvu, an imported " +
                "book is the signal that first-run setup is complete.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PillButton("Import a book", onClick = onPickBooks)
        state.importSummary?.let { summary ->
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = AyvuSpacing.XS),
            )
            PillButton("Done", onClick = {
                onDismissSummary()
                onFinish()
            })
        }
    }
}

@Composable
private fun StepCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AyvuSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
            content = content,
        )
    }
}

private val IMPORT_MIME_TYPES =
    arrayOf(
        "application/epub+zip",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/octet-stream",
    )
