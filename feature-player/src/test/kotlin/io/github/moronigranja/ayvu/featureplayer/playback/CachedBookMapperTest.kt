package io.github.moronigranja.ayvu.featureplayer.playback

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CachedBookMapperTest {
    @Test
    fun `cached rows rebuild the book in spine order with titles and texts`() {
        val cached =
            CachedBook(
                id = "b1",
                title = "Anna",
                passages =
                    listOf(
                        CachedPassage(0, "Happy", 1, "p1"),
                        CachedPassage(0, "Happy", 0, "p0"),
                        CachedPassage(1, null, 0, "q0"),
                        CachedPassage(0, "Happy", 2, "p2"),
                    ),
            )
        val book: Book = cached.toBook()
        assertEquals("b1", book.id)
        assertEquals(2, book.chapters.size)
        val first = book.chapters[0]
        assertEquals(0, first.index)
        assertEquals("Happy", first.title)
        assertEquals(listOf("p0", "p1", "p2"), first.passages.map { it.text })
        val second = book.chapters[1]
        assertEquals(1, second.index)
        assertEquals(null, second.title)
        assertEquals(listOf("q0"), second.passages.map { it.text })
    }

    @Test
    fun `empty passage list yields an empty book`() {
        assertEquals(Book("b1", "Empty"), CachedBook("b1", "Empty").toBook())
    }
}
