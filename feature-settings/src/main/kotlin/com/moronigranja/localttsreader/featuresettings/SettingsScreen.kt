package com.moronigranja.localttsreader.featuresettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.player.formatBytes
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.ui.AyvuSpacing
import com.moronigranja.localttsreader.ui.ConfirmDialog
import com.moronigranja.localttsreader.ui.PacksPlanCard
import com.moronigranja.localttsreader.ui.PillButton
import com.moronigranja.localttsreader.ui.PlanPackRow
import com.moronigranja.localttsreader.ui.PlanPackStatus
import com.moronigranja.localttsreader.ui.SectionHeader
import kotlin.math.roundToInt

/**
 * Settings root, grouped by concern (Phase K item 1): Speech (one entry row
 * into the Speech subpane), Reading & sharing (match threshold, OCR
 * languages), Storage & data (offline audio, backup & restore), Appearance
 * (theme), About (build identity, license, third-party notices, privacy).
 *
 * Speech is an Android-settings-style subscreen (decisions #156 addendum):
 * engine + packs + the ~60-row voice selector outgrew the root's one-flick
 * budget, so the subpane holds them behind the entry row; the rest of the
 * root stays flat. Every row maps directly to a [SettingsViewModel] call — no
 * logic in the view.
 */
private enum class SettingsPane { Root, Speech, OcrLanguages }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val offlineRows by viewModel.offlineRows.collectAsState()
    // Open-bugs "Offline-audio usage row is stale on return to a live Settings
    // screen" (decisions #144): the tier was read only in init and after a
    // delete, so a live instance kept showing an empty row after a book was
    // pre-generated behind it. Re-read on every resume — idempotent, one IO
    // read on the ViewModel's background dispatcher, never a poll.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOfflineUsage() }
    var pane by remember { mutableStateOf(SettingsPane.Root) }
    // System back mirrors the top-bar arrow: any subpane (Speech, OCR
    // languages) collapses first, then the settings screen closes back to the
    // library (not app exit). Settings is a boolean branch in MainActivity,
    // not a nav destination, so root back MUST stay intercepted — disabling
    // the handler here would let the Activity finish and exit the app.
    BackHandler {
        if (pane == SettingsPane.Root) onBack() else pane = SettingsPane.Root
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (pane) {
                            SettingsPane.Speech -> "Speech"
                            SettingsPane.OcrLanguages -> "OCR languages"
                            SettingsPane.Root -> "Settings"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (pane == SettingsPane.Root) onBack() else pane = SettingsPane.Root }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (pane) {
            SettingsPane.Speech -> SpeechPane(state, viewModel, padding)
            SettingsPane.OcrLanguages -> OcrLanguagesPane(state, viewModel, padding)
            SettingsPane.Root ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(AyvuSpacing.LG),
                    verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
                ) {
                    item {
                        // Android-settings-style subscreen entry (decisions
                        // #156 addendum): the engine + packs + the ~60-row
                        // voice selector outgrew the root's one-flick budget.
                        SpeechEntryRow(
                            engineLabel = speechEngineLabel(state.ttsEngine),
                            summary = state.voiceSelector.summary,
                            onClick = { pane = SettingsPane.Speech },
                        )
                    }

                    item { SectionHeader("Reading & sharing", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS)) }
                    item {
                        Column {
                            Text("Match threshold: ${"%.2f".format(state.matchThreshold)}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "How closely a shared snippet must match a book passage.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = state.matchThreshold.toFloat(),
                                onValueChange = { viewModel.setThreshold(it.toDouble()) },
                                valueRange = 0.3f..0.9f,
                            )
                        }
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pane = SettingsPane.OcrLanguages }
                                    .padding(vertical = AyvuSpacing.SM),
                        ) {
                            Text(
                                "OCR languages",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }

                    item { SectionHeader("Storage & data", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS)) }
                    if (offlineRows.isEmpty()) {
                        item {
                            Text(
                                "No pre-generated audio — the library row's Pre-generate fills it.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Total: ${formatBytes(offlineRows.sumOf { it.bytes })} — one listened hour ≈ 170 MB",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = AyvuSpacing.XS, vertical = AyvuSpacing.XS),
                            )
                        }
                        items(offlineRows, key = { it.bookId }) { row ->
                            OfflineAudioRow(
                                title = row.title,
                                bytes = row.bytes,
                                onDelete = { viewModel.deleteOffline(row.bookId) },
                            )
                        }
                    }

                    item { SectionHeader("Appearance", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS)) }
                    item {
                        Column {
                            ThemeMode.entries.forEach { mode ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = state.themeMode == mode,
                                                onClick = { viewModel.setTheme(mode) },
                                            ).padding(vertical = AyvuSpacing.XS),
                                ) {
                                    RadioButton(selected = state.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "Follow system"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item { BackupSection() }

                    // Release 0.1.1: build identity + license/notices + the
                    // on-device claim, last in the list.
                    item {
                        aboutSection(
                            versionName = viewModel.appVersion,
                            onOpenLink = viewModel::openLink,
                        )
                    }
                }
        }
    }
}

/**
 * The Speech subpane (decisions #156 addendum): engine radios + the selected
 * engine's descriptor-derived pack rows + espeak detail + the generation-
 * threads bound + the voice selector + playback volume, moved off the root
 * unchanged.
 */
@Composable
private fun SpeechPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    padding: PaddingValues,
) {
    val speechRows = state.packs.filter { it.packId in state.speechPackIds }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(AyvuSpacing.LG),
        verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
    ) {
        item {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.ttsEngine == SettingsStore.DEFAULT_TTS_ENGINE,
                                onClick = { viewModel.setEngine(SettingsStore.DEFAULT_TTS_ENGINE) },
                            ).padding(vertical = AyvuSpacing.XS),
                ) {
                    RadioButton(
                        selected = state.ttsEngine == SettingsStore.DEFAULT_TTS_ENGINE,
                        onClick = { viewModel.setEngine(SettingsStore.DEFAULT_TTS_ENGINE) },
                    )
                    Column {
                        Text("Kokoro-82M (downloaded)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "High-quality offline voices — download required.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.ttsEngine == SettingsStore.PIPER_ENGINE,
                                onClick = { viewModel.setEngine(SettingsStore.PIPER_ENGINE) },
                            ).padding(vertical = AyvuSpacing.XS),
                ) {
                    RadioButton(
                        selected = state.ttsEngine == SettingsStore.PIPER_ENGINE,
                        onClick = { viewModel.setEngine(SettingsStore.PIPER_ENGINE) },
                    )
                    Column {
                        Text("Piper (downloaded)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Compact open-weight voices — English (US) and German. Download required; no read-along highlights.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                                onClick = { viewModel.setEngine(SettingsStore.SYSTEM_TTS_ENGINE) },
                            ).padding(vertical = AyvuSpacing.XS),
                ) {
                    RadioButton(
                        selected = state.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                        onClick = { viewModel.setEngine(SettingsStore.SYSTEM_TTS_ENGINE) },
                    )
                    Column {
                        Text("Device voice (system)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Zero-download fallback — degraded quality, no read-along highlights.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // C1.5: with the degraded voice active but the open-weight upgrade
        // packs missing, the section IS the install plan the setup flow shows
        // — the same rows once, never a second copy beside the plain rows.
        if (state.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE && speechRows.any { it.status != PackStatus.Ready }) {
            item {
                PacksPlanCard(
                    rows = speechRows.map { it.toPlanRow() },
                    onDownload = { viewModel.download(it) },
                    onCancel = { /* settings downloads are not user-cancelled */ },
                )
            }
        } else {
            // K2 (decisions #156): the selected engine's rows derive from the
            // registered engine descriptors — a new engine adds its packs with
            // no settings-surface edit (companion artifacts are excluded
            // upstream; a base row's download fetches them).
            items(speechRows) { row ->
                PackRow(row, onDownload = { viewModel.download(row.packId) })
            }
        }
        item {
            Text(
                "espeak-ng: ${state.espeakDetail}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = AyvuSpacing.XS, vertical = AyvuSpacing.XS),
            )
        }
        // Decisions #137: generation threads bound the downloaded
        // open-weight engines (Kokoro AND Piper — D4 #154 addendum);
        // a degraded session never touches ORT, so the row hides.
        if (state.ttsEngine != SettingsStore.SYSTEM_TTS_ENGINE) {
            item {
                Column {
                    Text(
                        "Generation threads: ${state.ttsThreads}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Cores used while generating audio. Fewer keep the phone snappier; more generate faster. Applies after restart.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = state.ttsThreads.toFloat(),
                        onValueChange = { viewModel.setTtsThreads(it.roundToInt()) },
                        valueRange = SettingsStore.MIN_TTS_THREADS.toFloat()..SettingsStore.MAX_TTS_THREADS.toFloat(),
                    )
                }
            }
        }
        // C2 shared selector: persistent "Selected voice:" summary, one
        // radio indicator, favorites independent, per-row Preview/Stop,
        // missing packs → the explicit download action.
        item {
            com.moronigranja.localttsreader.ui.VoiceSelector(
                state = state.voiceSelector,
                onSelect = viewModel::selectVoice,
                onToggleFavorite = viewModel::toggleFavorite,
                onPreview = viewModel::previewVoice,
                onStopPreview = viewModel::stopPreview,
                onDownload = { viewModel.downloadVoicePacks() },
            )
        }
        item {
            Column {
                Text("Playback volume: ${"%.1f".format(state.playbackGain)}×", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Multiplier applied to the generated voice on top of the device media volume.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = state.playbackGain,
                    onValueChange = viewModel::setPlaybackGain,
                    valueRange = SettingsStore.PLAYBACK_GAIN_MIN..SettingsStore.PLAYBACK_GAIN_MAX,
                )
            }
        }
    }
}

/** The OCR-languages picker subpane (decisions #144) — unchanged body. */
@Composable
private fun OcrLanguagesPane(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        item { SectionHeader("OCR languages", Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS)) }
        items(state.packs.filter { it.engineId == TESS_ENGINE_ID }) { row ->
            PackRow(row, onDownload = { viewModel.download(row.packId) })
            OcrLanguageRow(
                packId = row.packId,
                enabled = row.staged,
                selected = row.packId in state.ocrLanguages,
                onToggle = { viewModel.setOcrLanguage(row.packId, it) },
            )
        }
        item {
            Text(
                "Selected languages are used for shared-image snippets; the bundle installs once.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = AyvuSpacing.XS),
            )
        }
    }
}

/** The root's Speech entry row: engine label + voice summary, opens the subpane. */
@Composable
private fun SpeechEntryRow(
    engineLabel: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.SM),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Speech", style = MaterialTheme.typography.bodyLarge)
            Text(
                "$engineLabel · $summary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** The root row's engine label, mirroring the Speech pane's radio copy. */
private fun speechEngineLabel(engine: String): String =
    when (engine) {
        SettingsStore.PIPER_ENGINE -> "Piper (downloaded)"
        SettingsStore.SYSTEM_TTS_ENGINE -> "Device voice (system)"
        else -> "Kokoro-82M (downloaded)"
    }

@Composable
private fun OfflineAudioRow(
    title: String,
    bytes: Long,
    onDelete: () -> Unit,
) {
    // Destructive action behind an explicit confirm (decisions #94) — the
    // delete cancels queued pregen work and drops cached audio.
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = AyvuSpacing.XS),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(formatBytes(bytes), style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.TextButton(onClick = { confirmDelete = true }) {
            Text("Delete")
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete offline audio?",
            text = "Frees ${formatBytes(bytes)} for this book. It can be regenerated later.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PackRow(
    row: PackRow,
    onDownload: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = AyvuSpacing.XS),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.displayName, style = MaterialTheme.typography.bodyMedium)
            when {
                row.progress != null -> {
                    LinearProgressIndicator(
                        progress = { row.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = AyvuSpacing.XS),
                    )
                    Text("${(row.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                row.status == PackStatus.Ready ->
                    Text(
                        if (row.staged) "ready · installed" else "ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                row.error != null -> {
                    Text("failed: ${row.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    PillButton("Retry", onClick = onDownload)
                }
                else ->
                    Text(
                        "${row.sizeBytes / 1_048_576} MiB — download required",
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
        }
        if (row.progress == null && row.status != PackStatus.Ready) {
            androidx.compose.material3.TextButton(onClick = onDownload) {
                Text("Download")
            }
        } else if (row.status == PackStatus.Ready) {
            Icon(Icons.Default.Check, contentDescription = "Ready", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun OcrLanguageRow(
    packId: String,
    enabled: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = AyvuSpacing.SM, end = AyvuSpacing.XS, bottom = AyvuSpacing.SM),
    ) {
        Switch(
            checked = selected && enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
        Text(
            if (enabled) "Use for share OCR" else "Download above, then enable",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = AyvuSpacing.SM),
        )
    }
}

/** C1.5: settings PackRow → the shared plan card's neutral row shape. */
private fun PackRow.toPlanRow(): PlanPackRow {
    val planStatus =
        when (val s = status) {
            is PackStatus.Downloading -> PlanPackStatus.Downloading(s.downloadedBytes, s.totalBytes)
            PackStatus.Ready -> PlanPackStatus.Ready
            is PackStatus.Failed -> PlanPackStatus.Failed(error)
            PackStatus.NotDownloaded -> PlanPackStatus.NotDownloaded
        }
    return PlanPackRow(
        packId = packId,
        displayName = displayName,
        sizeBytes = sizeBytes,
        status = planStatus,
        staged = staged,
    )
}
