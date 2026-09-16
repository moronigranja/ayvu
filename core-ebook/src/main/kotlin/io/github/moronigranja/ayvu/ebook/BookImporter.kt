package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.model.LibraryEntry

/**
 * The import pipeline's parse core (C6/F1/A3): format detection → read →
 * parse → segment, plus the cover. Pure logic, no Android and NO side
 * effects — the [ImportCoordinator] owns where the result lands (`A3: parse
 * without publication → durable commit → index publish`). The SAF picker and
 * UI are a thin adapter over that coordinator.
 *
 * Re-import semantics are content-addressed: the book id is the SHA-256 of
 * the file bytes, so the same file always maps to the same id — the
 * coordinator's durable duplicate gate ([LibraryStore.contains]) decides
 * whether parsing is necessary at all.
 *
 * Every failure maps to a typed [ImportOutcome.Failed] with a user-facing
 * reason; the pipeline never throws for bad input.
 */
class BookImporter(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Format gate for the typed failure path (UnsupportedFormat). */
    fun isSupported(fileName: String): Boolean = EBookFormats.parserFor(fileName) != null

    /**
     * Reads + hashes the source WITHOUT parsing — the coordinator's duplicate gate; null when
     * unsupported or unreadable, [EBookLimitExceededException] when over the container ceiling.
     */
    fun sourceId(source: EBookSource): String? {
        if (!isSupported(source.fileName)) return null
        return try {
            Bytes.sha256Hex(source.readCapped())
        } catch (e: EBookLimitExceededException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Parses one source into a segmented [LibraryEntry] (+cover); the coordinator owns index + persistence. */
    fun import(source: EBookSource): ImportOutcome {
        val parser =
            EBookFormats.parserFor(source.fileName)
                ?: return ImportOutcome.Failed(source.fileName, ImportFailureReason.UnsupportedFormat)

        val book =
            try {
                parser.parse(source) // EBookSource.open() is a factory: a fresh stream per call
            } catch (e: EBookParseException) {
                return ImportOutcome.Failed(source.fileName, ImportFailureReason.ParseError(e.message ?: "parse failed"))
            } catch (e: OutOfMemoryError) {
                // An OOM is an Error: one file's parse must fail that file, never the process.
                return ImportOutcome.Failed(source.fileName, ImportFailureReason.ParseError("not enough memory to read this book"))
            } catch (e: Exception) {
                // Stream/open failures land here (the pre-import bytes read is gone; the coordinator owns id/lookup).
                return ImportOutcome.Failed(source.fileName, ImportFailureReason.Unreadable)
            }

        val segmented = BookSegmentation.segment(book) // index/segmentation contract (C4)
        // E1: ONE capped read reused for the cover + source-bytes capture; a source that cannot be re-read has no sidecar.
        val raw =
            try {
                source.readCapped()
            } catch (e: Exception) {
                null
            }
        val cover =
            raw?.let { bytes ->
                try {
                    parser.coverOf(bytes)
                } catch (e: Exception) {
                    null
                }
            }
        return ImportOutcome.Added(LibraryEntry(segmented, now()), cover, raw, source.fileName)
    }
}

/** One import's verdict. Durable membership decided by the store; the index
 * is only ever mutated by the coordinator AFTER the durable write. */
sealed interface ImportOutcome {
    data class Added(
        val entry: LibraryEntry,
        val coverBytes: ByteArray? = null,
        /** E1: the original file bytes captured at import — opt-in include-books
         * export source; null when the source could not be re-opened. */
        val sourceBytes: ByteArray? = null,
        val sourceFileName: String? = null,
    ) : ImportOutcome

    data class Unchanged(
        val bookId: String,
    ) : ImportOutcome

    data class Failed(
        val fileName: String,
        val reason: ImportFailureReason,
    ) : ImportOutcome
}

sealed interface ImportFailureReason {
    data object UnsupportedFormat : ImportFailureReason

    data object Unreadable : ImportFailureReason

    data class ParseError(
        val message: String,
    ) : ImportFailureReason

    /** The durable commit failed (CR-3/A3): the index was NOT touched. */
    data class Storage(
        val message: String,
    ) : ImportFailureReason
}
