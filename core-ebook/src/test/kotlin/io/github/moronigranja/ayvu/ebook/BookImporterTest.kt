package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.EBookFormats
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.ebook.EpubFixture.CONTAINER
import io.github.moronigranja.ayvu.ebook.EpubFixture.chapterHtml
import io.github.moronigranja.ayvu.ebook.EpubFixture.ncx
import io.github.moronigranja.ayvu.ebook.EpubFixture.opf
import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.ebook.TextParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class BookImporterTest {
    // ------------------------------------------------------------------
    // Fixture books
    // ------------------------------------------------------------------

    private fun epubBook(
        title: String,
        chapterTitle: String,
        body: String,
    ): ByteArray =
        zip(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to
                opf(
                    title = title,
                    spine = listOf("f0" to "title.xhtml", "c1" to "chap1.xhtml"),
                    ncxHref = "toc.ncx",
                ),
            "OEBPS/toc.ncx" to
                ncx(
                    listOf("title.xhtml" to "Title Page", "chap1.xhtml" to chapterTitle),
                ),
            "OEBPS/title.xhtml" to chapterHtml(null, listOf("A Novel by Someone")),
            "OEBPS/chap1.xhtml" to chapterHtml(null, listOf(body)),
        )

    private fun source(
        name: String,
        bytes: ByteArray,
    ): EBookSource = EBookSource(name) { ByteArrayInputStream(bytes) }

    private fun importer() = BookImporter(now = { 1_700_000_000_000L })

    private fun textBytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------------
    // Happy path + contract
    // ------------------------------------------------------------------

    @Test
    fun `imports parses and segments a book with no side effects`() {
        val outcome = importer().import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))

        val entry = assertInstanceOf(ImportOutcome.Added::class.java, outcome).entry
        assertEquals("Novel", entry.book.title)
        assertEquals(listOf("Chapter 1"), entry.book.chapters.map { it.title })
        assertEquals(1_700_000_000_000L, entry.importedAtEpochMillis)
        assertEquals(64, entry.book.id.length)
    }

    @Test
    fun `sourceId identifies content without parsing`() {
        val id = importer().sourceId(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))
        assertEquals(64, id?.length)
        val broken = importer().sourceId(EBookSource("Book.epub") { throw IllegalStateException("uri gone") })
        assertNull(broken, "unreadable sources have no id (the coordinator maps them to Unreadable)")
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    @Test
    fun `unsupported format fails cleanly`() {
        val outcome = importer().import(source("Book.pdf", epubBook("X", "C", "y")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertEquals(ImportFailureReason.UnsupportedFormat, failed.reason)
    }

    @Test
    fun `garbage content fails with a parse error`() {
        val outcome = importer().import(source("Broken.epub", "not a zip".toByteArray()))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
    }

    @Test
    fun `unreadable source fails cleanly`() {
        val broken = EBookSource("Book.epub") { throw IllegalStateException("uri gone") }
        val outcome = importer().import(broken)

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertEquals(ImportFailureReason.Unreadable, failed.reason)
    }

    @Test
    fun `drm-encrypted book fails with a parse error`() {
        val encrypted = Files.readAllBytes(Path.of("core-ebook/src/test/resources/mobi7_encrypted.mobi"))
        val outcome = importer().import(source("Book.mobi", encrypted))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertTrue(reason.message.contains("DRM"))
    }

    // ------------------------------------------------------------------
    // txt / markdown end-to-end
    // ------------------------------------------------------------------

    @Test
    fun `imports a txt file and indexes a book`() {
        val text = "Hello world.\n\nSecond paragraph."
        val bytes = textBytes(text)
        val outcome = importer().import(source("book.txt", bytes))
        val added = assertInstanceOf(ImportOutcome.Added::class.java, outcome)
        assertEquals(1, added.entry.book.chapters.size)
        assertEquals(
            2,
            added.entry.book.chapters[0]
                .passages.size,
        )
        assertEquals(
            "Hello world.",
            added.entry.book.chapters[0]
                .passages[0]
                .text,
        )
    }

    @Test
    fun `imports a markdown file and indexes a book`() {
        val text = "# Chapter 1\n\nBody paragraph.\n\n## Chapter 2\n\nMore."
        val bytes = textBytes(text)
        val outcome = importer().import(source("book.md", bytes))
        val added = assertInstanceOf(ImportOutcome.Added::class.java, outcome)
        assertEquals(2, added.entry.book.chapters.size)
        assertEquals(
            "Chapter 1",
            added.entry.book.chapters[0]
                .title,
        )
        assertEquals(
            "Chapter 2",
            added.entry.book.chapters[1]
                .title,
        )
    }

    @Test
    fun `importing a txt file with no chapters raises parse error`() {
        val outcome = importer().import(source("book.txt", textBytes("")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
    }

    @Test
    fun `importing a markdown file with no chapters raises parse error`() {
        val outcome = importer().import(source("book.md", textBytes("")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
    }

    // ------------------------------------------------------------------
    // Import ceilings + OOM containment (A7)
    // ------------------------------------------------------------------

    @Test
    fun `a container over the entry ceiling fails its own file with the ceiling's message`() {
        val bomb = zip(*Array(ImportLimits.MAX_ENTRY_COUNT + 1) { "e$it.txt" to "x" })
        val outcome = importer().import(source("Bomb.epub", bomb))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertTrue(reason.message.contains("archive has too many entries"), "was ${reason.message}")

        // One refused container never poisons the importer: the next file still parses.
        val next = importer().import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))
        assertInstanceOf(ImportOutcome.Added::class.java, next)
    }

    @Test
    fun `an out of memory during a file's parse fails that file, not the process`() {
        val outcome = importer().import(EBookSource("Book.epub") { StarvedStream() })

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertEquals("not enough memory to read this book", reason.message)

        // The Error was contained inside the one file: the next import still runs.
        val next = importer().import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))
        assertInstanceOf(ImportOutcome.Added::class.java, next)
    }

    @Test
    fun `errors other than out of memory are not contained`() {
        assertThrows(StackOverflowError::class.java) {
            importer().import(EBookSource("Book.epub") { DeepStackStream() })
        }
    }

    @Test
    fun `an out of memory after the parse fails that file, not the process`() {
        // The parse itself succeeds: the SECOND open — the E1 source-bytes/cover capture that runs
        // after segmentation — is where the heap runs out, outside the parse-only block that used
        // to be guarded, so the Error escaped the importer and killed the app.
        var opens = 0
        val starved =
            EBookSource("Novel.epub") {
                if (opens++ == 0) ByteArrayInputStream(epubBook("Novel", "Chapter 1", "Prose here.")) else StarvedStream()
            }

        val outcome = importer().import(starved)

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertEquals("not enough memory to read this book", reason.message)

        // The Error was contained inside the one file: the next import still runs.
        val next = importer().import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))
        assertInstanceOf(ImportOutcome.Added::class.java, next)
    }

    @Test
    fun `an out of memory while hashing a source yields no id, not a crash`() {
        assertNull(importer().sourceId(EBookSource("Book.epub") { StarvedStream() }))
    }

    /** Heap exhaustion raised where a real one lands: inside a file's read/parse. */
    private class StarvedStream : ByteArrayInputStream(ByteArray(0)) {
        override fun read(): Int = throw OutOfMemoryError("simulated heap exhaustion")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = throw OutOfMemoryError("simulated heap exhaustion")
    }

    /** A non-memory [Error]: the containment must not become a blanket catch. */
    private class DeepStackStream : ByteArrayInputStream(ByteArray(0)) {
        override fun read(): Int = throw StackOverflowError("simulated stack exhaustion")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = throw StackOverflowError("simulated stack exhaustion")
    }
}
