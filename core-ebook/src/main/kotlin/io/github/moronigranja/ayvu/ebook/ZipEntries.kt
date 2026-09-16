package io.github.moronigranja.ayvu.ebook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ZIP container reading shared by the EPUB and KF8 parsers.
 *
 * - [readAll]: strict — a broken container throws [EBookParseException] (EPUB).
 * - [readUntilBroken]: returns whatever parsed before the first broken section, so an
 *   archive that trails non-zip data (e.g. KF8 with trailing resource records) still
 *   yields its files; only an archive that yields nothing throws.
 * - Both enforce [ImportLimits] while inflating — entry count, per-entry bytes, cumulative
 *   expanded bytes — so a bomb is refused before its contents are materialised. A breach
 *   raises [EBookLimitExceededException], which the lenient path does not swallow (that
 *   path only tolerates [IOException]: a zip bomb is not trailing junk).
 */
internal object ZipEntries {
    fun readAll(
        bytes: ByteArray,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): Map<String, ByteArray> {
        val entries = read(bytes, lenient = false, limits = limits)
        if (entries.isEmpty()) throw EBookParseException("ebook container is empty")
        return entries
    }

    fun readUntilBroken(
        bytes: ByteArray,
        limits: ImportLimits = ImportLimits.DEFAULT,
    ): Map<String, ByteArray> {
        val entries = read(bytes, lenient = true, limits = limits)
        if (entries.isEmpty()) throw EBookParseException("archive has no readable content")
        return entries
    }

    private fun read(
        bytes: ByteArray,
        lenient: Boolean,
        limits: ImportLimits,
    ): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = normalizePath(entry.name)
                        if (path.isNotEmpty()) {
                            count++
                            if (count > limits.maxEntryCount) {
                                throw EBookLimitExceededException("archive has too many entries (over ${limits.maxEntryCount})")
                            }
                            val data = inflate(zip, path, expanded, limits)
                            expanded += data.size
                            entries[path] = data
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            if (!lenient) throw EBookParseException("not a valid zip/ebook container", e)
        }
        return entries
    }

    /**
     * One entry's bytes. [expandedSoFar] is what the earlier entries already cost; both the
     * per-entry and the cumulative ceiling are checked AS the entry streams in, so an archive
     * that inflates past a ceiling throws before its bomb is ever held in memory.
     */
    private fun inflate(
        zip: ZipInputStream,
        path: String,
        expandedSoFar: Long,
        limits: ImportLimits,
    ): ByteArray {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        val out = ByteArrayOutputStream(IO_BUFFER_BYTES)
        var size = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            size += read
            if (size > limits.maxEntryBytes) {
                val cap = megabytes(limits.maxEntryBytes.toLong())
                throw EBookLimitExceededException("archive entry is too large (over $cap MB): $path")
            }
            if (expandedSoFar + size > limits.maxTotalExpandedBytes) {
                val cap = megabytes(limits.maxTotalExpandedBytes)
                throw EBookLimitExceededException("archive expands too far (over $cap MB)")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val out = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            } else {
                out += part
            }
        }
        return out.joinToString("/")
    }

    /** Case-insensitive path lookup: EPUB paths are case-sensitive, real files are sloppy. */
    fun Map<String, ByteArray>.lookup(path: String): ByteArray? {
        get(path)?.let { return it }
        val lower = path.lowercase()
        for ((key, value) in this) if (key.lowercase() == lower) return value
        return null
    }
}
