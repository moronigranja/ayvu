package io.github.moronigranja.ayvu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.ebook.BookImporter
import io.github.moronigranja.ayvu.ebook.EBookSource
import io.github.moronigranja.ayvu.ebook.EpubParser
import io.github.moronigranja.ayvu.ebook.ImportOutcome
import io.github.moronigranja.ayvu.locate.TextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S-debug device repro: a real-world EPUB (Gutenberg Pride and Prejudice,
 * 24.8 MiB) through the ACTUAL parsers on the Android runtime — Android's
 * DOM (Expat-backed) differs from host Xerces, which is where the field
 * "could not read container.xml" originated. The container path is now
 * regex-based and parseXml pre-processes doctype/single-quoted-declaration;
 * this probe exercises the full import and prints the cause chain on failure.
 *
 * Requires `files/import-probe/pp.epub` staged via adb (build.md pattern);
 * `files/import-probe/nmmng.epub` for the entity-in-metadata case (decisions #53).
 */
@RunWith(AndroidJUnit4::class)
class RealEpubImportProbe {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realEpubImportsOnDevice() {
        val file = File(context.filesDir, "import-probe/pp.epub")
        assertTrue("stage build.md's pp.epub first: $file", file.isFile)

        // Direct parse first: surfaces the cause chain if anything fails.
        val book =
            try {
                EpubParser.parse(file.readBytes())
            } catch (e: Throwable) {
                val chain =
                    generateSequence(e) { it.cause }
                        .map { "${it::class.java.simpleName}: ${it.message ?: ""}" }
                        .joinToString(" <- ")
                fail("EpubParser.parse failed: $chain")
                null
            }!!
        assertEquals("Pride and Prejudice", book.title)
        assertTrue("chapters parsed", book.chapters.size >= 5)
        assertTrue("passages parsed", book.chapters.sumOf { it.passages.size } > 500)

        // The importer path (what SAF import runs) must land Added too.
        val importer = BookImporter()
        val outcome = importer.import(EBookSource(fileName = "pp.epub") { file.inputStream() })
        assertTrue("expected Added, was $outcome", outcome is ImportOutcome.Added)
    }

    @Test
    fun niceGuyEntityEpubImportsOnDevice() {
        // S-device entity bug (decisions #53): an epub whose OPF metadata carries
        // XML-valid &amp; (e.g. <dc:subject>Love &amp; Romance</dc:subject>) failed
        // the import — the pre-parse entity pass decoded it to a bare '&' and the
        // Expat-backed DOM rejected the document. Requires nmmng.epub staged.
        val file = File(context.filesDir, "import-probe/nmmng.epub")
        assertTrue("stage nmmng.epub first: $file", file.isFile)

        val book = EpubParser.parse(file.readBytes())
        assertEquals("No More Mr Nice Guy", book.title)
        assertEquals(listOf("Robert A. Glover"), book.authors)
        assertTrue("chapters parsed ≥ 2, was ${book.chapters.size}", book.chapters.size >= 2)

        val outcome =
            BookImporter().import(
                EBookSource(fileName = "nmmng.epub") { file.inputStream() },
            )
        assertTrue("expected Added, was $outcome", outcome is ImportOutcome.Added)
    }
}
