package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.EpubFixture.CONTAINER
import io.github.moronigranja.ayvu.ebook.EpubFixture.chapterHtml
import io.github.moronigranja.ayvu.ebook.EpubFixture.navDoc
import io.github.moronigranja.ayvu.ebook.EpubFixture.ncx
import io.github.moronigranja.ayvu.ebook.EpubFixture.opf
import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.ebook.EpubFixture.zipBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class EpubParserTest {
    private val threeChapterSpine = listOf("c1" to "chap1.xhtml", "c2" to "chap2.xhtml", "c3" to "chap3.xhtml")

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    fun `parses epub2 with ncx chapter titles`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to
                    opf(
                        title = "Pride and Prejudice",
                        authors = listOf("Jane Austen"),
                        spine = threeChapterSpine,
                        ncxHref = "toc.ncx",
                    ),
                "OEBPS/toc.ncx" to
                    ncx(
                        listOf("chap1.xhtml" to "Chapter 1", "chap2.xhtml" to "Chapter 2", "chap3.xhtml" to "Chapter 3"),
                    ),
                "OEBPS/chap1.xhtml" to
                    chapterHtml(
                        null,
                        listOf(
                            "It is a truth universally acknowledged&nbsp;&mdash; that a single man in possession of a " +
                                "good fortune, must be in want of a wife.",
                            "However little known the feelings or views of such a man",
                        ),
                    ),
                "OEBPS/chap2.xhtml" to chapterHtml("A Heading", listOf("Second chapter paragraph &amp; more.")),
                "OEBPS/chap3.xhtml" to chapterHtml(null, listOf("Third chapter, &#8212; em dash numeric.")),
            )

        val book = EpubParser.parse(epub)

        assertEquals("Pride and Prejudice", book.title)
        assertEquals(listOf("Jane Austen"), book.authors)
        assertEquals(3, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title)
        assertEquals(2, book.chapters[0].passages.size)
        assertEquals(
            "It is a truth universally acknowledged\u00A0— that a single man in possession of a good " +
                "fortune, must be in want of a wife.",
            book.chapters[0].passages[0].text,
        )
        assertEquals("Chapter 2", book.chapters[1].title) // ncx label wins over the in-document heading
        assertEquals(2, book.chapters[1].passages.size)
        assertEquals("A Heading", book.chapters[1].passages[0].text) // headings are content too
        assertEquals("Second chapter paragraph & more.", book.chapters[1].passages[1].text)
        assertEquals("Chapter 3", book.chapters[2].title)
        assertEquals("Third chapter, — em dash numeric.", book.chapters[2].passages[0].text)
        assertEquals(0, book.chapters[0].index)
        assertEquals(2, book.chapters[2].index)
    }

    @Test
    fun `parses epub3 with nav chapter titles`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to
                    opf(
                        title = "Moby-Dick",
                        spine = threeChapterSpine,
                        navHref = "nav.xhtml",
                    ),
                "OEBPS/nav.xhtml" to
                    navDoc(
                        listOf("chap1.xhtml" to "Loomings", "chap2.xhtml" to "The Carpet-Bag", "chap3.xhtml" to "The Spouter-Inn"),
                    ),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Call me Ishmael.")),
                "OEBPS/chap2.xhtml" to chapterHtml(null, listOf("Some years ago.")),
                "OEBPS/chap3.xhtml" to chapterHtml(null, listOf("Entering that gable-ended Spouter-Inn.")),
            )

        val book = EpubParser.parse(epub)

        assertEquals("Moby-Dick", book.title)
        assertEquals(listOf("Loomings", "The Carpet-Bag", "The Spouter-Inn"), book.chapters.map { it.title })
        assertEquals("Call me Ishmael.", book.chapters[0].passages[0].text)
    }

    @Test
    fun `falls back to the first heading when no toc exists`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "Untitled Fixture", spine = threeChapterSpine),
                "OEBPS/chap1.xhtml" to chapterHtml("Chapter One Heading", listOf("Some text.")),
                "OEBPS/chap2.xhtml" to chapterHtml(null, listOf("More text.")),
                "OEBPS/chap3.xhtml" to chapterHtml(null, listOf("Even more text.")),
            )

        val book = EpubParser.parse(epub)

        assertEquals("Chapter One Heading", book.chapters[0].title)
        assertNull(book.chapters[1].title)
        assertNull(book.chapters[2].title)
    }

    @Test
    fun `book id is a stable content hash`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "A", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        val first = EpubParser.parse(epub)
        val second = EpubParser.parse(epub)
        assertEquals(first.id, second.id)
        assertEquals(64, first.id.length)
        assertTrue(first.id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `title falls back to the file name when the opf declares none`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        val book = EpubParser.parse(EBookSource("The Great Novel.epub") { ByteArrayInputStream(epub) })
        assertEquals("The Great Novel", book.title)
    }

    // ------------------------------------------------------------------
    // Defensive: structurally broken containers fail cleanly
    // ------------------------------------------------------------------

    @Test
    fun `missing container xml throws`() {
        val epub =
            zip(
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        assertThrows(EBookParseException::class.java) { EpubParser.parse(epub) }
    }

    @Test
    fun `container pointing at a missing opf throws`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        assertThrows(EBookParseException::class.java) { EpubParser.parse(epub) }
    }

    @Test
    fun `empty spine throws`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X"),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        assertThrows(EBookParseException::class.java) { EpubParser.parse(epub) }
    }

    @Test
    fun `garbage bytes throw instead of crashing`() {
        assertThrows(EBookParseException::class.java) {
            EpubParser.parse("this is definitely not a zip archive".toByteArray())
        }
    }

    @Test
    fun `doctype entities in the container are never expanded`() {
        // Container full-path is regex-extracted (no XML parse on that path —
        // Android DOM and host Xerces disagree on doctype files), so the
        // entity declaration is inert: nothing fetched, nothing expanded.
        val evilContainer =
            """<!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
        val epub =
            zip(
                "META-INF/container.xml" to evilContainer,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        val book = EpubParser.parse(epub)
        assertEquals("X", book.title)
        assertEquals(1, book.chapters.size)
    }

    // ------------------------------------------------------------------
    // Defensive: sloppy real-world content is tolerated
    // ------------------------------------------------------------------

    @Test
    fun `malformed xhtml is still extracted leniently`() {
        val malformedChapter =
            """<?xml version="1.0"?><html><body><p>First &nbsp; paragraph<p>Second paragraph</body></html>"""
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to malformedChapter,
            )
        val book = EpubParser.parse(epub)
        assertEquals(
            listOf("First \u00A0 paragraph", "Second paragraph"),
            book.chapters[0].passages.map { it.text },
        )
    }
    // ------------------------------------------------------------------
    // Covers (library polish, 2026-08-27)
    // ------------------------------------------------------------------

    @Test
    fun `epub2 meta cover is extracted`() {
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 7, 8, 9)
        val epub =
            zipBytes(
                "META-INF/container.xml" to CONTAINER.toByteArray(),
                "OEBPS/content.opf" to
                    opf(
                        title = "X",
                        spine = listOf("c1" to "chap1.xhtml"),
                        coverItem = "cover-id" to "cover.jpg",
                    ).toByteArray(),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")).toByteArray(),
                "OEBPS/cover.jpg" to cover,
            )
        assertEquals(cover.toList(), EpubParser.coverOf(epub)?.toList())
    }

    @Test
    fun `epub3 cover-image property is extracted`() {
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val epub =
            zipBytes(
                "META-INF/container.xml" to CONTAINER.toByteArray(),
                "OEBPS/content.opf" to
                    opf(
                        title = "X",
                        spine = listOf("c1" to "chap1.xhtml"),
                        epub3CoverHref = "cover.jpg",
                    ).toByteArray(),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")).toByteArray(),
                "OEBPS/cover.jpg" to cover,
            )
        assertEquals(cover.toList(), EpubParser.coverOf(epub)?.toList())
    }

    @Test
    fun `no cover yields null without throwing`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Hello.")),
            )
        assertNull(EpubParser.coverOf(epub))
    }

    @Test
    fun `head style and script content never leaks into passages`() {
        val leakyChapter =
            """<html><head><title>Book Title</title><style>p { color: red }</style></head>
<body><p>Real prose.</p><script>alert('xss')</script><p>More prose.</p></body></html>"""
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to leakyChapter,
            )
        val book = EpubParser.parse(epub)
        val texts = book.chapters[0].passages.map { it.text }
        assertEquals(listOf("Real prose.", "More prose."), texts)
        assertFalse(texts.any { "Book Title" in it || "color" in it || "alert" in it })
    }

    @Test
    fun `missing spine files are skipped not fatal`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to
                    opf(
                        title = "X",
                        spine = threeChapterSpine,
                        ncxHref = "toc.ncx",
                    ),
                "OEBPS/toc.ncx" to
                    ncx(
                        listOf("chap1.xhtml" to "Chapter 1", "chap2.xhtml" to "Chapter 2", "chap3.xhtml" to "Chapter 3"),
                    ),
                "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("One.")),
                "OEBPS/chap3.xhtml" to chapterHtml(null, listOf("Three.")),
                // chap2.xhtml intentionally missing
            )
        val book = EpubParser.parse(epub)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title)
        assertEquals("Chapter 3", book.chapters[1].title)
        assertEquals(0, book.chapters[0].index)
        assertEquals(1, book.chapters[1].index)
    }

    @Test
    fun `paragraph markup spanning lines stays one passage`() {
        val spanning =
            "<html><body><p>First line\nsecond line\tcontinued</p><p>Next paragraph.</p></body></html>"
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to spanning,
            )
        val book = EpubParser.parse(epub)
        assertEquals(
            listOf("First line second line continued", "Next paragraph."),
            book.chapters[0].passages.map { it.text },
        )
    }

    @Test
    fun `book with no readable chapters throws`() {
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to opf(title = "X", spine = listOf("c1" to "chap1.xhtml")),
                "OEBPS/chap1.xhtml" to "<html><body><p>   </p><p></p></body></html>",
            )
        assertThrows(EBookParseException::class.java) { EpubParser.parse(epub) }
    }
}
