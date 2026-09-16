package com.moronigranja.localttsreader.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportCoordinator
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.EspeakStager
import com.moronigranja.localttsreader.player.IoDispatcher
import com.moronigranja.localttsreader.player.VoiceAudition
import com.moronigranja.localttsreader.tts.DownloadFailureReason
import com.moronigranja.localttsreader.tts.DownloadOutcome
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackState
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.tts.VoiceCatalog
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import com.moronigranja.localttsreader.tts.piper.PiperVoiceMetadata
import com.moronigranja.localttsreader.tts.setup.SetupEnginePacks
import com.moronigranja.localttsreader.tts.setup.SetupFacts
import com.moronigranja.localttsreader.tts.setup.SetupState
import com.moronigranja.localttsreader.tts.setup.StepKind
import com.moronigranja.localttsreader.tts.setup.StorageProbe
import com.moronigranja.localttsreader.ui.PlanPackRow
import com.moronigranja.localttsreader.ui.PlanPackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val engineVoice: com.moronigranja.localttsreader.ui.EngineVoiceUiState =
        com.moronigranja.localttsreader.ui
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
        private val cache: PackCache,
        private val settings: AppSettings,
        private val voiceCatalog: VoiceCatalog,
        private val libraryStore: LibraryStore,
        @Named("app_files_dir") private val filesDir: File,
        private val storageProbe: StorageProbe,
        private val coordinator: ImportCoordinator,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val voiceAudition: VoiceAudition,
    ) : ViewModel() {
        private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        private val stageTick = MutableStateFlow(0)
        private val auditionFlow = voiceAudition.state
        private val importTick = MutableStateFlow(0)
        private val wizardTick = MutableStateFlow(0)
        private val jobs = mutableMapOf<String, Job>()
        private var importSummaryValue: String? = null

        /** The wizard pointer (item 6) — held here so Back/Next survive
         * re-derivations, mutated only through [wizardNext]/[wizardBack]. */
        private var wizardStep: StepKind? = null
        private var lastSteps: List<StepKind> = emptyList()

        // `combine` types at most 5 flows — nest: (packs, errors, settings) then
        // books + the two ticks re-derive the checklist on every fact change.
        private val core =
            combine(
                combine(registry.packs, errors, settings.state) { packs, err, prefs ->
                    Triple(packs, err, prefs)
                },
                libraryStore.books,
                combine(stageTick, importTick, wizardTick, auditionFlow) { _, _, _, audition -> audition },
            ) { (packs, err, prefs), books, audition ->
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
                    packs = required.map { it.toPlanRow(err, filesDir) },
                    selectedVoice = prefs.voice,
                    engineVoice = engineVoice(required, prefs, audition),
                    storageTotalBytes = required.sumOf { it.pack.sizeBytes },
                    requiredBytes = requiredBytes,
                    availableBytes = available,
                    shortfallBytes = (requiredBytes - available).coerceAtLeast(0L),
                    systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                    ttsEngine = prefs.ttsEngine,
                    importSummary = importSummaryValue,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState())

        val state: StateFlow<SetupUiState> = core

        init {
            viewModelScope.launch { autoStageEspeak() }
        }

        /** A verified-but-unstaged espeak pack self-heals like Settings does. */
        private suspend fun autoStageEspeak() {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == ESPEAK_PACK_ID }
                    ?.pack ?: return
            if (!EspeakStager.isStaged(filesDir) && cache.isVerified(pack)) stageEspeak(pack.id)
        }

        fun download(packId: String) {
            if (jobs.containsKey(packId)) return
            jobs[packId] =
                viewModelScope.launch {
                    errors.update(packId, null)
                    try {
                        val outcome = registry.download(packId) { _, _ -> }
                        when (outcome) {
                            is DownloadOutcome.Ready, is DownloadOutcome.AlreadyCached -> {
                                if (packId == ESPEAK_PACK_ID) stageEspeak(packId)
                                if (packId == KokoroPacks.voices.id) voiceCatalog.invalidate()
                            }
                            is DownloadOutcome.Failed -> errors.update(packId, shortReason(outcome.reason))
                        }
                    } catch (e: CancellationException) {
                        errors.update(packId, null) // cancelled — the `.part` survives for resume
                    } finally {
                        jobs.remove(packId)
                    }
                }
        }

        /** Cancels one pack's in-flight transfer; the `.part` survives → resume. */
        fun cancelDownload(packId: String) {
            jobs[packId]?.cancel()
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
            audition: com.moronigranja.localttsreader.player.AuditionUiState,
        ): com.moronigranja.localttsreader.ui.EngineVoiceUiState {
            val piperSelected = prefs.ttsEngine == SettingsStore.PIPER_ENGINE
            return com.moronigranja.localttsreader.ui.buildEngineVoiceState(
                engineId = prefs.ttsEngine,
                engines =
                    com.moronigranja.localttsreader.ui.engineOptions(prefs.ttsEngine) {
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

        /** SAF import hand-off — the contact LibraryScreen uses, driven here
         * against app-injected dependencies (no feature-library VM). */
        fun importBooks(sources: List<EBookSource>) {
            if (sources.isEmpty()) return
            importSummaryValue = null
            viewModelScope.launch {
                importTick.value += 1
                try {
                    val outcomes = withContext(ioDispatcher) { coordinator.importAll(sources, onProgress = { _, _, _ -> }) }
                    importSummaryValue = buildSummary(outcomes)
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    importTick.value += 1
                }
            }
        }

        fun consumeImportSummary() {
            importSummaryValue = null
        }

        private fun buildSummary(outcomes: List<ImportOutcome>): String {
            var added = 0
            var unchanged = 0
            var failed = 0
            for (outcome in outcomes) {
                when (outcome) {
                    is ImportOutcome.Added -> added += 1
                    is ImportOutcome.Unchanged -> unchanged += 1
                    is ImportOutcome.Failed -> failed += 1
                }
            }
            return when {
                failed > 0 -> "$added imported · $unchanged unchanged · $failed failed"
                added > 0 -> "$added added · $unchanged unchanged"
                else -> "Nothing new to import"
            }
        }

        private fun shortReason(reason: DownloadFailureReason): String =
            when (reason) {
                is DownloadFailureReason.HttpStatus -> "HTTP ${reason.status}"
                is DownloadFailureReason.IoError -> reason.message ?: "network error"
                is DownloadFailureReason.CorruptContent -> "checksum mismatch"
                is DownloadFailureReason.Incomplete -> "incomplete download"
            }

        private suspend fun stageEspeak(packId: String) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: return
            runCatching { EspeakStager.stage(filesDir, cache, pack) }
                .onSuccess { staged -> if (staged) stageTick.value += 1 }
                .onFailure { errors.update(packId, "staging failed: ${it.message}") }
        }

        private fun PackState.toPlanRow(
            err: Map<String, String>,
            filesDir: File,
        ): PlanPackRow {
            val status =
                when (val s = status) {
                    is PackStatus.Downloading -> PlanPackStatus.Downloading(s.downloadedBytes, s.totalBytes)
                    PackStatus.Ready -> PlanPackStatus.Ready
                    is PackStatus.Failed -> PlanPackStatus.Failed(err[pack.id] ?: shortReason(s.reason))
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

private fun MutableStateFlow<Map<String, String>>.update(
    key: String,
    value: String?,
) {
    this.value = if (value == null) this.value - key else this.value + (key to value)
}
