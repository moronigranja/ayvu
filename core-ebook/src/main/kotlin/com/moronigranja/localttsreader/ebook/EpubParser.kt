package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.model.Book

/**
 * EPUB2/EPUB3 parser: OCF container (META-INF/container.xml → content.opf), spine in
 * order, paragraph passages per chapter, NCX (EPUB2) / nav (EPUB3) chapter titles with
 * heading fallback. Lenient with sloppy real-world files; structurally broken
 * containers raise [EBookParseException]; XML is XXE-hardened (see [OpfBookReader]).
 */
object EpubParser : EBookParser {
    override fun parse(source: EBookSource): Book {
        val bytes = source.readCapped()
        val base = source.fileName.substringBeforeLast('.').substringAfterLast('/')
        return parse(bytes, fallbackTitle = base.ifBlank { "Untitled" })
    }

    /** Parse raw container bytes. [fallbackTitle] is used when the OPF declares none. */
    fun parse(
        bytes: ByteArray,
        fallbackTitle: String = "Untitled",
    ): Book {
        val entries = ZipEntries.readAll(bytes)
        val opfPath = OpfBookReader.findOpfPath(entries)
        return OpfBookReader.parseBook(Bytes.sha256Hex(bytes), entries, opfPath, fallbackTitle)
    }

    /** Cover image bytes, or null when the container has no standard cover. */
    override fun coverOf(bytes: ByteArray): ByteArray? {
        val entries = ZipEntries.readAll(bytes)
        val opfPath = runCatching { OpfBookReader.findOpfPath(entries) }.getOrNull() ?: return null
        return OpfBookReader.coverImage(entries, opfPath)
    }
}
