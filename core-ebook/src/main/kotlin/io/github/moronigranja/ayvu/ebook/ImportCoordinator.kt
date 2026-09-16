package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.LibraryStore
import kotlinx.coroutines.delay

/**
 * CR-3/A3: the one import orchestration boundary. Order is enforced:
 *
 *   parse/segment WITHOUT externally visible mutation
 *     → durable commit to [LibraryStore] (Room — the duplicate truth)
 *     → publish to [TextIndex] under [IndexLock] (derived)
 *
 * - The durable store owns idempotency: [LibraryStore.contains] (not index
 *   membership) decides whether persistence work is needed, so a failed
 *   commit can never poison the retry path (Failure A: the index no longer
 *   holds ids whose Room write failed).
 * - A failed durable commit returns [ImportFailureReason.Storage] and
 *   leaves the index untouched — retry re-parses and re-commits.
 * - The read stage enforces the [ImportLimits] container ceiling; a breach is reported as
 *   [ImportFailureReason.ParseError] carrying the ceiling's own message ("file is too large"),
 *   NOT as [ImportFailureReason.Unreadable] — an oversized source is a policy refusal, and
 *   the user gets told which.
 * - Deletion is the mirror order: durable [LibraryStore.delete] first;
 *   the index removal happens only after it succeeds.
 * - Every index mutation (publish here, remove on delete, launch rebuild)
 *   is serialized through the shared [IndexLock], so a stale rebuild
 *   snapshot can never purge a book committed after its snapshot (Failure B).
 */
class ImportCoordinator(
    private val importer: BookImporter,
    private val store: LibraryStore,
    private val index: TextIndex,
    private val indexLock: IndexLock,
) {
    /**
     * One source through the full pipeline. [ImportOutcome.Unchanged] means
     * the durable store already holds the content hash — no parse, no write.
     * [onStage] reports the per-file pipeline stage (read → parse → commit →
     * index) for UI progress; it fires before the corresponding step begins.
     */
    suspend fun import(
        source: EBookSource,
        onStage: (ImportStage) -> Unit = {},
    ): ImportOutcome {
        if (!importer.isSupported(source.fileName)) {
            return ImportOutcome.Failed(source.fileName, ImportFailureReason.UnsupportedFormat)
        }
        onStage(ImportStage.READING)
        val id =
            try {
                importer.sourceId(source)
            } catch (e: EBookLimitExceededException) {
                return ImportOutcome.Failed(source.fileName, ImportFailureReason.ParseError(e.message ?: "file is too large"))
            }
        if (id == null) return ImportOutcome.Failed(source.fileName, ImportFailureReason.Unreadable)
        if (store.contains(id)) return ImportOutcome.Unchanged(id)
        onStage(ImportStage.PARSING)
        val outcome = importer.import(source)
        if (outcome !is ImportOutcome.Added) return outcome
        onStage(ImportStage.COMMITTING)
        try {
            store.add(outcome.entry) // durable FIRST
        } catch (e: Exception) {
            return ImportOutcome.Failed(source.fileName, ImportFailureReason.Storage(e.message ?: "storage failure"))
        }
        onStage(ImportStage.INDEXING)
        indexLock.withExclusiveIndex { index.add(outcome.entry.book) } // derived after durable
        return outcome
    }

    /** Mirrors the durable-delete-first order for the removal path. Callers
     * must have cancelled playback/pre-gen concerns themselves. */
    suspend fun removeFromIndex(bookId: String) {
        indexLock.withExclusiveIndex { index.remove(bookId) }
    }

    /**
     * Batch import with progress reporting (F1 semantics moved here with the
     * orchestration): a pre-parse event per file and a 1 ms cooperative
     * boundary so a cancelled batch stops between files — and never commits
     * or indexes a file it never started. Outcomes preserve input order.
     */
    suspend fun importAll(
        sources: List<EBookSource>,
        onProgress: (current: EBookSource, done: Int, total: Int) -> Unit = { _, _, _ -> },
        onStage: (ImportStage) -> Unit = {},
    ): List<ImportOutcome> {
        val results = ArrayList<ImportOutcome>(sources.size)
        sources.forEachIndexed { i, source ->
            delay(1) // cancellation/cooperation boundary between files (F1)
            onProgress(source, i, sources.size)
            results += import(source, onStage)
            onProgress(source, i + 1, sources.size)
        }
        return results
    }
}

/** The per-file pipeline stages a batch import reports ([ImportCoordinator]). */
enum class ImportStage {
    READING,
    PARSING,
    COMMITTING,
    INDEXING,
}
