package io.github.moronigranja.ayvu.ebook

import java.io.ByteArrayOutputStream

/**
 * The import ceilings in ONE place: the numbers, the failure a breach raises, and the capped
 * read every call site goes through.
 *
 * Why they exist: a container is untrusted input. Without ceilings a zip bomb — or simply an
 * absurd file — allocates until the process dies, and an [OutOfMemoryError] is an `Error`, so
 * it escapes every `catch (Exception)` on the import path and takes the app down with it. The
 * guard is applied DURING the read/inflate, never after, so the bomb is never materialised.
 *
 * Defaults are sized against real books: the repo's own fixtures top out at ~3 KB and the
 * fattest commercial EPUB/AZW3 files (image- and font-heavy) stay under ~100 MB, so these
 * ceilings leave legitimate books untouched — while a real bomb, which inflates three orders
 * of magnitude, trips immediately. Tests inject small values ([ImportLimits] is a plain data
 * class) instead of building a quarter-gigabyte file.
 */
internal data class ImportLimits(
    val maxContainerBytes: Int = MAX_CONTAINER_BYTES,
    val maxEntryCount: Int = MAX_ENTRY_COUNT,
    val maxEntryBytes: Int = MAX_ENTRY_BYTES,
    val maxTotalExpandedBytes: Long = MAX_TOTAL_EXPANDED_BYTES,
) {
    companion object {
        /** The whole container, read into memory to parse — ~2.5× the largest real ebook. */
        const val MAX_CONTAINER_BYTES = 256 * 1024 * 1024

        /** One entry per chapter/stylesheet/image/font: a 2000-chapter book still fits. */
        const val MAX_ENTRY_COUNT = 4096

        /** The largest single entry (cover art, embedded font) any real book carries. */
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024

        /** Cumulative inflation of the archive: 2× the container, so stored entries still fit. */
        const val MAX_TOTAL_EXPANDED_BYTES = 512L * 1024 * 1024

        /** Sized for real books; the test suite injects smaller values. */
        val DEFAULT = ImportLimits()
    }
}

/**
 * A container exceeded an [ImportLimits] ceiling. Subtypes [EBookParseException] so the import
 * flow reports it through the SAME typed per-file failure as any other bad container — the
 * ceiling's own message included ("file is too large", "archive expands too far"). It is not a
 * [java.io.IOException], so [ZipEntries.readUntilBroken]'s lenient path does not swallow it: a
 * zip bomb is not trailing junk.
 */
internal class EBookLimitExceededException(
    message: String,
) : EBookParseException(message)

/** Read/inflate chunk — one buffer size for every capped path. */
internal const val IO_BUFFER_BYTES = 64 * 1024

/** Whole MB, for the ceiling messages a user ends up reading. */
internal fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)

/**
 * The ONE way a source's bytes are pulled into memory: streamed, with the container ceiling
 * applied as it fills (hash path, parse path, cover/source-bytes capture). A source over
 * [ImportLimits.maxContainerBytes] raises [EBookLimitExceededException] instead of forcing the
 * heap to hold it — the caller decides whether that fails the file or just skips a sidecar.
 */
internal fun EBookSource.readCapped(limits: ImportLimits = ImportLimits.DEFAULT): ByteArray {
    val cap = limits.maxContainerBytes
    val buffer = ByteArray(IO_BUFFER_BYTES)
    val out = ByteArrayOutputStream(minOf(cap, IO_BUFFER_BYTES))
    open().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > cap) {
                throw EBookLimitExceededException("file is too large (over ${megabytes(cap.toLong())} MB)")
            }
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}
