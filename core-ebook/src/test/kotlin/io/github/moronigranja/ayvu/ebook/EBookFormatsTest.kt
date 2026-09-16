package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.TextParser
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EBookFormatsTest {
    @Test
    fun `detects epub case-insensitively`() {
        assertSame(EpubParser, EBookFormats.parserFor("book.epub"))
        assertSame(EpubParser, EBookFormats.parserFor("BOOK.EPUB"))
    }

    @Test
    fun `detects the mobi family`() {
        assertSame(MobiParser, EBookFormats.parserFor("book.mobi"))
        assertSame(MobiParser, EBookFormats.parserFor("book.azw"))
        assertSame(MobiParser, EBookFormats.parserFor("book.azw3"))
        assertSame(MobiParser, EBookFormats.parserFor("book.KF8"))
    }

    @Test
    fun `detects the text family`() {
        assertSame(TextParser, EBookFormats.parserFor("book.txt"))
        assertSame(TextParser, EBookFormats.parserFor("book.markdown"))
        assertSame(TextParser, EBookFormats.parserFor("MyBook.txt"))
        assertSame(TextParser, EBookFormats.parserFor("MyBook.markdown"))
        assertSame(TextParser, EBookFormats.parserFor("book.md"))
        assertSame(TextParser, EBookFormats.parserFor("MyBook.MD"))
    }

    @Test
    fun `unsupported and extensionless files return null`() {
        assertNull(EBookFormats.parserFor("book"))
        assertNull(EBookFormats.parserFor(""))
        assertNull(EBookFormats.parserFor("book.epubx"))
        assertNull(EBookFormats.parserFor("book.kfx")) // closed container: out of scope
    }
}
