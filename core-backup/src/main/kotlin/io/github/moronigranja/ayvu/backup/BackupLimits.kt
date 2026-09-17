package io.github.moronigranja.ayvu.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The backup-archive ceilings in ONE place: the numbers, and the capped read every
 * untrusted archive byte goes through.
 *
 * Why they exist: a picked "backup" is untrusted input. Without ceilings a zip bomb — or
 * simply an absurd file — allocates until the process dies, and an [OutOfMemoryError] is an
 * `Error`, so it escaped every `catch (Exception)` on the restore path and took the app down
 * with it. Every ceiling is applied DURING the read/inflate, never after, so the bomb is
 * never materialised. The archive is parsed whole in memory (JSON sections are handed to
 * kotlinx-serialization, book files back verbatim), so these cap one allocation, not disk.
 *
 * Mirrors core-ebook's `ImportLimits`/`ZipEntries` pattern. core-backup is pure JVM and must
 * NOT depend on core-ebook, so the ceiling logic is implemented locally instead of imported.
 *
 * Sizing: an archive is dominated by the opt-in book files, whose import path already stays
 * under a quarter gigabyte; the JSON sections are kilobytes. Real backups therefore sit far
 * below these ceilings, while a real bomb (three orders of magnitude of inflation) trips
 * immediately. Tests inject small values — [BackupLimits] is a plain data class.
 */
internal data class BackupLimits(
    val maxArchiveBytes: Int = MAX_ARCHIVE_BYTES,
    val maxEntryCount: Int = MAX_ENTRY_COUNT,
    val maxEntryBytes: Int = MAX_ENTRY_BYTES,
    val maxTotalExpandedBytes: Long = MAX_TOTAL_EXPANDED_BYTES,
    val maxManifestBytes: Int = MAX_MANIFEST_BYTES,
) {
    companion object {
        /** The whole archive, held in memory to parse — the same ceiling as an ebook container. */
        const val MAX_ARCHIVE_BYTES = 256 * 1024 * 1024

        /** 8 JSON sections + one entry per book file: a 4000-book library still fits. */
        const val MAX_ENTRY_COUNT = 4096

        /** The largest single entry: one book's original bytes (a fat EPUB is ~100 MB). */
        const val MAX_ENTRY_BYTES = 128 * 1024 * 1024

        /** Cumulative inflation: 2× the archive, so stored (uncompressed) entries still fit. */
        const val MAX_TOTAL_EXPANDED_BYTES = 512L * 1024 * 1024

        /** The manifest carries three fields; 64 KB is already absurd for it. */
        const val MAX_MANIFEST_BYTES = 64 * 1024

        /** Sized for real archives; the test suite injects smaller values. */
        val DEFAULT = BackupLimits()
    }
}

/** Read/inflate chunk — one buffer size for every capped path. */
internal const val IO_BUFFER_BYTES = 64 * 1024

/** Whole MB, for the ceiling messages a user ends up reading. */
internal fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)

/**
 * The ONE way an untrusted archive's bytes are pulled into memory: streamed, with the archive
 * ceiling applied as it fills. A SAF/`ContentResolver` stream publishes no trustworthy length,
 * so the COUNT is what decides — a stream that would exceed [BackupLimits.maxArchiveBytes]
 * raises [BackupReadError.ArchiveTooLarge] instead of forcing the heap to hold it.
 *
 * The stream is left open: the caller owns it (the SAF edge already reads it under `use`).
 */
internal fun InputStream.readCapped(limits: BackupLimits = BackupLimits.DEFAULT): ByteArray {
    val cap = limits.maxArchiveBytes
    val buffer = ByteArray(IO_BUFFER_BYTES)
    val out = ByteArrayOutputStream(IO_BUFFER_BYTES)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > cap) throw BackupReadError.ArchiveTooLarge(cap)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
