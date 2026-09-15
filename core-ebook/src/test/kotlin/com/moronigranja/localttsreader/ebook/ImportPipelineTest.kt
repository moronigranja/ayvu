package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.ebook.EpubFixture.CONTAINER
import com.moronigranja.localttsreader.ebook.EpubFixture.chapterHtml
import com.moronigranja.localttsreader.ebook.EpubFixture.ncx
import com.moronigranja.localttsreader.ebook.EpubFixture.opf
import com.moronigranja.localttsreader.ebook.EpubFixture.zip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The C4 index contract end to end: parse a book with front matter and a very long
 * paragraph → segment → the book handed to TextIndex has only content chapters with
 * bounded passages.
 */
class ImportPipelineTest {
    @Test
    fun `parse then segment produces an index-ready book`() {
        val longChapter = (1..50).joinToString(" ") { "Sentence $it of a genuinely long opening paragraph continues." }
        val epub =
            zip(
                "META-INF/container.xml" to CONTAINER,
                "OEBPS/content.opf" to
                    opf(
                        title = "The Long Book",
                        spine = listOf("f0" to "title.xhtml", "f1" to "c1.xhtml", "f2" to "c2.xhtml"),
                        ncxHref = "toc.ncx",
                    ),
                "OEBPS/toc.ncx" to
                    ncx(
                        listOf(
                            "title.xhtml" to "Title Page",
                            "c1.xhtml" to "Chapter 1",
                            "c2.xhtml" to "Chapter 2",
                        ),
                    ),
                "OEBPS/title.xhtml" to chapterHtml(null, listOf("A Novel by Someone")),
                "OEBPS/c1.xhtml" to chapterHtml(null, listOf(longChapter)),
                "OEBPS/c2.xhtml" to chapterHtml(null, listOf("Much shorter second chapter.")),
            )

        val parsed = EpubParser.parse(epub)
        val ready = BookSegmentation.segment(parsed)

        assertEquals(listOf("Chapter 1", "Chapter 2"), ready.chapters.map { it.title })
        assertEquals(0, ready.chapters[0].index)
        assertTrue(
            ready.chapters[0].passages.size > 1,
            "long paragraph must be split (was ${ready.chapters[0].passages.size})",
        )
        assertTrue(ready.chapters[0].passages.all { BookSegmentation.wordCount(it.text) <= 110 })
        assertEquals(
            parsed.chapters[1].passages.joinToString(" ") { it.text },
            ready.chapters[0].passages.joinToString(" ") { it.text }, // lossless
        )
    }
}
