package com.moronigranja.localttsreader.ocr

/**
 * The OCR seam (S1): an engine that turns a screenshot raster into text.
 *
 * The interface is engine-agnostic and suspension-based — a recognize pass on
 * a full-page screenshot is seconds of native work and must never run on the
 * UI thread. [languages] are tessdata identifiers ("eng", "spa", …) that must
 * already be installed (the settings UI owns the downloads); engines treat an
 * unknown language as a failed pass rather than a silent empty result.
 *
 * Implementations live behind this core: a tess-two adapter on Android
 * (feature-ocr), fakes/alternates in tests and tooling.
 */
interface OcrEngine {
    /** Extracts [text] + confidence from [image] for the requested [languages]. */
    suspend fun recognize(
        image: OcrImage,
        languages: List<String>,
    ): OcrResult
}
