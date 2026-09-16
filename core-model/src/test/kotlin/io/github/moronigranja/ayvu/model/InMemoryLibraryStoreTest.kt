package io.github.moronigranja.ayvu.model

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InMemoryLibraryStore] is the reference implementation every store-agnostic
 * consumer is tested against (the ViewModel contract suite, the import
 * coordinator), so its semantics are pinned here — including the ordering rule
 * the Room store forced: the observable library is ordered by
 * `importedAtEpochMillis`, NOT by insertion, which is the divergence the contract
 * suite found on 2026-09-15.
 */
class InMemoryLibraryStoreTest {
    private fun entry(
        id: String,
        importedAt: Long,
    ) = LibraryEntry(
        book =
            Book(
                id = id,
                title = "Title $id",
                chapters =
                    listOf(
                        Chapter(index = 0, title = "One", passages = listOf(TextPassage("first"))),
                        Chapter(index = 1, title = null, passages = listOf(TextPassage("second"))),
                    ),
            ),
        importedAtEpochMillis = importedAt,
    )

    @Test
    fun `books observe import order, not insertion order`() =
        runBlocking {
            val store = InMemoryLibraryStore()
            store.add(entry("later", importedAt = 200))
            store.add(entry("earlier", importedAt = 100))
            assertEquals(listOf("earlier", "later"), store.books.value.map { it.book.id })
        }

    @Test
    fun `re-adding an id replaces the entry instead of duplicating it`() =
        runBlocking {
            val store = InMemoryLibraryStore()
            store.add(entry("a", importedAt = 1))
            store.add(entry("a", importedAt = 2))
            assertEquals(1, store.books.value.size)
            assertEquals(
                2L,
                store.books.value
                    .single()
                    .importedAtEpochMillis,
            )
        }

    @Test
    fun `contains is the durable membership gate`() =
        runBlocking {
            val store = InMemoryLibraryStore()
            assertFalse(store.contains("a"))
            store.add(entry("a", importedAt = 1))
            assertTrue(store.contains("a"))
        }

    @Test
    fun `delete removes the book and is a no-op for an unknown id`() =
        runBlocking {
            val store = InMemoryLibraryStore()
            store.add(entry("a", importedAt = 1))
            store.delete("missing")
            assertEquals(listOf("a"), store.books.value.map { it.book.id })
            store.delete("a")
            assertTrue(store.books.value.isEmpty())
        }

    @Test
    fun `cachedBooks flattens chapters into spine-ordered passage rows`() =
        runBlocking {
            val store = InMemoryLibraryStore()
            store.add(entry("a", importedAt = 1))
            val cached = store.cachedBooks().single()
            assertEquals("a", cached.id)
            assertEquals(listOf("first", "second"), cached.passages.map { it.text })
            assertEquals(listOf(0 to "One", 1 to null), cached.passages.map { it.chapterIndex to it.chapterTitle })
            assertEquals(listOf(0, 0), cached.passages.map { it.passageIndex })
        }
}
