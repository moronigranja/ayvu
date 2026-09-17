package io.github.moronigranja.ayvu.featurelibrary

import io.github.moronigranja.ayvu.ebook.BookImporter
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.ebook.EpubFixture.CONTAINER
import io.github.moronigranja.ayvu.ebook.EpubFixture.chapterHtml
import io.github.moronigranja.ayvu.ebook.EpubFixture.ncx
import io.github.moronigranja.ayvu.ebook.EpubFixture.opf
import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.ebook.ImportCoordinator
import io.github.moronigranja.ayvu.ebook.ImportStage
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.ops.OperationChannel
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * C1/K3: the import operation is one cancellable batch over the shared holder —
 * progress (per file, with the stage), the batch summary, Stop → Idle (never a
 * partial Done), and the notification text/percent the reporter carries.
 *
 * The coordinator is the real one (the pipeline is the domain's); the file
 * access and the operation host are the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportOperationsTest {
    @TempDir
    lateinit var tempDir: File

    private val dispatcher = UnconfinedTestDispatcher()
    private val holder = ImportStateHolder()

    private fun operations(store: InMemoryLibraryStore = InMemoryLibraryStore()): ImportOperations =
        ImportOperations(
            coordinator = ImportCoordinator(BookImporter(), store, TextIndex(), IndexLock()),
            holder = holder,
            filesDir = tempDir,
            appScope = CoroutineScope(dispatcher),
        )

    /** A host that runs the body on the test scheduler and records how the
     *  operation was addressed and reported. */
    private class RecordingHost(
        private val scope: CoroutineScope,
    ) : OperationRunner {
        val specs = mutableListOf<OperationSpec>()
        val reports = mutableListOf<Pair<String, Int?>>()
        val cancelled = mutableListOf<String>()
        private var job: Job? = null

        override fun run(
            spec: OperationSpec,
            block: suspend (OperationReporter) -> Unit,
        ) {
            specs += spec
            job = scope.launch { block(OperationReporter { text, percent -> reports += text to percent }) }
        }

        override fun cancel(id: String): Boolean {
            cancelled += id
            job?.cancel()
            return true
        }
    }

    @Test
    fun `a two-file batch drives the holder from 0 to the summary and reports each file`() =
        runTest(dispatcher) {
            val states = mutableListOf<ImportUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.state.collect(states::add) }
            val host = RecordingHost(CoroutineScope(dispatcher))

            operations().start(listOf(source("First.epub", "First"), source("Second.epub", "Second")), truncated = false, operations = host)
            testScheduler.advanceUntilIdle()

            assertEquals(ImportUiState.Idle, states.first())
            assertEquals(
                ImportUiState.Importing(0, 2, "First.epub"),
                states.filterIsInstance<ImportUiState.Importing>().first(),
                "the operation starts on the first file before anything completes",
            )
            val importings = states.filterIsInstance<ImportUiState.Importing>()
            assertTrue(
                importings.map { it.stage }.containsAll(
                    listOf(ImportStage.READING, ImportStage.PARSING, ImportStage.COMMITTING, ImportStage.INDEXING),
                ),
                "the per-file pipeline stage rides the progress state: $importings",
            )
            val done = states.last() as ImportUiState.Done
            assertEquals(2, done.summary.added)
            assertEquals(emptyList<Pair<String, String>>(), done.summary.failed)
            assertFalse(done.summary.truncated)

            assertEquals("book-import", host.specs.single().id)
            assertEquals(OperationChannel.IMPORT, host.specs.single().channel)
            assertTrue(
                host.reports.any { it.first == "1/2 — First.epub" },
                "the notification names the file being imported: ${host.reports}",
            )
            val percents = host.reports.mapNotNull { it.second }
            assertTrue(percents.containsAll(listOf(0, 50, 100)), "reports: ${host.reports}")
            assertEquals(100, percents.last())
        }

    @Test
    fun `Stop cancels the batch at a file boundary and never lands Done`() =
        runTest(dispatcher) {
            val store = InMemoryLibraryStore()
            val states = mutableListOf<ImportUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.state.collect(states::add) }
            val host = RecordingHost(CoroutineScope(dispatcher))
            val imports = operations(store)

            imports.start(listOf(source("First.epub", "First"), source("Second.epub", "Second")), truncated = false, operations = host)
            testScheduler.advanceTimeBy(2) // first file parsed; the batch parks on the boundary
            val before = host.cancelled.size
            imports.cancel(host)

            assertEquals(listOf("book-import"), host.cancelled.drop(before))
            assertEquals(ImportUiState.Idle, holder.state.value)
            testScheduler.advanceUntilIdle()
            assertEquals(ImportUiState.Idle, holder.state.value, "a cancelled batch never lands Done")
            assertTrue(states.none { it is ImportUiState.Done }, "states: $states")
            assertEquals(
                listOf("First"),
                store.books.value.map { it.book.title },
                "already-committed books stay",
            )
        }

    @Test
    fun `without an operation host the batch still drives the holder and truncation rides the summary`() =
        runTest(dispatcher) {
            val imports = operations()

            imports.start(listOf(source("Only.epub", "Only")), truncated = true, operations = null)
            testScheduler.advanceUntilIdle()

            val done = holder.state.value as ImportUiState.Done
            assertEquals(1, done.summary.added)
            assertTrue(done.summary.truncated, "the folder-scan cap must reach the summary")
        }

    @Test
    fun `without an operation host Stop still cancels the batch`() =
        runTest(dispatcher) {
            val store = InMemoryLibraryStore()
            val imports = operations(store)

            imports.start(listOf(source("First.epub", "First"), source("Second.epub", "Second")), truncated = false, operations = null)
            testScheduler.advanceTimeBy(2)
            imports.cancel(operations = null)

            assertEquals(ImportUiState.Idle, holder.state.value)
            testScheduler.advanceUntilIdle()
            assertEquals(ImportUiState.Idle, holder.state.value)
        }

    @Test
    fun `a new batch supersedes the running one`() =
        runTest(dispatcher) {
            val imports = operations()

            imports.start(listOf(source("First.epub", "First"), source("Second.epub", "Second")), truncated = false, operations = null)
            testScheduler.advanceTimeBy(2)
            imports.start(listOf(source("Third.epub", "Third")), truncated = false, operations = null)
            testScheduler.advanceUntilIdle()

            val done = holder.state.value as ImportUiState.Done
            assertEquals(
                1,
                done.summary.added,
                "the superseded batch never publishes its summary",
            )
        }

    private fun source(
        name: String,
        title: String,
    ): EBookSource = EBookSource(name) { ByteArrayInputStream(epubBook(title)) }

    private fun epubBook(title: String): ByteArray =
        zip(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to
                opf(
                    title = title,
                    spine = listOf("f0" to "title.xhtml", "c1" to "chap1.xhtml"),
                    ncxHref = "toc.ncx",
                ),
            "OEBPS/toc.ncx" to ncx(listOf("title.xhtml" to "Title Page", "chap1.xhtml" to "Chapter 1")),
            "OEBPS/title.xhtml" to chapterHtml(null, listOf("A Novel by Someone")),
            "OEBPS/chap1.xhtml" to chapterHtml(null, listOf("Prose here.")),
        )
}
