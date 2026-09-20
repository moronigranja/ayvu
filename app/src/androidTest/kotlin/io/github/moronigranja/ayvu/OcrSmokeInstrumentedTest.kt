package io.github.moronigranja.ayvu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureocr.TesseractOcrEngine
import io.github.moronigranja.ayvu.ocr.OcrImage
import io.github.moronigranja.ayvu.ocr.ScreenshotDownscaler
import io.github.moronigranja.ayvu.ocr.TessDataStager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1 on-device verification: the real Tesseract adapter reads the staged
 * `eng.traineddata` (staged via adb, same consent/staging mechanism the
 * settings UI drives) and recognizes large, clean, rendered glyphs — proving
 * the native stack, the data path, and the downscaler-to-engine handoff.
 *
 * Requires: the staged model at
 * [TessDataStager.tesseractDataPath]`/tessdata/eng.traineddata` (adb stage
 * from the pinned `tessdata_fast` pack — see build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class OcrSmokeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tesseractReadsRenderedGlyphs() =
        runBlocking {
            val engine = TesseractOcrEngine(TessDataStager.tesseractDataPath(context.filesDir))

            // Canvas wide enough for the whole string (15 monospace glyphs at 130 px
            // span ~1170 px from x=60): at 1200 px the trailing "3" was cut off, and a
            // clipped glyph reads as punctuation — Tesseract 5 returned "HELLO WORLD
            // 12:" while the retired legacy engine's "123" was a lucky guess off the
            // sliver. The downscaler's default cap (1600) leaves this render untouched.
            val bitmap = Bitmap.createBitmap(1440, 420, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint =
                Paint().apply {
                    color = Color.BLACK
                    textSize = 130f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    isAntiAlias = true
                }
            canvas.drawText("HELLO WORLD 123", 60f, 250f, paint)

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val image = ScreenshotDownscaler.downscale(OcrImage(bitmap.width, bitmap.height, pixels))

            val result = engine.recognize(image, listOf("eng"))
            assertFalse("OCR must not return empty for rendered glyphs, got: ${result.text}", result.text.isBlank())
            val upper = result.text.uppercase()
            assertTrue("expected HELLO in OCR text, got: ${result.text}", "HELLO" in upper)
            assertTrue("expected 123 in OCR text, got: ${result.text}", "123" in upper)
        }
}
