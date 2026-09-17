package io.github.moronigranja.ayvu.persistence

import java.io.File

/**
 * Original book-file sidecar tier (E1): `files/books/<bookId>.<ext>` raw
 * source bytes captured at import, the opt-in "include book files" export
 * source. Content-hash book ids make re-imports no-ops, so a row's sidecar
 * is written exactly once. Keys are archive names (`<bookId>.<ext>`); a
 * missing sidecar simply means the book is absent from an include-books
 * export — never a failed export.
 *
 * [save] is contained and atomic, because a restored archive is untrusted input:
 * the name is resolved to its canonical path and refused unless that path stays
 * inside [root] — the same canonical-containment guard the espeak stager applies
 * to a zip entry, since `books/../../databases/ayvu.db` would otherwise overwrite
 * the database — and the bytes land in a temp file in the target directory that is
 * renamed into place, so a disk-full write can never leave a truncated sidecar
 * behind the Room transaction that recorded the book.
 */
class BookFileStore(
    private val root: File,
) {
    fun save(
        name: String,
        bytes: ByteArray,
    ) {
        root.mkdirs()
        val canonicalRoot = root.canonicalFile
        val target = File(root, name).canonicalFile
        check(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "book file name escapes the store root: $name"
        }
        writeAtomically(target, bytes)
    }

    /** Every stored file, name-sorted — the include-books snapshot source. */
    fun all(): Map<String, ByteArray> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedBy { it.name }
            .associate { it.name to it.readBytes() }

    /** Book removal: drop the sidecar (`<bookId>.*`), like covers/offline audio. */
    fun deleteForBook(bookId: String) {
        (root.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("$bookId.") }
            .forEach { it.delete() }
    }

    /**
     * Temp file in the SAME directory, then rename: the rename is atomic on the
     * one filesystem the sidecar lives on, so a reader never sees a half-written
     * book file — and a failure (disk full, or a target that cannot be replaced)
     * removes the temp file and leaves whatever was already there untouched.
     */
    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeBytes(bytes)
            check(temp.renameTo(target)) { "cannot replace ${target.name}" }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }
}
