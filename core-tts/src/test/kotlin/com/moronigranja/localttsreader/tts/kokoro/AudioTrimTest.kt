package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTrimTest {
    private fun tone(
        frequency: Double = 440.0,
        samples: Int = 24_000,
        amplitude: Float = 0.5f,
    ): FloatArray = FloatArray(samples) { (amplitude * sin(2 * PI * frequency * it / 24000.0)).toFloat() }

    private fun quiet(samples: Int): FloatArray = FloatArray(samples) { (1e-6f * sin(2 * PI * 50 * it / 24000.0)).toFloat() }

    @Test
    fun `uniform signal is not trimmed - librosa reference semantics`() {
        // With ref = max RMS, a uniform (even silent) signal has no frames
        // quieter than the reference: nothing is trimmed. Reference
        // kokoro-onnx/librosa behavior, kept verbatim.
        val (trimmed, range) = AudioTrim.trim(FloatArray(48_000))
        assertEquals(48_000, trimmed.size)
        assertEquals(0, range.first)
        assertEquals(48_000, range.count())
    }

    @Test
    fun `leading and trailing silence is cut at frame granularity`() {
        val silence = 3000
        val y = FloatArray(silence) + tone(samples = 24_000) + FloatArray(silence)
        val (trimmed, range) = AudioTrim.trim(y)

        // The tone occupies samples [3000, 27000); trimming is hop-granular.
        assertTrue(range.first <= 3000, "trim may cut into the leading silence but not past the tone")
        assertTrue(range.last >= 27_000, "trim may cut trailing silence but not the tone")
        assertEquals(range.count(), trimmed.size)
        println("DEBUG trimmed size=${trimmed.size} range=$range max=${trimmed.maxByOrNull { abs(it) }}")
    }

    @Test
    fun `quiet frames around a loud signal are trimmed`() {
        val quietSamples = 4000
        val y = quiet(quietSamples) + tone(samples = 24_000) + quiet(quietSamples)
        val (trimmed, range) = AudioTrim.trim(y)
        assertTrue(range.first > 0, "leading quiet frames must be dropped")
        assertTrue(range.last < y.size, "trailing quiet frames must be dropped")
        assertTrue(range.first < quietSamples && range.last > quietSamples + 24_000, "tone region kept")
        assertEquals(range.count(), trimmed.size)
        assertEquals(0.5f, abs(trimmed.maxByOrNull { abs(it) }!!), 1e-6f)
    }

    @Test
    fun `short signals still frame`() {
        val y = FloatArray(1_000) { if (it in 300..700) 0.5f else 0f }
        val (trimmed, range) = AudioTrim.trim(y)
        assertTrue(trimmed.isNotEmpty())
        assertTrue(range.first < 300 && range.last > 700)
        assertEquals(range.count(), trimmed.size)
        assertEquals(0.5f, abs(trimmed.maxByOrNull { abs(it) }!!), 1e-6f)
    }
}
