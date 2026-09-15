package com.moronigranja.localttsreader.featureshare

import com.moronigranja.localttsreader.ocr.OcrImage

/** What the share gateway received (S2): a text snippet or a shared image. */
sealed interface ShareInput {
    data class Text(
        val text: String,
    ) : ShareInput

    /** Full-resolution decoded raster; the resolver downscales before OCR. */
    data class Image(
        val image: OcrImage,
    ) : ShareInput
}

/**
 * The typed outcome of resolving a shared snippet against the library.
 * [Found] is actionable (S3 wires the "open at passage" step); [NotFound]
 * carries the closest candidate — dimmed in the not-found UI — so a
 * below-threshold quote is recoverable, and [Failed] covers decode/OCR
 * outages with a user-facing message.
 */
sealed interface ShareResolution {
    val snippet: String

    data class Found(
        val bookId: String,
        val bookTitle: String,
        val chapterIndex: Int,
        val chapterTitle: String?,
        val passageIndex: Int,
        val confidence: Double,
        override val snippet: String,
    ) : ShareResolution

    data class NotFound(
        val reason: Reason,
        /** The best candidate even when below threshold, for the hint line. */
        val closest: Closest? = null,
        override val snippet: String,
    ) : ShareResolution

    data class Closest(
        val bookTitle: String,
        val chapterTitle: String?,
        val confidence: Double,
    )

    enum class Reason {
        /** The snippet normalized to nothing (blank / punctuation-only share). */
        BLANK,

        /** The shared image produced no readable text (OCR empty/failed). */
        NO_READABLE_TEXT,

        /** OCR is unavailable (no tessdata staged/downloaded yet). */
        OCR_UNAVAILABLE,

        /** No passage reached the configured threshold. */
        NO_MATCH,
    }

    data class Failed(
        val message: String,
        override val snippet: String,
    ) : ShareResolution
}
