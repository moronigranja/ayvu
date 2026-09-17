package io.github.moronigranja.ayvu.featurelibrary

import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.ebook.ImportCoordinator
import io.github.moronigranja.ayvu.ebook.ImportFailureReason
import io.github.moronigranja.ayvu.ebook.ImportOutcome
import io.github.moronigranja.ayvu.ebook.ImportStage
import io.github.moronigranja.ayvu.ops.OperationChannel
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import io.github.moronigranja.ayvu.persistence.BookFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The import operation: one batch ([EBookSource]s, file or folder) run as a
 * cancellable, observable long operation over the [ImportStateHolder].
 *
 * Runs on the process-lifetime [appScope] — the same scope every other long
 * operation uses — so an import survives the screen that started it, and the
 * shared holder is what both the library overlay and the setup wizard render.
 * Stop cancels the batch at the next file boundary; already-committed books
 * stay (each one is fully parsed, segmented and indexed by then).
 */
@Singleton
class ImportOperations
    @Inject
    constructor(
        private val coordinator: ImportCoordinator,
        private val holder: ImportStateHolder,
        @Named("app_files_dir") private val filesDir: File,
        private val appScope: CoroutineScope,
        // Default null: the pure-JVM harness skips the book-bytes sidecar (Hilt provides it).
        private val bookFileStore: BookFileStore? = null,
    ) {
        /** Identifies the current batch, so a superseded one cannot publish. */
        private val generation = AtomicInteger()

        /** The no-host batch's job (pure-JVM harness only). */
        private var hostlessJob: Job? = null

        /**
         * Imports [sources] in order through [operations] (null in the pure-JVM
         * harness, where the batch still runs so the holder stays observable);
         * a new batch supersedes the running one, and an empty list is a no-op.
         */
        fun start(
            sources: List<EBookSource>,
            truncated: Boolean,
            operations: OperationRunner?,
        ) {
            if (sources.isEmpty()) return
            cancel(operations) // supersede: one import at a time
            val batch = generation.incrementAndGet()
            // F1: visible progress from the very first file's parse — a large
            // (or single-file) import must never look hung before its first
            // completed file.
            holder.set(ImportUiState.Importing(done = 0, total = sources.size, currentFileName = sources.first().fileName))
            val body: suspend (OperationReporter) -> Unit = { reporter -> runBatch(sources, truncated, batch, reporter) }
            if (operations != null) {
                operations.run(
                    OperationSpec(
                        id = IMPORT_ID,
                        channel = OperationChannel.IMPORT,
                        title = "Ayvu — importing books",
                    ),
                    body,
                )
            } else {
                hostlessJob = appScope.launch { body(NO_REPORT) }
            }
        }

        /**
         * Stops the running import (the notification's Stop action): the batch
         * ends at the next file boundary and the overlay returns to
         * [ImportUiState.Idle]. Already-committed books stay imported.
         */
        fun cancel(operations: OperationRunner?) {
            holder.set(ImportUiState.Idle)
            if (operations != null) {
                operations.cancel(IMPORT_ID)
            } else {
                hostlessJob?.cancel()
                hostlessJob = null
            }
        }

        private suspend fun runBatch(
            sources: List<EBookSource>,
            truncated: Boolean,
            batch: Int,
            reporter: OperationReporter,
        ) {
            try {
                // The batch itself runs on the app scope (already IO); the
                // coordinator owns the parse → durable → index boundary.
                var currentStage = ImportStage.READING
                val outcomes =
                    coordinator.importAll(
                        sources,
                        onProgress = { current, done, total ->
                            holder.set(ImportUiState.Importing(done, total, current.fileName, currentStage))
                            reporter.report(
                                "$done/$total — ${current.fileName}",
                                if (total > 0) done * 100 / total else null,
                            )
                        },
                        onStage = { stage ->
                            // keep the latest per-file counts while the stage flips
                            currentStage = stage
                            val current = holder.state.value
                            if (current is ImportUiState.Importing) holder.set(current.copy(stage = stage))
                        },
                    )
                val summary = buildSummary(outcomes)
                // A superseded batch must not publish over its successor's state.
                if (generation.get() == batch) holder.set(ImportUiState.Done(summary.copy(truncated = truncated)))
            } catch (e: CancellationException) {
                // Stop: without this the overlay would stay on "Importing" forever.
                if (generation.get() == batch) holder.set(ImportUiState.Idle)
                throw e
            } finally {
                if (generation.get() == batch) hostlessJob = null
            }
        }

        private fun buildSummary(outcomes: List<ImportOutcome>): ImportUiState.Summary {
            var added = 0
            var unchanged = 0
            val failed = mutableListOf<Pair<String, String>>()
            for (outcome in outcomes) {
                when (outcome) {
                    is ImportOutcome.Added -> {
                        added += 1
                        // CR-3/A3: the durable commit + index publish already
                        // happened in the coordinator — only UI side effects here.
                        outcome.coverBytes?.let { cover ->
                            CoverStore(File(filesDir, "covers")).save(outcome.entry.book.id, cover)
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

        companion object {
            /** The cancel address of the import operation (one import at a time). */
            const val IMPORT_ID = "book-import"

            private val NO_REPORT = OperationReporter { _, _ -> }
        }
    }

/** [ImportFailureReason] → the row/summary text. */
internal fun reasonMessage(reason: ImportFailureReason): String =
    when (reason) {
        ImportFailureReason.UnsupportedFormat -> "format not supported"
        ImportFailureReason.Unreadable -> "could not read file"
        is ImportFailureReason.ParseError -> reason.message
        is ImportFailureReason.Storage -> "could not save the book: ${reason.message}"
    }
