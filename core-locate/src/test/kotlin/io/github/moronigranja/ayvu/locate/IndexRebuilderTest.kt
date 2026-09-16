package io.github.moronigranja.ayvu.locate

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IndexRebuilderTest {
    private fun cached(
        id: String = "book-1",
        title: String = "Title",
        passages: List<CachedPassage> = emptyList(),
    ) = CachedBook(id = id, title = title, passages = passages)

    private fun passage(
        chapter: Int,
        index: Int,
        text: String,
        chapterTitle: String? = "Chapter $chapter",
    ) = CachedPassage(chapterIndex = chapter, chapterTitle = chapterTitle, passageIndex = index, text = text)

    /** The importer's view of a book, flattened the way the store caches it. */
    private fun Book.toCachedBook() =
        CachedBook(
            id = id,
            title = title,
            authors = authors,
            passages =
                chapters.flatMap { chapter ->
                    chapter.passages.mapIndexed { index, p ->
                        CachedPassage(chapter.index, chapter.title, index, p.text)
                    }
                },
        )

    // ------------------------------------------------------------------
    // Rebuild semantics
    // ------------------------------------------------------------------

    @Test
    fun `rebuild populates the index from cached passages`() {
        val book =
            cached(
                id = "abc",
                title = "Anna Karenina",
                passages =
                    listOf(
                        passage(0, 0, "All happy families are alike."),
                        passage(1, 0, "Happy families are all alike; every unhappy family is unhappy in its own way."),
                    ),
            )

        val target = TextIndex()
        IndexRebuilder(target).rebuild(listOf(book))

        val match = target.query("All happy families are alike", minConfidence = 0.6)
        assertNotNull(match)
        assertEquals("abc", match!!.bookId)
        assertEquals("Anna Karenina", match.bookTitle)
        assertEquals(0, match.chapterIndex)
        assertEquals("Chapter 0", match.chapterTitle)
        assertEquals(0, match.passageIndex)
        assertEquals(1.0, match.confidence)
    }

    @Test
    fun `rebuild drops books missing from the cache`() {
        val target = TextIndex()
        val rebuilder = IndexRebuilder(target)
        rebuilder.rebuild(listOf(cached("kept", passages = listOf(passage(0, 0, "kept prose")))))
        rebuilder.rebuild(listOf(cached("gone", passages = listOf(passage(0, 0, "gone prose")))))

        assertEquals(setOf("gone"), target.bookIds())
        assertNull(target.query("kept prose", minConfidence = 0.6))
        assertNotNull(target.query("gone prose", minConfidence = 0.6))
    }

    @Test
    fun `rebuild is idempotent for an unchanged cache`() {
        val target = TextIndex()
        val rebuilder = IndexRebuilder(target)
        val cache = listOf(cached(id = "b", passages = listOf(passage(0, 0, "same prose"))))

        rebuilder.rebuild(cache)
        rebuilder.rebuild(cache)

        assertEquals(1, target.bookCount())
        assertEquals("b", target.query("same prose", minConfidence = 0.6)?.bookId)
    }

    @Test
    fun `empty cache clears the index`() {
        val target = TextIndex()
        val rebuilder = IndexRebuilder(target)
        rebuilder.rebuild(listOf(cached(id = "b", passages = listOf(passage(0, 0, "prose")))))

        rebuilder.rebuild(emptyList())

        assertEquals(0, target.bookCount())
        assertNull(target.query("prose", minConfidence = 0.6))
    }

    @Test
    fun `same id re-add replaces cached content`() {
        val target = TextIndex()
        val rebuilder = IndexRebuilder(target)
        rebuilder.rebuild(listOf(cached(id = "b", passages = listOf(passage(0, 0, "first draft")))))
        rebuilder.rebuild(listOf(cached(id = "b", passages = listOf(passage(0, 0, "second draft")))))

        assertEquals(1, target.bookCount())
        assertNull(target.query("first draft", minConfidence = 0.6))
        assertEquals("b", target.query("second draft", minConfidence = 0.6)?.bookId)
    }

    // ------------------------------------------------------------------
    // Reconstruction from flat rows
    // ------------------------------------------------------------------

    @Test
    fun `reconstruction groups passages into ordered chapters and keeps null titles`() {
        val target = TextIndex()
        IndexRebuilder(target).rebuild(
            listOf(
                cached(
                    title = "Mixed",
                    passages =
                        listOf(
                            passage(2, 0, "chapter two prose", chapterTitle = null),
                            passage(0, 1, "chapter zero second"),
                            passage(0, 0, "chapter zero first"),
                            passage(1, 0, "chapter one prose"),
                        ),
                ),
            ),
        )

        val match = target.query("chapter two prose", minConfidence = 0.6)
        assertNotNull(match)
        // Mixed input order must not leak into the index: chapters are re-ordered by index.
        assertEquals(2, match!!.chapterIndex)
        assertNull(match.chapterTitle)
    }

    @Test
    fun `rebuild from cache equals index after direct import`() {
        val book =
            Book(
                id = "imported",
                title = "Direct",
                authors = listOf("A. Writer"),
                chapters =
                    listOf(
                        Chapter(0, "Intro", listOf(TextPassage("The first passage."), TextPassage("The second."))),
                        Chapter(1, null, listOf(TextPassage("Dénouement — with accents."))),
                    ),
            )

        val direct = TextIndex().also { it.add(book) }
        val rebuilt = TextIndex()
        IndexRebuilder(rebuilt).rebuild(listOf(book.toCachedBook()))

        assertEquals(direct.bookCount(), rebuilt.bookCount())
        val snippet = "first passage"
        val directMatch = direct.query(snippet, minConfidence = 0.6)
        val rebuiltMatch = rebuilt.query(snippet, minConfidence = 0.6)
        assertEquals(directMatch, rebuiltMatch)
        assertEquals(0, rebuiltMatch!!.chapterIndex)
        assertEquals("Intro", rebuiltMatch.chapterTitle)
        assertEquals(0, rebuiltMatch.passageIndex)
    }

    // ------------------------------------------------------------------
    // TextIndex.bookIds
    // ------------------------------------------------------------------

    @Test
    fun `bookIds is empty initially and reflects additions and removals`() {
        val index = TextIndex()
        assertEquals(emptySet<String>(), index.bookIds())

        index.add(Book(id = "a", title = "A"))
        index.add(Book(id = "b", title = "B"))
        assertEquals(setOf("a", "b"), index.bookIds())

        index.remove("a")
        assertEquals(setOf("b"), index.bookIds())
    }
}
