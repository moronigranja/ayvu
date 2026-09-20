package io.github.moronigranja.ayvu.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.featurelibrary.ImportOperations
import io.github.moronigranja.ayvu.featurelibrary.ImportStateHolder
import io.github.moronigranja.ayvu.featurelibrary.ImportUiState
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.tts.DownloadOutcome
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackState
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.installPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
import io.github.moronigranja.ayvu.tts.setup.SetupFacts
import io.github.moronigranja.ayvu.tts.setup.SetupState
import io.github.moronigranja.ayvu.tts.setup.StepKind
import io.github.moronigranja.ayvu.tts.setup.StorageProbe
import io.github.moronigranja.ayvu.tts.shortMessage
import io.github.moronigranja.ayvu.ui.PlanPackRow
import io.github.moronigranja.ayvu.ui.PlanPackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Named

data class SetupUiState(
    val steps: List<StepKind> = emptyList(),
    /** The wizard's single visible step (item 6): derived identity with
     * clamp rules — a step that disappears never throws the user back, a
     * reappearing step inserts without moving the pointer. */
    val currentStep: StepKind? = null,
    /** The active engine's required packs, mapped for the shared plan card. */
    val packs: List<PlanPackRow> = emptyList(),
    val selectedVoice: String = SettingsStore.DEFAULT_VOICE,
    /** Shared engine+voice picker state — rows + "Selected voice:" summary
     * (+ the one audition), built from the static catalog + required-pack
     * readiness/bytes. */
    val engineVoice: io.github.moronigranja.ayvu.ui.EngineVoiceUiState =
        io.github.moronigranja.ayvu.ui
            .EngineVoiceUiState(),
    /** Sum of the three required packs' descriptor sizes — the plan's total. */
    val storageTotalBytes: Long = 0L,
    /** Sum of the not-yet-ready required packs' sizes — what still needs
     * space beyond what is already on disk. */
    val requiredBytes: Long = 0L,
    val availableBytes: Long = 0L,
    /** `availableBytes - requiredBytes` when negative (named on the plan
     * before any download starts, C1 acceptance leg 4). */
    val shortfallBytes: Long = 0L,
    val systemTtsOptedIn: Boolean = false,
    /** The active engine id ([SettingsStore] constants) — drives the required
     * packs, the voice catalog and the engine radio. */
    val ttsEngine: String = SettingsStore.DEFAULT_TTS_ENGINE,
    val importSummary: String? = null,
)

/**
 * C1.4: the first-run setup driver — the screen the app gate shows while
 * [SetupState] derives anything but COMPLETE. It owns the coordinated
 * download (progress/cancel/resume/retry via the registry), the storage
 * probe, the voice pick (persisted immediately via AppSettings), the
 * system-TTS opt-in (decisions #102) and the import hand-off (SAF →
 * [ImportCoordinator], app-injected — no feature-library VM is imported).
 *
 * Post-success hooks mirror SettingsVM's downloadInternal (espeak staging +
 * voice catalog invalidation), so Ready in setup equals Ready in Settings.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val registry: PackRegistry,
        private val installer: PackInstaller,
        private val settings: AppSettings,
        private val libraryStore: LibraryStore,
        @Named("app_files_dir") private val filesDir: File,
        private val storageProbe: StorageProbe,
        private val importOperations: ImportOperations,
        private val importStateHolder: ImportStateHolder,
        private val voiceAudition: VoiceAudition,
        // Null in the pure-JVM harness; Hilt supplies the process-lifetime runner.
        private val operations: OperationRunner? = null,
    ) : ViewModel() {
        private val auditionFlow = voiceAudition.state
        private val wizardTick = MutableStateFlow(0)

        /** The wizard pointer (item 6) — held here so Back/Next survive
         * re-derivations, mutated only through [wizardNext]/[wizardBack]. */
        private var wizardStep: StepKind? = null
        private var lastSteps: List<StepKind> = emptyList()

        // `combine` types at most 5 flows — nest: (packs, install failures,
        // settings) then books + (staged tick, wizard pointer, audition, import
        // state) re-derive the checklist on every fact change.
        private val core =
            combine(
                combine(registry.packs, registry.installFailed, settings.state) { packs, installFailures, prefs ->
                    Triple(packs, installFailures, prefs)
                },
                libraryStore.books,
                combine(registry.stagedTick, wizardTick, auditionFlow, importStateHolder.state) { _, _, audition, importState ->
                    audition to importState
                },
            ) { (packs, installFailures, prefs), books, (audition, importState) ->
                val requiredIds = SetupEnginePacks.requiredIds(prefs.ttsEngine, prefs.voice)
                val required = requiredIds.mapNotNull { id -> packs.firstOrNull { it.pack.id == id } }
                val requiredReady =
                    requiredIds.all { id -> packs.firstOrNull { it.pack.id == id }?.status == PackStatus.Ready }
                val facts =
                    SetupFacts(
                        requiredPacksReady = requiredReady,
                        espeakStaged = EspeakStager.isStaged(filesDir),
                        voiceSelected = prefs.voice != SettingsStore.DEFAULT_VOICE,
                        bookCount = books.size,
                        systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                        importDeferred = prefs.setupImportDeferred,
                    )
                val steps = SetupState.derive(facts)
                // Wizard clamp (item 6): keep the pointer on its step while it
                // survives; a removed step lands on the nearest PRECEDING
                // surviving step (the flow never throws the user back); a
                // re-inserted step never moves the pointer.
                wizardStep = clampWizardStep(wizardStep, lastSteps, steps)
                lastSteps = steps
                val requiredBytes = required.filter { it.status != PackStatus.Ready }.sumOf { it.pack.sizeBytes }
                val available = storageProbe.availableBytes()
                SetupUiState(
                    steps = steps,
                    currentStep = wizardStep,
                    packs = required.map { it.toPlanRow(installFailures, filesDir) },
                    selectedVoice = prefs.voice,
                    engineVoice = engineVoice(required, prefs, audition),
                    storageTotalBytes = required.sumOf { it.pack.sizeBytes },
                    requiredBytes = requiredBytes,
                    availableBytes = available,
                    shortfallBytes = (requiredBytes - available).coerceAtLeast(0L),
                    systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                    ttsEngine = prefs.ttsEngine,
                    importSummary = importSummary(importState),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState())

        val state: StateFlow<SetupUiState> = core

        init {
            viewModelScope.launch { autoStageEspeak() }
        }

        /** A verified-but-unstaged espeak pack self-heals like Settings does —
         * through the shared install path. */
        private suspend fun autoStageEspeak() {
            if (EspeakStager.isStaged(filesDir)) return
            val id =
                registry.packs.value
                    .firstOrNull { it.pack.id == ESPEAK_PACK_ID && it.status == PackStatus.Ready }
                    ?.pack
                    ?.id ?: return
            install(listOf(id))
        }

        /** The one download entry point: download + stage as a cancellable,
         * observable operation (K3). */
        fun download(packId: String) {
            install(listOf(packId))
        }

        private fun install(packIds: List<String>) {
            operations?.installPacks(installer, packIds)
        }

        /** Stops [packId]'s running download (the notification's Stop action);
         * the `.part` survives → resume. */
        fun cancelDownload(packId: String) {
            operations?.cancel("pack-download:$packId")
        }

        fun retry(packId: String) = download(packId)

        fun chooseVoice(name: String) = viewModelScope.launch { settings.setVoice(name) }

        /** Wizard Next (item 6): the next surviving step; Terminal Heads
         * settle through the derive-driven auto-finish, not here. */
        fun wizardNext() {
            val steps = state.value.steps
            val current = state.value.currentStep
            val index = steps.indexOf(current)
            val next = steps.getOrNull(index + 1) ?: return
            wizardStep = next
            wizardTick.value += 1
        }

        /** Wizard Back (item 6) — the system-back mapping. Blocked on the
         * first step (PRIVACY): the gate owns dismissal. */
        fun wizardBack() {
            val steps = state.value.steps
            val index = steps.indexOf(state.value.currentStep)
            if (index > 0) {
                wizardStep = steps[index - 1]
                wizardTick.value += 1
            }
        }

        /** The clamp rule shared by every re-derivation (item 6). */
        private fun clampWizardStep(
            previous: StepKind?,
            oldList: List<StepKind>,
            newList: List<StepKind>,
        ): StepKind? {
            val current = previous ?: return newList.firstOrNull()
            if (newList.isEmpty()) return current
            if (current in newList) return current
            // Walk backwards from where the step USED to sit; the first
            // surviving step found is the landing (never a forward throw).
            var index = oldList.indexOf(current)
            while (index > 0) {
                index -= 1
                val candidate = oldList[index]
                if (candidate in newList) return candidate
            }
            return newList.first()
        }

        /** decisions #102: opt into the zero-download degraded device voice. */
        fun optInSystemTts() = viewModelScope.launch { settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE) }

        /** C2: audition one voice without selecting it (one at a time). */
        fun previewVoice(voice: String) = voiceAudition.preview(voice)

        fun stopPreview() = voiceAudition.stop()

        /** The picker's per-voice download action while the active engine's packs
         * are missing — starts the same downloads the plan card lists. */
        fun downloadVoicePacks(voice: String) {
            val prefs = settings.state.value
            SetupEnginePacks.requiredIds(prefs.ttsEngine, voice).forEach { download(it) }
        }

        /** Shared engine+voice picker state — required-pack readiness/bytes +
         * the one audition, via the single shared builder (C2, #102.4 +
         * #166 follow-up). The catalog follows the selected engine (D4 #154
         * addendum): Piper voices under piper-v1, Kokoro's otherwise. */
        private fun engineVoice(
            required: List<PackState>,
            prefs: AppSettings.Snapshot,
            audition: io.github.moronigranja.ayvu.player.AuditionUiState,
        ): io.github.moronigranja.ayvu.ui.EngineVoiceUiState {
            val piperSelected = prefs.ttsEngine == SettingsStore.PIPER_ENGINE
            return io.github.moronigranja.ayvu.ui.buildEngineVoiceState(
                engineId = prefs.ttsEngine,
                engines =
                    io.github.moronigranja.ayvu.ui.engineOptions(prefs.ttsEngine) {
                        SetupEnginePacks.readyFor(prefs.ttsEngine, it, required)
                    },
                voices = if (piperSelected) PiperVoiceMetadata.all else KokoroVoiceMetadata.all,
                selectedVoice = prefs.voice,
                favorites = prefs.favorites.toSet(),
                readyFor = { SetupEnginePacks.readyFor(prefs.ttsEngine, it, required) },
                bytesFor = { SetupEnginePacks.bytesFor(prefs.ttsEngine, it, required) },
                audition = audition,
            )
        }

        /** The active-engine radio — the engine is a device decision
         * (decisions #144); selecting it re-derives the required packs, the
         * voice catalog and the download plan. */
        fun setEngine(engineId: String) = viewModelScope.launch { settings.setTtsEngine(engineId) }

        /** SAF import hand-off — the contact LibraryScreen uses, driven through
         * the shared import operation (one state holder, no feature-library VM). */
        fun importBooks(sources: List<EBookSource>) {
            importOperations.start(sources, truncated = false, operations = operations)
        }

        /** Dismisses the finished-batch summary (the wizard's consume). */
        fun consumeImportSummary() {
            importStateHolder.set(ImportUiState.Idle)
        }

        /**
         * The import step's Skip — and its last-step Finish — plus the summary's
         * Done (decisions #184): record the choice durably and let the
         * derivation finish, so the gate stays inactive on later cold starts
         * even with an empty library, and the Import tab is the way in. Without
         * it, "Finish" on a book-less install dismissed the wizard and the gate
         * put it straight back.
         */
        fun skipImport() =
            viewModelScope.launch {
                settings.setSetupImportDeferred(true)
                importStateHolder.set(ImportUiState.Idle)
            }

        /** The batch summary text the import step renders; null while nothing
         * finished (in-flight states render the overlay, not a summary). */
        private fun importSummary(state: ImportUiState): String? =
            (state as? ImportUiState.Done)?.summary?.let { summary ->
                when {
                    summary.failed.isNotEmpty() ->
                        "${summary.added} imported · ${summary.unchanged} unchanged · ${summary.failed.size} failed"
                    summary.added > 0 -> "${summary.added} added · ${summary.unchanged} unchanged"
                    else -> "Nothing new to import"
                }
            }

        private fun PackState.toPlanRow(
            installFailures: Map<String, String>,
            filesDir: File,
        ): PlanPackRow {
            val status =
                when (val s = status) {
                    is PackStatus.Downloading -> PlanPackStatus.Downloading(s.downloadedBytes, s.totalBytes)
                    // A staging failure wins over Ready: the transfer finished but
                    // the bundle did not, and "Ready" would hide why the engine
                    // still cannot run.
                    PackStatus.Ready ->
                        installFailures[pack.id]?.let { PlanPackStatus.Failed(it) } ?: PlanPackStatus.Ready
                    is PackStatus.Failed -> PlanPackStatus.Failed(installFailures[pack.id] ?: s.reason.shortMessage())
                    PackStatus.NotDownloaded -> PlanPackStatus.NotDownloaded
                }
            return PlanPackRow(
                packId = pack.id,
                displayName = pack.displayName,
                sizeBytes = pack.sizeBytes,
                status = status,
                staged = pack.id == ESPEAK_PACK_ID && EspeakStager.isStaged(filesDir),
            )
        }

        private companion object {
            const val ESPEAK_PACK_ID = "espeak-ng"
        }
    }
