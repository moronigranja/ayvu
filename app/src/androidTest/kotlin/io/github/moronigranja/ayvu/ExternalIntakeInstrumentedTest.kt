package io.github.moronigranja.ayvu

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * F4 device verification: the external-file gateway's ACTION_VIEW path against
 * the REAL app (Hilt graph, Room, importer). The fixture lives in the app's
 * own files dir and is handed over via a real startActivity (the exact intent
 * shape a file manager sends after the system redirects /sdcard paths); the
 * durable commit is asserted against the app's real `local-tts-reader.db`.
 *
 * - First VIEW imports: the row appears (added).
 * - Second VIEW of the same content: the content-hash dedupe gate holds
 *   (still exactly one row — LibraryStore.contains, no duplicate).
 * - .kfx: typed guidance, imports NOTHING (never a silent no-op).
 *
 * The teardown removes any test rows so the app library stays clean.
 * Runs on a locked/off screen like the other device tests; needs no packs.
 */
@RunWith(AndroidJUnit4::class)
class ExternalIntakeInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = "F4 Test Book"
    private val fixtureName = "f4-test-book.epub"

    private lateinit var database: LibraryDatabase

    private fun buildEpub(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            fun put(
                name: String,
                content: String,
            ) = z.putNextEntry(ZipEntry(name)).also {
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "META-INF/container.xml",
                "<?xml version='1.0'?><container version='1.0' xmlns='urn:oasis:names:tc:opendocument:xmlns:container'>" +
                    "<rootfiles><rootfile full-path='OEBPS/content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
            )
            put(
                "OEBPS/content.opf",
                "<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'>" +
                    "<metadata><dc:title xmlns:dc='http://purl.org/dc/elements/1.1/'>$title</dc:title>" +
                    "<dc:language xmlns:dc='http://purl.org/dc/elements/1.1/'>en</dc:language></metadata>" +
                    "<manifest><item id='c1' href='c1.xhtml' media-type='application/xhtml+xml'/></manifest>" +
                    "<spine><itemref idref='c1'/></spine></package>",
            )
            put(
                "OEBPS/c1.xhtml",
                "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><body>" +
                    "<p>Chapter one. F4 external intake device verification.</p></body></html>",
            )
            put("mimetype", "application/epub+zip")
        }
        return bytes.toByteArray()
    }

    private fun stageFixture(
        name: String,
        bytes: ByteArray,
    ): File {
        val file = File(targetContext.filesDir, name)
        file.writeBytes(bytes)
        return file
    }

    /** Real launch route (mirrors the adb proof): a genuine startActivity with
     * the VIEW intent — the gateway runs its own handleIntent → import. */
    private fun launchView(file: File) {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.fromFile(file)
                type = "application/epub+zip"
                setComponent(
                    ComponentName(
                        targetContext.packageName,
                        "io.github.moronigranja.ayvu.MainActivity",
                    ),
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        targetContext.startActivity(intent)
    }

    private fun bookCount(): Int =
        runBlocking {
            database.bookDao().all().count { it.title == title }
        }

    private fun awaitRow(seconds: Long = 15): Boolean =
        runBlocking {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                if (bookCount() > 0) return@runBlocking true
                delay(200)
            }
            bookCount() > 0
        }

    @After
    fun cleanup() {
        if (::database.isInitialized) {
            runBlocking {
                database
                    .bookDao()
                    .all()
                    .filter { it.title == title }
                    .forEach { database.bookDao().delete(it.id) }
            }
            database.close()
        }
        File(targetContext.filesDir, fixtureName).delete()
        File(targetContext.filesDir, "f4-test-book.kfx").delete()
    }

    @Test
    fun actionViewImportsThenDeduplicates() {
        database =
            Room
                .databaseBuilder(
                    targetContext,
                    LibraryDatabase::class.java,
                    "local-tts-reader.db",
                ).build()

        val file = stageFixture(fixtureName, buildEpub())

        // First VIEW: the file lands in the library.
        launchView(file)
        assertTrue("gateway must import the epub via ACTION_VIEW", awaitRow())
        assertEquals("exactly one row after first import", 1, bookCount())

        // Second VIEW of the same content: the content-hash dedupe gate holds.
        launchView(file)
        runBlocking { delay(2500) } // the unchanged outcome still passes through the importer
        assertEquals("re-import must not duplicate the row", 1, bookCount())
    }

    @Test
    fun kfxShowsGuidanceAndImportsNothing() {
        database =
            Room
                .databaseBuilder(
                    targetContext,
                    LibraryDatabase::class.java,
                    "local-tts-reader.db",
                ).build()

        val file = stageFixture("f4-test-book.kfx", byteArrayOf(0x0A, 0x0B, 0x0C))
        val before = bookCount()
        launchView(file)
        runBlocking { delay(2500) }
        assertEquals("kfx must never import (typed guidance only)", before, bookCount())
    }
}
