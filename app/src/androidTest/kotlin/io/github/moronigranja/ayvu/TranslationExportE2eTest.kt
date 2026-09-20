package io.github.moronigranja.ayvu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.ebook.export.ExportFormat
import io.github.moronigranja.ayvu.ebook.export.ExportShape
import io.github.moronigranja.ayvu.featurelibrary.ExportSink
import io.github.moronigranja.ayvu.featurelibrary.ExportUiState
import io.github.moronigranja.ayvu.featuresettings.AndroidHttpTransport
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The translated-book export on the real device: a book whose DISPLAY language is
 * set (read-aloud off — the case that motivated the picker) exports to Markdown
 * through the production orchestrator, and a book that is not in the library
 * fails without writing anything.
 *
 * The observable is the file's bytes and the state holder; the per-passage
 * decode runs the real staged LFM pack (the same prerequisite
 * [LfmTranslatedPlaybackE2eTest] documents), so this also proves the export
 * seam reuses [io.github.moronigranja.ayvu.player.pregen.TranslationService]
 * as the reader does.
 */
@RunWith(AndroidJUnit4::class)
class TranslationExportE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val files: File get() = context.filesDir

    private val book =
        Book(
            id = "export-e2e-book",
            title = "Light Years",
            authors = listOf("Ana Dias"),
            chapters =
                listOf(
                    Chapter(
                        0,
                        "Chapter 1",
                        listOf(
                            TextPassage("We now have 4-month-old mice that are non-diabetic that used to be diabetic."),
                            TextPassage("Dr. Ehud Ur cautioned that the research is still in its early days."),
                        ),
                    ),
                ),
        )

    @Before
    fun setUp() {
        val cache = PackCache(files)
        val downloader = PackDownloader(cache, AndroidHttpTransport())
        runBlocking {
            downloader.download(TranslatePacks.shipped.pack)
            TranslatePackStager.stage(files, cache, TranslatePacks.shipped.pack)
            assertTrue("translate bundle must be staged", TranslatePackStager.isStaged(files, TranslatePacks.shipped))

            val app = context.applicationContext as AyvuApp
            app.appSettings.setBookDisplay(book.id, "pt-BR")
            app.libraryStore.add(LibraryEntry(book, importedAtEpochMillis = 1L))
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            val app = context.applicationContext as AyvuApp
            app.appSettings.setBookDisplay(book.id, null)
            app.libraryStore.delete(book.id)
        }
    }

    /** Polls the export state until it settles, so the assertion reads THIS run. */
    private fun awaitTerminal(timeoutMillis: Long = 240_000): ExportUiState {
        val app = context.applicationContext as AyvuApp
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val state = app.exportStateHolder.state.value
            if (state is ExportUiState.Finished || state is ExportUiState.Failed) return state
            Thread.sleep(250)
        }
        throw AssertionError("the export never settled: ${app.exportStateHolder.state.value}")
    }

    @Test
    fun displayLanguageExportsMarkdownEvenWithReadAloudOff() {
        val app = context.applicationContext as AyvuApp
        val destination = File(context.cacheDir, "export-e2e.md")
        destination.delete()

        app.bookExports.start(
            bookId = book.id,
            fileName = "Light Years (pt-BR).md",
            language = "pt-BR",
            format = ExportFormat.MARKDOWN,
            shape = ExportShape.TRANSLATED_ONLY,
            sink = ExportSink { bytes -> destination.writeBytes(bytes) },
            operations = null,
        )

        val terminal = awaitTerminal()
        assertTrue("export failed: $terminal", terminal is ExportUiState.Finished)
        assertEquals(destination.length(), (terminal as ExportUiState.Finished).bytes)

        val markdown = destination.readText()
        assertTrue(
            "the header must name the chosen language: $markdown",
            markdown.startsWith("<!-- Ayvu translation export v1 · Light Years · Portuguese (Brazil) · lfm12b · "),
        )
        assertTrue(markdown, markdown.contains("## Chapter 1"))
        // One non-empty translated paragraph per passage: the comment, the
        // heading and blanks are structure, not text.
        val paragraphs =
            markdown
                .lines()
                .filterNot { it.startsWith("<!--") || it.startsWith("## ") || it.isBlank() }
        assertEquals(book.chapters.sumOf { it.passages.size }, paragraphs.size)
        paragraphs.forEach { assertTrue("empty translated paragraph in: $markdown", it.isNotBlank()) }
    }

    @Test
    fun anUnknownBookFailsWithoutWriting() {
        val app = context.applicationContext as AyvuApp
        val destination = File(context.cacheDir, "export-missing.md")
        destination.delete()

        app.bookExports.start(
            bookId = "not-in-the-library",
            fileName = "missing.md",
            language = "pt-BR",
            format = ExportFormat.MARKDOWN,
            shape = ExportShape.TRANSLATED_ONLY,
            sink = ExportSink { bytes -> destination.writeBytes(bytes) },
            operations = null,
        )

        val terminal = awaitTerminal(timeoutMillis = 30_000)
        assertTrue("expected a typed failure, got $terminal", terminal is ExportUiState.Failed)
        assertFalse("nothing may be written for a failed export", destination.exists())
    }
}
