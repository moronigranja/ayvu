package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.tts.SegmentAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Storage transparency (decisions #44): cached-exact + estimated footprint. */
class PregenSpaceEstimatorTest {
    @TempDir
    lateinit var tmp: File

    private val book =
        Book(
            id = "b1",
            title = "Anna",
            chapters =
                listOf(
                    Chapter(0, "One", listOf(TextPassage("a".repeat(600)), TextPassage("b".repeat(300)))),
                    Chapter(1, "Two", listOf(TextPassage("c".repeat(150)))),
                ),
        )

    private val voice = "af_heart"
    private val speed = 1.0
    private val bytesPerSecond = 24_000.0 * 2.0 // 24 kHz 16-bit mono

    private fun cache(maxBytes: Long = Long.MAX_VALUE) = PcmPassageCache(File(tmp, "cache"), maxBytes)

    private fun expectedUncached(
        chars: Int,
        cps: Double = 15.0,
        atSpeed: Double = 1.0,
    ): Long = (chars / cps / atSpeed * bytesPerSecond).toLong()

    @Test
    fun `empty cache estimates from text at the default speaking rate`() {
        val result = PregenSpaceEstimator(cache()).estimate(book, voice, speed)
        val expected = expectedUncached(600) + expectedUncached(300) + expectedUncached(150)
        assertEquals(expected, result.totalBytes)
        assertEquals(0L, result.cachedBytes)
    }

    @Test
    fun `cached passages count their exact on-disk bytes`() {
        val cache = cache()
        val key = PregenKey(book.id, 0, 1, voice, speed, engine = PregenKey.DEFAULT_ENGINE)
        cache.put(key, PregenAudio(ByteArray(5_000), 24_000, listOf(SegmentAnchor(0.0, 1.0))))

        val result = PregenSpaceEstimator(cache).estimate(book, voice, speed)
        assertEquals(5_000L, result.cachedBytes, "pcm file size, not the formula")
        assertEquals(expectedUncached(600) + 5_000 + expectedUncached(150), result.totalBytes)
    }

    @Test
    fun `speed scales the uncached estimate but not the cached part`() {
        val cache = cache()
        val key = PregenKey(book.id, 0, 1, voice, 2.0, engine = PregenKey.DEFAULT_ENGINE)
        cache.put(key, PregenAudio(ByteArray(5_000), 24_000, listOf(SegmentAnchor(0.0, 1.0))))

        val result = PregenSpaceEstimator(cache).estimate(book, voice, 2.0)
        assertEquals(5_000L, result.cachedBytes)
        assertEquals(expectedUncached(600, atSpeed = 2.0) + 5_000 + expectedUncached(150, atSpeed = 2.0), result.totalBytes)
    }

    @Test
    fun `a custom speaking rate is honored`() {
        val cps = 20.0
        val result = PregenSpaceEstimator(cache(), cps).estimate(book, voice, speed)
        assertEquals(expectedUncached(600, cps) + expectedUncached(300, cps) + expectedUncached(150, cps), result.totalBytes)
    }

    @Test
    fun `an empty book costs nothing`() {
        val empty = Book("e", "Empty", emptyList())
        val result = PregenSpaceEstimator(cache()).estimate(empty, voice, speed)
        assertEquals(PregenSpaceEstimate(0L, 0L), result)
    }

    @Test
    fun `the per-engine rate map pins kokoro and falls back safely`() {
        assertEquals(24_000, PregenSpaceEstimator.sampleRateHz(PregenKey.DEFAULT_ENGINE), "kokoro is 24 kHz")
        assertEquals(
            PregenSpaceEstimator.sampleRateHz(PregenKey.DEFAULT_ENGINE),
            PregenSpaceEstimator.sampleRateHz("some-future-engine"),
            "unknown engines keep the documented safe default",
        )
        assertEquals(22_050, PregenSpaceEstimator.sampleRateHz("piper-v1"), "piper-v1 is pinned at 22.05 kHz (D4)")
        // An estimate for an unknown engine uses that same safe rate today.
        val result = PregenSpaceEstimator(cache()).estimate(book, voice, speed, engine = "some-future-engine")
        assertEquals(expectedUncached(600) + expectedUncached(300) + expectedUncached(150), result.totalBytes)
    }

    @Test
    fun `estimates address the per-engine cache key`() {
        val cache = cache()
        cache.put(
            PregenKey(book.id, 0, 1, voice, speed, engine = "cosyvoice3"),
            PregenAudio(ByteArray(5_000), 24_000, listOf(SegmentAnchor(0.0, 1.0))),
        )

        val kokoro = PregenSpaceEstimator(cache).estimate(book, voice, speed)
        assertEquals(0L, kokoro.cachedBytes, "kokoro estimate never sees another engine's bytes")
        val cosy = PregenSpaceEstimator(cache).estimate(book, voice, speed, engine = "cosyvoice3")
        assertEquals(5_000L, cosy.cachedBytes, "the engine's own cached bytes are exact")
    }
}
