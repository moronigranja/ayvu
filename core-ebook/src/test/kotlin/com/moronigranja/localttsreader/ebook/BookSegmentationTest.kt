package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookSegmentationTest {
    private fun chapter(
        title: String?,
        vararg texts: String,
    ) = Chapter(0, title, texts.map(::TextPassage))

    private fun book(
        title: String,
        vararg chapters: Chapter,
    ): Book = Book("id-$title", title, chapters = chapters.mapIndexed { i, it -> it.copy(index = i) })

    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    // ------------------------------------------------------------------
    // Front/back matter
    // ------------------------------------------------------------------

    @Test
    fun `front matter chapters are stripped`() {
        val source =
            book(
                "T",
                chapter("Title Page", "A Novel"),
                chapter("Table of Contents", "1. First\n2. Second"),
                chapter("Copyright", "Copyright 2024"),
                chapter("Chapter 1", "Real content one."),
                chapter("Chapter 2", "Real content two."),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapter 1", "Chapter 2"), result.chapters.map { it.title })
    }

    @Test
    fun `back matter chapters are stripped`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "Real content one."),
                chapter("Chapter 2", "Real content two."),
                chapter("The End", "and so the story closes"),
                chapter("About the Author", "Jane Austen wrote books."),
                chapter("Index", "Austen, Jane, 1"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapter 1", "Chapter 2", "The End"), result.chapters.map { it.title })
    }

    @Test
    fun `mid-book chapter named like back matter is kept`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "one"),
                chapter("Chapter 2", "two"),
                chapter("Index", "the novel's index chapter"), // index 2 of 6: not in last 3
                chapter("Chapter 3", "three"),
                chapter("Chapter 4", "four"),
                chapter("Chapter 5", "five"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(6, result.chapters.size)
        assertEquals("Index", result.chapters[2].title)
    }

    @Test
    fun `never strips the whole book`() {
        val source =
            book(
                "T",
                chapter("Title Page", "A Novel"),
                chapter("Copyright", "All rights reserved."),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(2, result.chapters.size) // safety net: original returned
        assertEquals("Title Page", result.chapters[0].title)
    }

    @Test
    fun `a front-matter run longer than the old window is stripped entirely`() {
        // Impulse: Title Page, Copyright Notice, Dedication, Contents — the TOC
        // sits at spine index 3, past any fixed window, and must still go.
        val source =
            book(
                "T",
                chapter("Title Page", "Impulse"),
                chapter("Copyright Notice", "All rights reserved."),
                chapter("Dedication", "For my sisters"),
                chapter("Contents", "1. Millie\n2. Cent\n3. Davy"),
                chapter("1. Millie: The Underlying Problem", "Real first chapter prose."),
                chapter("2. Cent", "More prose."),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(
            listOf("1. Millie: The Underlying Problem", "2. Cent"),
            result.chapters.map { it.title },
        )
        assertEquals(0, result.chapters[0].index)
        assertEquals(1, result.chapters[1].index)
    }

    @Test
    fun `a back-matter run longer than the old window is stripped entirely`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "one"),
                chapter("Chapter 2", "two"),
                chapter("The End", "fin"),
                chapter("About the Author", "bio"),
                chapter("Books by the Author", "list"),
                chapter("Index", "entries"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapter 1", "Chapter 2", "The End"), result.chapters.map { it.title })
    }

    @Test
    fun `kept chapters are renumbered contiguously from zero`() {
        val source =
            book(
                "T",
                chapter("Title Page", "x"),
                chapter("Copyright", "y"),
                chapter("Chapter 1", "real content"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(1, result.chapters.size)
        assertEquals(0, result.chapters[0].index)
    }

    // Passage-level front/back matter (I1)
    // ------------------------------------------------------------------

    @Test
    fun `single chapter front matter passages are stripped`() {
        val source =
            book(
                "T",
                chapter(
                    "Chapter 1",
                    "Cover Page",
                    "Title Page",
                    "Copyright 2026 Someone",
                    "Contents",
                    "Story begins.",
                    "Prose one.",
                    "Prose two.",
                ),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(
            listOf("Story begins.", "Prose one.", "Prose two."),
            result.chapters[0].passages.map { it.text },
        )
    }

    @Test
    fun `single chapter back matter is stripped`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "Story begins.", "Prose one.", "About the Author", "Index"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(
            listOf("Story begins.", "Prose one."),
            result.chapters[0].passages.map { it.text },
        )
    }

    @Test
    fun `middle chapter mentioning index or copyright is NOT stripped`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "one prose."),
                chapter("An Interlude", "Index of a Plant", "edited under copyright law"),
                chapter("Chapter 2", "two prose."),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(3, result.chapters.size)
        assertEquals(
            listOf("Index of a Plant", "edited under copyright law"),
            result.chapters[1].passages.map { it.text }, // middle chapter untouched
        )
    }

    @Test
    fun `single chapter with only furniture stays unchanged`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "Copyright 2026", "Contents"),
            )
        val result = BookSegmentation.segment(source)
        // whole-book guard: strip would empty the chapter, so the original is restored.
        assertEquals(listOf("Copyright 2026", "Contents"), result.chapters[0].passages.map { it.text })
    }

    // Smart chapter detection (I2)
    // ------------------------------------------------------------------

    @Test
    fun `monolith splits on Chapter N`() {
        val source =
            book(
                "T",
                chapter(
                    "Chapter 1",
                    "Chapter 1",
                    "It was a dark and stormy night.",
                    "Chapter 2",
                    "More prose.",
                    "Chapter 3",
                    "Finale.",
                ),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), result.chapters.map { it.title })
        assertEquals(
            listOf(listOf("It was a dark and stormy night."), listOf("More prose."), listOf("Finale.")),
            result.chapters.map { it.passages.map { p -> p.text } },
        )
    }

    @Test
    fun `a book with one chapter heading and prose stays one chapter`() {
        val source =
            book(
                "T",
                chapter(
                    "Chapter 1",
                    "Chapter 1",
                    "It was a dark and stormy night in London, unusually early.",
                    "He stood at the window.",
                    "Outside, rain.",
                ),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(1, result.chapters.size)
    }

    @Test
    fun `roman and name-case headings split`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "CHAPTER II", "a line", "CHAPTER II the Bill", "b"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("CHAPTER II", "CHAPTER II the Bill"), result.chapters.map { it.title })
    }

    @Test
    fun `numeric heading lines split when consistent`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "12. The Mill", "content 1", "13. The second", "content 2"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("12. The Mill", "13. The second"), result.chapters.map { it.title })
    }

    @Test
    fun `mix of chapter-numeral and all-caps headings does not split`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "CHAPTER 2", "x", "THE MILL", "y"),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(1, result.chapters.size)
    }

    @Test
    fun `chapter indexes contiguous after split`() {
        val source =
            book(
                "T",
                chapter("Chapter 1", "Chapter 1", "one.", "Chapter 2", "two.", "Chapter 3", "three."),
            )
        val result = BookSegmentation.segment(source)
        assertEquals(listOf(0, 1, 2), result.chapters.map { it.index })
    }

    @Test
    fun `book of only headings does not divide into empty chapters`() {
        val source = book("T", chapter("Chapter 1", "Chapter 1", "Chapter 2"))
        val result = BookSegmentation.segment(source)
        assertEquals(1, result.chapters.size)
    }

    @Test
    fun `en headings split`() {
        val source = book("T", chapter("Chapter 1", "Chapter 1", "prose a.", "Chapter 2", "prose b."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapter 1", "Chapter 2"), result.chapters.map { it.title })
    }

    @Test
    fun `fr headings split`() {
        val source = book("T", chapter("Chapter 1", "Chapitre 1", "texte.", "Chapitre 2", "texte."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Chapitre 1", "Chapitre 2"), result.chapters.map { it.title })
    }

    @Test
    fun `es headings split`() {
        val source = book("T", chapter("Chapter 1", "Capítulo 1", "texto.", "Capítulo 2", "texto."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), result.chapters.map { it.title })
    }

    @Test
    fun `pt headings split`() {
        val source = book("T", chapter("Chapter 1", "Livro 1", "texto.", "Livro 2", "texto."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Livro 1", "Livro 2"), result.chapters.map { it.title })
    }

    @Test
    fun `it headings split`() {
        val source = book("T", chapter("Chapter 1", "Parte Prima", "testo.", "Parte Seconda", "testo."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("Parte Prima", "Parte Seconda"), result.chapters.map { it.title })
    }

    @Test
    fun `ja headings split`() {
        val source = book("T", chapter("Chapter 1", "第1話", "本文.", "第2話", "本文."))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("第1話", "第2話"), result.chapters.map { it.title })
    }

    @Test
    fun `zh-cmn headings split`() {
        val source = book("T", chapter("Chapter 1", "第一节", "正文。", "第二节", "正文。"))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("第一节", "第二节"), result.chapters.map { it.title })
    }

    @Test
    fun `hi headings split`() {
        val source = book("T", chapter("Chapter 1", "अध्याय १", "पाठ।", "अध्याय २", "पाठ।"))
        val result = BookSegmentation.segment(source)
        assertEquals(listOf("अध्याय १", "अध्याय २"), result.chapters.map { it.title })
    }

    // ------------------------------------------------------------------
    // Long-passage splitting (the grain)
    // ------------------------------------------------------------------

    @Test
    fun `long passages split at sentence boundaries`() {
        val longText = (1..60).joinToString(" ") { "Sentence number $it of the story continues here." }
        assertTrue(words(longText) > 400) // 60 x 8 words = 480
        val segments = BookSegmentation.splitLongPassages(listOf(TextPassage(longText)), 100)
        assertTrue(segments.size > 3, "expected several chunks, got ${segments.size}")
        val joined = segments.joinToString(" ") { it.text }
        assertEquals(longText, joined) // lossless: chunks reassemble to the original
        assertTrue(segments.all { words(it.text) <= 110 }, "chunks must respect the soft cap")
    }

    @Test
    fun `abbreviations do not split sentences`() {
        val tricky = "Dr. Watson and Mr. Holmes spoke. e.g. evidence was thin. It rained."
        val segments = BookSegmentation.splitLongPassages(listOf(TextPassage(tricky)), 10)
        assertEquals(2, segments.size) // "Dr." / "Mr." / "e.g." are not boundaries
    }

    @Test
    fun `run-on text without sentence boundaries stays whole`() {
        val runOn = (1..200).joinToString(" ") { "word$it" }
        val segments = BookSegmentation.splitLongPassages(listOf(TextPassage(runOn)), 50)
        assertEquals(1, segments.size) // no mid-sentence cuts
        assertEquals(runOn, segments[0].text)
    }

    @Test
    fun `short passages are untouched`() {
        val passages = listOf(TextPassage("Short."), TextPassage("Also short."))
        val result = BookSegmentation.splitLongPassages(passages, 100)
        assertEquals(passages, result)
    }

    @Test
    fun `non-positive limits disable splitting`() {
        val longText = (1..50).joinToString(" ") { "Sentence number $it here." }
        val result = BookSegmentation.splitLongPassages(listOf(TextPassage(longText)), 0)
        assertEquals(1, result.size)
    }

    @Test
    fun `sentence counting handles whitespace`() {
        assertEquals(0, BookSegmentation.wordCount("   "))
        assertEquals(3, BookSegmentation.wordCount("a b c"))
    }
}
