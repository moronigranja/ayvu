package io.github.moronigranja.ayvu.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.ocr.TessDataStager
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.persistence.ThemeMode
import io.github.moronigranja.ayvu.player.AuditionStage
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.player.OfflineStorage
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.player.formatBytes
import io.github.moronigranja.ayvu.tts.DownloadFailureReason
import io.github.moronigranja.ayvu.tts.DownloadOutcome
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackState
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.VoiceCatalog
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMeta
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
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

internal const val TESS_ENGINE_ID = "tess-two"

data class PackRow(
    val packId: String,
    val engineId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: PackStatus,
    val progress: Double? = null, // 0..1 while downloading
    val error: String? = null,
    val staged: Boolean = false, // tessdata copied into the tess-two data dir
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
 * into the tess-two data dir after each language download.
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
        private val cache: PackCache,
        private val settings: AppSettings,
        private val voiceCatalog: VoiceCatalog,
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
    ) : ViewModel() {
        private val progress = MutableStateFlow<Map<String, Double>>(emptyMap())
        private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        private val auditionFlow = voiceAudition?.state ?: MutableStateFlow(AuditionUiState())

        /** Bumped after each espeak staging so the filesystem-derived status
         * re-evaluates (staging completes AFTER the registry's ready emission). */
        private val espeakStageTick = MutableStateFlow(0)

        private val packState =
            combine(registry.packs, progress, errors) { packs, prog, err ->
                Triple(packs, prog, err)
            }

        // settings.state is push-based (AppSettings mirrors every write), so the
        // UI reflects a change the moment the store lands — no polling.
        private val core =
            combine(packState, auditionFlow, settings.state, espeakStageTick) { (packs, prog, err), audition, prefs, _ ->
                SettingsUiState(
                    packs = packs.map { packRow(it, prog[it.pack.id], err[it.pack.id]) },
                    speechPackIds = speechPackIds(prefs.ttsEngine),
                    engineVoice = engineVoice(packs, prefs, audition),
                    ttsEngine = prefs.ttsEngine,
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
         * staging-failure retry) self-heals when the settings screen opens. */
        private suspend fun autoStageEspeak() {
            if (EspeakStager.isStaged(filesDir)) return
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == ESPEAK_PACK_ID }
                    ?.pack ?: return
            if (cache.isVerified(pack)) stageEspeak(pack.id)
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
            prog: Double?,
            err: String?,
        ): PackRow =
            PackRow(
                packId = pack.pack.id,
                engineId = pack.pack.engineId,
                displayName = pack.pack.displayName,
                sizeBytes = pack.pack.sizeBytes,
                status = pack.status,
                progress = prog,
                error = err,
                staged =
                    when (pack.pack.engineId) {
                        TESS_ENGINE_ID -> TessDataStager.isStaged(filesDir, pack.pack)
                        TranslatePacks.PACK_ENGINE_ID -> TranslatePackStager.isStaged(filesDir)
                        else -> false
                    },
            )

        fun download(packId: String) {
            val packs = registry.packs.value
            val isTess = packs.firstOrNull { it.pack.id == packId }?.pack?.engineId == TESS_ENGINE_ID
            downloadInternal(packId, isTess)
            // A companion artifact is never listed as its own row: its base
            // row's Download must fetch it too, or the voice is left unusable
            // (Piper model ready, `.onnx.json` missing).
            packs
                .filter { it.pack.companionOf == packId }
                .forEach { downloadInternal(it.pack.id, isTess = false) }
        }

        /** The named voice's required-pack download action (never silence, never an
         * unannounced fallback) — the shared required-pack table resolves the
         * ids under the active engine (D4 #154 addendum). */
        fun downloadVoice(voice: String) {
            SetupEnginePacks.requiredIds(settings.state.value.ttsEngine, voice).forEach { download(it) }
        }

        private fun downloadInternal(
            packId: String,
            isTess: Boolean,
        ) {
            viewModelScope.launch {
                errors.update(packId, null)
                registry
                    .download(packId) { done, total ->
                        progress.value = progress.value + (packId to done.toDouble() / total)
                    }.let { outcome ->
                        progress.value = progress.value - packId
                        when (outcome) {
                            is DownloadOutcome.Ready, is DownloadOutcome.AlreadyCached -> {
                                if (isTess) stageTess(packId)
                                if (packId == ESPEAK_PACK_ID) stageEspeak(packId)
                                if (packId == TranslatePacks.pack.id) stageTranslate(packId)
                                if (packId == KokoroPacks.voices.id) voiceCatalog.invalidate()
                            }
                            is DownloadOutcome.Failed -> errors.update(packId, shortReason(outcome.reason))
                        }
                    }
            }
        }

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

        private suspend fun stageTess(packId: String) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: return
            runCatching { TessDataStager.stage(filesDir, cache, pack) }
                .onFailure { errors.update(packId, "staging failed: ${it.message}") }
        }

        private suspend fun stageEspeak(packId: String) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: return
            runCatching { EspeakStager.stage(filesDir, cache, pack) }
                .onSuccess { staged -> if (staged) espeakStageTick.value += 1 }
                .onFailure { errors.update(packId, "staging failed: ${it.message}") }
        }

        /** Read-in-language (decisions #114/#162): extracts the verified
         * ~730 MB zip under `files/translate-lfm/` so the runtime can open the
         * GGUF ([TranslatePackStager]): the pack artifact in the cache is the
         * zip; the staged bundle is what the translator loads. */
        private suspend fun stageTranslate(packId: String) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: return
            runCatching { TranslatePackStager.stage(filesDir, cache, pack) }
                .onFailure { errors.update(packId, "staging failed: ${it.message}") }
        }

        /** A verified-but-unstaged translate pack (download completed but the
         * extract died, or a reinstall) self-heals when settings opens. */
        private suspend fun autoStageTranslate() {
            if (TranslatePackStager.isStaged(filesDir)) return
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == TranslatePacks.pack.id }
                    ?.pack ?: return
            if (cache.isVerified(pack)) stageTranslate(pack.id)
        }

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

        private fun shortReason(reason: DownloadFailureReason): String =
            when (reason) {
                is DownloadFailureReason.HttpStatus -> "HTTP ${reason.status}"
                is DownloadFailureReason.IoError -> reason.message ?: "network error"
                is DownloadFailureReason.CorruptContent -> "checksum mismatch"
                is DownloadFailureReason.Incomplete -> "incomplete download"
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
