package com.moronigranja.localttsreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureocr.TessTwoOcrEngine
import com.moronigranja.localttsreader.ocr.OcrImage
import com.moronigranja.localttsreader.ocr.ScreenshotDownscaler
import com.moronigranja.localttsreader.ocr.TessDataStager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1 on-device verification: the real tess-two adapter reads the staged
 * `eng.traineddata` (staged via adb, same consent/staging mechanism the
 * settings UI drives) and recognizes large, clean, rendered glyphs — proving
 * the native stack, the data path, and the downscaler-to-engine handoff.
 *
 * Requires: `files/tesseract/tessdata/eng.traineddata` (adb stage from the
 * pinned pack — see build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class OcrSmokeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tesseractReadsRenderedGlyphs() =
        runBlocking {
            val engine = TessTwoOcrEngine(TessDataStager.tesseractDataPath(context.filesDir))

            val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
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
