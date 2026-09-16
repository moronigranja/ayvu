package io.github.moronigranja.ayvu.featureshare

import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.ocr.OcrEngine
import io.github.moronigranja.ayvu.ocr.OcrImage
import io.github.moronigranja.ayvu.ocr.ScreenshotDownscaler
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The share pipeline (S2), pure JVM and engine-agnostic: normalize the shared
 * text (or OCR the shared image), run it through the library [TextIndex]
 * against the configured threshold, and emit a typed [ShareResolution].
 *
 * - The OCR branch DOWNSCALES here (core-ocr's bilinear downscaler) — the
 *   codec-side decode passes a full-resolution raster and never needs to know
 *   OCR sizing policy.
 * - Cold-start shares await the app's async [IndexRebuilder] (up to 10 s)
 *   before querying, so an ACTION_SEND that boots the process cannot race an
 *   empty index.
 * - The threshold and the OCR languages come from settings([AppSettings]
 *   mirror, V1); both lambdas are synchronous reads of cached values.
 */
class ShareSnippetResolver(
    private val index: TextIndex,
    private val rebuildGate: IndexRebuilder,
    private val threshold: () -> Double,
    private val ocr: OcrEngine?,
    private val ocrLanguages: () -> List<String>,
    private val maxOcrLongSide: Int = ScreenshotDownscaler.DEFAULT_MAX_LONG_SIDE,
) {
    suspend fun resolve(input: ShareInput): ShareResolution {
        if (input is ShareInput.Image && ocr == null) {
            return ShareResolution.NotFound(
                reason = ShareResolution.Reason.OCR_UNAVAILABLE,
                snippet = "",
            )
        }
        awaitIndexReady()

        val text =
            when (input) {
                is ShareInput.Text -> input.text
                is ShareInput.Image ->
                    extractText(input.image)
                        ?: return ShareResolution.NotFound(
                            reason = ShareResolution.Reason.NO_READABLE_TEXT,
                            snippet = "",
                        )
            }
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return ShareResolution.NotFound(
                reason =
                    if (input is ShareInput.Text) {
                        ShareResolution.Reason.BLANK
                    } else {
                        ShareResolution.Reason.NO_READABLE_TEXT
                    },
                snippet = normalized,
            )
        }

        val candidate = index.best(normalized)
        return if (candidate != null && candidate.confidence >= threshold()) {
            ShareResolution.Found(
                bookId = candidate.bookId,
                bookTitle = candidate.bookTitle,
                chapterIndex = candidate.chapterIndex,
                chapterTitle = candidate.chapterTitle,
                passageIndex = candidate.passageIndex,
                confidence = candidate.confidence,
                snippet = normalized,
            )
        } else {
            ShareResolution.NotFound(
                reason = ShareResolution.Reason.NO_MATCH,
                closest =
                    candidate?.let {
                        ShareResolution.Closest(
                            bookTitle = it.bookTitle,
                            chapterTitle = it.chapterTitle,
                            confidence = it.confidence,
                        )
                    },
                snippet = normalized,
            )
        }
    }

    private suspend fun awaitIndexReady() {
        try {
            withTimeout(INDEX_READY_TIMEOUT_MS) { rebuildGate.readiness.await() }
        } catch (e: TimeoutCancellationException) {
            // The rebuild is still running (huge library). A query on the
            // partially built index is better than a denied share; best() is
            // synchronized and safe at any point.
        }
    }

    private suspend fun extractText(image: OcrImage): String? {
        val engine = ocr ?: return null
        val downscaled = ScreenshotDownscaler.downscale(image, maxOcrLongSide)
        val languages = ocrLanguages().ifEmpty { listOf(DEFAULT_OCR_LANGUAGE) }
        return runCatching { engine.recognize(downscaled, languages) }
            .getOrNull()
            ?.text
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val INDEX_READY_TIMEOUT_MS = 10_000L
        const val DEFAULT_OCR_LANGUAGE = "eng"
    }
}
