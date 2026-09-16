package io.github.moronigranja.ayvu.persistence

import androidx.room.Room
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.model.TextPassage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The [LibraryStore] contract, run against BOTH implementations in one pass: the
 * Room-backed production store ([RoomLibraryStore]) and the in-memory reference
 * the ViewModel/import tests use ([InMemoryLibraryStore]). Every consumer — the
 * list UI ([LibraryStore.books]), the re-import duplicate gate
 * ([LibraryStore.contains]), the launch-time index rebuild (`AyvuApp`
 * hands [LibraryStore.cachedBooks] to `IndexRebuilder.rebuild`) and
 * pre-generation — is written against the interface, so these bodies are what
 * both stores must keep.
 *
 * Assertions are on what a consumer observes: emitted metadata and order, the
 * cached parse as read back, durable membership. Rows, SQL and mapper calls are
 * deliberately not asserted — they are not part of the contract.
 *
 * Fixtures carry distinct `importedAtEpochMillis` values and the emitted order is
 * the import order — asserted independently of the order the adds are issued in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryStoreContractTest {
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    LibraryDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * One pair per test: each implementation gets its own storage, and the same
     * bodies must hold for both. `name` prefixes the assertions that would
     * otherwise not say which store broke.
     */
    private fun TestScope.stores(): List<Pair<String, LibraryStore>> =
        listOf(
            "InMemoryLibraryStore" to InMemoryLibraryStore(),
            "RoomLibraryStore" to RoomLibraryStore(database, backgroundScope),
        )

    private val tolstoy =
        LibraryEntry(
            Book(
                id = "b1",
                title = "Anna Karenina",
                authors = listOf("Leo Tolstoy", "C. Garnett, trans."),
                chapters =
                    listOf(
                        Chapter(
                            0,
                            "Happy Families",
                            listOf(TextPassage("All happy families are alike."), TextPassage("Each unhappy one in its own way.")),
                        ),
                        Chapter(1, null, listOf(TextPassage("Dénouement follows."))),
                    ),
            ),
            importedAtEpochMillis = 1_000,
        )

    private val tolstoyCached =
        CachedBook(
            id = "b1",
            title = "Anna Karenina",
            authors = listOf("Leo Tolstoy", "C. Garnett, trans."),
            passages =
                listOf(
                    CachedPassage(0, "Happy Families", 0, "All happy families are alike."),
                    CachedPassage(0, "Happy Families", 1, "Each unhappy one in its own way."),
                    CachedPassage(1, null, 0, "Dénouement follows."),
                ),
        )

    private val herbert =
        LibraryEntry(
            Book(
                id = "b2",
                title = "Dune",
                authors = listOf("Frank Herbert"),
                chapters =
                    listOf(
                        Chapter(0, "Arrakis", listOf(TextPassage("A beginning is the time for taking the most delicate care."))),
                    ),
            ),
            importedAtEpochMillis = 2_000,
        )

    private val herbertCached =
        CachedBook(
            id = "b2",
            title = "Dune",
            authors = listOf("Frank Herbert"),
            passages = listOf(CachedPassage(0, "Arrakis", 0, "A beginning is the time for taking the most delicate care.")),
        )

    private val joyce =
        LibraryEntry(
            Book(
                id = "b3",
                title = "Ulysses",
                authors = listOf("James Joyce"),
                chapters =
                    listOf(
                        Chapter(0, "Telemachus", listOf(TextPassage("Stately, plump Buck Mulligan came from the stairhead."))),
                    ),
            ),
            importedAtEpochMillis = 3_000,
        )

    private val joyceCached =
        CachedBook(
            id = "b3",
            title = "Ulysses",
            authors = listOf("James Joyce"),
            passages = listOf(CachedPassage(0, "Telemachus", 0, "Stately, plump Buck Mulligan came from the stairhead.")),
        )

    /** A book whose segmentation produced no passages at all. */
    private val notes =
        LibraryEntry(
            Book(id = "b4", title = "Reading notes", authors = listOf("Anna Rosen")),
            importedAtEpochMillis = 4_000,
        )

    private val notesCached =
        CachedBook(id = "b4", title = "Reading notes", authors = listOf("Anna Rosen"), passages = emptyList())

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    @Test
    fun `add makes the book a member and contains rejects unknown ids`() =
        runTest {
            for ((name, store) in stores()) {
                assertFalse(name, store.contains("b1"))

                store.add(tolstoy)

                assertTrue(name, store.contains("b1"))
                assertFalse(name, store.contains("b2"))
            }
        }

    // ------------------------------------------------------------------
    // books: the list UI's surface
    // ------------------------------------------------------------------

    @Test
    fun `books emits id title and authors in import order`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(herbert)

                // The state the two adds produce: [books] here carries metadata
                // only, so title/authors are the whole list-UI payload.
                val emitted = store.books.first { it.size == 2 }

                assertEquals(name, listOf("b1", "b2"), emitted.map { it.book.id })
                assertEquals(name, listOf("Anna Karenina", "Dune"), emitted.map { it.book.title })
                assertEquals(
                    name,
                    listOf(listOf("Leo Tolstoy", "C. Garnett, trans."), listOf("Frank Herbert")),
                    emitted.map { it.book.authors },
                )
            }
        }

    // ------------------------------------------------------------------
    // cachedBooks: the never-re-parse-on-launch surface
    // ------------------------------------------------------------------

    @Test
    fun `cachedBooks returns every book's full parse in spine order`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(herbert)

                assertEquals(name, listOf(tolstoyCached, herbertCached), store.cachedBooks())
            }
        }

    @Test
    fun `cachedBooks keeps a book that carries no passages`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(notes)

                assertEquals(name, listOf(tolstoyCached, notesCached), store.cachedBooks())
            }
        }

    // ------------------------------------------------------------------
    // Re-import and removal
    // ------------------------------------------------------------------

    @Test
    fun `re-adding the same id is idempotent, not a duplicate`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(tolstoy)

                val emitted = store.books.first { it.isNotEmpty() }

                assertEquals(name, listOf("Anna Karenina"), emitted.map { it.book.title })
                assertEquals(name, listOf(tolstoyCached), store.cachedBooks())
            }
        }

    /** The interface's promise is "re-adding the same id REPLACES", so a same-id
     * add of different content must land the new entry and its parse. The two
     * stores used to disagree here — Room replaced, the in-memory reference kept
     * the first entry — a trap for every test that reuses the double. */
    @Test
    fun `re-adding the same id with different content replaces the stored entry`() =
        runTest {
            val revised =
                LibraryEntry(
                    tolstoy.book.copy(title = "Anna Karenina (revised)"),
                    importedAtEpochMillis = 1_500,
                )
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(revised)

                val emitted = store.books.first { it.isNotEmpty() }

                assertEquals(name, 1, emitted.size)
                assertEquals(name, listOf("Anna Karenina (revised)"), emitted.map { it.book.title })
                assertEquals(
                    name,
                    listOf(tolstoyCached.copy(title = "Anna Karenina (revised)")),
                    store.cachedBooks(),
                )
            }
        }

    /** Order is the library's import order — Room derives it from
     * `importedAtEpochMillis`, so it must not depend on the order the adds
     * happened to arrive in (the in-memory reference used plain insertion
     * order; aligned 2026-09-15). */
    @Test
    fun `books emits import order even when the imports arrive out of order`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(notes) // importedAt 4_000
                store.add(tolstoy) // importedAt 1_000
                store.add(herbert) // importedAt 2_000

                val emitted = store.books.first { it.size == 3 }

                assertEquals(name, listOf("b1", "b2", "b4"), emitted.map { it.book.id })
                assertEquals(name, listOf(tolstoyCached, herbertCached, notesCached), store.cachedBooks())
            }
        }

    @Test
    fun `delete drops the book, its membership and its cached parse`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.add(herbert)
                store.books.first { it.size == 2 }

                store.delete("b1")

                assertFalse(name, store.contains("b1"))
                assertTrue(name, store.contains("b2"))

                val remaining = store.books.first { it.size == 1 }
                assertEquals(name, listOf("b2"), remaining.map { it.book.id })
                assertEquals(name, listOf(herbertCached), store.cachedBooks())

                // Imports landing after the delete must not resurrect the
                // removed book's passages, and stand on their own.
                store.add(joyce)
                store.add(notes)

                val afterReimport = store.books.first { it.size == 3 }
                assertEquals(name, listOf("b2", "b3", "b4"), afterReimport.map { it.book.id })
                assertEquals(name, listOf(herbertCached, joyceCached, notesCached), store.cachedBooks())
                assertFalse(name, store.contains("b1"))
            }
        }

    @Test
    fun `deleting an unknown id leaves the library untouched`() =
        runTest {
            for ((name, store) in stores()) {
                store.add(tolstoy)
                store.books.first { it.isNotEmpty() }

                store.delete("b9")

                assertTrue(name, store.contains("b1"))
                assertEquals(name, listOf("b1"), store.books.first { it.isNotEmpty() }.map { it.book.id })
                assertEquals(name, listOf(tolstoyCached), store.cachedBooks())
            }
        }
}
