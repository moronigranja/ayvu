package io.github.moronigranja.ayvu.ebook.export

import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The export walk: spine order, per-passage translation through the seam (stored
 * rows reused, unstored decoded), typed failure instead of a silent gap, and
 * cancellation producing no document.
 */
class TranslationExportJobTest {
    /** A two-chapter book: chapter 0 has two passages, chapter 1 has one. */
    private fun book(): CachedBook =
        CachedBook(
            id = "book-1",
            title = "Light Years",
            authors = listOf("Ana Dias"),
            passages =
                listOf(
                    CachedPassage(0, "Chapter 1", 0, "First original."),
                    CachedPassage(0, "Chapter 1", 1, "Second original."),
                    CachedPassage(1, "Chapter 2", 0, "Third original."),
                ),
        )

    @Test
    fun `the document groups rows into chapters and reuses stored text`() =
        runTest {
            // (chapter, passage) -> stored translation; the rest need a decode.
            val stored = mapOf((0 to 0) to "Primeiro traduzido.")
            val decodes = AtomicInteger()
            val sources = mutableListOf<String>()
            val progress = mutableListOf<Pair<Int, Int>>()
            val job =
                TranslationExportJob(
                    translated = { chapter, passage, source ->
                        sources += source
                        if (stored.containsKey(chapter to passage)) {
                            stored.getValue(chapter to passage)
                        } else {
                            decodes.incrementAndGet()
                            "traduzido $chapter/$passage"
                        }
                    },
                    nowEpochMillis = { 1_700_000_000_000L },
                    onProgress = { done, total -> progress += done to total },
                )

            val document = job.build(book(), "pt-BR", "Portuguese (Brazil)", "lfm12b", ExportShape.BILINGUAL)

            assertEquals(listOf(0, 1), document.chapters.map { it.index })
            assertEquals(listOf("Chapter 1", "Chapter 2"), document.chapters.map { it.title })
            assertEquals(
                listOf("First original.", "Second original.", "Third original."),
                document.chapters.flatMap { it.passages }.map { it.original },
            )
            assertEquals(
                listOf("Primeiro traduzido.", "traduzido 0/1", "traduzido 1/0"),
                document.chapters.flatMap { it.passages }.map { it.translated },
            )
            // The provider is handed the ORIGINAL text, and only unstored rows decode.
            assertEquals(listOf("First original.", "Second original.", "Third original."), sources)
            assertEquals(2, decodes.get())
            // Progress advances 1..N in spine order.
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
            assertEquals("pt-BR", document.language)
            assertEquals("lfm12b", document.translator)
            assertEquals(ExportShape.BILINGUAL, document.shape)
            assertEquals(1_700_000_000_000L, document.generatedAtEpochMillis)
        }

    @Test
    fun `an unavailable passage is a typed failure carrying its coordinates`() =
        runTest {
            val job =
                TranslationExportJob(
                    translated = { chapter, passage, _ ->
                        if (chapter == 1 && passage == 0) null else "t"
                    },
                    nowEpochMillis = { 0L },
                )

            val error =
                runCatching { job.build(book(), "pt-BR", "Portuguese (Brazil)", "lfm12b", ExportShape.TRANSLATED_ONLY) }
                    .exceptionOrNull()
            val unavailable = error as TranslationExportError.Unavailable
            assertEquals(1, unavailable.chapter)
            assertEquals(0, unavailable.passage)
        }

    @Test
    fun `a blank translation is the same failure, never a silent gap`() =
        runTest {
            val job =
                TranslationExportJob(
                    translated = { _, _, _ -> "   " },
                    nowEpochMillis = { 0L },
                )

            val error =
                runCatching { job.build(book(), "pt-BR", "Portuguese (Brazil)", "lfm12b", ExportShape.TRANSLATED_ONLY) }
                    .exceptionOrNull()
            assertTrue(error is TranslationExportError.Unavailable)
        }

    @Test
    fun `an empty book has nothing to export`() =
        runTest {
            val job = TranslationExportJob(translated = { _, _, _ -> "t" }, nowEpochMillis = { 0L })

            val error =
                runCatching {
                    job.build(CachedBook("empty", "Empty"), "pt-BR", "Portuguese (Brazil)", "lfm12b", ExportShape.TRANSLATED_ONLY)
                }.exceptionOrNull()
            assertTrue(error is TranslationExportError.EmptyBook)
        }

    @Test
    fun `cancelling the walk produces no document`() =
        runTest {
            val calls = AtomicInteger()
            var document: ExportDocument? = null
            val job =
                TranslationExportJob(
                    translated = { _, _, _ ->
                        if (calls.incrementAndGet() >= 2) currentCoroutineContext().cancel()
                        "t"
                    },
                    nowEpochMillis = { 0L },
                )

            var cause: Throwable? = null
            val deferred =
                async(Job() + UnconfinedTestDispatcher(testScheduler)) {
                    document = job.build(book(), "pt-BR", "Portuguese (Brazil)", "lfm12b", ExportShape.TRANSLATED_ONLY)
                }
            deferred.invokeOnCompletion { cause = it }
            deferred.join()

            assertTrue(cause is CancellationException, "the walk threw ${cause ?: "nothing"}")
            assertNull(document, "no document is produced once the walk is cancelled")
        }
}
