package io.github.moronigranja.ayvu.player.pregen

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Torrent-bar coverage encoding (coverage-bar redo, step 1): char-weighted
 * run-length spans over the spine and deduplicated chapter ticks.
 */
class CoverageEncoderTest {
    private fun book(vararg chapters: List<Int>) =
        Book(
            id = "b1",
            title = "t",
            chapters =
                chapters.mapIndexed { chapterIndex, lengths ->
                    Chapter(
                        index = chapterIndex,
                        title = "ch$chapterIndex",
                        passages = lengths.map { TextPassage("x".repeat(it)) },
                    )
                },
        )

    private fun key(
        bookId: String,
        chapter: Int,
        passage: Int,
        voice: String = "af_heart",
        speed: Double = 1.0,
    ) = PregenKey(bookId, chapter, passage, voice, speed, engine = PregenKey.DEFAULT_ENGINE)

    @Test
    fun `spans are char-weighted runs of generated vs not`() {
        // 100/200/300/400 chars = 1000 total; passages 0-1 generated.
        val b = book(listOf(100, 200, 300, 400))
        val generated =
            setOf(
                key("b1", 0, 0),
                key("b1", 0, 1),
            )

        assertEquals(
            listOf(
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(0.3f, true),
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(1.0f, false),
            ),
            CoverageEncoder.spans(b, generated),
            "first span ends at 300/1000; the rest is not generated",
        )
    }

    @Test
    fun `a fully generated book yields one span ending at 1`() {
        val b = book(listOf(100, 200))
        val generated = setOf(key("b1", 0, 0), key("b1", 0, 1))

        assertEquals(
            listOf(
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(1.0f, true),
            ),
            CoverageEncoder.spans(b, generated),
        )
    }

    @Test
    fun `keys from another book and non-spine passages are ignored`() {
        val b = book(listOf(100, 200))
        val generated =
            setOf(
                key("b-other", 0, 0), // another book
                PregenKey("b1", 0, 9, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), // past the spine end
            )

        assertEquals(
            listOf(
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(1.0f, false),
            ),
            CoverageEncoder.spans(b, generated),
            "foreign keys never paint a span",
        )
    }

    @Test
    fun `an empty book yields empty spans`() {
        assertEquals(emptyList<io.github.moronigranja.ayvu.player.CoverageSpan>(), CoverageEncoder.spans(book(), emptySet()))
        assertEquals(emptyList<Float>(), CoverageEncoder.chapterMarks(book()))
    }

    @Test
    fun `chapterMarks deduplicate empty chapters and omit the first mark`() {
        // Chapters: 100 chars, empty, 100 chars → only the 1/2 mark remains.
        val b = book(listOf(100), emptyList(), listOf(100))

        assertEquals(listOf(0.5f), CoverageEncoder.chapterMarks(b))
    }

    @Test
    fun `chapterMarks cover every non-empty chapter boundary in order`() {
        // 3 non-empty chapters, weights 200/300/500: marks at 200/1000 and 500/1000.
        val b = book(listOf(200), listOf(300), listOf(500))

        assertEquals(listOf(0.2f, 0.5f), CoverageEncoder.chapterMarks(b))
    }

    @Test
    fun `zero-length passages never break or add spans`() {
        val b = book(listOf(50, 0, 50))
        val generated = setOf(key("b1", 0, 0))

        assertEquals(
            listOf(
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(0.5f, true),
                io.github.moronigranja.ayvu.player
                    .CoverageSpan(1.0f, false),
            ),
            CoverageEncoder.spans(b, generated),
        )
        assertTrue(b.chapters[0].passages.size == 3, "guard: the empty passage is in the spine")
    }
}
