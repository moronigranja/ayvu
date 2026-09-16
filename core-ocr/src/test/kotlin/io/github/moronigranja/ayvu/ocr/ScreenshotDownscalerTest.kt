package io.github.moronigranja.ayvu.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S1: downscale geometry, interpolation, edge clamping — pure arithmetic. */
class ScreenshotDownscalerTest {
    private fun solid(
        width: Int,
        height: Int,
        color: Int,
    ) = OcrImage(width, height, IntArray(width * height) { color })

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun row(
        image: OcrImage,
        y: Int,
    ) = image.argb.copyOfRange(y * image.width, (y + 1) * image.width)

    @Test
    fun `at-or-below the cap the image passes through unchanged`() {
        val src = OcrImage(800, 600, IntArray(800 * 600) { argb(10, 20, 30) })
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 1600)
        assertSame(src, out, "no copy below the cap")
    }

    @Test
    fun `downscale halves a square image to the cap`() {
        val src = solid(2000, 2000, argb(255, 0, 0))
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 1000)
        assertEquals(1000, out.width)
        assertEquals(1000, out.height)
        assertEquals(argb(255, 0, 0), out.argb[0])
    }

    @Test
    fun `aspect ratio is preserved`() {
        val src = solid(1440, 3088, argb(0, 255, 0)) // S22 display, long side portrait
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 1600)
        assertEquals(1600, out.height, "long side capped")
        assertEquals(1440.0 / 3088.0, out.width.toDouble() / out.height, 0.01, "aspect preserved")
        assertEquals(1600 * 1440 / 3088, out.width, "width scaled by the same factor")
    }

    @Test
    fun `exact 2x box average of four distinct pixels`() {
        // 2x2 source with per-corner colors; the single output pixel must be
        // the mean of all four (bilinear at the box centre = box average).
        val src =
            OcrImage(
                2,
                2,
                intArrayOf(argb(255, 0, 0), argb(0, 255, 0), argb(0, 0, 255), argb(255, 255, 255)),
            )
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 1)
        assertEquals(1, out.width)
        assertEquals(1, out.height)
        // (r = (255+0+0+255)/4 = 127, g = (0+255+0+255)/4 = 127, b = (0+0+255+255)/4 = 127)
        assertEquals(argb(127, 127, 127), out.argb[0])
    }

    @Test
    fun `vertical gradient interpolates between rows`() {
        // 1x2 image: pure black row on top, white below. Downscale to 1x1 ->
        // exactly mid-grey at the box centre, all channels equal.
        val src = OcrImage(1, 2, intArrayOf(argb(0, 0, 0), argb(255, 255, 255)))
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 1)
        assertEquals(argb(127, 127, 127), out.argb[0])
    }

    @Test
    fun `a tiny image downscales without index faults`() {
        val src = solid(3, 3, argb(1, 2, 3))
        val out = ScreenshotDownscaler.downscale(src, maxLongSide = 2)
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertTrue(out.argb.all { it == argb(1, 2, 3) }, "solid stays solid at the edges")
    }
}
