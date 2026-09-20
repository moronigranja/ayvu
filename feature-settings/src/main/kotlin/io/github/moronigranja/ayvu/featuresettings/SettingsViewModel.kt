package io.github.moronigranja.ayvu.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.ocr.TessDataStager
import io.github.moronigranja.ayvu.ocr.TrainedDataPacks
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.persistence.ThemeMode
import io.github.moronigranja.ayvu.player.AuditionStage
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.player.OfflineStorage
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.player.formatBytes
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackState
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.installPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMeta
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
import io.github.moronigranja.ayvu.tts.shortMessage
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.ui.EngineVoiceUiState
import io.github.moronigranja.ayvu.ui.buildEngineVoiceState
import io.github.moronigranja.ayvu.ui.engineOptions
import kotlinx.coroutines.Dispatchers
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

internal const val TESS_ENGINE_ID = TrainedDataPacks.ENGINE_ID

data class PackRow(
    val packId: String,
    val engineId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: PackStatus,
    val progress: Double? = null, // 0..1 while downloading
    val error: String? = null,
    val staged: Boolean = false, // tessdata copied into the engine's data dir
)

/**
 * One read-in-language engine option (decisions #182), for the Speech section's
 * Translation picker: the user's radio choice plus that choice's download
 * state. Built from the static registry (`TranslatePacks.all`) joined with the
 * live pack states, so a newly registered engine appears here with no screen
 * edit.
 */
data class TranslateEngineRow(
    val id: String,
    val label: String,
    val summary: String,
    val packId: String,
    /** The pack's display name (`LFM2.5-2.6B-Base translate model (Q4_K_M)`). */
    val packName: String,
    /** Download size, from the descriptor (`~731 MB` / `~1.7 GB`). */
    val sizeLabel: String,
    val status: PackStatus = PackStatus.NotDownloaded,
    val progress: Double? = null, // 0..1 while downloading
    val error: String? = null,
    /** The engine's GGUF is staged — it can translate right now. */
    val staged: Boolean = false,
    /** The stored selection. */
    val selected: Boolean = false,
)

data class SettingsUiState(
    val packs: List<PackRow> = emptyList(),
    /**
     * K2 (decisions #156): the pack rows the Speech section shows, derived
     * from the registered engines' descriptors — the selected engine's own
     * packs plus the shared espeak-ng bundle every open-weight engine
     * phonemizes through. The degraded system voice has no packs of its
     * own: its rows are the open-weight upgrade path (Kokoro's), the same
     * set the install plan card offers. A newly registered engine adds its
     * packs with no settings-surface edit.
     */
    val speechPackIds: Set<String> = emptySet(),
    /** The shared engine+voice picker state (core-ui rows + "Selected voice:"
     * summary + unavailable-saved-voice), built from the static catalog +
     * required-pack readiness/bytes + the one audition. */
    val engineVoice: EngineVoiceUiState = EngineVoiceUiState(),
    /** The speech engine id (C1.5): kokoro-82m or the degraded system-tts. */
    val ttsEngine: String = SettingsStore.DEFAULT_TTS_ENGINE,
    /** The read-in-language engine options (decisions #182): the Translation
     * picker's radios + each engine's pack download state. */
    val translateEngines: List<TranslateEngineRow> = emptyList(),
    val matchThreshold: Double = SettingsStore.DEFAULT_MATCH_THRESHOLD,
    val playbackGain: Float = SettingsStore.DEFAULT_PLAYBACK_GAIN,
    /** ORT intra-op threads for Kokoro synthesis (decisions #137). */
    val ttsThreads: Int = SettingsStore.DEFAULT_TTS_THREADS,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val ocrLanguages: List<String> = listOf(SettingsStore.DEFAULT_OCR_LANGUAGE),
    val espeakReady: Boolean = false,
    val espeakDetail: String = "",
)

/**
 * V1 settings: packs (engine/OCR, download + progress), the shared C2 voice
 * selector (selection + favorites + per-voice preview), match threshold,
 * theme, OCR language selection. All writes go through [AppSettings]
 * (playback hot-path mirror); downloads go through the [PackRegistry]
 * (explicit, resumable, verified — decision #7); staged tess data is copied
 * into the OCR engine's data dir after each language download.
 *
 * C2: [voiceAudition] is the composition-root coordinator — one sample at a
 * time, narration-safe via [PlayerCommands]. Defaulted null so the pure-JVM
 * tests skip audition (and the offline-audio section) exactly like [storage].
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val registry: PackRegistry,
        private val installer: PackInstaller,
        private val settings: AppSettings,
        @Named("app_files_dir") private val filesDir: File,
        // About seams (release 0.1.1): the composition root supplies the build
        // identity and the browser dispatch — this module has neither. The
        // defaults keep the pure-JVM harness constructing this VM directly.
        private val appInfo: AppInfo = AppInfo(versionName = ""),
        private val linkOpener: LinkOpener = LinkOpener { },
        // Default null: pure-JVM tests skip the offline-audio section (Hilt supplies it).
        private val repository: LibraryStore? = null,
        private val storage: OfflineStorage? = null,
        private val voiceAudition: VoiceAudition? = null,
        // Null in the pure-JVM harness: the install path needs a runner (Hilt supplies it).
        private val operations: OperationRunner? = null,
    ) : ViewModel() {
        private val auditionFlow = voiceAudition?.state ?: MutableStateFlow(AuditionUiState())

        // registry.stagedTick is the filesystem-status re-evaluation signal:
        // staging completes AFTER the pack turns Ready, so nothing else re-emits.
        private val packState =
            combine(registry.packs, registry.installFailed, registry.stagedTick) { packs, installFailures, _ ->
                packs to installFailures
            }

        // settings.state is push-based (AppSettings mirrors every write), so the
        // UI reflects a change the moment the store lands — no polling.
        private val core =
            combine(packState, auditionFlow, settings.state) { (packs, installFailures), audition, prefs ->
                SettingsUiState(
                    packs = packs.map { packRow(it, installFailures) },
                    speechPackIds = speechPackIds(prefs.ttsEngine),
                    engineVoice = engineVoice(packs, prefs, audition),
                    ttsEngine = prefs.ttsEngine,
                    translateEngines = translateEngineRows(packs, installFailures, prefs),
                    matchThreshold = prefs.threshold,
                    playbackGain = prefs.playbackGain,
                    ttsThreads = prefs.ttsThreads,
                    themeMode = prefs.theme,
                    ocrLanguages = prefs.ocrLanguages,
                    espeakReady = espeakReady(filesDir),
                    espeakDetail = espeakDetail(filesDir),
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                SettingsUiState(espeakReady = espeakReady(filesDir), espeakDetail = espeakDetail(filesDir)),
            )

        val state: StateFlow<SettingsUiState> = core

        /** About footer (release 0.1.1): the app's `versionName`, injected from
         * the composition root — this module has no `BuildConfig`. */
        val appVersion: String = appInfo.versionName

        /** About links (source, third-party notices): the composition root's
         * browser dispatch; a device with no handler silently does nothing. */
        fun openLink(url: String) = linkOpener.open(url)

        /** One settings row: how much pre-generated audio a book holds (decisions #44). */
        data class OfflineAudioRow(
            val bookId: String,
            val title: String,
            val bytes: Long,
        )

        private val usage = MutableStateFlow<Map<String, Long>>(emptyMap())

        /** Books with offline audio + their bytes; reacts to library and usage changes. */
        val offlineRows: StateFlow<List<OfflineAudioRow>> =
            combine(repository?.books ?: MutableStateFlow(emptyList()), usage) { books, usage ->
                books.mapNotNull { entry ->
                    usage[entry.book.id]?.takeIf { it > 0L }?.let {
                        OfflineAudioRow(entry.book.id, entry.book.title, it)
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        init {
            viewModelScope.launch {
                refreshOfflineUsage()
                autoStageEspeak()
                autoStageTranslate()
            }
        }

        /** A verified-but-unstaged espeak-ng pack (reinstall after download, or a
         * staging-failure retry) self-heals when the settings screen opens —
         * through the same install path as an explicit download. */
        private suspend fun autoStageEspeak() {
            if (EspeakStager.isStaged(filesDir)) return
            val id = readyPackId(ESPEAK_PACK_ID) ?: return
            install(listOf(id))
        }

        /** Re-reads the disk tier (IO) — called at open, on every screen resume
         * (open-bugs "Offline-audio usage row is stale on return to a live
         * Settings screen", decisions #144) and after every delete. Idempotent:
         * one background read, never a poll. */
        fun refreshOfflineUsage() {
            val storage = storage ?: return
            viewModelScope.launch {
                usage.value = withContext(Dispatchers.IO) { storage.usageByBook() }
            }
        }

        /** One-tap reclaim: cancels queued pre-gen work first, then deletes the subtree. */
        fun deleteOffline(bookId: String) {
            val storage = storage ?: return
            viewModelScope.launch {
                withContext(Dispatchers.IO) { storage.deleteBook(bookId) }
                refreshOfflineUsage()
            }
        }

        private fun packRow(
            pack: PackState,
            installFailures: Map<String, String>,
        ): PackRow =
            PackRow(
                packId = pack.pack.id,
                engineId = pack.pack.engineId,
                displayName = pack.pack.displayName,
                sizeBytes = pack.pack.sizeBytes,
                status = pack.status,
                progress = (pack.status as? PackStatus.Downloading)?.let { it.downloadedBytes.toDouble() / it.totalBytes },
                error = installFailures[pack.pack.id] ?: (pack.status as? PackStatus.Failed)?.reason?.shortMessage(),
                staged =
                    when (pack.pack.engineId) {
                        TESS_ENGINE_ID -> TessDataStager.isStaged(filesDir, pack.pack)
                        else ->
                            TranslatePacks.byPackId(pack.pack.id)?.let {
                                TranslatePackStager.isStaged(filesDir, it)
                            } ?: false
                    },
            )

        /** The Translation picker's rows (decisions #182): registry order
         * (shipped first), each joined with its pack's live state and staged
         * flag. An engine with no registry row (a pure-JVM harness without the
         * translate descriptors) still renders — its state is NotDownloaded. */
        private fun translateEngineRows(
            packs: List<PackState>,
            installFailures: Map<String, String>,
            prefs: AppSettings.Snapshot,
        ): List<TranslateEngineRow> =
            TranslatePacks.all.map { engine ->
                val state = packs.firstOrNull { it.pack.id == engine.pack.id }
                TranslateEngineRow(
                    id = engine.id,
                    label = engine.label,
                    summary = engine.summary,
                    packId = engine.pack.id,
                    packName = engine.pack.displayName,
                    sizeLabel = engine.sizeLabel,
                    status = state?.status ?: PackStatus.NotDownloaded,
                    progress =
                        (state?.status as? PackStatus.Downloading)
                            ?.let { it.downloadedBytes.toDouble() / it.totalBytes },
                    error = installFailures[engine.pack.id] ?: (state?.status as? PackStatus.Failed)?.reason?.shortMessage(),
                    staged = TranslatePackStager.isStaged(filesDir, engine),
                    selected = engine.id == prefs.translateEngine,
                )
            }

        fun download(packId: String) {
            // A companion artifact is never listed as its own row: its base
            // row's Download must fetch it too, or the voice is left unusable
            // (Piper model ready, `.onnx.json` missing).
            val companions =
                registry.packs.value
                    .filter { it.pack.companionOf == packId }
                    .map { it.pack.id }
            install(listOf(packId) + companions)
        }

        /** The named voice's required-pack download action (never silence, never an
         * unannounced fallback) — the shared required-pack table resolves the
         * ids under the active engine (D4 #154 addendum). One operation per
         * required pack, so a per-row Stop stays meaningful. */
        fun downloadVoice(voice: String) {
            SetupEnginePacks.requiredIds(settings.state.value.ttsEngine, voice).forEach { install(listOf(it)) }
        }

        /** Stops the running download of [packId] (the notification's Stop action). */
        fun cancelDownload(packId: String) {
            operations?.cancel("pack-download:$packId")
        }

        /** The one download entry point: download + stage as a cancellable,
         * observable operation (K3). */
        private fun install(packIds: List<String>) {
            operations?.installPacks(installer, packIds)
        }

        /** [packId]'s id when it is verified on disk (the self-heal gate). */
        private fun readyPackId(packId: String): String? =
            registry.packs.value
                .firstOrNull { it.pack.id == packId && it.status == PackStatus.Ready }
                ?.pack
                ?.id

        fun selectVoice(voice: String) = viewModelScope.launch { settings.setVoice(voice) }

        /** C1.5: the speech engine radio — kokoro-82m or the degraded system-tts. */
        fun setEngine(value: String) = viewModelScope.launch { settings.setTtsEngine(value) }

        fun toggleFavorite(voice: String) = viewModelScope.launch { settings.toggleFavorite(voice) }

        /** C2: audition one voice without selecting it (one at a time; narration-
         * safe via the coordinator). */
        fun previewVoice(voice: String) = voiceAudition?.preview(voice)

        fun stopPreview() = voiceAudition?.stop()

        fun setThreshold(value: Double) = viewModelScope.launch { settings.setMatchThreshold(value) }

        fun setPlaybackGain(value: Float) = viewModelScope.launch { settings.setPlaybackGain(value) }

        /** #137: the generation-threads slider (fewer = snappier phone). */
        fun setTtsThreads(value: Int) = viewModelScope.launch { settings.setTtsThreads(value) }

        fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

        fun setOcrLanguage(
            lang: String,
            enable: Boolean,
        ) {
            viewModelScope.launch {
                val current = settings.state.value.ocrLanguages
                val next = if (enable) (current + lang).distinct() else current - lang
                settings.setOcrLanguages(next)
            }
        }

        /** A verified-but-unstaged translate pack (download completed but the
         * extract died, or a reinstall) self-heals when settings opens; the
         * 730 MB–1.7 GB extract itself is the installer's (`TranslatePackStager`,
         * decisions #114/#162). EVERY engine is checked, not just the selected
         * one — the other engine can hold a finished download the user is about
         * to switch to. */
        private suspend fun autoStageTranslate() {
            TranslatePacks.all.forEach { engine ->
                if (TranslatePackStager.isStaged(filesDir, engine)) return@forEach
                val id = readyPackId(engine.pack.id) ?: return@forEach
                install(listOf(id))
            }
        }

        /** #182: the read-in-language engine radio. The choice is global and it
         * keys every translation of a book (speech render path `t<id>` + the
         * stored text's translator column), so the next resolve re-translates
         * under the newly selected engine instead of reusing the other one's
         * artifacts. A selection whose pack is not downloaded yet is allowed:
         * the read-in flow then offers that engine's download. */
        fun setTranslateEngine(id: String) = viewModelScope.launch { settings.setTranslateEngine(id) }

        /** Live espeak-ng readiness from the staged bundle (downloads change it). */
        private fun espeakReady(filesDir: File): Boolean = EspeakStager.isStaged(filesDir)

        private fun espeakDetail(filesDir: File): String =
            if (espeakReady(filesDir)) "staged (lib + data)" else "not staged — download the pack above"

        // ------------------------------------------------------------------
        // C4 shared engine+voice picker state
        // ------------------------------------------------------------------

        private fun engineVoice(
            packs: List<PackState>,
            prefs: AppSettings.Snapshot,
            audition: AuditionUiState,
        ): EngineVoiceUiState {
            val engineId = prefs.ttsEngine
            // The ONE shared builder (C2, #102.4 + #166 follow-up) — Setup/
            // Reader route here too; the catalog follows the selected engine
            // (D4 #154 addendum) and a saved voice the engine does not expose
            // degrades to the builder's unavailable row (decisions #144
            // availability shape).
            val voices =
                if (engineId == SettingsStore.PIPER_ENGINE) {
                    PiperVoiceMetadata.all
                } else {
                    KokoroVoiceMetadata.all
                }
            val readyFor: (String) -> Boolean = { SetupEnginePacks.readyFor(engineId, it, packs) }
            return buildEngineVoiceState(
                engineId = engineId,
                engines = engineOptions(engineId, readyFor),
                voices = voices,
                selectedVoice = prefs.voice,
                favorites = prefs.favorites.toSet(),
                readyFor = readyFor,
                bytesFor = { SetupEnginePacks.bytesFor(engineId, it, packs) },
                audition = audition,
            )
        }

        /**
         * K2 (decisions #156): the Speech section's pack rows, derived from
         * the registry's engine descriptors instead of hardcoded id lists —
         * the selected engine's own packs plus the shared espeak-ng bundle
         * (Kokoro's descriptor registers it; every open-weight engine
         * phonemizes through it). An unknown/absent engine id (the degraded
         * system voice, which registers no packs) shows the open-weight
         * upgrade path — Kokoro's rows, the same set the install plan card
         * offers. Adding an engine to the registry adds its rows here with
         * no settings-surface edit. Companion artifacts ([TtsPack.companionOf],
         * e.g. Piper's per-voice `.onnx.json`) are excluded: they are fetched
         * with their base pack, so listing them would show one voice as two
         * rows and offer a download that leaves the voice unusable.
         */
        private fun speechPackIds(engineId: String): Set<String> {
            val engines = registry.engines()
            val engine =
                engines.firstOrNull { it.spec.id == engineId }
                    ?: engines.firstOrNull { it.spec.id == SettingsStore.DEFAULT_TTS_ENGINE }
            return engine
                ?.packs
                .orEmpty()
                .filter { it.companionOf == null }
                .map { it.id }
                .toSet() + ESPEAK_PACK_ID
        }

        private companion object {
            const val ESPEAK_PACK_ID = "espeak-ng"
        }
    }
