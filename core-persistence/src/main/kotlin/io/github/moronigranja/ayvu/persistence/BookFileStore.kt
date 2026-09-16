package io.github.moronigranja.ayvu.persistence

import java.io.File

/**
 * Original book-file sidecar tier (E1): `files/books/<bookId>.<ext>` raw
 * source bytes captured at import, the opt-in "include book files" export
 * source. Content-hash book ids make re-imports no-ops, so a row's sidecar
 * is written exactly once. Keys are archive names (`<bookId>.<ext>`); a
 * missing sidecar simply means the book is absent from an include-books
 * export — never a failed export.
 */
class BookFileStore(
    private val root: File,
) {
    fun save(
        name: String,
        bytes: ByteArray,
    ) {
        root.mkdirs()
        File(root, name).writeBytes(bytes)
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
}
