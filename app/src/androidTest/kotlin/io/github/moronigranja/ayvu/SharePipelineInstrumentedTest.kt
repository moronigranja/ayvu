package io.github.moronigranja.ayvu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureocr.TesseractOcrEngine
import io.github.moronigranja.ayvu.featureshare.ImageDecoder
import io.github.moronigranja.ayvu.featureshare.ShareInput
import io.github.moronigranja.ayvu.featureshare.ShareResolution
import io.github.moronigranja.ayvu.featureshare.ShareSnippetResolver
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.CachedPassage
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.ocr.TessDataStager
import io.github.moronigranja.ayvu.persistence.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S2 on-device verification: the real pipeline against the real index and
 * the real Tesseract engine. The text branch resolves a shared quote; the
 * image branch renders the quote to a bitmap, decodes it through
 * [ImageDecoder] (the same code the share activity runs), and resolves the
 * OCR text back to the book passage.
 *
 * Constructed manually (no Hilt harness — the app's @HiltAndroidApp bypass
 * needs a test-manifest override this AGP setup doesn't apply); the pieces
 * are the app's own: Tesseract engine + stager path, core-ocr downscaler,
 * TextIndex + IndexRebuilder + the S2 resolver. Threshold/languages default
 * to the product settings defaults ([SettingsStore.DEFAULT_MATCH_THRESHOLD] / "eng").
 *
 * Requires staged packs + `files/tesseract/tessdata/eng.traineddata`
 * (build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class SharePipelineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val quote = "All happy families are alike; each unhappy family is unhappy in its own way."

    private val book =
        Book(
            id = "share-e2e-book",
            title = "Anna Karenina (share test)",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "All happy families",
                        listOf(TextPassage(quote)),
                    ),
                ),
        )

    private fun resolver(): ShareSnippetResolver {
        val index = TextIndex()
        val gate = IndexRebuilder(index)
        gate.rebuild(
            listOf(
                CachedBook(
                    id = book.id,
                    title = book.title,
                    passages =
                        listOf(
                            CachedPassage(
                                chapterIndex = 0,
                                chapterTitle = "All happy families",
                                passageIndex = 0,
                                text = quote,
                            ),
                        ),
                ),
            ),
        )
        val ocr = TesseractOcrEngine(TessDataStager.tesseractDataPath(context.filesDir))
        return ShareSnippetResolver(
            index = index,
            rebuildGate = gate,
            threshold = { SettingsStore.DEFAULT_MATCH_THRESHOLD },
            ocr = ocr,
            ocrLanguages = { listOf("eng") },
        )
    }

    @Test
    fun textShareResolvesToTheBookPassage() =
        runBlocking {
            val resolution = resolver().resolve(ShareInput.Text(quote))
            assertTrue("expected Found, was $resolution", resolution is ShareResolution.Found)
            val found = resolution as ShareResolution.Found
            assertEquals(book.id, found.bookId)
            assertEquals(0, found.chapterIndex)
            assertEquals(0, found.passageIndex)
            assertTrue(found.confidence >= 0.99)
        }

    @Test
    fun imageShareDecodesOcrsAndResolves() =
        runBlocking {
            // Render the quote the way a screenshot of the page would look.
            val bitmap = Bitmap.createBitmap(1400, 700, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint =
                Paint().apply {
                    color = Color.BLACK
                    textSize = 72f
                    isAntiAlias = true
                }
            val lines = quote.split("; ")
            var y = 140f
            for (line in lines) {
                canvas.drawText(line, 60f, y, paint)
                y += 110f
            }

            val file = File(context.cacheDir, "share-test.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            val image = ImageDecoder.decode(Uri.fromFile(file), context.contentResolver)
            assertTrue("decoded image", image != null)
            image!!

            val resolution = resolver().resolve(ShareInput.Image(image))
            assertTrue("expected Found, was $resolution", resolution is ShareResolution.Found)
            val found = resolution as ShareResolution.Found
            assertEquals(book.id, found.bookId)
            assertTrue("OCR confidence for the rendered quote", found.confidence >= 0.5)
            file.delete()
            Unit
        }
}
