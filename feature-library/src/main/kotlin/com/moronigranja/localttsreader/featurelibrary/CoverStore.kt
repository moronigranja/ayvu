package com.moronigranja.localttsreader.featurelibrary

import java.io.File

/**
 * Cover artwork sidecar for library rows: `files/covers/<bookId>` raw image
 * bytes, extracted once at import (content-hash ids, so a re-import of the
 * same file is a no-op and never re-writes). TXT/Markdown/MOBI books simply
 * have no file — the row falls back to a placeholder.
 */
class CoverStore(
    private val root: File,
) {
    fun save(
        bookId: String,
        bytes: ByteArray,
    ) {
        root.mkdirs()
        File(root, bookId).writeBytes(bytes)
    }

    fun load(bookId: String): ByteArray? {
        val file = File(root, bookId)
        return if (file.isFile) file.readBytes() else null
    }

    /** Book removal: drop the sidecar cover (decisions #50 pass). */
    fun delete(bookId: String) {
        File(root, bookId).delete()
    }
}
