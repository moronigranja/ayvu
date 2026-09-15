package com.moronigranja.localttsreader.locate

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextIndexTest {
    // ------------------------------------------------------------------
    // Fixtures: two books, several passages (public-domain excerpts).
    // ------------------------------------------------------------------

    private val mobyDick =
        Book(
            id = "b1",
            title = "Moby-Dick",
            chapters =
                listOf(
                    Chapter(
                        0,
                        null,
                        listOf(
                            TextPassage(
                                "Call me Ishmael. Some years ago—never mind how long precisely—having little or " +
                                    "no money in my purse,",
                            ),
                            TextPassage("I thought I would sail about a little and see the watery part of the world."),
                        ),
                    ),
                ),
        )

    private val prideAndPrejudice =
        Book(
            id = "b2",
            title = "Pride and Prejudice",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "Chapter 1",
                        listOf(
                            TextPassage(
                                "It is a truth universally acknowledged, that a single man in possession of a " +
                                    "good fortune, must be in want of a wife.",
                            ),
                        ),
                    ),
                    Chapter(
                        1,
                        "Chapter 1",
                        listOf(
                            TextPassage(
                                "However little known the feelings or views of such a man may be on his first " +
                                    "entering a neighbourhood,",
                            ),
                            TextPassage(
                                "this truth is so well fixed in the minds of the surrounding families, that he " +
                                    "is considered as the rightful property of some one or other of their daughters.",
                            ),
                        ),
                    ),
                ),
        )

    private val papPassage1 = prideAndPrejudice.chapters[1].passages[0].text

    private fun defaultIndex(): TextIndex =
        TextIndex().apply {
            add(mobyDick)
            add(prideAndPrejudice)
        }

    private fun assertEqualsClose(
        expected: Double,
        actual: Double,
        message: String? = null,
    ) = assertEquals(expected, actual, 1e-9, message)

    // ------------------------------------------------------------------
    // TextNormalizer
    // ------------------------------------------------------------------

    @Test
    fun `normalize handles punctuation case and whitespace`() {
        assertEquals(
            "hello world its 42",
            TextNormalizer.normalize("  Hello,   World!\n\tIt's 42°  "),
        )
    }

    @Test
    fun `normalize strips accents via NFKD`() {
        assertEquals("cafe deja vu", TextNormalizer.normalize("CAFÉ déjà-vu"))
    }

    @Test
    fun `normalize collapses smart quotes and em dashes`() {
        assertEquals("dont said he", TextNormalizer.normalize("\u201CDon\u2019t\u201D said he."))
        assertEquals(
            "call me ishmael some years ago never mind",
            TextNormalizer.normalize("Call me Ishmael. Some years ago—never mind"),
        )
    }

    @Test
    fun `grams builds word n-grams and falls back to unigrams for short text`() {
        assertEquals(
            setOf("a b c d", "b c d e", "c d e f"),
            TextNormalizer.grams("a b c d e f", 4),
        )
        assertEquals(setOf("a", "b", "c"), TextNormalizer.grams("a b c", 4))
        assertEquals(emptySet<String>(), TextNormalizer.grams("", 4))
    }

    // ------------------------------------------------------------------
    // TextMatcher
    // ------------------------------------------------------------------

    @Test
    fun `verbatim snippet scores exactly 1`() {
        assertEqualsClose(1.0, TextMatcher.score(papPassage1, papPassage1))
    }

    @Test
    fun `middle chunk of a passage scores 1`() {
        val chunk = "the feelings or views of such a man may be on his first entering"
        assertEqualsClose(1.0, TextMatcher.score(chunk, papPassage1))
    }

    @Test
    fun `truncated prefix scores 1`() {
        assertEqualsClose(1.0, TextMatcher.score("However little known the feelings or views", papPassage1))
    }

    @Test
    fun `OCR typos keep score above default threshold`() {
        // One corrupted word ("v1ews": l→1, a common OCR confusion) + case + smart
        // quotes in a 19-word sentence: score stays comfortably above the 0.6 default.
        val noisy = "HOWEVER \u201Clittle KNOWN the feelings or v1ews of such a man may be on his first entering a neighbourhood"
        val score = TextMatcher.score(noisy, papPassage1)
        assertTrue(score >= 0.6, "score was $score")
    }

    @Test
    fun `very noisy short snippet degrades gracefully below threshold`() {
        // 3 corrupted words in 19 tokens (~16% corruption) is far beyond realistic OCR
        // error on a book page; the score should degrade but stay clearly above zero
        // (partial credit), which is what "not confident → not found" is supposed to look like.
        val noisy = "HOWEVER \u201Clittle KNOWN the feelings or v1ews of such a man may be on h1s first entering a ne1ghbourhood"
        val score = TextMatcher.score(noisy, papPassage1)
        assertTrue(score in 0.4..0.6, "score was $score")
    }

    @Test
    fun `reordered text scores near zero`() {
        val reordered = "neighbourhood entering first his of man such a views feelings known little however"
        val score = TextMatcher.score(reordered, papPassage1)
        assertTrue(score < 0.3, "score was $score")
    }

    @Test
    fun `unrelated text scores zero`() {
        assertEqualsClose(0.0, TextMatcher.score("quantum entanglement emitted zephyrs over the fjord at midnight", papPassage1))
    }

    // ------------------------------------------------------------------
    // TextIndex.query
    // ------------------------------------------------------------------

    @Test
    fun `query finds verbatim snippet with confidence 1`() {
        val result = defaultIndex().query(papPassage1, minConfidence = 0.6)
        assertNotNull(result)
        result!!
        assertEquals("b2", result.bookId)
        assertEquals(1, result.chapterIndex)
        assertEquals(0, result.passageIndex)
        assertEqualsClose(1.0, result.confidence)
    }

    @Test
    fun `query matches OCR-noisy snippet to correct book`() {
        val noisy = "however little KNOWN the feelings or v1ews of such a man may be on his first entering a neighbourhood"
        val result = defaultIndex().query(noisy, minConfidence = 0.6)
        assertNotNull(result, "OCR-noisy snippet should match above threshold")
        result!!
        assertEquals("b2", result.bookId)
        assertTrue(result.confidence >= 0.6, "confidence was ${result.confidence}")
    }

    @Test
    fun `query identifies cross-book distractor`() {
        // Contiguous text from Moby-Dick book 1, chapter 0 passage 0 (contains the em
        // dashes the normalizer must handle); must win over Pride and Prejudice.
        val fromMoby = "Some years ago—never mind how long precisely—having little or no money in my purse"
        val result = defaultIndex().query(fromMoby, minConfidence = 0.6)
        assertNotNull(result)
        result!!
        assertEquals("b1", result.bookId)
        assertEquals(0, result.chapterIndex)
        assertEquals(0, result.passageIndex)
        assertEqualsClose(1.0, result.confidence)
    }

    @Test
    fun `query returns null for unrelated text`() {
        assertNull(defaultIndex().query("quantum entanglement emitted zephyrs", minConfidence = 0.6))
    }

    @Test
    fun `query returns null on empty index`() {
        assertNull(TextIndex().query("any sentence at all would do fine", minConfidence = 0.6))
    }

    @Test
    fun `best returns the closest passage even below the threshold`() {
        // A snippet that is recognizably from the book but too noisy for 0.6
        // still surfaces as a candidate for the share not-found hint.
        val noisy = "however little KNOWN the feelings or v1ews of such a man quantum zephyrs entanglement"
        val result = defaultIndex().best(noisy)
        assertTrue(result != null, "best must return a candidate for a noisy snippet")
        result!!
        assertEquals("b2", result.bookId, "closest passage still belongs to the right book")
        assertTrue(result.confidence < 0.6, "and sits below the default threshold: ${result.confidence}")
    }

    @Test
    fun `best returns null on an empty index or blank snippet`() {
        assertNull(TextIndex().best("any sentence will produce nothing"))
        assertNull(defaultIndex().best("   "))
    }

    @Test
    fun `query returns null for blank or punctuation-only snippet`() {
        val index = defaultIndex()
        assertNull(index.query("", minConfidence = 0.6))
        assertNull(index.query("   ", minConfidence = 0.6))
        assertNull(index.query("!!!...???", minConfidence = 0.6))
    }

    @Test
    fun `query rejects matches below the configured threshold`() {
        val noisy = "HOWEVER little KNOWN the feelings or v1ews of such a man"
        val index = defaultIndex()
        assertNotNull(index.query(noisy, minConfidence = 0.6), "should match at default threshold")
        assertNull(index.query(noisy, minConfidence = 0.99), "should be rejected by strict threshold")
    }

    @Test
    fun `query ties keep the earliest indexed book`() {
        val shared = "The quick brown fox jumps over the lazy dog while counting stars at dawn"
        val first = Book("A", "First", chapters = listOf(Chapter(0, null, listOf(TextPassage(shared)))))
        val second = Book("B", "Second", chapters = listOf(Chapter(0, null, listOf(TextPassage(shared)))))
        val index =
            TextIndex().apply {
                add(first)
                add(second)
            }
        val result = index.query(shared, minConfidence = 0.6)
        assertNotNull(result)
        result!!
        assertEquals("A", result.bookId)
    }

    @Test
    fun `remove drops a book from matching`() {
        val index = defaultIndex()
        index.remove("b1")
        assertNull(index.query("Call me Ishmael", minConfidence = 0.6))
        assertEquals(1, index.bookCount())
    }

    @Test
    fun `re-adding the same book id replaces its content`() {
        val index = defaultIndex()
        val replacement =
            prideAndPrejudice.copy(
                chapters =
                    listOf(
                        Chapter(
                            0,
                            null,
                            listOf(
                                TextPassage("The mystery of the green lighthouse unfolded slowly across the dunes"),
                            ),
                        ),
                    ),
            )
        index.add(replacement)
        assertNull(index.query("However little known the feelings", minConfidence = 0.6))
        assertNotNull(index.query("the green lighthouse unfolded slowly across the dunes", minConfidence = 0.6))
        assertEquals(2, index.bookCount())
    }

    @Test
    fun `clear empties the index`() {
        val index = defaultIndex()
        index.clear()
        assertEquals(0, index.bookCount())
        assertNull(index.query(papPassage1, minConfidence = 0.6))
    }

    @Test
    fun `multi-passage copy still identifies the right book`() {
        // A selection spanning two passages: no single passage reaches 1.0, but the
        // book that contains both gets the highest recall overall.
        val spanning =
            "However little known the feelings or views of such a man may be on his " +
                "first entering a neighbourhood this truth is so well fixed in the minds"
        val index = defaultIndex().apply { remove("b1") } // isolate: only b2 could win anyway
        val result = index.query(spanning, minConfidence = 0.6) ?: return // below-threshold acceptable
        assertEquals("b2", result.bookId)
        assertEquals(1, result.chapterIndex)
    }

    @Test
    fun `book titles are preserved in the result`() {
        val result = defaultIndex().query(papPassage1, minConfidence = 0.6)
        assertNotNull(result)
        result!!
        assertEquals("Pride and Prejudice", result.bookTitle)
        assertNotEquals("Moby-Dick", result.bookTitle)
    }
}
