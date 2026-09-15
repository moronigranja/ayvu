package com.moronigranja.localttsreader.persistence

import androidx.room.Room
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.model.CachedPassage
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** DAO + store round-trips against a real in-memory SQLite (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLibraryStoreTest {
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

    private fun entry(
        id: String,
        title: String = "Title",
        authors: List<String> = emptyList(),
        chapters: List<Chapter> = emptyList(),
        importedAt: Long = 1_000,
    ) = LibraryEntry(Book(id, title, authors, chapters), importedAt)

    private val importedBook =
        entry(
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
            importedAt = 2_000,
        )

    // ------------------------------------------------------------------
    // Store contract
    // ------------------------------------------------------------------

    @Test
    fun `add persists the book and its cached parse`() =
        runTest {
            val store = RoomLibraryStore(database, backgroundScope)

            store.add(importedBook)

            assertEquals(
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
                ),
                store.cachedBooks().single(),
            )
        }

    @Test
    fun `books emits library entries in import order`() =
        runTest {
            val store = RoomLibraryStore(database, backgroundScope)
            store.add(entry(id = "a", importedAt = 1_000))
            store.add(entry(id = "b", importedAt = 2_000))

            val ids = store.books.first { it.isNotEmpty() }.map { it.book.id }
            assertEquals(listOf("a", "b"), ids)
        }

    @Test
    fun `re-adding the same id replaces without duplicating rows`() =
        runTest {
            val store = RoomLibraryStore(database, backgroundScope)

            store.add(importedBook)
            store.add(importedBook)
            store.add(entry(id = "b1", title = "Anna Karenina (revised)", importedAt = 3_000))

            val books = store.books.first { it.isNotEmpty() }
            assertEquals(1, books.size)
            assertEquals("Anna Karenina (revised)", books.single().book.title)
            // The third add carried no passages: the cached parse follows the last add.
            assertTrue(
                store
                    .cachedBooks()
                    .single()
                    .passages
                    .isEmpty(),
            )
        }

    @Test
    fun `deleting a book cascades to its cached passages`() =
        runTest {
            val store = RoomLibraryStore(database, backgroundScope)
            store.add(importedBook)

            database.bookDao().delete("b1")

            assertTrue(store.books.first().isEmpty())
            assertTrue(store.cachedBooks().isEmpty())
            assertTrue(database.passageDao().all().isEmpty())
            assertEquals(emptyList<CachedPassage>(), database.passageDao().forBook("b1"))
        }

    // ------------------------------------------------------------------
    // DAO round-trips
    // ------------------------------------------------------------------

    @Test
    fun `progress upsert and get round-trip per book`() =
        runTest {
            val dao = database.progressDao()
            assertNull(dao.get("b1"))

            dao.upsert(ProgressEntity("b1", chapterIndex = 2, passageIndex = 3, updatedAtEpochMillis = 100))
            assertEquals(
                ProgressEntity("b1", chapterIndex = 2, passageIndex = 3, updatedAtEpochMillis = 100),
                dao.get("b1"),
            )

            dao.upsert(ProgressEntity("b1", chapterIndex = 2, passageIndex = 4, updatedAtEpochMillis = 200))
            assertEquals(4, dao.get("b1")!!.passageIndex)
        }

    @Test
    fun `settings store falls back to the default threshold when unset`() =
        runTest {
            val settings = SettingsStore(database.settingsDao())

            assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.matchThreshold(), 0.0)

            settings.setMatchThreshold(0.75)
            assertEquals(0.75, settings.matchThreshold(), 0.0)
        }

    @Test
    fun `settings store treats a corrupt stored value as missing`() =
        runTest {
            val settings = SettingsStore(database.settingsDao())
            database.settingsDao().put(SettingEntity(SettingsStore.KEY_MATCH_THRESHOLD, "not-a-number"))

            assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.matchThreshold(), 0.0)
        }

    @Test
    fun `settings store rejects out-of-range thresholds`() =
        runTest {
            val settings = SettingsStore(database.settingsDao())

            var thrown: Throwable? = null
            try {
                settings.setMatchThreshold(1.5)
            } catch (expected: IllegalArgumentException) {
                thrown = expected
            }
            assertNotNull(thrown)
            assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.matchThreshold(), 0.0)
        }

    // ------------------------------------------------------------------
    // Mappers
    // ------------------------------------------------------------------

    @Test
    fun `authors encode and decode round-trip edge cases`() {
        assertEquals("", encodeAuthors(emptyList()))
        assertEquals(emptyList<String>(), decodeAuthors(""))
        assertEquals(
            listOf("O'Brien", "Io, 神"),
            decodeAuthors(encodeAuthors(listOf("O'Brien", "Io, 神"))),
        )
    }
}
