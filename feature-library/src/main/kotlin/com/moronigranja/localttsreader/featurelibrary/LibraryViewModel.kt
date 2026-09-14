package com.moronigranja.localttsreader.featurelibrary

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportCoordinator
import com.moronigranja.localttsreader.ebook.ImportFailureReason
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.featurelibrary.CoverStore
import com.moronigranja.localttsreader.locate.IndexLock
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.ActivitySecondsDao
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.BookFileStore
import com.moronigranja.localttsreader.persistence.ChapterCount
import com.moronigranja.localttsreader.persistence.PassageDao
import com.moronigranja.localttsreader.persistence.ProgressDao
import com.moronigranja.localttsreader.persistence.ProgressEntity
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.ActivityKind
import com.moronigranja.localttsreader.player.ActivityRow
import com.moronigranja.localttsreader.player.DailyTotals
import com.moronigranja.localttsreader.player.IoDispatcher
import com.moronigranja.localttsreader.player.LocalDays
import com.moronigranja.localttsreader.player.OfflineStorage
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerCommands
import com.moronigranja.localttsreader.player.PregenJobState
import com.moronigranja.localttsreader.player.PregenScheduler
import com.moronigranja.localttsreader.player.Streak
import com.moronigranja.localttsreader.player.TodayStats
import com.moronigranja.localttsreader.player.WeekSummary
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import com.moronigranja.localttsreader.tts.piper.PiperVoiceMetadata
import com.moronigranja.localttsreader.tts.translate.TranslateLanguages
import com.moronigranja.localttsreader.tts.translate.TranslatePackStager
import com.moronigranja.localttsreader.tts.translate.TranslatePacks
import com.moronigranja.localttsreader.ui.ReadInLanguageUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * Drives the import flow: batches [EBookSource]s through the domain [BookImporter]
 * on the IO dispatcher, publishes progress as [Importing], appends [Added]
 * entries to the [LibraryStore], and lands on [ImportUiState.Done] with the
 * batch summary — for every outcome, including all-failed batches.
 *
 * [ioDispatcher] is qualifier-injected so unit tests can hand a virtual dispatcher
 * to [import]'s coroutine (production gets [kotlinx.coroutines.Dispatchers.IO]
 * from [ImportModule]).
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
        // CR-3/A3: the one import orchestration boundary (parse → durable → index).
        private val coordinator: ImportCoordinator,
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
        private val activityDao: ActivitySecondsDao? = null,
        // A6: the app binds the intent-dispatching sender; tests pass a fake.
        private val commands: PlayerCommands,
    ) : ViewModel() {
        /** Books the user has covers for (extracted at import; sidecar files). */
        fun cover(bookId: String): ByteArray? = context?.let { CoverStore(File(it.filesDir, "covers")).load(bookId) }

        /** Dismisses the finished-batch summary; the snackbar/dialog must not re-show on revisit. */
        fun consumeImportResult() {
            _importState.value = ImportUiState.Idle
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

        private val translateDownload = MutableStateFlow<Pair<String, Float>?>(null)

        /** The "Read in language" dialog state for [bookId]. */
        fun translateState(bookId: String): StateFlow<ReadInLanguageUiState> {
            val prefs = settings ?: return MutableStateFlow(ReadInLanguageUiState(bookId = bookId))
            val packs = registry ?: return MutableStateFlow(ReadInLanguageUiState(bookId = bookId))
            return combine(prefs.state, packs.packs, translateDownload) { prefsSnapshot, packStates, downloading ->
                val target = prefsSnapshot.bookTranslate[bookId]
                ReadInLanguageUiState(
                    bookId = bookId,
                    target = target,
                    languages = translateLanguages(prefsSnapshot),
                    packDownloaded =
                        packStates.any { it.pack.id == TranslatePacks.pack.id && it.status == PackStatus.Ready } &&
                            (context?.let { TranslatePackStager.isStaged(it.filesDir) } ?: false),
                    downloadProgress = downloading?.takeIf { it.first == bookId }?.second,
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

        /** Inline download for the translation pack (content-row action). */
        fun downloadTranslatePack(bookId: String) {
            val packs = registry ?: return
            viewModelScope.launch {
                translateDownload.value = bookId to 0f
                packs
                    .download(TranslatePacks.pack.id) { done, total ->
                        translateDownload.value = bookId to (done.toDouble() / total).toFloat()
                    }.also { translateDownload.value = null }
            }
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
            if (activityDao == null) {
                MutableStateFlow(TodayStats.EMPTY)
            } else {
                val dao = activityDao
                combine(
                    statsDay.flatMapLatest { key -> dao.observeSince(WeekSummary.startKey(key)) },
                    dao.observeActiveDays(),
                    statsDay,
                ) { rows, activeDays, todayKey ->
                    val activityRows =
                        rows.map { row ->
                            ActivityRow(row.dayKey, ActivityKind.valueOf(row.kind), row.seconds)
                        }
                    TodayStats(
                        dayKey = todayKey,
                        today = DailyTotals.summarize(todayKey, activityRows),
                        week = WeekSummary.series(activityRows, todayKey),
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

        /** The in-flight import batch (F1): a new import supersedes the old one,
         * and [cancelImport] stops it at the next file boundary. */
        private var importJob: Job? = null

        private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
        val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

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
                    com.moronigranja.localttsreader.ebook.IntakeRouting
                        .resolveFile(sources.firstOrNull()?.fileName)
            ) {
                is com.moronigranja.localttsreader.ebook.IntakeRouting.IntakeVerdict.Import -> import(sources)
                is com.moronigranja.localttsreader.ebook.IntakeRouting.IntakeVerdict.Guidance ->
                    showGuidance(verdict.displayName, verdict.message)
            }
        }

        /** Dismisses the import overlay (guidance or finished summary). */
        fun dismissIntake() {
            _intakeGuidance.value = null
            _importState.value = ImportUiState.Idle
        }

        /** Imports [sources] in order, reporting per-file progress; no-op for an empty list. */
        fun import(sources: List<EBookSource>) {
            if (sources.isEmpty()) return
            importJob?.cancel()
            // F1: visible progress from the very first file's parse — a large
            // (or single-file) import must never look hung before its first
            // completed file.
            _importState.value =
                ImportUiState.Importing(
                    done = 0,
                    total = sources.size,
                    currentFileName = sources.first().fileName,
                )
            importJob =
                viewModelScope.launch {
                    try {
                        runImport(sources, truncated = false)
                    } catch (e: CancellationException) {
                        throw e // cancelImport already published Idle; never a partial Done
                    }
                }
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
            importJob?.cancel()
            _importState.value = ImportUiState.Scanning("Scanning folder\u2026")
            importJob =
                viewModelScope.launch {
                    try {
                        val result = withContext(ioDispatcher) { ctx.scanTree(uri) }
                        coroutineContext.ensureActive()
                        if (result.files.isEmpty()) {
                            _importState.value =
                                ImportUiState.Done(
                                    ImportUiState.Summary(
                                        added = 0,
                                        unchanged = 0,
                                        failed = listOf("Folder" to NO_BOOKS_MESSAGE),
                                    ),
                                )
                            return@launch
                        }
                        _importState.value =
                            ImportUiState.Importing(
                                done = 0,
                                total = result.files.size,
                                currentFileName = result.files.first().name,
                            )
                        runImport(ctx.toEBookSources(result), truncated = result.truncated)
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
        }

        /** The shared import loop for file and folder batches: progress → durable
         * commit/index (owned by the coordinator) → the summary, in-flight-truncated. */
        private suspend fun runImport(
            sources: List<EBookSource>,
            truncated: Boolean,
        ) {
            val outcomes =
                withContext(ioDispatcher) {
                    coordinator.importAll(
                        sources,
                        onProgress = { current, done, total ->
                            _importState.value = ImportUiState.Importing(done, total, current.fileName)
                        },
                        onStage = { stage ->
                            // keep the latest per-file counts while the stage flips
                            val cur = _importState.value
                            if (cur is ImportUiState.Importing) {
                                _importState.value =
                                    ImportUiState.Importing(
                                        done = cur.done,
                                        total = cur.total,
                                        currentFileName = cur.currentFileName,
                                        stage = stage,
                                    )
                            }
                        },
                    )
                }
            val summary = buildSummary(outcomes)
            currentCoroutineContext().ensureActive() // a racing cancel must never land Done
            _importState.value = ImportUiState.Done(summary.copy(truncated = truncated))
        }

        /** Cancels the in-flight import (F1): the batch stops at the next file
         * boundary; already-committed books remain (they are fully imported). */
        fun cancelImport() {
            importJob?.cancel()
            importJob = null
            _importState.value = ImportUiState.Idle
        }

        private suspend fun buildSummary(outcomes: List<ImportOutcome>): ImportUiState.Summary {
            var added = 0
            var unchanged = 0
            val failed = mutableListOf<Pair<String, String>>()
            for (outcome in outcomes) {
                when (outcome) {
                    is ImportOutcome.Added -> {
                        added += 1
                        // CR-3/A3: the durable commit + index publish already
                        // happened in the coordinator — the VM only owns UI work.
                        outcome.coverBytes?.let { cover ->
                            context?.let { CoverStore(File(it.filesDir, "covers")).save(outcome.entry.book.id, cover) }
                        }
                        // E1: capture the original bytes for the opt-in include-books
                        // export — `files/books/<bookId>.<ext>`.
                        outcome.sourceBytes?.let { bytes ->
                            outcome.sourceFileName?.let { fileName ->
                                bookFileStore?.save(
                                    outcome.entry.book.id + "." + fileName.substringAfterLast('.', "bin"),
                                    bytes,
                                )
                            }
                        }
                    }
                    is ImportOutcome.Unchanged -> unchanged += 1
                    is ImportOutcome.Failed -> failed += outcome.fileName to reasonMessage(outcome.reason)
                }
            }
            return ImportUiState.Summary(added, unchanged, failed)
        }

        private fun reasonMessage(reason: ImportFailureReason): String =
            when (reason) {
                ImportFailureReason.UnsupportedFormat -> "format not supported"
                ImportFailureReason.Unreadable -> "could not read file"
                is ImportFailureReason.ParseError -> reason.message
                is ImportFailureReason.Storage -> "could not save the book: ${reason.message}"
            }

        private companion object {
            const val MAX_RECENT = 5

            /** The empty-folder ("no supported books") message for a folder import (F3). */
            const val NO_BOOKS_MESSAGE = "no supported book files found"
        }
    }
