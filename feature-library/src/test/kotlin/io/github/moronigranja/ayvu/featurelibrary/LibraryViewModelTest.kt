package io.github.moronigranja.ayvu.featurelibrary

import io.github.moronigranja.ayvu.ebook.BookImporter
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.ebook.EpubFixture.CONTAINER
import io.github.moronigranja.ayvu.ebook.EpubFixture.chapterHtml
import io.github.moronigranja.ayvu.ebook.EpubFixture.ncx
import io.github.moronigranja.ayvu.ebook.EpubFixture.opf
import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.ebook.ImportCoordinator
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.player.PlayerCommands
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    // Main is virtual for every test; viewModelScope work runs eagerly on the
    // test thread (no real threads/time → fully deterministic).
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    // ------------------------------------------------------------------
    // Fixture helpers (EpubFixture comes from core-ebook's testFixtures)
    // ------------------------------------------------------------------

    private fun epubBook(
        title: String,
        chapterTitle: String,
        body: String,
    ): ByteArray =
        zip(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to
                opf(
                    title = title,
                    spine = listOf("f0" to "title.xhtml", "c1" to "chap1.xhtml"),
                    ncxHref = "toc.ncx",
                ),
            "OEBPS/toc.ncx" to
                ncx(
                    listOf("title.xhtml" to "Title Page", "chap1.xhtml" to chapterTitle),
                ),
            "OEBPS/title.xhtml" to chapterHtml(null, listOf("A Novel by Someone")),
            "OEBPS/chap1.xhtml" to chapterHtml(null, listOf(body)),
        )

    private fun source(
        name: String,
        bytes: ByteArray,
    ): EBookSource = EBookSource(name) { ByteArrayInputStream(bytes) }

    /** A6: tests supply a no-op command surface (the app binds the sender). */
    private val noopCommands =
        object : PlayerCommands {
            override fun play(bookId: String) = Unit

            override fun playAt(
                bookId: String,
                chapterIndex: Int,
                passageIndex: Int,
            ) = Unit

            override fun changeVoice(voice: String) = Unit

            override fun resume() = Unit

            override fun pause() = Unit

            override fun stop() = Unit

            override fun seekForward() = Unit

            override fun seekBackward() = Unit
        }

    private fun viewModel(
        store: io.github.moronigranja.ayvu.model.LibraryStore = InMemoryLibraryStore(),
        index: TextIndex = TextIndex(),
        indexLock: io.github.moronigranja.ayvu.locate.IndexLock =
            io.github.moronigranja.ayvu.locate
                .IndexLock(),
    ) = LibraryViewModel(
        repository = store,
        coordinator = ImportCoordinator(BookImporter(), store, index, indexLock),
        ioDispatcher = mainDispatcherRule.testDispatcher,
        indexLock = indexLock,
        index = index,
        commands = noopCommands,
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `importing a valid epub adds a library entry and lands on Done`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()

            vm.import(listOf(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here."))))
            testScheduler.advanceUntilIdle() // F1: the batch parks on its 1 ms file boundary

            assertEquals(1, vm.library.value.size)
            assertEquals(
                "Novel",
                vm.library.value
                    .first()
                    .book.title,
            )
            assertEquals(
                listOf("Chapter 1"),
                vm.library.value
                    .first()
                    .book.chapters
                    .map { it.title },
            )
            assertEquals(
                ImportUiState.Done(ImportUiState.Summary(added = 1, unchanged = 0, failed = emptyList())),
                vm.importState.value,
            )
        }

    @Test
    fun `re-importing identical bytes is idempotent through the repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()
            val sameBook = source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here."))

            vm.import(listOf(sameBook))
            testScheduler.advanceUntilIdle() // F1: batch 1 parks on its 1 ms boundary — let it land
            vm.import(listOf(sameBook))
            testScheduler.advanceUntilIdle() // then batch 2 sees the indexed copy → Unchanged

            assertEquals(1, vm.library.value.size)
            assertEquals(
                ImportUiState.Done(ImportUiState.Summary(added = 0, unchanged = 1, failed = emptyList())),
                vm.importState.value,
            )
        }

    @Test
    fun `unsupported format fails with a typed message and leaves the library untouched`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()

            vm.import(listOf(source("notes.rtf", "{\rtf1 hello}".toByteArray())))
            testScheduler.advanceUntilIdle() // F1: the batch parks on its 1 ms file boundary

            val done = vm.importState.value as ImportUiState.Done
            assertEquals(0, done.summary.added)
            assertEquals(0, done.summary.unchanged)
            assertEquals(listOf("notes.rtf" to "format not supported"), done.summary.failed)
            assertTrue(vm.library.value.isEmpty())
        }

    @Test
    fun `corrupted epub fails with a parse error and leaves the library untouched`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()

            vm.import(listOf(source("broken.epub", "not a zip".toByteArray())))
            testScheduler.advanceUntilIdle() // F1: the batch parks on its 1 ms file boundary

            val done = vm.importState.value as ImportUiState.Done
            assertEquals(0, done.summary.added)
            assertEquals(1, done.summary.failed.size)
            assertEquals(
                "broken.epub",
                done.summary.failed
                    .first()
                    .first,
            )
            assertTrue(
                done.summary.failed
                    .first()
                    .second
                    .isNotBlank(),
                "parse error should carry a message",
            )
            assertTrue(vm.library.value.isEmpty())
        }

    @Test
    fun `importAll reports per-file progress and lands on Done`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // The exact onProgress callback sequence is deterministic to assert
            // synchronously at the importer level — the same call the ViewModel
            // makes with its progress wiring. F1 adds a pre-parse event per file.
            val progress = mutableListOf<Triple<String, Int, Int>>()
            val store = InMemoryLibraryStore()
            ImportCoordinator(
                BookImporter(),
                store,
                TextIndex(),
                io.github.moronigranja.ayvu.locate
                    .IndexLock(),
            ).importAll(
                listOf(
                    source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                    source("book.txt", "nope".toByteArray()),
                ),
                onProgress = { current, done, total -> progress += Triple(current.fileName, done, total) },
            )
            assertEquals(
                listOf(
                    Triple("Novel.epub", 0, 2),
                    Triple("Novel.epub", 1, 2),
                    Triple("book.txt", 1, 2),
                    Triple("book.txt", 2, 2),
                ),
                progress,
            )

            // ViewModel wiring: Idle → Importing(0,total) immediately → per-file
            // Importing → Done. Importing(1,2) specifically is not asserted:
            // StateFlow conflates intermediate values when the emitter never
            // suspends, so only the last per-file progress + the F1 start state
            // are guaranteed observable — a UI sees the same.
            val vm = viewModel()
            val states = mutableListOf<ImportUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.importState.collect(states::add)
            }
            vm.import(
                listOf(
                    source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                    source("notes.rtf", "{\rtf1 nope}".toByteArray()),
                ),
            )

            assertEquals(ImportUiState.Idle, states.first(), "states: $states")
            val importings = states.filterIsInstance<ImportUiState.Importing>()
            assertTrue(importings.isNotEmpty(), "expected an Importing state in $states")
            // F1: the batch reports 0/total before the first file completes.
            assertEquals(ImportUiState.Importing(0, 2, "Novel.epub"), importings.first(), "states: $states")
            testScheduler.advanceUntilIdle() // finish the batch (1 ms boundary per file)
            val finished = states.filterIsInstance<ImportUiState.Importing>()
            assertTrue(finished.size >= importings.size, "later Importing states may exist: $states")
            assertEquals(2, finished.last().total)
            assertEquals("notes.rtf", finished.last().currentFileName)
            assertTrue(
                states.indexOf(finished.last()) < states.lastIndex,
                "Importing must precede the final state in $states",
            )
            val done = states.last() as ImportUiState.Done
            assertEquals(1, done.summary.added)
            assertEquals(listOf("notes.rtf" to "format not supported"), done.summary.failed)
        }

    /** F1: cancelling an import settles to Idle (never a partial Done), and a
     * later import works — completed work stays committed, nothing half-way
     * is presented as a finished batch. */
    @Test
    fun `cancelling an import settles to Idle and a later import still works`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()

            vm.import(
                listOf(
                    source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                    source("Other.epub", epubBook("Other", "Chapter 1", "Other.")),
                ),
            )
            testScheduler.advanceTimeBy(2) // file 1 parsed; the batch parks on the next boundary
            vm.cancelImport()

            assertEquals(ImportUiState.Idle, vm.importState.value, "cancel settles to Idle")
            testScheduler.advanceUntilIdle() // the parked boundary throws: still Idle, never Done
            assertEquals(ImportUiState.Idle, vm.importState.value, "a cancelled batch never lands Done")

            vm.import(listOf(source("Other.epub", epubBook("Other", "Chapter 1", "Other."))))
            testScheduler.advanceUntilIdle()
            val done = vm.importState.value as ImportUiState.Done
            assertEquals(1, done.summary.added)
            assertTrue(vm.library.value.any { it.book.title == "Other" }, "a later import is unaffected")
        }

    @Test
    fun `empty source list is a no-op and stays Idle`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()

            vm.import(emptyList())

            assertEquals(ImportUiState.Idle, vm.importState.value)
            assertTrue(vm.library.value.isEmpty())
        }

    @Test
    fun `store add appends and dedupes by book id`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store = InMemoryLibraryStore()
            val entry =
                BookImporter().import(
                    source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                ) as io.github.moronigranja.ayvu.ebook.ImportOutcome.Added

            store.add(entry.entry)
            store.add(entry.entry)

            assertEquals(1, store.books.value.size)
            assertEquals(
                "Novel",
                store.books.value
                    .first()
                    .book.title,
            )
        }

    /** CR-3 Failure C: a failed durable DELETE must not leave a surviving
     * Room book missing from the index — durable-first order. */
    @Test
    fun `failed durable delete leaves the surviving book still indexed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val store =
                object : io.github.moronigranja.ayvu.model.LibraryStore by InMemoryLibraryStore() {
                    override suspend fun delete(bookId: String): Unit = throw java.io.IOException("disk full")
                }
            val index = TextIndex()
            val vm = viewModel(store = store, index = index)

            vm.import(listOf(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here."))))
            testScheduler.advanceUntilIdle()
            val bookId =
                vm.library.value
                    .first()
                    .book.id
            assertTrue(index.contains(bookId))

            vm.removeBook(bookId)
            testScheduler.advanceUntilIdle()

            assertEquals(1, vm.library.value.size, "durable delete failed → the book survives")
            assertTrue(index.contains(bookId), "the index must stay consistent with the surviving book")
        }

    // ------------------------------------------------------------------
    // F2 — library search
    // ------------------------------------------------------------------

    /** Two books with title/author overlap, pre-seeded via the store. */
    private suspend fun seededStore(): InMemoryLibraryStore =
        InMemoryLibraryStore().also { store ->
            store.add(
                io.github.moronigranja.ayvu.model.LibraryEntry(
                    io.github.moronigranja.ayvu.model.Book(
                        id = "wot",
                        title = "The Wheel of Time",
                        authors = listOf("Robert Jordan"),
                        chapters = emptyList(),
                    ),
                    importedAtEpochMillis = 1,
                ),
            )
            store.add(
                io.github.moronigranja.ayvu.model.LibraryEntry(
                    io.github.moronigranja.ayvu.model.Book(
                        id = "frem",
                        title = "Dune",
                        authors = listOf("Frank Herbert", "Brian Herbert"),
                        chapters = emptyList(),
                    ),
                    importedAtEpochMillis = 2,
                ),
            )
        }

    @Test
    fun `blank query shows every book`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel(store = seededStore())

            assertEquals(2, vm.searchResults.first().size)
        }

    @Test
    fun `query filters by title case-insensitively`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel(store = seededStore())

            vm.setQuery("dune")
            assertEquals(listOf("Dune"), vm.searchResults.first().map { it.book.title })

            vm.setQuery("WHEEL")
            assertEquals(listOf("The Wheel of Time"), vm.searchResults.first().map { it.book.title })
        }

    @Test
    fun `query matches any author`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel(store = seededStore())

            vm.setQuery("frank")
            assertEquals(listOf("Dune"), vm.searchResults.first().map { it.book.title })
        }

    @Test
    fun `unmatched query returns empty and trimming removes padding`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel(store = seededStore())

            vm.setQuery("  wheel  ")
            assertEquals(listOf("The Wheel of Time"), vm.searchResults.first().map { it.book.title })

            vm.setQuery("zzz")
            assertTrue(vm.searchResults.first().isEmpty())

            // Clearing the query restores the full list.
            vm.setQuery("")
            assertEquals(2, vm.searchResults.first().size)
        }
}
