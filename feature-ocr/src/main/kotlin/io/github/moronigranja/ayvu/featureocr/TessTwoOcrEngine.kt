package io.github.moronigranja.ayvu.featureocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import io.github.moronigranja.ayvu.ocr.OcrEngine
import io.github.moronigranja.ayvu.ocr.OcrImage
import io.github.moronigranja.ayvu.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The tess-two adapter (S1): the one place the app touches Tesseract/Leptonica.
 *
 * [dataPath] must contain `tessdata/<lang>.traineddata` (init(dataPath, …)
 * resolves `<dataPath>/tessdata/…`) for every requested
 * language (the settings UI downloads + stages them via [TessDataStager]);
 * a missing model is a typed failure, never an empty-result silent pass.
 *
 * A fresh [TessBaseAPI] per recognition: instances are not safe to reuse
 * across concurrent passes, and a full-page pass runs on the IO dispatcher —
 * the same lazily-allocated, disposed-per-call pattern keeps memory bounded.
 */
class TessTwoOcrEngine
    @Inject
    constructor(
        private val dataPath: File,
    ) : OcrEngine {
        override suspend fun recognize(
            image: OcrImage,
            languages: List<String>,
        ): OcrResult {
            require(languages.isNotEmpty()) { "at least one tessdata language is required" }
            return withContext(Dispatchers.IO) {
                val bitmap = toBitmap(image)
                try {
                    val tess = TessBaseAPI()
                    try {
                        val initialized =
                            try {
                                tess.init(dataPath.absolutePath, languages.joinToString("+"))
                            } catch (e: RuntimeException) {
                                // Missing/foreign traineddata and cube-only quirks throw
                                // from init; surface the precise cause instead of a guess.
                                throw IllegalStateException(
                                    "tessdata init failed for ${languages.joinToString("+")} under $dataPath: ${e.message}",
                                    e,
                                )
                            }
                        if (!initialized) {
                            throw IllegalStateException(
                                "tessdata init returned false for ${languages.joinToString("+")} under $dataPath",
                            )
                        }
                        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                        tess.setImage(bitmap)
                        val text = tess.utF8Text
                        val confidence = tess.meanConfidence().toDouble() / 100.0
                        OcrResult(text?.trim().orEmpty(), confidence)
                    } finally {
                        tess.end()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        private fun toBitmap(image: OcrImage): Bitmap {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
            return bitmap
        }
    }
