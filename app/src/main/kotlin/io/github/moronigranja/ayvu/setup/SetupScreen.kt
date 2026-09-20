package io.github.moronigranja.ayvu.setup

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import io.github.moronigranja.ayvu.featurelibrary.takeReadPermission
import io.github.moronigranja.ayvu.featurelibrary.toEBookSources
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.formatBytes
import io.github.moronigranja.ayvu.tts.setup.SetupState
import io.github.moronigranja.ayvu.tts.setup.StepKind
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.PacksPlanCard
import io.github.moronigranja.ayvu.ui.PillButton
import io.github.moronigranja.ayvu.ui.PlanPackStatus
import io.github.moronigranja.ayvu.ui.SectionHeader

/**
 * C1.4: the guided first-run flow. The checklist is derived by
 * [io.github.moronigranja.ayvu.tts.setup.SetupState] from durable facts
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
                            engineVoice = state.engineVoice,
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
                            onSkip = viewModel::skipImport,
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
                        when {
                            // The import step is the last one, and its card owns
                            // both actions (Import / Skip for now) — a nav
                            // "Finish" here duplicated the skip and dead-ended
                            // before #184.
                            isLast && step == StepKind.IMPORT_BOOK -> Unit
                            isLast -> PillButton("Finish", onClick = onFinished)
                            step == StepKind.DOWNLOAD_PACKS ->
                                PillButton(
                                    "Next",
                                    onClick = viewModel::wizardNext,
                                    enabled = state.packs.all { it.status == PlanPackStatus.Ready },
                                )
                            else -> PillButton("Next", onClick = viewModel::wizardNext)
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
    engineVoice: io.github.moronigranja.ayvu.ui.EngineVoiceUiState,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (String) -> Unit,
) {
    StepCard {
        Text("Choose a voice", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick from Ayvu's built-in catalog — your choice is saved immediately. " +
                "Preview needs the speech packs; until they are installed the " +
                "selected voice shows the download action instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // The ONE shared compact picker (decisions #166 follow-up) — the
        // owner override of the C2 full-list selector is retired: Setup now
        // renders the same surface Settings and the reader use.
        io.github.moronigranja.ayvu.ui.EngineVoicePicker(
            state = engineVoice,
            voiceLabel = "Voice",
            onEngineSelect = null,
            onSelect = onSelect,
            onToggleFavorite = { },
            onPreview = onPreview,
            onStopPreview = onStopPreview,
            onDownload = onDownload,
            onOpenSettings = null,
        )
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
    onSkip: () -> Unit,
) {
    StepCard {
        Text("Import a book", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick an EPUB or plain-text file to begin listening — or skip this and import " +
                "later from the library's Import tab.",
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
                onSkip()
            })
        }
        // Skip is a real choice, not a dismissal: it is recorded durably, so
        // the wizard does not come back on the next cold start (decisions
        // #184). Same action the nav's removed Finish used to imply.
        androidx.compose.material3.TextButton(onClick = onSkip) {
            Text("Skip for now")
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
