package io.github.moronigranja.ayvu.ocr

/**
 * A raster to OCR: ARGB pixels row-major, 8 bits per channel.
 *
 * Intentionally raw (no Bitmap / BufferedImage) so the OCR core stays pure
 * JVM: Android screenshots arrive as ARGB ints (the platform's default
 * premultiplied format is handled at the adapter seam), and host tests
 * build images by arithmetic. [argb].size MUST equal [width] * [height].
 */
data class OcrImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "image dimensions must be positive, was ${width}x$height" }
        require(argb.size == width * height) {
            "pixel buffer size ${argb.size} != ${width}x$height"
        }
    }
}

/** One recognition pass: the extracted text and the engine's mean confidence (0..1). */
data class OcrResult(
    val text: String,
    val confidence: Double,
)
