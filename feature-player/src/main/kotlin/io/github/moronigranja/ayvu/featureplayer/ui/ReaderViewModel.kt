package io.github.moronigranja.ayvu.featureplayer.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector
import io.github.moronigranja.ayvu.featureplayer.playback.PlaybackService
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.ChapterDisplay
import io.github.moronigranja.ayvu.player.DisplayBlock
import io.github.moronigranja.ayvu.player.DisplayMode
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlaybackUiState
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.TranslationState
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.player.VoicePackDownloader
import io.github.moronigranja.ayvu.player.pregen.TranslationService
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackState
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.installPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
import io.github.moronigranja.ayvu.tts.translate.TranslateLanguages
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.ui.EngineVoiceUiState
import io.github.moronigranja.ayvu.ui.ReadInLanguageUiState
import io.github.moronigranja.ayvu.ui.VoiceRowUi
import io.github.moronigranja.ayvu.ui.buildEngineVoiceState
import io.github.moronigranja.ayvu.ui.engineOptions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reader-side commands to the [PlaybackService]; state is read from the
 * service-published [PlaybackStateHolder] (the service is the single writer).
 *
 * C2: the reader voice sheet reuses the shared [EngineVoicePicker] surface
 * and [buildEngineVoiceState] builder; selecting a voice or engine persists
 * it AND rebuilds the active book under it at the same playhead via
 * [changeVoice] (A5 single-writer — stale synthesis can never publish).
 */
@HiltViewModel
class ReaderViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
        private val audition: VoiceAudition,
        private val selector: EngineSelector,
        private val registry: PackRegistry,
        private val download: VoicePackDownloader,
        private val translationService: TranslationService,
        private val installer: PackInstaller,
        // Null only in the pure-JVM harness; Hilt supplies the runner.
        private val operations: OperationRunner? = null,
    ) : ViewModel(),
        PlayerCommands {
        val state: StateFlow<PlaybackUiState> = PlaybackStateHolder.state

        /** The shared engine+voice picker state for the reader's voice sheet
         * (decisions #166 follow-up): the catalog follows the selected engine
         * (piper rows with the resolved voice's pack readiness under
         * `piper-v1`, the Kokoro catalog otherwise), readiness/bytes come from
         * the required-pack table. A saved voice the active engine does not
         * expose degrades to the builder's unavailable row (decisions #144
         * availability shape). */
        val engineVoice: StateFlow<EngineVoiceUiState> =
            combine(settings.state, audition.state, registry.packs) { prefs, aud, packs ->
                engineVoice(packs, prefs, aud)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                engineVoice(
                    registry.packs.value,
                    settings.state.value,
                    audition.state.value,
                ),
            )

        private fun engineVoice(
            packs: List<PackState>,
            prefs: AppSettings.Snapshot,
            audition: AuditionUiState,
        ): EngineVoiceUiState {
            val engineId = prefs.ttsEngine
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

        /** The book this reader shows — [resume] carries it so a machine-less
         * service (STOP's post-stop fill self-stopped it) can rebuild and
         * resume from the persisted playhead instead of dead-ending the
         * play button. */
        private var openedBookId: String? = null

        // ---- read-in-language (decisions #114) ----

        /** The voice-sheet "Read in" section state: the active book's target,
         * the target languages the active engine can voice, and pack
         * readiness (selection is disabled until the pack is staged). */
        val translateState: StateFlow<ReadInLanguageUiState> =
            combine(settings.state, registry.packs, PlaybackStateHolder.state) {
                prefs,
                packs,
                playback,
                ->
                // The translate row's progress is registry truth (the download
                // runs as a foreground operation, not in this VM's scope).
                val progress =
                    (packs.firstOrNull { it.pack.id == TranslatePacks.pack.id }?.status as? PackStatus.Downloading)
                        ?.let { it.downloadedBytes.toFloat() / it.totalBytes }
                val bookId = playback.bookId
                val display = bookId?.let { prefs.bookDisplays[it] }
                val speech = bookId?.let { prefs.bookTranslate[it] }
                val storedTargetVoice = bookId?.let { selector.translateVoice(it) }
                ReadInLanguageUiState(
                    bookId = bookId,
                    target = speech,
                    languages = selector.availableTranslateLanguages(),
                    // The reader's display surface: the book's display language
                    // + the global display mode.
                    displayTarget = display,
                    displayMode = prefs.displayMode,
                    // The constrained SPEECH row set: with a display language in
                    // force, the spoken language is Off or that SAME translation
                    // (a passage is translated at most once).
                    speechLanguages = display?.let { d -> listOf(d) },
                    // The spoken TRANSLATION's own voice (decisions #166
                    // follow-up): the resolved (stored-or-automatic) target
                    // voice, the target language's catalog voices with per-voice
                    // pack readiness, and the in-force voice's download state.
                    translateVoice = storedTargetVoice,
                    translateVoices =
                        speech?.let { lang ->
                            val catalog = selector.voiceCatalog()
                            TranslateLanguages.voicesFor(catalog, lang).map { name ->
                                val meta = catalog.first { it.name == name }
                                VoiceRowUi(
                                    name = meta.name,
                                    language = meta.language,
                                    gender = meta.gender,
                                    displayName = meta.displayName,
                                    grade = meta.grade,
                                    ready = SetupEnginePacks.readyFor(prefs.ttsEngine, meta.name, packs),
                                    bytes = SetupEnginePacks.bytesFor(prefs.ttsEngine, meta.name, packs),
                                    selected = meta.name == storedTargetVoice,
                                )
                            }
                        } ?: emptyList(),
                    translateVoiceReady =
                        storedTargetVoice?.let { SetupEnginePacks.readyFor(prefs.ttsEngine, it, packs) } ?: true,
                    packDownloaded =
                        packs.any { it.pack.id == TranslatePacks.pack.id && it.status == PackStatus.Ready } &&
                            TranslatePackStager.isStaged(context.filesDir),
                    downloadProgress = progress,
                    degradeReason = selector.translateDegradeReason(bookId),
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ReadInLanguageUiState(),
            )

        // ---- read-in-language display (the block projection) ----

        /** The translation map under display: chapter-scoped so a stale map
         * can never project against a different chapter (passage indices
         * overlap across chapters). */
        private val chapterTranslations =
            kotlinx.coroutines.flow.MutableStateFlow(
                ChapterTranslations(chapterIndex = -1, entries = emptyMap()),
            )

        private data class ChapterTranslations(
            val chapterIndex: Int,
            val entries: Map<Int, TranslationState>,
        )

        /** The seed identity — distinguishes book/chapter/passage-count AND
         * the display target for the current book, so setting/clearing a
         * display language mid-chapter re-seeds (distinctUntilChanged). */
        private data class SeedKey(
            val bookId: String?,
            val chapter: Int,
            val passageCount: Int,
            val display: String?,
        )

        /** The projected chapter the reader renders: blocks composed from the
         * published `chapterPassages` + the reader-owned translation map (the
         * published list itself is never interleaved — the bookmark/resume
         * invariant holds by construction), plus the per-book display target,
         * the global display mode and the in-force SPEECH target (the
         * highlight rule). */
        val chapterDisplay: StateFlow<ChapterDisplayState> =
            combine(
                PlaybackStateHolder.state,
                chapterTranslations,
                settings.state,
            ) { playback, ct, prefs ->
                val bookId = playback.bookId
                // Guard against a stale map (the seed for the NEW chapter has
                // not landed yet): only project the map that belongs to the
                // chapter being displayed.
                val usable =
                    if (bookId != null && ct.chapterIndex == playback.chapterIndex) ct.entries else emptyMap()
                // Pending that can never decode renders Unavailable (the
                // translator pack removed / failed-open) — the inline note,
                // never an eternal dots placeholder.
                val normalized =
                    if (translationService.translatePossible) {
                        usable
                    } else {
                        usable.mapValues { (_, state) ->
                            if (state is TranslationState.Pending) TranslationState.Unavailable else state
                        }
                    }
                val displayTarget = bookId?.let { prefs.bookDisplays[it] }
                ChapterDisplayState(
                    blocks = ChapterDisplay.project(playback.chapterPassages, normalized, prefs.displayMode),
                    translations = normalized,
                    displayTarget = displayTarget,
                    speechTarget = bookId?.let { selector.translateTarget(it) },
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ChapterDisplayState(),
            )

        init {
            // Seed on every book/chapter change AND every display-target
            // change for the current book (setting a display language
            // mid-chapter must re-project with Pending blocks + stored rows —
            // the eager path of the progressive landing). Stored rows become
            // Ready, absent rows Pending; never a flash of the original-only
            // layout: the projection is emitted only after the seed lands.
            viewModelScope.launch {
                combine(
                    PlaybackStateHolder.state.map { Triple(it.bookId, it.chapterIndex, it.chapterPassages.size) },
                    settings.state.map { it.bookDisplays },
                ) { nav, displays ->
                    SeedKey(nav.first, nav.second, nav.third, displays[nav.first])
                }.distinctUntilChanged()
                    .collect { key ->
                        seedTranslations(key.bookId, key.chapter, key.passageCount, key.display)
                    }
            }
            // Merge each passage's text as it becomes available — the PROGRESSIVE
            // landing that re-paginates through the reader's keep-place effect.
            viewModelScope.launch {
                translationService.ready.collect { ready ->
                    val current = PlaybackStateHolder.state.value
                    // A landed decode carries the target it STARTED for, and it
                    // can land after that target was cleared or switched:
                    // clearing the display language starts no new prefetch, so
                    // nothing supersedes the in-flight one (the service
                    // supersedes on a NEW target only). Merging such a late text
                    // rendered a translated block while display was OFF, and the
                    // entry survived until the next re-seed — the whole chapter
                    // read as a rewrite of the original (device report
                    // 2026-09-17). Only the book's CURRENT display target's own
                    // text may enter the display map.
                    val display = settings.bookDisplay(ready.bookId)
                    val belongs =
                        display != null &&
                            ready.target == TranslationTarget(display) &&
                            ready.bookId == current.bookId &&
                            ready.chapter == current.chapterIndex
                    if (!belongs) return@collect
                    chapterTranslations.update { ct ->
                        if (ct.chapterIndex == ready.chapter) {
                            ct.copy(entries = ct.entries + (ready.passage to TranslationState.Ready(ready.text)))
                        } else {
                            ct
                        }
                    }
                }
            }
        }

        private suspend fun seedTranslations(
            bookId: String?,
            chapter: Int,
            passageCount: Int,
            display: String?,
        ) {
            if (bookId == null || passageCount == 0 || display == null) {
                chapterTranslations.value = ChapterTranslations(chapter, emptyMap())
                return
            }
            val target = TranslationTarget(display)
            // Absent rows are Pending while a decode is possible; with the
            // translator pack removed / failed-open they are Unavailable from
            // the start (the original renders plus the inline note — never an
            // eternal dots placeholder).
            val unavailable = !translationService.translatePossible
            val entries = LinkedHashMap<Int, TranslationState>(passageCount)
            for (p in 0 until passageCount) {
                entries[p] =
                    translationService
                        .cached(bookId, chapter, p, target)
                        ?.let { TranslationState.Ready(it) }
                        ?: if (unavailable) TranslationState.Unavailable else TranslationState.Pending
            }
            chapterTranslations.value = ChapterTranslations(chapter, entries)
        }

        /** Background display-priority fill of the current + next page's
         * passages — the reader calls it on every page entry (the service
         * dedupes by passage). No-op when no display language is set. */
        fun prefetchChapter(
            bookId: String,
            chapter: Int,
            passages: List<Int>,
        ) {
            val display = settings.bookDisplay(bookId) ?: return
            translationService.prefetch(bookId, chapter, passages.distinct(), TranslationTarget(display))
            // The pack can vanish mid-session (removed/failed-open): the next
            // page entry is where Unavailable is (re)derived — Pending rows
            // that can never decode flip to the inline note; the rows retry on
            // a later entry when the translator comes back.
            if (!translationService.translatePossible) {
                chapterTranslations.update { ct ->
                    if (ct.chapterIndex == chapter) {
                        ct.copy(
                            entries =
                                ct.entries.mapValues { (_, state) ->
                                    if (state is TranslationState.Pending) TranslationState.Unavailable else state
                                },
                        )
                    } else {
                        ct
                    }
                }
            }
        }

        /** Writes or clears the book's display language. A display change is
         * a TEXT re-projection, not a session rebuild — the next synthesize
         * re-resolves the speech target through the constraint (a mismatched
         * speech was already degrading to original audio), so NO
         * [PlaybackService.ACTION_CHANGE_VOICE] here. */
        fun setDisplayTarget(target: String?) {
            // The published state lags a fresh open (same fallback as the
            // speech picker — S22 2026-09-14).
            val bookId = PlaybackStateHolder.state.value.bookId ?: openedBookId ?: return
            val current = settings.bookDisplay(bookId)
            if (target != current) {
                viewModelScope.launch { settings.setBookDisplay(bookId, target) }
            }
        }

        /** The reader's display mode (global reading style). */
        fun setDisplayMode(mode: DisplayMode) {
            viewModelScope.launch { settings.setDisplayMode(mode) }
        }

        /**
         * Persists the book's translate target (null = Off) and rebuilds the
         * active book at the same playhead through the [changeVoice] path —
         * same single-writer rule as voice selection: stale audio can never
         * publish. The service re-reads the setting on the rebuild, so the
         * queue + cache keys pick up the `x<lang>` dimension.
         */
        fun setTranslateTarget(target: String?) {
            // The published state lags a fresh open (the service publishes
            // after the queue builds) — fall back to the VM's opened book
            // instead of silently dropping the selection (S22 2026-09-14:
            // the user's pick vanished with zero feedback).
            val bookId = PlaybackStateHolder.state.value.bookId ?: openedBookId ?: return
            val current = settings.bookTranslate(bookId)
            if (target != current) {
                viewModelScope.launch { settings.setBookTranslate(bookId, target) }
                changeVoice(settings.state.value.voice)
            }
        }

        /** The spoken TRANSLATION's own voice (global; null = automatic):
         * persists it AND rebuilds the active book at the same playhead —
         * the cache key changes with the target voice, so stale audio can
         * never publish under the old key. */
        fun setTranslateVoice(voice: String?) {
            if (voice == settings.translateVoice()) return
            viewModelScope.launch { settings.setTranslateVoice(voice) }
            changeVoice(settings.state.value.voice)
        }

        /** The translation pack row's download (per-book, never a global
         * gate): explicit, resumable, verified (decision #7) and it stages the
         * bundle after Ready — one cancellable foreground operation. */
        fun downloadTranslatePack() {
            operations?.installPacks(installer, listOf(TranslatePacks.pack.id))
        }

        /** C2: select a voice AND rebuild the active book under it at the same
         * playhead (persist via [settings], supersede stale synthesis via
         * [changeVoice]). */
        fun selectVoice(voice: String) {
            val current = settings.state.value.voice
            if (voice != current) {
                viewModelScope.launch { settings.setVoice(voice) }
                changeVoice(voice)
            }
        }

        /** The star toggles favorite state only — selection is the row tap. */
        fun toggleFavorite(voice: String) {
            viewModelScope.launch { settings.toggleFavorite(voice) }
        }

        /** The speech engine (global): persists it AND rebuilds the active
         * book at the playhead (the catalog switch re-resolves the voice). */
        fun setEngine(engineId: String) {
            if (engineId == settings.state.value.ttsEngine) return
            viewModelScope.launch { settings.setTtsEngine(engineId) }
            changeVoice(settings.state.value.voice)
        }

        fun previewVoice(voice: String) = audition.preview(voice)

        fun stopPreview() = audition.stop()

        /** The named voice's pack download action while packs are missing —
         * routed to the composition root (A6). */
        fun downloadVoicePacks(voice: String) = download.requestDownload(voice)

        /** Opens a book in the reader WITHOUT starting playback (decisions #52). */
        fun open(bookId: String) {
            openedBookId = bookId
            command(PlaybackService.ACTION_OPEN, bookId)
        }

        override fun play(bookId: String) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY, bookId)
        }

        override fun playAt(
            bookId: String,
            chapterIndex: Int,
            passageIndex: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapterIndex, passageIndex)
        }

        /** C2: the persisted voice was already switched by the selector — this
         * rebuilds the active book under it, preserving the playhead (A5). */
        override fun changeVoice(voice: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_CHANGE_VOICE)
                        .putExtra(PlaybackService.EXTRA_VOICE, voice),
                )
            }
        }

        fun playPosition(
            bookId: String,
            chapter: Int,
            passage: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapter, passage)
        }

        /** Positions the reader at (chapter, passage) WITHOUT starting
         * playback — the chapter selector and bookmark jumps use this instead
         * of [playPosition] (open ≠ auto-play, decisions #52). */
        fun openPosition(
            bookId: String,
            chapter: Int,
            passage: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_OPEN_POSITION, bookId, chapter, passage)
        }

        fun openChapter(
            bookId: String,
            direction: Int,
        ) = command(PlaybackService.ACTION_OPEN_CHAPTER, bookId, direction = direction)

        fun skipForward() = command(PlaybackService.ACTION_SKIP_FORWARD)

        fun skipBackward() = command(PlaybackService.ACTION_SKIP_BACKWARD)

        fun undo() = command(PlaybackService.ACTION_UNDO)

        override fun stop() = command(PlaybackService.ACTION_STOP)

        fun cycleSleep() = command(PlaybackService.ACTION_SLEEP)

        fun bookmark() = command(PlaybackService.ACTION_BOOKMARK)

        override fun resume() = command(PlaybackService.ACTION_RESUME, openedBookId)

        override fun pause() = command(PlaybackService.ACTION_PAUSE)

        override fun seekForward() = command(PlaybackService.ACTION_SEEK_FORWARD)

        override fun seekBackward() = command(PlaybackService.ACTION_SEEK_BACKWARD)

        private fun command(
            action: String,
            bookId: String? = null,
            chapter: Int = 0,
            passage: Int = 0,
            direction: Int = 0,
        ) {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            if (bookId != null) intent.putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
            if (action == PlaybackService.ACTION_PLAY_POSITION || action == PlaybackService.ACTION_OPEN_POSITION) {
                intent.putExtra(PlaybackService.EXTRA_CHAPTER, chapter)
                intent.putExtra(PlaybackService.EXTRA_PASSAGE, passage)
            }
            if (action == PlaybackService.ACTION_OPEN_CHAPTER) {
                intent.putExtra(PlaybackService.EXTRA_DIRECTION, direction)
            }
            runCatching { context.startForegroundService(intent) }
        }
    }

/** The projected chapter the reader renders ([ReaderViewModel.chapterDisplay]):
 * the [DisplayBlock]s plus the highlight context — the display language and
 * the in-force speech target (through the constraint), which decide which
 * block the read-along highlight anchors on. */
data class ChapterDisplayState(
    val blocks: List<DisplayBlock> = emptyList(),
    val translations: Map<Int, TranslationState> = emptyMap(),
    /** The in-force display language (null = original-only layout). */
    val displayTarget: String? = null,
    /** The in-force SPEECH target through the constraint (null = original
     * audio). When it equals the display language, the utterance is the
     * displayed translation — the highlight moves to the translation block. */
    val speechTarget: String? = null,
)
