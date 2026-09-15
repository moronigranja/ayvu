package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroTimingsTest {
    @Test
    fun `token edges scale durations onto the sample count`() {
        val edges = KokoroTimings.tokenEdges(intArrayOf(10, 20, 10), samples = 400)
        assertEquals(intArrayOf(0, 100, 300, 400).toList(), edges.toList())
    }

    @Test
    fun `token edges with zero duration stay empty`() {
        val edges = KokoroTimings.tokenEdges(intArrayOf(0, 0), samples = 100)
        assertTrue(edges.all { it == 0 })
    }

    @Test
    fun `timings pair phonemes with their spans`() {
        val edges = intArrayOf(0, 240, 480)
        val timings = KokoroTimings.timings("ab", edges, sampleRate = 24_000)
        assertEquals(2, timings.size)
        assertEquals('a', timings[0].phoneme)
        assertEquals(0.0, timings[0].start)
        assertEquals(0.01, timings[0].end)
        assertEquals('b', timings[1].phoneme)
        assertEquals(0.01, timings[1].start)
    }

    @Test
    fun `wanted pause after marks`() {
        assertEquals(0.25, KokoroTimings.wantedAfter('.', 0.25, 0.1))
        assertEquals(0.25, KokoroTimings.wantedAfter('…', 0.25, 0.1))
        assertEquals(0.1, KokoroTimings.wantedAfter(';', 0.25, 0.1))
        assertEquals(0.0, KokoroTimings.wantedAfter('h', 0.25, 0.1))
    }

    @Test
    fun `no timings or no wanted pauses leaves audio untouched`() {
        val audio = FloatArray(100) { 0.5f }
        val (out1, moved1) = KokoroTimings.insertPauses(audio, emptyList(), 24_000, 0.25, 0.1)
        assertEquals(audio.toList(), out1.toList())
        assertEquals(emptyList<Timing>(), moved1)
        val (out2, _) = KokoroTimings.insertPauses(audio, listOf(Timing('.', 0.0, 0.01)), 24_000, 0.0, 0.0)
        assertEquals(audio.toList(), out2.toList())
    }

    @Test
    fun `existing long pause is not lengthened`() {
        // Mark at 0.6s inside 0.5s tone + 1.0s silence + tone: the quiet run
        // after the mark already exceeds the wanted 0.25s.
        val audio = tone(0.5f) + FloatArray(24_000) + tone(0.5f)
        val timings = listOf(Timing('.', 0.5, 0.6))
        val (out, moved) = KokoroTimings.insertPauses(audio, timings, 24_000, 0.25, 0.1)
        assertEquals(audio.size, out.size)
        assertEquals(1, moved.size)
        assertEquals(0.5, moved[0].start)
    }

    @Test
    fun `short pause after a mark is topped up`() {
        // Mark at 0.6s; the run of quiet frames around it spans ~0.1s of
        // silence between tone blocks; the gap is topped up to 0.25s.
        val audio = tone(0.5f) + FloatArray(2_400) + tone(0.5f)
        val timings = listOf(Timing('.', 0.5, 0.6))
        val (out, moved) = KokoroTimings.insertPauses(audio, timings, 24_000, 0.25, 0.1)
        assertTrue(out.size > audio.size, "missing pause samples must be spliced in")
        val insertedSamples = out.size - audio.size
        assertTrue(insertedSamples in 2000..4200, "topped up to ~0.25s minus existing ~0.1s, got $insertedSamples")
        assertEquals(1, moved.size)
        assertEquals(
            0.6,
            moved[0].end,
            "reference semantics: timings are shifted only after the spliced silence, so the mark itself keeps its position",
        )
    }

    private fun tone(seconds: Float): FloatArray = FloatArray((seconds * 24_000).toInt()) { 0.4f }
}
