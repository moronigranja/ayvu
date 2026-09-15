package com.moronigranja.localttsreader.ocr

/**
 * Screenshot downscale (S1): phones share display-resolution rasters
 * (1440×3088 on the S22) — far too many pixels for tess-two's speed or its
 * text-line segmentation. Bilinear resize to a cap on the long side (default
 * 1600 px ≈ 180–300 dpi for typical page screenshots), single pass, one
 * output buffer, per-channel ARGB interpolation with edge clamping.
 */
object ScreenshotDownscaler {
    const val DEFAULT_MAX_LONG_SIDE = 1600

    fun downscale(
        image: OcrImage,
        maxLongSide: Int = DEFAULT_MAX_LONG_SIDE,
    ): OcrImage {
        require(maxLongSide > 0) { "maxLongSide must be positive, was $maxLongSide" }
        val longSide = maxOf(image.width, image.height)
        if (longSide <= maxLongSide) return image

        val outWidth = maxOf(1, (image.width.toDouble() * maxLongSide / longSide).toInt())
        val outHeight = maxOf(1, (image.height.toDouble() * maxLongSide / longSide).toInt())
        val out = IntArray(outWidth * outHeight)
        val src = image.argb
        val sw = image.width
        val sh = image.height

        // Each output pixel's centre maps to srcX = (x+0.5)*scaleX - 0.5; the
        // four neighbours are interpolated with the box-cut fractional parts.
        val scaleX = sw.toDouble() / outWidth
        val scaleY = sh.toDouble() / outHeight

        for (y in 0 until outHeight) {
            val srcY = (y + 0.5) * scaleY - 0.5
            val y0 = floorClamp(srcY, sh)
            val y1 = minOf(y0 + 1, sh - 1)
            val wy = (srcY - y0).coerceIn(0.0, 1.0)
            for (x in 0 until outWidth) {
                val srcX = (x + 0.5) * scaleX - 0.5
                val x0 = floorClamp(srcX, sw)
                val x1 = minOf(x0 + 1, sw - 1)
                val wx = (srcX - x0).coerceIn(0.0, 1.0)
                val row0 = y0 * sw
                val row1 = y1 * sw
                val top = lerp(src[row0 + x0], src[row0 + x1], wx)
                val bottom = lerp(src[row1 + x0], src[row1 + x1], wx)
                out[y * outWidth + x] = lerp(top, bottom, wy)
            }
        }
        return OcrImage(outWidth, outHeight, out)
    }

    private fun floorClamp(
        v: Double,
        size: Int,
    ): Int =
        when {
            v <= 0.0 -> 0
            v >= size - 1.0 -> size - 1
            else -> v.toInt()
        }

    private fun lerp(
        a: Int,
        b: Int,
        w: Double,
    ): Int {
        val inv = 1.0 - w
        val r = (red(a) * inv + red(b) * w).toInt().coerceIn(0, 255)
        val g = (green(a) * inv + green(b) * w).toInt().coerceIn(0, 255)
        val bl = (blue(a) * inv + blue(b) * w).toInt().coerceIn(0, 255)
        val al = (alpha(a) * inv + alpha(b) * w).toInt().coerceIn(0, 255)
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun red(p: Int) = (p shr 16) and 0xFF

    private fun green(p: Int) = (p shr 8) and 0xFF

    private fun blue(p: Int) = p and 0xFF

    private fun alpha(p: Int) = (p shr 24) and 0xFF
}
