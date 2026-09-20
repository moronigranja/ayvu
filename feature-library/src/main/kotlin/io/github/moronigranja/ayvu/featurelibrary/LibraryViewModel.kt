package io.github.moronigranja.ayvu.featurelibrary

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.featurelibrary.CoverStore
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.BookFileStore
import io.github.moronigranja.ayvu.persistence.ChapterCount
import io.github.moronigranja.ayvu.persistence.PassageDao
import io.github.moronigranja.ayvu.persistence.ProgressDao
import io.github.moronigranja.ayvu.persistence.ProgressEntity
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.ActivityStore
import io.github.moronigranja.ayvu.player.DailyTotals
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.player.LocalDays
import io.github.moronigranja.ayvu.player.OfflineStorage
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlaybackUiState
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PregenJobState
import io.github.moronigranja.ayvu.player.PregenScheduler
import io.github.moronigranja.ayvu.player.Streak
import io.github.moronigranja.ayvu.player.TodayStats
import io.github.moronigranja.ayvu.player.WeekSummary
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.installPacks
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperPacks
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.translate.TranslateLanguages
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.ui.ReadInLanguageUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The library surface's ViewModel: rows, search, stats, offline audio, and the
 * entry points into the shared import operation ([ImportOperations] over the
 * [ImportStateHolder]) — file, folder and external-intent batches all land in
 * that one operation, which owns progress ([ImportUiState.Importing]) and the
 * batch summary ([ImportUiState.Done]) for every outcome, including all-failed
 * batches.
 *
 * [ioDispatcher] is qualifier-injected so unit tests can hand a virtual
 * dispatcher to the VM's own scans/reads (production gets
 * [kotlinx.coroutines.Dispatchers.IO] from [ImportModule]).
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: LibraryStore,
        // Default null: pure-JVM unit tests skip read-in-language state
        // (Hilt supplies both).
        private val settings: AppSettings? = null,
        private val registry: PackRegistry? = null,
        private val installer: PackInstaller,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        // Default null: tests pass their own lock (Hilt supplies it).
        private val indexLock: IndexLock? = null,
        // Default null: pure-JVM unit tests skip pre-generation (Hilt always supplies it).
        private val pregenScheduler: PregenScheduler? = null,
        private val storage: OfflineStorage? = null,
        @ApplicationContext private val context: Context? = null,
        // Default null: pure-JVM unit tests skip the Room progress surface (Hilt provides it).
        private val passageDao: PassageDao? = null,
        private val progressDao: ProgressDao? = null,
        // Default null: unit tests drop the index; Hilt provides the shared one.
        private val index: TextIndex? = null,
        // Default null: pure-JVM unit tests skip the book-bytes sidecar (Hilt provides it).
        private val bookFileStore: BookFileStore? = null,
        // Default null: pure-JVM unit tests skip the activity stats store (Hilt provides it).
        private val activityStore: ActivityStore? = null,
        // A6: the app binds the intent-dispatching sender; tests pass a fake.
        private val commands: PlayerCommands,
        // Null in the pure-JVM harness; Hilt supplies both.
        private val operations: OperationRunner? = null,
        private val imports: ImportOperations? = null,
        private val importStateHolder: ImportStateHolder? = null,
    ) : ViewModel() {
        /** Books the user has covers for (extracted at import; sidecar files). */
        fun cover(bookId: String): ByteArray? = context?.let { CoverStore(File(it.filesDir, "covers")).load(bookId) }

        /** Dismisses the finished-batch summary; the snackbar/dialog must not re-show on revisit. */
        fun consumeImportResult() {
            importStateHolder?.set(ImportUiState.Idle)
        }

        /** The service-published player state — docks the shared player card. */
        val playerState: StateFlow<PlaybackUiState> = PlaybackStateHolder.state

        /** The app-bound command surface, exposed for the player card (A6). */
        val playerCommands: PlayerCommands = commands

        /** Quick play from a library row: resumes the book's audio (decisions #52). */
        fun playBook(bookId: String) = commands.play(bookId)

        // Player-card command surface (decisions #53): delegated to the
        // app-bound [PlayerCommands] implementation (A6).
        fun resume() = commands.resume()

        fun pause() = commands.pause()

        fun stop() = commands.stop()

        fun seekForward() = commands.seekForward()

        fun seekBackward() = commands.seekBackward()

        /** Starts a manual pre-generation run for one book (#42); null budget = whole book. */
        fun pregenerate(
            bookId: String,
            budgetMinutes: Long? = null,
        ) = pregenScheduler?.pregenerate(bookId, budgetMinutes)

        /** Stops the book's running manual pre-generation at the next passage
         * boundary — the row/card "Stop generating" control (#136); already-
         * cached audio stays on disk. */
        fun cancelPregen(bookId: String) = pregenScheduler?.cancel(bookId)

        /** The book's manual pre-generation job, for row progress (KEEP-deduplicated). */
        fun pregenWork(bookId: String): Flow<PregenJobState> = pregenScheduler?.observe(bookId) ?: flowOf(PregenJobState())

        // ---- read-in-language (decisions #114) ----
        //
        // The library row/card menus share the reader's per-book target
        // mechanism: persist `book.translate.<bookId>` and rebuild the active
        // book through the [PlayerCommands.changeVoice] path (the service
        // re-reads the setting; same single-writer rule as voice changes).

        /** The "Read in language" dialog state for [bookId]. */
        fun translateState(bookId: String): StateFlow<ReadInLanguageUiState> {
            val prefs = settings ?: return MutableStateFlow(ReadInLanguageUiState(bookId = bookId))
            val packs = registry ?: return MutableStateFlow(ReadInLanguageUiState(bookId = bookId))
            return combine(prefs.state, packs.packs) { prefsSnapshot, packStates ->
                val target = prefsSnapshot.bookTranslate[bookId]
                // Readiness follows the ACTIVE translate engine (decisions
                // #182): the user must be offered the download of the engine
                // that will actually translate, and `ready` must describe it.
                val engine = TranslatePacks.byId(prefsSnapshot.translateEngine)
                val staged = context?.let { TranslatePackStager.isStaged(it.filesDir, engine) } ?: false
                ReadInLanguageUiState(
                    bookId = bookId,
                    target = target,
                    languages = translateLanguages(prefsSnapshot),
                    packDownloaded =
                        packStates.any { it.pack.id == engine.pack.id && it.status == PackStatus.Ready } && staged,
                    // Registry truth: the download runs as a foreground
                    // operation, not in this VM's scope.
                    downloadProgress =
                        (packStates.firstOrNull { it.pack.id == engine.pack.id }?.status as? PackStatus.Downloading)
                            ?.let { it.downloadedBytes.toFloat() / it.totalBytes },
                    degradeReason =
                        io.github.moronigranja.ayvu.tts.translate.TranslateAvailability.degradeReason(
                            target = target,
                            catalog =
                                if (prefsSnapshot.ttsEngine == SettingsStore.PIPER_ENGINE) {
                                    PiperVoiceMetadata.all
                                } else {
                                    KokoroVoiceMetadata.all
                                },
                            voiceServable = { voice ->
                                prefsSnapshot.ttsEngine != SettingsStore.PIPER_ENGINE ||
                                    PiperPacks.forVoice(voice).all { pack ->
                                        packStates.any { it.pack.id == pack.id && it.status == PackStatus.Ready }
                                    }
                            },
                            translatorReady =
                                packStates.any { it.pack.id == engine.pack.id && it.status == PackStatus.Ready } &&
                                    staged,
                            translatorFailure = null,
                        ),
                    // The download row's copy names the engine the user would
                    // actually get (never a fixed LFM2.5-1.2B ~730 MB line).
                    engineLabel = engine.pack.displayName,
                    packSizeLabel = engine.sizeLabel,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadInLanguageUiState(bookId = bookId))
        }

        /** Persists the per-book target (null = Off) and rebuilds the active
         * book at the same playhead via the voice-change path. */
        fun setTranslateTarget(
            bookId: String,
            target: String?,
        ) {
            val prefs = settings ?: return
            if (target == prefs.bookTranslate(bookId)) return
            viewModelScope.launch { prefs.setBookTranslate(bookId, target) }
            commands.changeVoice(prefs.state.value.voice)
        }

        /** The ACTIVE translate engine's pack download (content-row action):
         * one cancellable foreground operation, staged after Ready. The
         * selected engine's own pack (decisions #182). */
        fun downloadTranslatePack(bookId: String) {
            if (registry == null) return
            val engine = TranslatePacks.byId(settings?.state?.value?.translateEngine)
            operations?.installPacks(installer, listOf(engine.pack.id))
        }

        /** The active engine's target languages (mirrors the selector's
         * availability shape: the degraded system voice has none). */
        private fun translateLanguages(prefs: AppSettings.Snapshot): List<String> =
            when (prefs.ttsEngine) {
                SettingsStore.SYSTEM_TTS_ENGINE -> emptyList()
                SettingsStore.PIPER_ENGINE -> TranslateLanguages.codes(PiperVoiceMetadata.all)
                else -> TranslateLanguages.codes(KokoroVoiceMetadata.all)
            }

        /** All library rows, in import order — the F2 search filter source. */
        val library: StateFlow<List<LibraryEntry>> = repository.books

        /** Live title/author query — filters [library] locally, case-insensitively.
         * Empty query shows everything (F2). */
        private val _query = MutableStateFlow("")

        /** The live query text (backing-property pairing for [_query]). */
        val query: StateFlow<String> = _query.asStateFlow()

        /** Set by the search field; blank resets the list. */
        fun setQuery(query: String) {
            _query.value = query
        }

        /** Books whose title or any author contains the trimmed query (ignoring
         * case); the continue-list/recent section is NOT filtered (F2 keeps
         * recent always visible so resume stays one tap away). */
        val searchResults: StateFlow<List<LibraryEntry>> =
            combine(repository.books, _query) { books, query ->
                val q = query.trim()
                if (q.isEmpty()) {
                    books
                } else {
                    books.filter { entry ->
                        entry.book.title.contains(q, ignoreCase = true) ||
                            entry.book.authors.any { it.contains(q, ignoreCase = true) }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Recently-active books (resume rows, most recent first) — the library's
         * "Continue listening" section (decisions #50 pass). */
        val recent: StateFlow<List<LibraryEntry>> =
            if (progressDao == null) {
                MutableStateFlow(emptyList())
            } else {
                combine(progressDao!!.observeAll(), repository.books) { rows, books ->
                    val byId = books.associateBy { it.book.id }
                    rows
                        .sortedByDescending { it.updatedAtEpochMillis }
                        .mapNotNull { byId[it.bookId] }
                        .take(MAX_RECENT)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            }

        /** bookId → read/listened fraction (0..1): completed passages over the
         * cached book's total, from the resume rows (passage-granular — the
         * player's resume unit). Null DAOs (unit tests) → empty. */
        val readProgress: StateFlow<Map<String, Float>> =
            if (passageDao == null || progressDao == null) {
                MutableStateFlow(emptyMap())
            } else {
                combine(passageDao!!.chapterCounts(), progressDao!!.observeAll()) { counts, rows ->
                    rows.associate { row -> row.bookId to progressFraction(row, counts) }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
            }

        private fun progressFraction(
            row: ProgressEntity,
            counts: List<ChapterCount>,
        ): Float {
            val byBook = counts.filter { it.bookId == row.bookId }
            val total = byBook.sumOf { it.passageCount }
            if (total == 0) return 0f
            val before = byBook.filter { it.chapterIndex < row.chapterIndex }.sumOf { it.passageCount } + row.passageIndex
            return ((before + 1).coerceAtMost(total).toFloat() / total).coerceIn(0f, 1f)
        }

        // ------------------------------------------------------------------
        // TODAY stats (Phase H, decisions #109, post-v1-plan Slice A)

        /** The local day the stats window ends at; [refreshStats] re-pins it
         * on resume so the card rolls over at midnight (Room flows already
         * re-emit on every capture write). */
        private val statsDay = MutableStateFlow(LocalDays.key(System.currentTimeMillis()))

        /** Re-pins the TODAY window; the library screen calls this on resume. */
        fun refreshStats() {
            statsDay.value = LocalDays.key(System.currentTimeMillis())
        }

        /** Today's read/listen minutes + total, the 7-day mini bar and the
         * streak — pure aggregation over the activity rows. Null DAO (unit
         * tests) → [TodayStats.EMPTY]. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val todayStats: StateFlow<TodayStats> =
            if (activityStore == null) {
                MutableStateFlow(TodayStats.EMPTY)
            } else {
                val store = activityStore
                combine(
                    statsDay.flatMapLatest { key -> store.observeSince(WeekSummary.startKey(key)) },
                    store.observeActiveDays(),
                    statsDay,
                ) { rows, activeDays, todayKey ->
                    TodayStats(
                        dayKey = todayKey,
                        today = DailyTotals.summarize(todayKey, rows),
                        week = WeekSummary.series(rows, todayKey),
                        streakDays = Streak.count(activeDays.toSet(), todayKey),
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayStats.EMPTY)
            }

        /** bookId → offline-audio facts for the row (#44): usage + full-book estimate. */
        data class OfflineBook(
            val usageBytes: Long,
            val estimateBytes: Long,
        )

        private val _offline = MutableStateFlow<Map<String, OfflineBook>>(emptyMap())
        val offline: StateFlow<Map<String, OfflineBook>> = _offline.asStateFlow()

        init {
            refreshOffline()
        }

        /** Recomputes usage + estimates from the disk tier (IO). */
        fun refreshOffline() {
            val storage = storage ?: return
            viewModelScope.launch {
                val usage = withContext(ioDispatcher) { storage.usageByBook() }
                val estimates = withContext(ioDispatcher) { storage.estimateAll() }
                _offline.value =
                    estimates.mapValues { (id, est) ->
                        OfflineBook(usageBytes = usage[id] ?: 0L, estimateBytes = est.totalBytes)
                    }
            }
        }

        /** Reclaims one book's offline audio: cancel queued work, delete the subtree. */
        fun deleteOffline(bookId: String) {
            val storage = storage ?: return
            viewModelScope.launch {
                withContext(ioDispatcher) { storage.deleteBook(bookId) }
                refreshOffline()
            }
        }

        /** Removes a book from the library entirely: index, offline audio,
         * covers and all rows (decisions #50 pass). */
        fun removeBook(bookId: String) {
            viewModelScope.launch {
                withContext(ioDispatcher) {
                    // Stop live playback first: the service holds its own book
                    // reference and would otherwise keep reading the removed book.
                    commands.stop()
                    // CR-3/A3: durable delete FIRST — a failed durable removal must
                    // never leave a surviving Room book missing from the index.
                    val deleted = runCatching { repository.delete(bookId) }
                    if (deleted.isFailure) return@withContext // durable truth unchanged — derived state untouched
                    indexLock?.withExclusiveIndex { index?.remove(bookId) }
                    storage?.deleteBook(bookId) // cancels queued pre-gen first
                    context?.let { CoverStore(File(it.filesDir, "covers")).delete(bookId) }
                    bookFileStore?.deleteForBook(bookId) // E1: no `files/books/` orphans
                }
                refreshOffline()
            }
        }

        /** The shared import state (K3): the singleton holder, so the library
         * overlay and the setup wizard render ONE import; [cancelImport] stops
         * it at the next file boundary. */
        val importState: StateFlow<ImportUiState> =
            importStateHolder?.state ?: MutableStateFlow(ImportUiState.Idle)

        /** F4: non-import guidance shown by the external-file gateway (unsupported
         * format / kfx / DRM) — set once per received intent, never a silent no-op. */
        private val _intakeGuidance = MutableStateFlow<Pair<String, String>?>(null)
        val intakeGuidance: StateFlow<Pair<String, String>?> = _intakeGuidance.asStateFlow()

        /** F4: surfaces typed guidance (friendly name, message) on the overlay. */
        fun showGuidance(
            displayName: String,
            message: String,
        ) {
            _intakeGuidance.value = displayName to message
        }

        /**
         * F4: one file delivered by an external intent (file-manager VIEW or a
         * forwarded book-file share) lands in the SAME batch importer as the
         * in-app picker — the overlay shows progress, stage and the typed
         * summary for every entry point.
         */
        fun intakeUri(uri: Uri) {
            val ctx = context ?: return
            ctx.takeReadPermission(uri)
            val sources = ctx.toEBookSources(listOf(uri))
            when (
                val verdict =
                    io.github.moronigranja.ayvu.ebook.IntakeRouting
                        .resolveFile(sources.firstOrNull()?.fileName)
            ) {
                is io.github.moronigranja.ayvu.ebook.IntakeRouting.IntakeVerdict.Import -> import(sources)
                is io.github.moronigranja.ayvu.ebook.IntakeRouting.IntakeVerdict.Guidance ->
                    showGuidance(verdict.displayName, verdict.message)
            }
        }

        /** Dismisses the import overlay (guidance or finished summary). */
        fun dismissIntake() {
            _intakeGuidance.value = null
            importStateHolder?.set(ImportUiState.Idle)
        }

        /** Imports [sources] in order as one cancellable operation; no-op for an
         * empty list. The batch runs on the app scope (the operation host), so a
         * screen close no longer abandons it. */
        fun import(sources: List<EBookSource>) {
            imports?.start(sources, truncated = false, operations = operations)
        }

        /**
         * F3: scans a SAF tree grant off-main (publishing [ImportUiState.Scanning],
         * which has no file count), then feeds the supported files through the
         * shared batch importer. An empty scan surfaces as a typed failure, never a
         * silent no-op; a scan that hit [FolderScanPolicy.MAX_FILES] carries its
         * truncation through to the summary.
         */
        fun importFolder(uri: Uri) {
            val ctx = context ?: return
            // The scan itself stays here: it is bounded (FolderScanPolicy) and
            // needs this screen's Context; the batch it produces is the shared
            // operation.
            imports?.cancel(operations) // a scan supersedes the running import
            importStateHolder?.set(ImportUiState.Scanning("Scanning folder\u2026"))
            viewModelScope.launch {
                try {
                    val result = withContext(ioDispatcher) { ctx.scanTree(uri) }
                    coroutineContext.ensureActive()
                    if (result.files.isEmpty()) {
                        importStateHolder?.set(
                            ImportUiState.Done(
                                ImportUiState.Summary(
                                    added = 0,
                                    unchanged = 0,
                                    failed = listOf("Folder" to NO_BOOKS_MESSAGE),
                                ),
                            ),
                        )
                        return@launch
                    }
                    imports?.start(ctx.toEBookSources(result), truncated = result.truncated, operations = operations)
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }

        /** Cancels the in-flight import (F1): the batch stops at the next file
         * boundary; already-committed books remain (they are fully imported).
         * The overlay clears immediately; the operation ends via Stop's cancel. */
        fun cancelImport() {
            imports?.cancel(operations) ?: importStateHolder?.set(ImportUiState.Idle)
        }

        private companion object {
            const val MAX_RECENT = 5

            /** The empty-folder ("no supported books") message for a folder import (F3). */
            const val NO_BOOKS_MESSAGE = "no supported book files found"
        }
    }
