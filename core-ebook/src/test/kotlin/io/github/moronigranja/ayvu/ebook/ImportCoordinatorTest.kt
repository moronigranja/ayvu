package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.EpubFixture.CONTAINER
import io.github.moronigranja.ayvu.ebook.EpubFixture.chapterHtml
import io.github.moronigranja.ayvu.ebook.EpubFixture.ncx
import io.github.moronigranja.ayvu.ebook.EpubFixture.opf
import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * CR-3/A3 orchestration contract: parse → durable commit → index publish,
 * with the durable store as the duplicate truth and every index mutation
 * serialized through [IndexLock] — a failed commit never poisons the retry,
 * and a stale rebuild snapshot can never purge a concurrently committed book.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportCoordinatorTest {
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

    private fun coordinator(
        store: LibraryStore = InMemoryLibraryStore(),
        index: TextIndex = TextIndex(),
    ) = ImportCoordinator(BookImporter(), store, index, IndexLock())

    /** Flat cache rows the rebuild consumes (the Room mapper's shape). */
    private fun cached(entry: LibraryEntry): CachedBook =
        CachedBook(
            id = entry.book.id,
            title = entry.book.title,
            authors = entry.book.authors,
            passages =
                entry.book.chapters.flatMap { ch ->
                    ch.passages.mapIndexed { i, p -> CachedPassage(ch.index, ch.title, i, p.text) }
                },
        )

    /** Fails the first durable commit, then delegates — the factory that
     * models a transient storage failure (disk full, SQLite error). */
    private class FlakyStore(
        private val inner: InMemoryLibraryStore = InMemoryLibraryStore(),
    ) : LibraryStore {
        var failures = 0
        override val books get() = inner.books

        override suspend fun cachedBooks(): List<CachedBook> = inner.cachedBooks()

        override suspend fun contains(bookId: String): Boolean = inner.contains(bookId)

        override suspend fun add(entry: LibraryEntry) {
            if (failures == 0) {
                failures++
                throw IOException("disk full")
            }
            inner.add(entry)
        }

        override suspend fun delete(bookId: String) = inner.delete(bookId)
    }

    private val bookA = source("A.epub", epubBook("A", "C", "aaa."))
    private val bookX = source("X.epub", epubBook("X", "C", "xxx."))

    @Test
    fun `import commits durably then publishes to the index`() =
        runTest {
            val store = InMemoryLibraryStore()
            val index = TextIndex()
            val result = coordinator(store, index).import(bookA)

            val added = assertInstanceOf(ImportOutcome.Added::class.java, result)
            assertTrue(store.contains(added.entry.book.id))
            assertTrue(index.contains(added.entry.book.id))
            assertEquals(1, index.bookCount())
        }

    @Test
    fun `the durable store is the duplicate gate - no re-parse for an existing book`() =
        runTest {
            val store = InMemoryLibraryStore()
            val coordinator = coordinator(store)

            val first = coordinator.import(bookA)
            val second = coordinator.import(bookA)

            assertInstanceOf(ImportOutcome.Added::class.java, first)
            val unchanged = assertInstanceOf(ImportOutcome.Unchanged::class.java, second)
            assertEquals((first as ImportOutcome.Added).entry.book.id, unchanged.bookId)
            assertEquals(1, store.books.value.size)
        }

    /** CR-3 Failure A: a failed durable commit leaves the index untouched and
     * a retry of the same bytes re-parses, commits and indexes exactly once. */
    @Test
    fun `a failed durable commit never poisons the retry`() =
        runTest {
            val index = TextIndex()
            val flaky = FlakyStore()
            val coordinator = ImportCoordinator(BookImporter(), flaky, index, IndexLock())

            val first = coordinator.import(bookA)
            val failed = assertInstanceOf(ImportOutcome.Failed::class.java, first)
            assertInstanceOf(ImportFailureReason.Storage::class.java, failed.reason)
            assertEquals(0, index.bookCount(), "the index must not hold an uncommitted id")
            assertEquals(0, flaky.books.value.size)

            // Retry: Room decides (no stale index hit), the commit lands, the
            // index gains exactly one entry.
            val second = coordinator.import(bookA)
            assertInstanceOf(ImportOutcome.Added::class.java, second)
            assertEquals(1, flaky.books.value.size)
            assertEquals(1, index.bookCount())
            val id =
                flaky.books.value
                    .first()
                    .book.id
            assertTrue(index.contains(id))
            assertEquals(64, id.length)
        }

    /** CR-3 Failure B: a rebuild reconciling under the index lock can never
     * purge a book whose durable commit landed during its critical section. */
    @Test
    fun `a stale rebuild snapshot can never purge a concurrently committed book`() =
        runTest {
            val store = InMemoryLibraryStore()
            val index = TextIndex()
            val lock = IndexLock()
            val coordinator = ImportCoordinator(BookImporter(), store, index, lock)
            val first = coordinator.import(bookA) as ImportOutcome.Added
            val idA = first.entry.book.id
            val rebuilder = IndexRebuilder(index)
            lock.withExclusiveIndex { rebuilder.rebuild(listOf(cached(first.entry))) } // seed state

            val rebuildHoldsLock = CompletableDeferred<Unit>()
            val releaseRebuild = CompletableDeferred<Unit>()
            val rebuild =
                launch(StandardTestDispatcher(testScheduler)) {
                    lock.withExclusiveIndex {
                        rebuildHoldsLock.complete(Unit)
                        releaseRebuild.await() // hold the lock across the import's commit
                        rebuilder.rebuild(listOf(cached(first.entry))) // stale-if-read-before-commit
                    }
                }
            val import =
                launch(StandardTestDispatcher(testScheduler)) {
                    coordinator.import(bookX) // commits durably, then parks on the lock
                }

            runCurrent() // rebuild holds the lock
            runCurrent() // import commits book X to the store, then waits for the lock
            assertEquals(2, store.books.value.size, "commit landed while the lock was held")
            releaseRebuild.complete(Unit)
            advanceUntilIdle()

            // final invariant: index == durable truth, X included.
            assertEquals(
                setOf(
                    idA,
                    store.books.value
                        .first { it.book.id != idA }
                        .book.id,
                ),
                index.bookIds().toSet(),
            )
        }

    // ------------------------------------------------------------------
    // F1 semantics moved with the batch loop
    // ------------------------------------------------------------------

    @Test
    fun `batch import reports progress around every file`() =
        runTest {
            val progress = mutableListOf<Triple<String, Int, Int>>()
            val outcomes =
                coordinator().importAll(
                    listOf(
                        source("One.epub", epubBook("One", "Chapter 1", "First.")),
                        source("Two.epub", epubBook("Two", "Chapter 1", "Second.")),
                    ),
                    onProgress = { current, done, total -> progress += Triple(current.fileName, done, total) },
                )

            assertEquals(
                listOf(
                    Triple("One.epub", 0, 2),
                    Triple("One.epub", 1, 2),
                    Triple("Two.epub", 1, 2),
                    Triple("Two.epub", 2, 2),
                ),
                progress,
            )
            assertEquals(2, outcomes.size)
        }

    /** A cancelled batch stops at the next file boundary and never commits or
     * indexes a file it never started. */
    @Test
    fun `cancelling a batch stops at the file boundary and skips later files`() =
        runTest {
            val store = InMemoryLibraryStore()
            val index = TextIndex()
            val coordinator = ImportCoordinator(BookImporter(), store, index, IndexLock())
            val secondBytes = epubBook("Second", "Chapter 1", "Second.")
            val secondId = Bytes.sha256Hex(secondBytes)
            var cancelled = false
            val job =
                launch(StandardTestDispatcher(testScheduler)) {
                    try {
                        coordinator.importAll(
                            listOf(
                                source("One.epub", epubBook("One", "Chapter 1", "First.")),
                                source("Two.epub", secondBytes),
                            ),
                            onProgress = { _, _, _ -> },
                        )
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            runCurrent() // body starts, parks on the first 1 ms boundary
            advanceTimeBy(2) // past the boundary: file 1 committed + indexed; parks again
            assertEquals(1, store.books.value.size, "file 1 completed before the cancel")
            job.cancel()
            advanceTimeBy(2) // the boundary delay throws: the batch stops cleanly
            assertTrue(cancelled)
            assertTrue(!index.contains(secondId), "file 2 must never reach the store or index")
            assertEquals(1, store.books.value.size)
        }

    /** The read-stage ceiling bridge: a source past the container ceiling is a typed per-file
     * parse failure carrying the ceiling's own message — never "unreadable". The guard itself
     * is exercised with injected limits in [ImportLimitsTest]; reaching it for real here would
     * mean allocating a quarter gigabyte inside a unit test. */
    @Test
    fun `an over-limit source fails as a parse failure at the read stage`() =
        runTest {
            val store = InMemoryLibraryStore()
            val index = TextIndex()
            val overLimit =
                EBookSource("Big.epub") { throw EBookLimitExceededException("file is too large (over 256 MB)") }

            val failed =
                assertInstanceOf(
                    ImportOutcome.Failed::class.java,
                    coordinator(store, index).import(overLimit),
                )

            val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
            assertEquals("file is too large (over 256 MB)", reason.message)
            assertEquals(0, store.books.value.size, "a refused source is never committed")
            assertEquals(0, index.bookCount())
        }
}
